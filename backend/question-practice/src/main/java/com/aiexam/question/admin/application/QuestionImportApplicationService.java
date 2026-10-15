package com.aiexam.question.admin.application;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ResourceNotFoundException;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.question.admin.dto.QuestionImportBatchDetailDto;
import com.aiexam.question.admin.dto.QuestionImportBatchSummaryDto;
import com.aiexam.question.admin.dto.QuestionImportItemDto;
import com.aiexam.question.admin.dto.QuestionImportRequest;
import com.aiexam.question.admin.dto.QuestionImportPublishResultDto;
import com.aiexam.question.admin.dto.QuestionImportResultDto;
import com.aiexam.question.admin.dto.QuestionWorkbookValidationDto;
import com.aiexam.question.admin.importing.QuestionCanonicalImportReader;
import com.aiexam.question.admin.importing.StrictQuestionWorkbookValidator;
import com.aiexam.question.admin.importing.QuestionCanonicalImportReader.CanonicalBatch;
import com.aiexam.question.admin.importing.QuestionCanonicalImportReader.CanonicalItem;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.CreatedVersion;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.ExistingImportTarget;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.ImportBatch;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.ImportItem;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.PendingKnowledgeBinding;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.Scope;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.VersionContent;
import com.aiexam.question.dto.request.QuestionCreateRequest;
import com.aiexam.question.application.PaperApplicationService;
import com.aiexam.question.dto.request.PaperCreateRequest;
import com.aiexam.question.dto.request.PaperQuestionAddRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class QuestionImportApplicationService {
  private static final String RIGHTS = "UNVERIFIED";
  private static final String RESTRICTION = "INTERNAL_ONLY";
  private static final Pattern IMAGE_MARKER = Pattern.compile(
      "\\[图片:\\s*([0-9a-f]{16}\\.(?:png|jpe?g|gif|webp))]", Pattern.CASE_INSENSITIVE);

  private final JdbcQuestionAdminRepository repository;
  private final QuestionAdminCurrentUser currentUser;
  private final QuestionCanonicalImportReader reader;
  private final StrictQuestionWorkbookValidator workbookValidator;
  private final QuestionPayloadValidator payloadValidator;
  private final PaperApplicationService paperApplicationService;
  private final String imageRoot;

  public QuestionImportApplicationService(JdbcQuestionAdminRepository repository,
      QuestionAdminCurrentUser currentUser, ObjectMapper objectMapper,
      @Value("${aiexam.question-import.allowed-root:}") String allowedRoot) {
    this(repository, currentUser, objectMapper,
        allowedRoot, null, "");
  }

  public QuestionImportApplicationService(JdbcQuestionAdminRepository repository,
      QuestionAdminCurrentUser currentUser, ObjectMapper objectMapper, Path allowedRoot) {
    this(repository, currentUser, objectMapper,
        allowedRoot == null ? null : allowedRoot.toString(), null, "");
  }

  public QuestionImportApplicationService(JdbcQuestionAdminRepository repository,
      QuestionAdminCurrentUser currentUser, ObjectMapper objectMapper, String allowedRoot,
      PaperApplicationService paperApplicationService) {
    this(repository, currentUser, objectMapper, allowedRoot, paperApplicationService, "");
  }

  @Autowired
  public QuestionImportApplicationService(JdbcQuestionAdminRepository repository,
      QuestionAdminCurrentUser currentUser, ObjectMapper objectMapper,
      @Value("${aiexam.question-import.allowed-root:}") String allowedRoot,
      PaperApplicationService paperApplicationService,
      @Value("${aiexam.question-assets.root:}") String imageRoot) {
    this.repository = repository;
    this.currentUser = currentUser;
    Path root = allowedRoot == null || allowedRoot.isBlank() ? null : Path.of(allowedRoot);
    this.reader = new QuestionCanonicalImportReader(root, objectMapper);
    this.workbookValidator = new StrictQuestionWorkbookValidator(root, objectMapper);
    this.payloadValidator = new QuestionPayloadValidator(objectMapper);
    this.paperApplicationService = paperApplicationService;
    this.imageRoot = imageRoot == null ? "" : imageRoot.trim();
  }

  /** Pure dry-run. It deliberately does not invoke any repository method. */
  public QuestionImportResultDto preflight(QuestionImportRequest request) {
    currentUser.require("question:import");
    CanonicalBatch batch = readBatch(request.inputPath());
    return result(null, null, batch, 0, 0, false,
        batch.items().stream().map(this::dryRunItem).toList());
  }

  /** Pure structural validation; incompatible workbooks fail closed and are never imported. */
  public QuestionWorkbookValidationDto validateWorkbook(QuestionImportRequest request) {
    currentUser.require("question:import");
    return workbookValidator.validate(request.inputPath());
  }

  public QuestionImportResultDto confirm(QuestionImportRequest request) {
    PlatformPrincipal principal = currentUser.require("question:import");
    if (request.clientMutationId() == null) {
      throw new BusinessException("QUESTION_IMPORT_MUTATION_ID_REQUIRED",
          "clientMutationId is required for confirmation", HttpStatus.BAD_REQUEST);
    }
    CanonicalBatch batch = readBatch(request.inputPath());
    if (batch.eligibleCount() == 0) {
      throw new BusinessException("QUESTION_IMPORT_NO_ELIGIBLE_ROWS",
          "Canonical JSONL has no eligible question to confirm", HttpStatus.BAD_REQUEST);
    }
    String operation = "CONFIRM_IMPORT";
    String scope = "CANONICAL:" + batch.sha256();
    String requestHash = batch.sha256();
    return repository.inTransaction(() -> {
      var replay = repository.beginImportMutation(principal.userId(), operation, scope,
          request.clientMutationId(), requestHash);
      if (replay.isPresent()) {
        return replay.get();
      }
      long batchId = repository.insertImportBatch(principal.userId(), request.clientMutationId(),
          batch.canonicalPath().toString(), batch.sha256(), batch.items().size(),
          batch.eligibleCount(), batch.sourceFileName(), batch.sourceFileSha256());
      Long paperId = createDraftPaper(batch);
      if (paperId != null) {
        repository.linkImportPaper(batchId, paperId);
      }
      List<QuestionImportResultDto.Item> results = new ArrayList<>();
      int imported = 0;
      int reused = 0;
      for (CanonicalItem item : batch.items()) {
        if (!item.eligible()) {
          repository.insertImportItem(batchId, item.lineNo(), item.externalKey(), item.contentHash(),
              false, "SKIPPED_INELIGIBLE", null, null, item.canonicalJson(), item.errorCode());
          results.add(dryRunItem(item));
          continue;
        }
        Scope scopeIds = repository.requireScope(item.qualificationCode(), item.subjectCode());
        VersionContent content = payloadValidator.validate(withScope(item.content(), scopeIds).payload());
        ExistingImportTarget existing = repository.findExactImportTarget(scopeIds,
            item.externalKey(), item.contentHash()).orElse(null);
        if (existing != null) {
          if (paperId != null) {
            paperApplicationService.addQuestion(paperId, new PaperQuestionAddRequest(
                existing.questionId(), null, questionNo(item), paperScore(content.payload())));
          }
          repository.insertImportItem(batchId, item.lineNo(), item.externalKey(), item.contentHash(),
              true, "SKIPPED_INELIGIBLE", existing.questionId(), existing.questionVersionId(),
              item.canonicalJson(), "EXACT_EXISTING_QUESTION");
          results.add(new QuestionImportResultDto.Item(item.lineNo(), item.externalKey(), true,
              "EXACT_EXISTING_QUESTION", existing.questionId(), existing.questionVersionId()));
          reused++;
          continue;
        }
        CreatedVersion created = repository.createQuestion(content, item.source(),
            principal.userId(), batchId);
        if (paperId != null) {
          paperApplicationService.addQuestion(paperId, new PaperQuestionAddRequest(
              created.questionId(), null, questionNo(item), paperScore(content.payload())));
        }
        repository.insertImportItem(batchId, item.lineNo(), item.externalKey(), item.contentHash(),
            true, "IMPORTED_DRAFT", created.questionId(), created.versionId(),
            item.canonicalJson(), null);
        repository.appendAudit(created.questionId(), created.versionId(), "IMPORT_DRAFT",
            principal.userId(), request.clientMutationId(),
            java.util.Map.of("batchId", batchId, "lineNo", item.lineNo(),
                "rightsStatus", RIGHTS, "usageRestriction", RESTRICTION));
        results.add(new QuestionImportResultDto.Item(item.lineNo(), item.externalKey(), true,
            null, created.questionId(), created.versionId()));
        imported++;
      }
      registerImportAssets(batchId, batch);
      repository.completeImportBatch(batchId, imported);
      QuestionImportResultDto response = result(batchId, paperId, batch, imported, reused,
          false, results);
      repository.completeMutation(principal.userId(), operation, scope,
          request.clientMutationId(), response);
      return response;
    });
  }

  public QuestionImportResultDto rollback(long batchId, QuestionImportRequest.Rollback request) {
    PlatformPrincipal principal = currentUser.require("question:import");
    String operation = "ROLLBACK_IMPORT";
    String scope = "BATCH:" + batchId;
    String requestHash = Long.toString(batchId);
    return repository.inTransaction(() -> {
      var replay = repository.beginImportMutation(principal.userId(), operation, scope,
          request.clientMutationId(), requestHash);
      if (replay.isPresent()) {
        return replay.get();
      }
      ImportBatch batch = repository.lockImportBatch(batchId);
      if (!"PENDING_REVIEW".equals(batch.status())) {
        throw new BusinessException("QUESTION_IMPORT_BATCH_STATE_CONFLICT",
            "Only a pending review batch may be rolled back", HttpStatus.CONFLICT);
      }
      if (batch.actorId() != principal.userId() && !currentUser.isAdmin(principal)) {
        throw new BusinessException("QUESTION_IMPORT_FORBIDDEN",
            "Only the importing actor or QUESTION_ADMIN may roll back the batch",
            HttpStatus.FORBIDDEN);
      }
      List<ImportItem> items = repository.lockImportedItems(batchId);
      List<QuestionImportResultDto.Item> resultItems = new ArrayList<>();
      for (ImportItem item : items) {
        repository.rollbackImportedDraft(item, batchId, principal.userId());
        repository.appendAudit(item.questionId(), item.questionVersionId(), "ROLLBACK_IMPORT_DRAFT",
            principal.userId(), request.clientMutationId(), java.util.Map.of("batchId", batchId));
        resultItems.add(new QuestionImportResultDto.Item(0, "ROLLED_BACK", true, null,
            item.questionId(), item.questionVersionId()));
      }
      if (batch.paperId() != null) {
        if (paperApplicationService == null) {
          throw new BusinessException("QUESTION_IMPORT_PAPER_SERVICE_UNAVAILABLE",
              "试卷服务不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
        paperApplicationService.discardDraftPaper(batch.paperId());
      }
      repository.markImportRolledBack(batchId);
      QuestionImportResultDto response = new QuestionImportResultDto(batchId, batch.paperId(), null,
          items.size(), items.size(), 0, 0, 0, 0, false, RIGHTS, RESTRICTION, resultItems);
      repository.completeMutation(principal.userId(), operation, scope,
          request.clientMutationId(), response);
      return response;
    });
  }

  public QuestionImportPublishResultDto publish(long batchId, QuestionImportRequest.Publish request) {
    PlatformPrincipal principal = currentUser.require("question:publish");
    if (!currentUser.isAdmin(principal)) {
      throw new BusinessException("QUESTION_IMPORT_BATCH_PUBLISH_FORBIDDEN",
          "Only a question administrator can release an import batch", HttpStatus.FORBIDDEN);
    }
    return repository.inTransaction(() -> {
      ImportBatch batch = repository.lockImportBatch(batchId);
      if (!"PENDING_REVIEW".equals(batch.status())) {
        throw new BusinessException("QUESTION_IMPORT_BATCH_STATE_CONFLICT",
            "Only a pending review import batch may be published", HttpStatus.CONFLICT);
      }
      List<ImportItem> items = repository.lockImportedItems(batchId);
      for (ImportItem item : items) {
        repository.publishImportedDraft(item, batchId, batch.actorId(), principal.userId());
        repository.appendAudit(item.questionId(), item.questionVersionId(), "BATCH_IMPORT_PUBLISH",
            principal.userId(), request.clientMutationId(), java.util.Map.of("batchId", batchId));
      }
      if (batch.paperId() != null) {
        if (paperApplicationService == null) {
          throw new BusinessException("QUESTION_IMPORT_PAPER_SERVICE_UNAVAILABLE",
              "试卷发布服务不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
        paperApplicationService.publishImportedPaper(batch.paperId());
      }
      repository.markImportPublished(batchId, principal.userId(), "整卷审核通过并发布");
      return new QuestionImportPublishResultDto(batchId, batch.paperId(), items.size(),
          "PUBLISHED");
    });
  }

  public void reject(long batchId, QuestionImportRequest.Reject request) {
    PlatformPrincipal principal = currentUser.require("question:publish");
    if (!currentUser.isAdmin(principal)) {
      throw new BusinessException("QUESTION_IMPORT_BATCH_REJECT_FORBIDDEN",
          "只有管理员可以驳回导入试卷", HttpStatus.FORBIDDEN);
    }
    repository.inTransaction(() -> {
      ImportBatch batch = repository.lockImportBatch(batchId);
      if (!"PENDING_REVIEW".equals(batch.status())) {
        throw new BusinessException("QUESTION_IMPORT_BATCH_STATE_CONFLICT",
            "只有待审核试卷可以驳回", HttpStatus.CONFLICT);
      }
      List<ImportItem> items = repository.lockImportedItems(batchId);
      for (ImportItem item : items) {
        repository.rollbackImportedDraft(item, batchId, principal.userId());
        repository.appendAudit(item.questionId(), item.questionVersionId(),
            "REJECT_IMPORT_PAPER", principal.userId(), request.clientMutationId(),
            java.util.Map.of("batchId", batchId, "reason", request.reason()));
      }
      if (batch.paperId() != null) {
        if (paperApplicationService == null) {
          throw new BusinessException("QUESTION_IMPORT_PAPER_SERVICE_UNAVAILABLE",
              "试卷服务不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
        paperApplicationService.discardDraftPaper(batch.paperId());
      }
      repository.markImportRejected(batchId, principal.userId(), request.reason());
      return null;
    });
  }

  private Long createDraftPaper(CanonicalBatch batch) {
    if (paperApplicationService == null || batch.paperKind() == null
        || batch.qualificationCode() == null || batch.subjectCode() == null) {
      return null;
    }
    Scope scope = repository.requireScope(batch.qualificationCode(), batch.subjectCode());
    String paperType = "REAL_PAPER".equals(batch.paperKind()) ? "REAL" : "MOCK";
    String name = batch.paperName() == null || batch.paperName().isBlank()
        ? batch.canonicalPath().getFileName().toString() : batch.paperName();
    BigDecimal totalScore = batch.totalScore() == null
        ? BigDecimal.valueOf(batch.eligibleCount()) : batch.totalScore();
    return paperApplicationService.createPaper(new PaperCreateRequest(scope.qualificationId(),
        scope.subjectId(), name, paperType, batch.yearNo(), totalScore,
        batch.durationMinutes() == null ? 150 : batch.durationMinutes())).paperId();
  }

  public PageResponse<QuestionImportBatchSummaryDto> listBatches(long pageNo, long pageSize,
      String status) {
    currentUser.require("question:import");
    if (status != null && !status.isBlank()
        && !Set.of("PENDING_REVIEW", "PUBLISHED", "REJECTED", "ROLLED_BACK")
            .contains(status.trim().toUpperCase(Locale.ROOT))) {
      throw new BusinessException("QUESTION_IMPORT_BATCH_STATUS_INVALID",
          "Unsupported question import batch status", HttpStatus.BAD_REQUEST);
    }
    long normalizedPage = Math.max(1, pageNo);
    long normalizedSize = Math.min(100, Math.max(1, pageSize));
    return repository.listImportBatches(normalizedPage, normalizedSize, status);
  }

  public QuestionImportBatchDetailDto batchDetail(long batchId) {
    currentUser.require("question:import");
    return repository.importBatchDetail(batchId);
  }

  public byte[] errorCsv(long batchId) {
    QuestionImportBatchDetailDto detail = batchDetail(batchId);
    StringBuilder csv = new StringBuilder("lineNo,externalKey,errorCode,itemStatus\r\n");
    for (QuestionImportItemDto item : detail.items()) {
      if (item.errorCode() == null && item.eligible()) {
        continue;
      }
      csv.append(item.lineNo()).append(',')
          .append(csv(item.externalKey())).append(',')
          .append(csv(item.errorCode())).append(',')
          .append(csv(item.itemStatus())).append("\r\n");
    }
    return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private VersionContent withScope(VersionContent original, Scope scope) {
    QuestionCreateRequest source = original.payload();
    List<QuestionCreateRequest.KnowledgeBindingRequest> questionBindings = new ArrayList<>(
        source.knowledgeBindings() == null ? List.of() : source.knowledgeBindings());
    java.util.Map<Integer, List<QuestionCreateRequest.KnowledgeBindingRequest>> subBindings =
        new java.util.LinkedHashMap<>();
    for (PendingKnowledgeBinding pending : original.pendingKnowledgeBindings()) {
      var resolved = repository.requireKnowledgeBinding(scope, pending);
      if (pending.subQuestionNo() == null) {
        questionBindings.add(resolved);
      } else {
        subBindings.computeIfAbsent(pending.subQuestionNo(), ignored -> new ArrayList<>()).add(resolved);
      }
    }
    List<QuestionCreateRequest.SubQuestionRequest> subQuestions = source.subQuestions().stream()
        .map(sub -> {
          List<QuestionCreateRequest.KnowledgeBindingRequest> bindings = new ArrayList<>(
              sub.knowledgeBindings() == null ? List.of() : sub.knowledgeBindings());
          bindings.addAll(subBindings.getOrDefault(sub.subQuestionNo(), List.of()));
          return new QuestionCreateRequest.SubQuestionRequest(sub.subQuestionNo(),
              sub.subQuestionType(), sub.content(), sub.score(), sub.answerFormat(),
              sub.gradingMode(), sub.sortOrder(), sub.metadataJson(), sub.options(),
              sub.answerKeys(), sub.solutions(), sub.scoringRules(), sub.scoringPoints(), bindings);
        }).toList();
    QuestionCreateRequest scoped = new QuestionCreateRequest(scope.qualificationId(),
        scope.subjectId(), source.questionType(), source.title(), source.yearNo(), source.difficulty(),
        source.sourceType(), source.sourceRef(), source.stems(), subQuestions, questionBindings);
    return new VersionContent(scoped, original.scoringPoints());
  }

  private QuestionImportResultDto.Item dryRunItem(CanonicalItem item) {
    return new QuestionImportResultDto.Item(item.lineNo(), item.externalKey(), item.eligible(),
        item.errorCode(), null, null);
  }

  private String csv(String value) {
    if (value == null) {
      return "";
    }
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private CanonicalBatch readBatch(String inputPath) {
    return inputPath != null && inputPath.toLowerCase(Locale.ROOT).endsWith(".xlsx")
        ? workbookValidator.readBatch(inputPath) : reader.read(inputPath);
  }

  private int questionNo(CanonicalItem item) {
    return item.source().originalQuestionNo() == null ? item.lineNo()
        : item.source().originalQuestionNo();
  }

  public QuestionImportBatchDetailDto batchDetailByPaper(long paperId) {
    currentUser.require("question:import");
    Long batchId = repository.findImportBatchIdByPaper(paperId);
    if (batchId == null) {
      throw new ResourceNotFoundException("该试卷没有关联的导入审核批次");
    }
    return repository.importBatchDetail(batchId);
  }

  private BigDecimal paperScore(QuestionCreateRequest payload) {
    BigDecimal score = payload.subQuestions().stream()
        .map(QuestionCreateRequest.SubQuestionRequest::score)
        .filter(java.util.Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return score.signum() > 0 ? score : BigDecimal.ONE;
  }

  private void registerImportAssets(long batchId, CanonicalBatch batch) {
    int sourceOrder = 0;
    for (CanonicalItem item : batch.items()) {
      if (!item.eligible()) {
        continue;
      }
      int questionNo = questionNo(item);
      Set<String> fileNames = new LinkedHashSet<>();
      QuestionCreateRequest payload = item.content().payload();
      payload.stems().forEach(stem -> collectImageFileNames(stem.content(), fileNames));
      payload.subQuestions().forEach(subQuestion -> {
        collectImageFileNames(subQuestion.content(), fileNames);
        if (subQuestion.options() != null) {
          subQuestion.options().forEach(option ->
              collectImageFileNames(option.optionContent(), fileNames));
        }
      });
      for (String fileName : fileNames) {
        sourceOrder++;
        registerImportAsset(batchId, questionNo, fileName, sourceOrder);
      }
    }
  }

  private void collectImageFileNames(String content, Set<String> fileNames) {
    if (content == null || content.isBlank()) {
      return;
    }
    Matcher matcher = IMAGE_MARKER.matcher(content);
    while (matcher.find()) {
      fileNames.add(matcher.group(1).toLowerCase(Locale.ROOT));
    }
  }

  private void registerImportAsset(long batchId, int questionNo, String fileName,
      int sourceOrder) {
    if (imageRoot.isBlank()) {
      throw new BusinessException("QUESTION_IMPORT_IMAGE_ROOT_REQUIRED",
          "题目包含图片，但题目图片存储目录未配置", HttpStatus.BAD_REQUEST);
    }
    Path root = Path.of(imageRoot).toAbsolutePath().normalize();
    Path imagePath = root.resolve(fileName).normalize();
    if (!imagePath.startsWith(root) || !Files.isRegularFile(imagePath)) {
      throw new BusinessException("QUESTION_IMPORT_IMAGE_NOT_FOUND",
          "题目图片不存在：" + fileName, HttpStatus.BAD_REQUEST);
    }
    try {
      byte[] bytes = Files.readAllBytes(imagePath);
      var bufferedImage = ImageIO.read(new ByteArrayInputStream(bytes));
      Integer width = bufferedImage == null ? null : bufferedImage.getWidth();
      Integer height = bufferedImage == null ? null : bufferedImage.getHeight();
      String mediaType = Files.probeContentType(imagePath);
      if (mediaType == null || mediaType.isBlank()) {
        mediaType = mediaType(fileName);
      }
      repository.insertImportAsset(batchId, "Q" + questionNo + ":" + fileName, sourceOrder,
          sha256(bytes), fileName, mediaType, bytes.length, width, height, questionNo);
    } catch (Exception exception) {
      throw new BusinessException("QUESTION_IMPORT_IMAGE_READ_FAILED",
          "读取题目图片失败：" + fileName, HttpStatus.BAD_REQUEST);
    }
  }

  private String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }

  private String mediaType(String fileName) {
    String normalized = fileName.toLowerCase(Locale.ROOT);
    if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")) {
      return "image/jpeg";
    }
    if (normalized.endsWith(".gif")) {
      return "image/gif";
    }
    if (normalized.endsWith(".webp")) {
      return "image/webp";
    }
    return "image/png";
  }

  private QuestionImportResultDto result(Long batchId, Long paperId, CanonicalBatch batch,
      int imported, int reused, boolean replayed, List<QuestionImportResultDto.Item> items) {
    return new QuestionImportResultDto(batchId, paperId, batch.sha256(), batch.items().size(),
        batch.eligibleCount(), batch.items().size() - batch.eligibleCount(), imported, reused, 0,
        replayed, RIGHTS, RESTRICTION, List.copyOf(items));
  }
}
