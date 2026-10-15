package com.aiexam.question.admin.application;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.question.admin.dto.QuestionActionResult;
import com.aiexam.question.admin.dto.QuestionAdminDashboardDto;
import com.aiexam.question.admin.dto.QuestionAnomalyDto;
import com.aiexam.question.admin.dto.QuestionAuditEventDto;
import com.aiexam.question.admin.dto.QuestionCopyRequest;
import com.aiexam.question.admin.dto.QuestionReviewRequest;
import com.aiexam.question.admin.dto.QuestionReviewRecordDto;
import com.aiexam.question.admin.dto.QuestionVersionDetailDto;
import com.aiexam.question.admin.dto.QuestionVersionHistoryDto;
import com.aiexam.question.admin.dto.QuestionVersionSummaryDto;
import com.aiexam.question.admin.dto.QuestionVersionWriteRequest;
import com.aiexam.question.admin.dto.QuestionWorkflowRequest;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.CreatedVersion;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.LockedQuestion;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.VersionContent;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.VersionRow;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.VersionSource;
import com.aiexam.question.dto.request.QuestionCreateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class QuestionAdminApplicationService {
  private static final Set<String> STATUSES = Set.of("DRAFT", "IN_REVIEW", "PUBLISHED", "RETIRED");
  private static final Set<String> QUESTION_TYPES = Set.of(
      "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CASE", "ESSAY", "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH",
      "REQUIREMENT_SPEC", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION");

  private final JdbcQuestionAdminRepository repository;
  private final QuestionAdminCurrentUser currentUser;
  private final ObjectMapper canonicalMapper;
  private final QuestionPayloadValidator payloadValidator;

  public QuestionAdminApplicationService(JdbcQuestionAdminRepository repository,
      QuestionAdminCurrentUser currentUser, ObjectMapper objectMapper) {
    this.repository = repository;
    this.currentUser = currentUser;
    this.canonicalMapper = objectMapper.copy()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    this.payloadValidator = new QuestionPayloadValidator(objectMapper);
  }

  public QuestionActionResult create(QuestionVersionWriteRequest request) {
    PlatformPrincipal principal = currentUser.require("question:create");
    if (request.baseVersionId() != null) {
      throw invalid("baseVersionId must be null when creating a question");
    }
    VersionContent content = validateAndNormalize(request.payload());
    validateSourceMetadata(request);
    repository.requireScope(content.payload().qualificationId(), content.payload().subjectId());
    String contentHash = hash(content.payload());
    String operation = "CREATE_QUESTION";
    String scope = "NEW";
    String requestHash = hash(List.of(operation, contentHash));
    return repository.inTransaction(() -> {
      var replay = repository.beginMutation(principal.userId(), operation, scope,
          request.clientMutationId(), requestHash);
      if (replay.isPresent()) {
        return replay.get();
      }
      CreatedVersion created = repository.createQuestion(content,
          VersionSource.manual(content.payload(), request.copyrightStatus(),
              request.usageRestriction(), contentHash), principal.userId(), null);
      QuestionActionResult result = created.result(false);
      repository.appendAudit(created.questionId(), created.versionId(), operation,
          principal.userId(), request.clientMutationId(), result);
      repository.completeMutation(principal.userId(), operation, scope,
          request.clientMutationId(), result);
      return result;
    });
  }

  public QuestionActionResult createRevision(long questionId, QuestionVersionWriteRequest request) {
    PlatformPrincipal principal = currentUser.require("question:update");
    if (request.baseVersionId() == null) {
      throw invalid("baseVersionId is required when creating a revision");
    }
    VersionContent content = validateAndNormalize(request.payload());
    validateSourceMetadata(request);
    String contentHash = hash(content.payload());
    String operation = "CREATE_REVISION";
    String scope = "QUESTION:" + questionId;
    String requestHash = hash(List.of(operation, questionId, request.baseVersionId(), contentHash));
    return repository.inTransaction(() -> {
      var replay = repository.beginMutation(principal.userId(), operation, scope,
          request.clientMutationId(), requestHash);
      if (replay.isPresent()) {
        return replay.get();
      }
      LockedQuestion question = repository.lockQuestion(questionId);
      Long base = question.currentWorkingVersionId() == null
          ? question.currentPublishedVersionId() : question.currentWorkingVersionId();
      if (base == null || !base.equals(request.baseVersionId())) {
        throw conflict("baseVersionId is not the current working/published version");
      }
      VersionRow baseVersion = repository.lockVersion(questionId, base);
      if (question.currentWorkingVersionId() != null && !"DRAFT".equals(baseVersion.status())) {
        throw conflict("An IN_REVIEW version cannot be replaced by a revision");
      }
      if (baseVersion.authorId() != null && !Objects.equals(baseVersion.authorId(), principal.userId())
          && !currentUser.isAdmin(principal)) {
        throw forbidden("Only the draft author or QUESTION_ADMIN may create its revision");
      }
      if (question.qualificationId() != content.payload().qualificationId()
          || question.subjectId() != content.payload().subjectId()
          || !question.questionType().equalsIgnoreCase(content.payload().questionType())) {
        throw invalid("qualificationId, subjectId and questionType are immutable");
      }
      repository.requireScope(content.payload().qualificationId(), content.payload().subjectId());
      VersionSource source = baseVersion.importBatchId() == null
          ? VersionSource.manual(content.payload(), request.copyrightStatus(),
              request.usageRestriction(), contentHash)
          : repository.versionSource(questionId, base, contentHash);
      CreatedVersion created = baseVersion.importBatchId() == null
          ? repository.createRevision(question, content, source, principal.userId())
          : repository.createImportedReviewRevision(question, content, source,
              principal.userId(), baseVersion.importBatchId(), base);
      QuestionActionResult result = created.result(false);
      repository.appendAudit(questionId, created.versionId(), operation, principal.userId(),
          request.clientMutationId(), result);
      repository.completeMutation(principal.userId(), operation, scope,
          request.clientMutationId(), result);
      return result;
    });
  }

  public QuestionActionResult copyAsDraft(long questionId, long sourceVersionId,
      QuestionCopyRequest request) {
    PlatformPrincipal principal = currentUser.require("question:update");
    String operation = "COPY_VERSION";
    String scope = "VERSION:" + questionId + ":" + sourceVersionId;
    String requestHash = hash(List.of(operation, questionId, sourceVersionId));
    return repository.inTransaction(() -> {
      var replay = repository.beginMutation(principal.userId(), operation, scope,
          request.clientMutationId(), requestHash);
      if (replay.isPresent()) {
        return replay.get();
      }
      LockedQuestion question = repository.lockQuestion(questionId);
      if (question.currentWorkingVersionId() != null) {
        throw conflict("A working DRAFT/IN_REVIEW version already exists");
      }
      VersionRow sourceVersion = repository.lockVersion(questionId, sourceVersionId);
      VersionContent content = validateAndNormalize(
          repository.detail(questionId, sourceVersionId).payload());
      CreatedVersion created = repository.createRevision(question, content,
          repository.versionSource(questionId, sourceVersionId, sourceVersion.contentHash()),
          principal.userId());
      QuestionActionResult result = created.result(false);
      repository.appendAudit(questionId, created.versionId(), operation, principal.userId(),
          request.clientMutationId(),
          java.util.Map.of("sourceVersionId", sourceVersionId, "result", result));
      repository.completeMutation(principal.userId(), operation, scope,
          request.clientMutationId(), result);
      return result;
    });
  }

  public QuestionActionResult submitReview(long questionId, long versionId,
      QuestionWorkflowRequest request) {
    PlatformPrincipal principal = currentUser.require("question:submit-review");
    return workflow(questionId, versionId, request, principal, "SUBMIT_REVIEW", () -> {
      LockedQuestion question = repository.lockQuestion(questionId);
      VersionRow version = repository.lockVersion(questionId, versionId);
      requireCurrentWorking(question, versionId);
      requireAuthorOrAdmin(version, principal);
      return repository.submitReview(questionId, versionId, request.expectedRowVersion(),
          principal.userId());
    });
  }

  public QuestionActionResult review(long questionId, long versionId,
      QuestionReviewRequest request) {
    PlatformPrincipal principal = currentUser.require("question:review");
    String decision = upper(request.decision());
    if (!Set.of("APPROVED", "REJECTED").contains(decision)) {
      throw invalid("Review decision must be APPROVED or REJECTED");
    }
    if ("REJECTED".equals(decision)
        && (request.reason() == null || request.reason().isBlank())) {
      throw invalid("A rejection reason is required");
    }
    QuestionWorkflowRequest workflow = new QuestionWorkflowRequest(request.clientMutationId(),
        request.expectedRowVersion(), request.reason());
    return workflow(questionId, versionId, workflow, principal, decision + "_REVIEW", () -> {
      LockedQuestion question = repository.lockQuestion(questionId);
      VersionRow version = repository.lockVersion(questionId, versionId);
      requireCurrentWorking(question, versionId);
      if (!"IN_REVIEW".equals(version.status()) || version.submittedBy() == null) {
        throw conflict("Question version is not awaiting review");
      }
      return "APPROVED".equals(decision)
          ? repository.approve(questionId, versionId, request.expectedRowVersion(),
              version.submittedBy(), principal.userId(), request.reason())
          : repository.reject(questionId, versionId, request.expectedRowVersion(),
              version.submittedBy(), principal.userId(), request.reason());
    });
  }

  public QuestionActionResult publish(long questionId, long versionId,
      QuestionWorkflowRequest request) {
    PlatformPrincipal principal = currentUser.require("question:publish");
    return workflow(questionId, versionId, request, principal, "PUBLISH", () -> {
      LockedQuestion question = repository.lockQuestion(questionId);
      VersionRow version = repository.lockVersion(questionId, versionId);
      requireCurrentWorking(question, versionId);
      return repository.publish(question, version, request.expectedRowVersion(), principal.userId());
    });
  }

  public QuestionActionResult retire(long questionId, long versionId,
      QuestionWorkflowRequest request) {
    PlatformPrincipal principal = currentUser.require("question:publish");
    return workflow(questionId, versionId, request, principal, "RETIRE", () -> {
      LockedQuestion question = repository.lockQuestion(questionId);
      if (!Long.valueOf(versionId).equals(question.currentPublishedVersionId())) {
        throw conflict("Only currentPublishedVersionId may be retired");
      }
      VersionRow version = repository.lockVersion(questionId, versionId);
      return repository.retire(question, version, request.expectedRowVersion(), principal.userId());
    });
  }

  public QuestionVersionDetailDto detail(long questionId, long versionId) {
    currentUser.require("question:read");
    return repository.detail(questionId, versionId);
  }

  public PageResponse<QuestionVersionSummaryDto> list(long pageNo, long pageSize,
      String status, Long questionId, Long qualificationId, Long subjectId, String keyword, String questionType,
      String sourceType, String copyrightStatus) {
    currentUser.require("question:read");
    if (status != null && !status.isBlank() && !STATUSES.contains(upper(status))) {
      throw invalid("Unsupported question version status");
    }
    if (questionType != null && !questionType.isBlank()
        && !QUESTION_TYPES.contains(upper(questionType))) {
      throw invalid("Unsupported questionType");
    }
    String normalizedKeyword = keyword == null ? null : keyword.trim();
    if (normalizedKeyword != null && normalizedKeyword.length() > 200) {
      throw invalid("keyword must be at most 200 characters");
    }
    long normalizedPage = Math.max(pageNo, 1);
    long normalizedSize = Math.min(Math.max(pageSize, 1), 100);
    return repository.list(normalizedPage, normalizedSize, status, questionId, qualificationId, subjectId,
        normalizedKeyword, questionType, sourceType, copyrightStatus);
  }

  public List<QuestionVersionHistoryDto> versionHistory(long questionId) {
    currentUser.require("question:read");
    return repository.versionHistory(questionId);
  }

  public List<QuestionReviewRecordDto> reviewRecords(long questionId, long versionId) {
    currentUser.require("question:read");
    repository.detail(questionId, versionId);
    return repository.reviewRecords(questionId, versionId);
  }

  public List<QuestionAuditEventDto> auditEvents(long questionId) {
    currentUser.require("question:read");
    return repository.auditEvents(questionId);
  }

  public QuestionAdminDashboardDto dashboard() {
    currentUser.require("question:read");
    return repository.dashboard();
  }

  public List<QuestionAnomalyDto> anomalies(String anomalyType, String status) {
    currentUser.require("question:read");
    repository.refreshAnomalies();
    return repository.listAnomalies(anomalyType, status);
  }

  public long openAnomalyCount() {
    currentUser.require("question:read");
    repository.refreshAnomalies();
    return repository.countOpenAnomalies();
  }

  public void resolveAnomaly(long anomalyId, String status, String note) {
    var principal = currentUser.require("question:update");
    if (!Set.of("IN_PROGRESS", "FIXED", "IGNORED", "FALSE_POSITIVE").contains(status)) {
      throw invalid("Unsupported anomaly status");
    }
    repository.resolveAnomaly(anomalyId, status, note, principal.userId());
  }

  private QuestionActionResult workflow(long questionId, long versionId,
      QuestionWorkflowRequest request, PlatformPrincipal principal, String operation,
      SupplierWithException<VersionRow> transition) {
    String scope = "VERSION:" + questionId + ":" + versionId;
    String requestHash = hash(List.of(operation, questionId, versionId,
        request.expectedRowVersion(), request.reason() == null ? "" : request.reason()));
    return repository.inTransaction(() -> {
      var replay = repository.beginMutation(principal.userId(), operation, scope,
          request.clientMutationId(), requestHash);
      if (replay.isPresent()) {
        return replay.get();
      }
      VersionRow changed = transition.get();
      QuestionActionResult result = changed.result();
      repository.appendAudit(questionId, versionId, operation, principal.userId(),
          request.clientMutationId(), result);
      repository.completeMutation(principal.userId(), operation, scope,
          request.clientMutationId(), result);
      return result;
    });
  }

  private VersionContent validateAndNormalize(QuestionCreateRequest payload) {
    return payloadValidator.validate(payload);
  }

  private void validateSourceMetadata(QuestionVersionWriteRequest request) {
    String sourceType = upper(request.payload().sourceType() == null
        ? "MANUAL" : request.payload().sourceType());
    if (!Set.of("MANUAL", "ORIGINAL", "AUTHORIZED_IMPORT", "SOURCE_DOCUMENT_IMPORT")
        .contains(sourceType)) {
      throw invalid("Unsupported sourceType");
    }
    String copyrightStatus = upper(request.copyrightStatus());
    if (copyrightStatus != null
        && !Set.of("UNVERIFIED", "ORIGINAL_DECLARED", "AUTHORIZED").contains(copyrightStatus)) {
      throw invalid("Unsupported copyrightStatus");
    }
    if ("ORIGINAL".equals(sourceType) && copyrightStatus != null
        && !"ORIGINAL_DECLARED".equals(copyrightStatus)) {
      throw invalid("ORIGINAL source requires ORIGINAL_DECLARED copyrightStatus");
    }
    if ("AUTHORIZED_IMPORT".equals(sourceType)) {
      if (request.payload().sourceRef() == null || request.payload().sourceRef().isBlank()) {
        throw invalid("AUTHORIZED_IMPORT source requires sourceRef");
      }
      if (copyrightStatus != null && !"AUTHORIZED".equals(copyrightStatus)) {
        throw invalid("AUTHORIZED_IMPORT source requires AUTHORIZED copyrightStatus");
      }
    }
    if ("SOURCE_DOCUMENT_IMPORT".equals(sourceType) && copyrightStatus != null
        && !"UNVERIFIED".equals(copyrightStatus)) {
      throw invalid("SOURCE_DOCUMENT_IMPORT source must remain UNVERIFIED");
    }
    if (request.usageRestriction() != null && request.usageRestriction().length() > 255) {
      throw invalid("usageRestriction must be at most 255 characters");
    }
  }

  private void requireCurrentWorking(LockedQuestion question, long versionId) {
    if (!Long.valueOf(versionId).equals(question.currentWorkingVersionId())) {
      throw conflict("Operation target is not currentWorkingVersionId");
    }
  }

  private void requireAuthorOrAdmin(VersionRow version, PlatformPrincipal principal) {
    if (version.authorId() != null && !Objects.equals(version.authorId(), principal.userId())
        && !currentUser.isAdmin(principal)) {
      throw forbidden("Only the draft author or QUESTION_ADMIN may submit it");
    }
  }

  private String hash(Object value) {
    try {
      byte[] bytes = canonicalMapper.writeValueAsBytes(value);
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Cannot hash canonical question command", exception);
    }
  }

  private String upper(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private BusinessException invalid(String message) {
    return new BusinessException("QUESTION_ADMIN_VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
  }

  private BusinessException conflict(String message) {
    return new BusinessException("QUESTION_VERSION_CONFLICT", message, HttpStatus.CONFLICT);
  }

  private BusinessException forbidden(String message) {
    return new BusinessException("QUESTION_ADMIN_FORBIDDEN", message, HttpStatus.FORBIDDEN);
  }

  @FunctionalInterface
  private interface SupplierWithException<T> {
    T get();
  }
}
