package com.aiexam.question.admin.application;

import com.aiexam.question.admin.dto.QuestionActionResult;
import com.aiexam.question.admin.dto.QuestionReviewRequest;
import com.aiexam.question.admin.dto.QuestionWorkflowRequest;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.BulkPublishCandidate;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.BulkPublishTargetState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public class QuestionInternalBulkPublishRunner {
  private static final String SCHEMA_VERSION = "w3-internal-bulk-publish-checkpoint/1.0";
  private static final Set<String> ALLOWED_TARGET_STATUSES = Set.of(
      "DRAFT", "IN_REVIEW", "PUBLISHED");

  private final JdbcQuestionAdminRepository repository;
  private final QuestionAdminApplicationService adminService;
  private final PrincipalExecutor principalExecutor;
  private final ObjectMapper objectMapper;
  private final CheckpointStore checkpointStore;

  public QuestionInternalBulkPublishRunner(JdbcQuestionAdminRepository repository,
      QuestionAdminApplicationService adminService, PrincipalExecutor principalExecutor,
      ObjectMapper objectMapper) {
    this(repository, adminService, principalExecutor, objectMapper,
        new AtomicFileCheckpointStore());
  }

  QuestionInternalBulkPublishRunner(JdbcQuestionAdminRepository repository,
      QuestionAdminApplicationService adminService, PrincipalExecutor principalExecutor,
      ObjectMapper objectMapper, CheckpointStore checkpointStore) {
    this.repository = repository;
    this.adminService = adminService;
    this.principalExecutor = principalExecutor;
    this.objectMapper = objectMapper.copy().findAndRegisterModules();
    this.checkpointStore = checkpointStore;
  }

  public BulkPublishResult run(BulkPublishCommand command) {
    validate(command);
    verifyTargetState(command);
    Checkpoint checkpoint = readCheckpoint(command);
    long started = System.nanoTime();
    long peakHeap = usedHeap();
    boolean injectedFailure = false;
    try {
      while (true) {
        List<BulkPublishCandidate> candidates = repository.bulkPublishCandidates(
            command.importBatchIds(), command.rightsStatus(), command.usageRestriction(),
            checkpoint.lastQuestionId, command.batchSize());
        if (candidates.isEmpty()) {
          break;
        }
        for (BulkPublishCandidate candidate : candidates) {
          try {
            CandidateProgress progress = publishCandidate(candidate, command);
            checkpoint.submittedCount += progress.submitted();
            checkpoint.approvedCount += progress.approved();
            checkpoint.publishedCount += progress.published();
            checkpoint.skippedAlreadyPublishedCount += progress.skippedAlreadyPublished();
            checkpoint.lastQuestionId = candidate.questionId();
            checkpoint.updatedAt = Instant.now().toString();
            checkpoint.peakObservedHeapBytes = Math.max(checkpoint.peakObservedHeapBytes, usedHeap());
            saveCheckpoint(command.checkpointPath(), checkpoint);
            if (command.failAfterPublished() != null
                && checkpoint.publishedCount >= command.failAfterPublished()) {
              injectedFailure = true;
              throw new InjectedBulkPublishFailure(
                  "Injected W3 internal bulk publish failure after "
                      + checkpoint.publishedCount + " published questions");
            }
          } catch (RuntimeException exception) {
            if (!(exception instanceof InjectedBulkPublishFailure)) {
              checkpoint.failures.add(new Failure(candidate.questionId(),
                  candidate.questionVersionId(), exception.getClass().getName(),
                  exception.getMessage()));
              checkpoint.updatedAt = Instant.now().toString();
              saveCheckpoint(command.checkpointPath(), checkpoint);
            }
            throw exception;
          }
        }
        peakHeap = Math.max(peakHeap, usedHeap());
      }
    } finally {
      peakHeap = Math.max(peakHeap, checkpoint.peakObservedHeapBytes);
    }
    double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
    return new BulkPublishResult(checkpoint.lastQuestionId, checkpoint.submittedCount,
        checkpoint.approvedCount, checkpoint.publishedCount,
        checkpoint.skippedAlreadyPublishedCount, checkpoint.failures.size(),
        elapsedSeconds, peakHeap, injectedFailure);
  }

  private CandidateProgress publishCandidate(BulkPublishCandidate candidate,
      BulkPublishCommand command) {
    if ("PUBLISHED".equals(candidate.status())) {
      return new CandidateProgress(0, 0, 0, 1);
    }
    long rowVersion = candidate.rowVersion();
    String status = candidate.status();
    String reviewDecision = candidate.reviewDecision();
    long submitted = 0;
    long approved = 0;
    long published = 0;
    if ("DRAFT".equals(status)) {
      long expectedRowVersion = rowVersion;
      QuestionActionResult result = principalExecutor.callAs(command.author(), () ->
          adminService.submitReview(candidate.questionId(), candidate.questionVersionId(),
              new QuestionWorkflowRequest(mutation(command, candidate, "submit"),
                  expectedRowVersion, "W3 internal bulk submit")));
      rowVersion = result.rowVersion();
      status = result.status();
      submitted++;
    }
    if ("IN_REVIEW".equals(status) && !"APPROVED".equals(reviewDecision)) {
      long expectedRowVersion = rowVersion;
      QuestionActionResult result = principalExecutor.callAs(command.reviewer(), () ->
          adminService.review(candidate.questionId(), candidate.questionVersionId(),
              new QuestionReviewRequest(mutation(command, candidate, "review"),
                  expectedRowVersion, "APPROVED", "W3 internal bulk review")));
      rowVersion = result.rowVersion();
      status = result.status();
      reviewDecision = "APPROVED";
      approved++;
    }
    if ("IN_REVIEW".equals(status) && "APPROVED".equals(reviewDecision)) {
      long expectedRowVersion = rowVersion;
      QuestionActionResult result = principalExecutor.callAs(command.reviewer(), () ->
          adminService.publish(candidate.questionId(), candidate.questionVersionId(),
              new QuestionWorkflowRequest(mutation(command, candidate, "publish"),
                  expectedRowVersion, "W3 internal bulk publish")));
      if (!"PUBLISHED".equals(result.status())) {
        throw new IllegalStateException("Publish did not return PUBLISHED for question "
            + candidate.questionId());
      }
      published++;
    }
    return new CandidateProgress(submitted, approved, published, 0);
  }

  private Checkpoint readCheckpoint(BulkPublishCommand command) {
    if (Files.exists(command.checkpointPath())) {
      Checkpoint checkpoint = checkpointStore.read(command.checkpointPath(), objectMapper);
      if (!SCHEMA_VERSION.equals(checkpoint.schemaVersion)
          || !command.runId().equals(checkpoint.runId)
          || !command.importBatchIds().equals(checkpoint.importBatchIds)
          || command.expectedTargetCount() != checkpoint.expectedTargetCount
          || command.author().userId() != checkpoint.authorUserId
          || command.reviewer().userId() != checkpoint.reviewerUserId
          || !command.rightsStatus().equals(checkpoint.rightsStatus)
          || !command.usageRestriction().equals(checkpoint.usageRestriction)) {
        throw new IllegalStateException("Bulk publish checkpoint does not match this command");
      }
      return checkpoint;
    }
    Checkpoint checkpoint = new Checkpoint();
    checkpoint.schemaVersion = SCHEMA_VERSION;
    checkpoint.runId = command.runId();
    checkpoint.importBatchIds = List.copyOf(command.importBatchIds());
    checkpoint.batchSize = command.batchSize();
    checkpoint.expectedTargetCount = command.expectedTargetCount();
    checkpoint.authorUserId = command.author().userId();
    checkpoint.reviewerUserId = command.reviewer().userId();
    checkpoint.rightsStatus = command.rightsStatus();
    checkpoint.usageRestriction = command.usageRestriction();
    checkpoint.updatedAt = Instant.now().toString();
    checkpoint.peakObservedHeapBytes = usedHeap();
    saveCheckpoint(command.checkpointPath(), checkpoint);
    return checkpoint;
  }

  private void saveCheckpoint(Path path, Checkpoint checkpoint) {
    checkpointStore.write(path, checkpoint, objectMapper);
  }

  private UUID mutation(BulkPublishCommand command, BulkPublishCandidate candidate,
      String operation) {
    String raw = command.runId() + ":" + operation + ":" + candidate.questionId()
        + ":" + candidate.questionVersionId();
    return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
  }

  private void validate(BulkPublishCommand command) {
    Objects.requireNonNull(command, "command is required");
    if (command.importBatchIds() == null || command.importBatchIds().isEmpty()) {
      throw new IllegalArgumentException("importBatchIds is required");
    }
    if (command.batchSize() < 1 || command.batchSize() > 1_000) {
      throw new IllegalArgumentException("batchSize must be between 1 and 1000");
    }
    if (command.author().userId() == command.reviewer().userId()) {
      throw new IllegalArgumentException("author and reviewer must be different users");
    }
    if (!"UNVERIFIED".equals(command.rightsStatus())
        || !"INTERNAL_ONLY".equals(command.usageRestriction())) {
      throw new IllegalArgumentException("W3 bulk publish must remain UNVERIFIED/INTERNAL_ONLY");
    }
    if (command.expectedTargetCount() < 1) {
      throw new IllegalArgumentException("expectedTargetCount must be positive");
    }
  }

  private void verifyTargetState(BulkPublishCommand command) {
    BulkPublishTargetState state = repository.bulkPublishTargetState(command.importBatchIds(),
        command.rightsStatus(), command.usageRestriction());
    if (state.totalCount() != command.expectedTargetCount()) {
      throw new IllegalStateException("W3 bulk publish target count mismatch: expected "
          + command.expectedTargetCount() + ", actual " + state.totalCount()
          + ", statusCounts=" + state.statusCounts()
          + ", pointerMismatchCount=" + state.pointerMismatchCount());
    }
    List<String> illegalStatuses = state.statusCounts().keySet().stream()
        .filter(status -> !ALLOWED_TARGET_STATUSES.contains(status))
        .sorted()
        .toList();
    if (state.pointerMismatchCount() > 0 || !illegalStatuses.isEmpty()) {
      throw new IllegalStateException("W3 bulk publish target state is unsafe: statusCounts="
          + state.statusCounts() + ", pointerMismatchCount=" + state.pointerMismatchCount()
          + ", unsafeTargets=" + state.unsafeTargets());
    }
  }

  private long usedHeap() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  public record BulkPublishCommand(String runId, List<Long> importBatchIds, int batchSize,
                                   int expectedTargetCount, Path checkpointPath, Actor author,
                                   Actor reviewer, String rightsStatus, String usageRestriction,
                                   Long failAfterPublished) {
  }

  public record Actor(long userId, String username) {
  }

  @FunctionalInterface
  public interface PrincipalExecutor {
    <T> T callAs(Actor actor, Supplier<T> work);
  }

  public record BulkPublishResult(long lastQuestionId, long submittedCount, long approvedCount,
                                  long publishedCount, long skippedAlreadyPublishedCount,
                                  long failureCount, double elapsedSeconds,
                                  long peakObservedHeapBytes, boolean injectedFailure) {
  }

  public static class Checkpoint {
    public String schemaVersion;
    public String runId;
    public List<Long> importBatchIds = new ArrayList<>();
    public int batchSize;
    public int expectedTargetCount;
    public long authorUserId;
    public long reviewerUserId;
    public String rightsStatus;
    public String usageRestriction;
    public long lastQuestionId;
    public long submittedCount;
    public long approvedCount;
    public long publishedCount;
    public long skippedAlreadyPublishedCount;
    public long peakObservedHeapBytes;
    public String updatedAt;
    public List<Failure> failures = new ArrayList<>();
  }

  public record Failure(long questionId, long questionVersionId, String errorType,
                        String message) {
  }

  private record CandidateProgress(long submitted, long approved, long published,
                                   long skippedAlreadyPublished) {
  }

  public static final class InjectedBulkPublishFailure extends RuntimeException {
    public InjectedBulkPublishFailure(String message) {
      super(message);
    }
  }

  interface CheckpointStore {
    Checkpoint read(Path path, ObjectMapper objectMapper);

    void write(Path path, Checkpoint checkpoint, ObjectMapper objectMapper);
  }

  static class AtomicFileCheckpointStore implements CheckpointStore {
    @Override
    public Checkpoint read(Path path, ObjectMapper objectMapper) {
      try {
        return objectMapper.readValue(path.toFile(), Checkpoint.class);
      } catch (IOException exception) {
        throw new IllegalStateException("Cannot read bulk publish checkpoint", exception);
      }
    }

    @Override
    public void write(Path path, Checkpoint checkpoint, ObjectMapper objectMapper) {
      Path target = path.toAbsolutePath().normalize();
      Path parent = target.getParent();
      if (parent == null) {
        throw new IllegalStateException("Bulk publish checkpoint path must have a parent");
      }
      Path temp = null;
      try {
        Files.createDirectories(parent);
        temp = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsBytes(checkpoint);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
          ByteBuffer buffer = ByteBuffer.wrap(bytes);
          while (buffer.hasRemaining()) {
            channel.write(buffer);
          }
          channel.force(true);
        }
        atomicMove(temp, target);
        temp = null;
        forceDirectory(parent);
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IllegalStateException(
            "Atomic checkpoint move is not supported; refusing non-atomic checkpoint write",
            exception);
      } catch (IOException exception) {
        throw new IllegalStateException("Cannot write bulk publish checkpoint", exception);
      } finally {
        if (temp != null) {
          try {
            Files.deleteIfExists(temp);
          } catch (IOException ignored) {
            // Best effort cleanup; the last valid checkpoint target is left untouched.
          }
        }
      }
    }

    void atomicMove(Path temp, Path target) throws IOException {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    }

    private void forceDirectory(Path parent) {
      try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
        directory.force(true);
      } catch (IOException | UnsupportedOperationException ignored) {
        // Directory fsync is not uniformly available on Windows; file fsync above is mandatory.
      }
    }
  }
}
