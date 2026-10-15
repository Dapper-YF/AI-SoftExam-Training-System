package com.aiexam.question.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.question.admin.application.QuestionInternalBulkPublishRunner.Actor;
import com.aiexam.question.admin.application.QuestionInternalBulkPublishRunner.AtomicFileCheckpointStore;
import com.aiexam.question.admin.application.QuestionInternalBulkPublishRunner.BulkPublishCommand;
import com.aiexam.question.admin.application.QuestionInternalBulkPublishRunner.Checkpoint;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.BulkPublishCandidate;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.BulkPublishTargetState;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.BulkPublishUnsafeTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QuestionInternalBulkPublishRunnerTest {
  private static final int W3_TARGET_COUNT = 26_284;
  private static final Actor AUTHOR = new Actor(881001L, "admin");
  private static final Actor REVIEWER = new Actor(881002L, "question_reviewer");

  @TempDir
  Path tempDir;

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void failsBeforeAnyWorkflowWriteWhenTargetStatusIsRejectedRetiredOrUnknown() {
    for (String illegalStatus : List.of("REJECTED", "RETIRED", "BOGUS_UNKNOWN")) {
      AtomicInteger principalCalls = new AtomicInteger();
      var runner = runnerWithState(state(Map.of("DRAFT", W3_TARGET_COUNT - 1L,
          illegalStatus, 1L), 0, sample(illegalStatus)), principalCalls);

      assertThatThrownBy(() -> runner.run(command(tempDir.resolve(illegalStatus + ".json"))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("target state is unsafe")
          .hasMessageContaining(illegalStatus);
      assertThat(principalCalls).as("no workflow write for %s", illegalStatus).hasValue(0);
    }
  }

  @Test
  void failsBeforeAnyWorkflowWriteWhenTargetCountDoesNotMatchDataset() {
    AtomicInteger principalCalls = new AtomicInteger();
    var runner = runnerWithState(state(Map.of("DRAFT", W3_TARGET_COUNT - 1L), 0, List.of()),
        principalCalls);

    assertThatThrownBy(() -> runner.run(command(tempDir.resolve("count-mismatch.json"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("target count mismatch")
        .hasMessageContaining(String.valueOf(W3_TARGET_COUNT));
    assertThat(principalCalls).hasValue(0);
  }

  @Test
  void atomicCheckpointWriteCompletesTargetAndLeavesNoTempFile() throws Exception {
    Path checkpointPath = tempDir.resolve("checkpoint.json");
    Checkpoint checkpoint = checkpoint("run-ok", W3_TARGET_COUNT);
    AtomicFileCheckpointStore store = new AtomicFileCheckpointStore();

    store.write(checkpointPath, checkpoint, mapper);

    Checkpoint saved = store.read(checkpointPath, mapper);
    assertThat(saved.runId).isEqualTo("run-ok");
    try (var paths = Files.list(tempDir)) {
      assertThat(paths
          .filter(path -> path.getFileName().toString().startsWith("checkpoint.json."))
          .filter(path -> path.getFileName().toString().endsWith(".tmp"))
          .toList()).isEmpty();
    }
  }

  @Test
  void failedAtomicCheckpointWriteKeepsPreviousCheckpointReadableAndCleansTemp()
      throws Exception {
    Path checkpointPath = tempDir.resolve("checkpoint.json");
    AtomicFileCheckpointStore store = new AtomicFileCheckpointStore();
    store.write(checkpointPath, checkpoint("old-run", W3_TARGET_COUNT), mapper);
    FailingAtomicMoveCheckpointStore failingStore = new FailingAtomicMoveCheckpointStore();

    assertThatThrownBy(() -> failingStore.write(checkpointPath,
        checkpoint("new-run", W3_TARGET_COUNT), mapper))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Atomic checkpoint move is not supported");

    Checkpoint stillOld = store.read(checkpointPath, mapper);
    assertThat(stillOld.runId).isEqualTo("old-run");
    try (var paths = Files.list(tempDir)) {
      assertThat(paths
          .filter(path -> path.getFileName().toString().startsWith("checkpoint.json."))
          .filter(path -> path.getFileName().toString().endsWith(".tmp"))
          .toList()).isEmpty();
    }
  }

  @Test
  void corruptedCheckpointFailsClosed() throws Exception {
    Path checkpointPath = tempDir.resolve("corrupted.json");
    Files.writeString(checkpointPath, "{\"schemaVersion\":");
    AtomicFileCheckpointStore store = new AtomicFileCheckpointStore();

    assertThatThrownBy(() -> store.read(checkpointPath, mapper))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot read bulk publish checkpoint");
  }

  @Test
  void checkpointIdentityMismatchFailsClosedBeforeWorkflowWrite() throws Exception {
    Path checkpointPath = tempDir.resolve("identity.json");
    new AtomicFileCheckpointStore().write(checkpointPath, checkpoint("other-run", W3_TARGET_COUNT),
        mapper);
    AtomicInteger principalCalls = new AtomicInteger();
    var runner = runnerWithState(state(Map.of("DRAFT", (long) W3_TARGET_COUNT), 0, List.of()),
        principalCalls);

    assertThatThrownBy(() -> runner.run(command(checkpointPath)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("checkpoint does not match");
    assertThat(principalCalls).hasValue(0);
  }

  private QuestionInternalBulkPublishRunner runnerWithState(BulkPublishTargetState state,
      AtomicInteger principalCalls) {
    return new QuestionInternalBulkPublishRunner(new FakeRepository(state), null,
        new QuestionInternalBulkPublishRunner.PrincipalExecutor() {
          @Override
          public <T> T callAs(Actor actor, java.util.function.Supplier<T> work) {
            principalCalls.incrementAndGet();
            return work.get();
          }
        }, mapper);
  }

  private BulkPublishCommand command(Path checkpointPath) {
    return new BulkPublishCommand("w3-hotfix-test", List.of(2L, 3L), 400,
        W3_TARGET_COUNT, checkpointPath, AUTHOR, REVIEWER, "UNVERIFIED", "INTERNAL_ONLY", null);
  }

  private BulkPublishTargetState state(Map<String, Long> statuses, long pointerMismatch,
      List<BulkPublishUnsafeTarget> samples) {
    long total = statuses.values().stream().mapToLong(Long::longValue).sum();
    return new BulkPublishTargetState(total, pointerMismatch, statuses, samples);
  }

  private List<BulkPublishUnsafeTarget> sample(String status) {
    return List.of(new BulkPublishUnsafeTarget(1001L, 2001L, 2001L, status));
  }

  private Checkpoint checkpoint(String runId, int expectedTargetCount) {
    Checkpoint checkpoint = new Checkpoint();
    checkpoint.schemaVersion = "w3-internal-bulk-publish-checkpoint/1.0";
    checkpoint.runId = runId;
    checkpoint.importBatchIds = List.of(2L, 3L);
    checkpoint.batchSize = 400;
    checkpoint.expectedTargetCount = expectedTargetCount;
    checkpoint.authorUserId = AUTHOR.userId();
    checkpoint.reviewerUserId = REVIEWER.userId();
    checkpoint.rightsStatus = "UNVERIFIED";
    checkpoint.usageRestriction = "INTERNAL_ONLY";
    checkpoint.updatedAt = "2026-07-18T00:00:00Z";
    return checkpoint;
  }

  private static final class FailingAtomicMoveCheckpointStore extends AtomicFileCheckpointStore {
    @Override
    void atomicMove(Path temp, Path target) throws IOException {
      throw new AtomicMoveNotSupportedException(temp.toString(), target.toString(),
          "forced by test");
    }
  }

  private static final class FakeRepository extends JdbcQuestionAdminRepository {
    private final BulkPublishTargetState state;

    FakeRepository(BulkPublishTargetState state) {
      super(new JdbcTemplate(new DriverManagerDataSource(
              "jdbc:h2:mem:w3_bulk_publish_runner_" + UUID.randomUUID()
                  + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")),
          new DataSourceTransactionManager(new DriverManagerDataSource(
              "jdbc:h2:mem:w3_bulk_publish_runner_tx_" + UUID.randomUUID()
                  + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")),
          new ObjectMapper());
      this.state = state;
    }

    @Override
    public BulkPublishTargetState bulkPublishTargetState(List<Long> importBatchIds,
        String copyrightStatus, String usageRestriction) {
      return state;
    }

    @Override
    public List<BulkPublishCandidate> bulkPublishCandidates(List<Long> importBatchIds,
        String copyrightStatus, String usageRestriction, long afterQuestionId, int limit) {
      return List.of();
    }
  }
}
