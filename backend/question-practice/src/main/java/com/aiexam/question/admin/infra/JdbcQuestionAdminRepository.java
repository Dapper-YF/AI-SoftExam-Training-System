package com.aiexam.question.admin.infra;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.question.admin.dto.QuestionActionResult;
import com.aiexam.question.admin.dto.QuestionAdminDashboardDto;
import com.aiexam.question.admin.dto.QuestionAnomalyDto;
import com.aiexam.question.admin.dto.QuestionAuditEventDto;
import com.aiexam.question.admin.dto.QuestionImportBatchDetailDto;
import com.aiexam.question.admin.dto.QuestionImportBatchSummaryDto;
import com.aiexam.question.admin.dto.QuestionImportItemDto;
import com.aiexam.question.admin.dto.QuestionImportResultDto;
import com.aiexam.question.admin.dto.QuestionReviewRecordDto;
import com.aiexam.question.admin.dto.QuestionVersionDetailDto;
import com.aiexam.question.admin.dto.QuestionVersionHistoryDto;
import com.aiexam.question.admin.dto.QuestionVersionSummaryDto;
import com.aiexam.question.dto.request.QuestionCreateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcQuestionAdminRepository {
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final ObjectMapper objectMapper;

  public JdbcQuestionAdminRepository(JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.objectMapper = objectMapper.copy().findAndRegisterModules();
  }

  public <T> T inTransaction(Supplier<T> work) {
    return transactionTemplate.execute(status -> work.get());
  }

  public Scope requireScope(long qualificationId, long subjectId) {
    try {
      return jdbcTemplate.queryForObject("""
          SELECT q.id AS qualification_id, s.id AS subject_id
          FROM exam_qualification q
          JOIN exam_subject s ON s.qualification_id = q.id
          WHERE q.id = ? AND s.id = ? AND q.deleted = 0 AND s.deleted = 0
            AND q.status = 'ENABLED' AND s.status = 'ENABLED'
          """, (rs, rowNum) -> new Scope(rs.getLong("qualification_id"), rs.getLong("subject_id")),
          qualificationId, subjectId);
    } catch (EmptyResultDataAccessException exception) {
      throw error("QUESTION_SCOPE_INVALID", "Qualification and subject scope is not enabled",
          HttpStatus.BAD_REQUEST);
    }
  }

  public Scope requireScope(String qualificationCode, String subjectCode) {
    try {
      return jdbcTemplate.queryForObject("""
          SELECT q.id AS qualification_id, s.id AS subject_id
          FROM exam_qualification q
          JOIN exam_subject s ON s.qualification_id = q.id
          WHERE q.qualification_code = ? AND s.subject_code = ?
            AND q.deleted = 0 AND s.deleted = 0
            AND q.status = 'ENABLED' AND s.status = 'ENABLED'
          """, (rs, rowNum) -> new Scope(rs.getLong("qualification_id"), rs.getLong("subject_id")),
          qualificationCode, subjectCode);
    } catch (EmptyResultDataAccessException exception) {
      throw error("QUESTION_IMPORT_SCOPE_UNKNOWN",
          "Canonical qualification/subject code does not map to an enabled scope",
          HttpStatus.BAD_REQUEST);
    }
  }

  public QuestionCreateRequest.KnowledgeBindingRequest requireKnowledgeBinding(Scope scope,
      PendingKnowledgeBinding binding) {
    String type = upper(binding.knowledgeType());
    List<Long> ids;
    String persistedType;
    switch (type) {
      case "SYLLABUS_NODE", "SYLLABUS" -> {
        persistedType = "SYLLABUS";
        ids = jdbcTemplate.queryForList("""
            SELECT id FROM kp_syllabus_node
            WHERE qualification_id = ? AND subject_id = ? AND node_code = ?
              AND status = 'ENABLED' AND deleted = 0
            """, Long.class, scope.qualificationId(), scope.subjectId(), binding.knowledgeCode());
      }
      case "CHAPTER_NODE", "CHAPTER" -> {
        persistedType = "CHAPTER";
        ids = jdbcTemplate.queryForList("""
            SELECT id FROM kp_chapter_node
            WHERE qualification_id = ? AND subject_id = ? AND chapter_code = ?
              AND status = 'ENABLED' AND deleted = 0
            """, Long.class, scope.qualificationId(), scope.subjectId(), binding.knowledgeCode());
      }
      case "TAG" -> {
        persistedType = "TAG";
        ids = jdbcTemplate.queryForList("""
            SELECT id FROM kp_tag
            WHERE tag_code = ? AND status = 'ENABLED' AND deleted = 0
              AND (qualification_id IS NULL OR qualification_id = ?)
              AND (subject_id IS NULL OR subject_id = ?)
            """, Long.class, binding.knowledgeCode(), scope.qualificationId(), scope.subjectId());
      }
      default -> throw error("QUESTION_IMPORT_KNOWLEDGE_MAPPING_REQUIRED",
          "Unsupported knowledgeType: " + binding.knowledgeType(), HttpStatus.BAD_REQUEST);
    }
    if (ids.size() != 1) {
      throw error("QUESTION_IMPORT_KNOWLEDGE_MAPPING_REQUIRED",
          "Knowledge code must resolve to exactly one enabled node in the imported scope",
          HttpStatus.BAD_REQUEST);
    }
    return new QuestionCreateRequest.KnowledgeBindingRequest(null, null, persistedType, ids.get(0),
        binding.knowledgeCode(), binding.weight(), binding.sortOrder());
  }

  public Optional<QuestionActionResult> beginMutation(long actorId, String operation,
      String scope, UUID clientMutationId, String requestHash) {
    MutationRow existing = findMutation(actorId, operation, scope, clientMutationId, true).orElse(null);
    if (existing != null) {
      return replayMutation(existing, requestHash);
    }
    try {
      jdbcTemplate.update("""
          INSERT INTO q_question_mutation (
            actor_user_id, operation_code, target_scope_id, client_mutation_id,
            request_hash, status, created_at
          ) VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?)
          """, actorId, operation, scope, clientMutationId.toString(), requestHash, now());
      return Optional.empty();
    } catch (DuplicateKeyException race) {
      MutationRow winner = findMutation(actorId, operation, scope, clientMutationId, true)
          .orElseThrow(() -> race);
      return replayMutation(winner, requestHash);
    }
  }

  public void completeMutation(long actorId, String operation, String scope,
      UUID clientMutationId, Object response) {
    int updated = jdbcTemplate.update("""
        UPDATE q_question_mutation
        SET status = 'COMPLETED', response_json = ?, completed_at = ?
        WHERE actor_user_id = ? AND operation_code = ? AND target_scope_id = ?
          AND client_mutation_id = ? AND status = 'PROCESSING'
        """, json(response), now(), actorId, operation, scope, clientMutationId.toString());
    if (updated != 1) {
      throw error("QUESTION_MUTATION_CONFLICT", "Question mutation ownership was lost",
          HttpStatus.CONFLICT);
    }
  }

  public CreatedVersion createQuestion(VersionContent content, VersionSource source,
      long actorId, Long importBatchId) {
    QuestionCreateRequest payload = content.payload();
    Timestamp timestamp = now();
    long questionId;
    try {
      questionId = insertAndReturnKey("""
          INSERT INTO q_question (
            qualification_id, subject_id, question_type, title, year_no, difficulty,
            source_type, source_ref, status, content_version,
            current_working_version_id, current_published_version_id, next_event_sequence,
            import_external_key, import_content_hash,
            created_at, updated_at, created_by, updated_by, deleted
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 1, NULL, NULL, 1, ?, ?, ?, ?, ?, ?, 0)
          """, payload.qualificationId(), payload.subjectId(), upper(payload.questionType()),
          payload.title(), payload.yearNo(), payload.difficulty(), source.sourceType(),
          source.sourceRef(), source.externalKey(), source.importIdentityHash(),
          timestamp, timestamp, actorId, actorId);
    } catch (DuplicateKeyException duplicate) {
      throw error("QUESTION_IMPORT_DUPLICATE_ACROSS_BATCH",
          "A question with the same scope/externalKey/contentHash already exists",
          HttpStatus.CONFLICT);
    }
    long versionId = insertVersion(questionId, 1, content, source, actorId, importBatchId);
    int pointed = jdbcTemplate.update("""
        UPDATE q_question
        SET current_working_version_id = ?, updated_at = ?, updated_by = ?
        WHERE id = ? AND current_working_version_id IS NULL AND deleted = 0
        """, versionId, timestamp, actorId, questionId);
    if (pointed != 1) {
      throw conflict("Could not establish the initial working version pointer");
    }
    insertVersionChildren(questionId, versionId, content, actorId);
    return new CreatedVersion(questionId, versionId, 1, "DRAFT", 0,
        source.contentHash(), timestamp.toInstant());
  }

  public Optional<ExistingImportTarget> findExactImportTarget(Scope scope,
      String externalKey, String contentHash) {
    List<ExistingImportIdentity> identities = jdbcTemplate.query("""
        SELECT id, import_external_key, import_content_hash,
               COALESCE(current_working_version_id, current_published_version_id) AS version_id
        FROM q_question
        WHERE qualification_id = ? AND subject_id = ? AND deleted = 0
          AND (import_external_key = ? OR import_content_hash = ?)
        FOR UPDATE
        """, (rs, rowNum) -> new ExistingImportIdentity(
            rs.getLong("id"), rs.getString("import_external_key"),
            rs.getString("import_content_hash"), nullableLong(rs, "version_id")),
        scope.qualificationId(), scope.subjectId(), externalKey, contentHash);
    if (identities.isEmpty()) {
      return Optional.empty();
    }
    if (identities.size() != 1
        || !externalKey.equals(identities.get(0).externalKey())
        || !contentHash.equalsIgnoreCase(identities.get(0).contentHash())) {
      throw error("QUESTION_IMPORT_IDENTITY_CONFLICT",
          "externalKey/contentHash matches different existing questions", HttpStatus.CONFLICT);
    }
    ExistingImportIdentity identity = identities.get(0);
    if (identity.versionId() == null) {
      throw error("QUESTION_IMPORT_EXISTING_VERSION_MISSING",
          "Existing imported question has no active version", HttpStatus.CONFLICT);
    }
    ExistingImportTarget target = jdbcTemplate.queryForObject("""
        SELECT qv.question_id, qv.id AS question_version_id, qv.status,
               qv.copyright_status, qv.usage_restriction
        FROM q_question_version qv
        WHERE qv.question_id = ? AND qv.id = ? AND qv.deleted = 0
        """, (rs, rowNum) -> new ExistingImportTarget(
            rs.getLong("question_id"), rs.getLong("question_version_id"),
            rs.getString("status"), rs.getString("copyright_status"),
            rs.getString("usage_restriction")),
        identity.questionId(), identity.versionId());
    if (!"UNVERIFIED".equals(target.copyrightStatus())
        || !"INTERNAL_ONLY".equals(target.usageRestriction())) {
      throw error("QUESTION_IMPORT_EXISTING_RIGHTS_CONFLICT",
          "Exact existing question is not UNVERIFIED/INTERNAL_ONLY", HttpStatus.CONFLICT);
    }
    if (!"PUBLISHED".equals(target.status())) {
      throw error("QUESTION_IMPORT_EXISTING_DRAFT_CONFLICT",
          "相同题目已有未发布草稿，请先处理该草稿后再导入整卷", HttpStatus.CONFLICT);
    }
    return Optional.of(target);
  }

  public CreatedVersion createRevision(LockedQuestion question, VersionContent content,
      VersionSource source, long actorId) {
    return createRevision(question, content, source, actorId, null, null);
  }

  public CreatedVersion createImportedReviewRevision(LockedQuestion question,
      VersionContent content, VersionSource source, long actorId, long importBatchId,
      long previousVersionId) {
    return createRevision(question, content, source, actorId, importBatchId, previousVersionId);
  }

  private CreatedVersion createRevision(LockedQuestion question, VersionContent content,
      VersionSource source, long actorId, Long importBatchId, Long previousVersionId) {
    Timestamp timestamp = now();
    int versionNo = nextVersionNo(question.questionId());
    if (question.currentWorkingVersionId() != null) {
      int retiredDraft = jdbcTemplate.update("""
          UPDATE q_question_version
          SET deleted = 1, updated_at = ?, row_version = row_version + 1
          WHERE id = ? AND question_id = ? AND status = 'DRAFT' AND deleted = 0
          """, timestamp, question.currentWorkingVersionId(), question.questionId());
      if (retiredDraft != 1) {
        throw conflict("The working draft changed before revision creation");
      }
    }
    long versionId = insertVersion(question.questionId(), versionNo, content, source, actorId,
        importBatchId);
    insertVersionChildren(question.questionId(), versionId, content, actorId);
    int pointed = jdbcTemplate.update("""
        UPDATE q_question
        SET current_working_version_id = ?,
            title = CASE WHEN current_published_version_id IS NULL THEN ? ELSE title END,
            year_no = CASE WHEN current_published_version_id IS NULL THEN ? ELSE year_no END,
            difficulty = CASE WHEN current_published_version_id IS NULL THEN ? ELSE difficulty END,
            source_type = CASE WHEN current_published_version_id IS NULL THEN ? ELSE source_type END,
            source_ref = CASE WHEN current_published_version_id IS NULL THEN ? ELSE source_ref END,
            updated_at = ?, updated_by = ?
        WHERE id = ? AND deleted = 0
        """, versionId, content.payload().title(), content.payload().yearNo(),
        content.payload().difficulty(), source.sourceType(), source.sourceRef(), timestamp, actorId,
        question.questionId());
    if (pointed != 1) {
      throw conflict("Question revision pointer was not updated");
    }
    if (importBatchId != null) {
      int itemUpdated = jdbcTemplate.update("""
          UPDATE q_question_import_item
          SET question_version_id = ?, content_hash = ?, canonical_json = NULL
          WHERE import_batch_id = ? AND question_id = ? AND question_version_id = ?
            AND item_status = 'IMPORTED_DRAFT'
          """, versionId, source.contentHash(), importBatchId, question.questionId(),
          previousVersionId);
      if (itemUpdated != 1) {
        throw conflict("Import review item changed before draft revision was saved");
      }
      jdbcTemplate.update("""
          UPDATE q_question
          SET import_content_hash = ?, updated_at = ?, updated_by = ?
          WHERE id = ? AND deleted = 0
          """, source.contentHash(), timestamp, actorId, question.questionId());
    }
    return new CreatedVersion(question.questionId(), versionId, versionNo, "DRAFT", 0,
        source.contentHash(), timestamp.toInstant());
  }

  public LockedQuestion lockQuestion(long questionId) {
    try {
      return jdbcTemplate.queryForObject("""
          SELECT id, qualification_id, subject_id, question_type,
                 current_working_version_id, current_published_version_id
          FROM q_question WHERE id = ? AND deleted = 0 FOR UPDATE
          """, this::questionRow, questionId);
    } catch (EmptyResultDataAccessException exception) {
      throw notFound();
    }
  }

  public VersionRow lockVersion(long questionId, long versionId) {
    try {
      return jdbcTemplate.queryForObject("""
          SELECT id, question_id, version_no, status, author_id, submitted_by,
                 reviewed_by, review_decision, row_version, content_hash, import_batch_id
          FROM q_question_version
          WHERE id = ? AND question_id = ? AND deleted = 0 FOR UPDATE
          """, this::versionRow, versionId, questionId);
    } catch (EmptyResultDataAccessException exception) {
      throw notFound();
    }
  }

  public VersionRow submitReview(long questionId, long versionId, long expectedRowVersion,
      long actorId) {
    int updated = jdbcTemplate.update("""
        UPDATE q_question_version
        SET status = 'IN_REVIEW', submitted_by = ?, submitted_at = ?,
            review_decision = NULL, reviewed_by = NULL, reviewed_at = NULL,
            row_version = row_version + 1, updated_at = ?
        WHERE id = ? AND question_id = ? AND status = 'DRAFT' AND deleted = 0
          AND row_version = ?
        """, actorId, now(), now(), versionId, questionId, expectedRowVersion);
    if (updated != 1) {
      throw conflict("Only the current DRAFT at the expected row version may be submitted");
    }
    return lockVersion(questionId, versionId);
  }

  public VersionRow approve(long questionId, long versionId, long expectedRowVersion,
      long submittedBy, long reviewerId, String reason) {
    if (submittedBy == reviewerId) {
      throw error("QUESTION_SELF_REVIEW_FORBIDDEN",
          "The submitter cannot review the same question version", HttpStatus.FORBIDDEN);
    }
    int updated = jdbcTemplate.update("""
        UPDATE q_question_version
        SET review_decision = 'APPROVED', reviewed_by = ?, reviewed_at = ?,
            row_version = row_version + 1, updated_at = ?
        WHERE id = ? AND question_id = ? AND status = 'IN_REVIEW'
          AND submitted_by = ? AND row_version = ? AND deleted = 0
        """, reviewerId, now(), now(), versionId, questionId, submittedBy, expectedRowVersion);
    if (updated != 1) {
      throw conflict("The review candidate changed or is not IN_REVIEW");
    }
    jdbcTemplate.update("""
        INSERT INTO q_question_review (
          question_id, question_version_id, submitted_by, reviewer_id, decision, reason, created_at
        ) VALUES (?, ?, ?, ?, 'APPROVED', ?, ?)
        """, questionId, versionId, submittedBy, reviewerId, reason, now());
    return lockVersion(questionId, versionId);
  }

  public VersionRow reject(long questionId, long versionId, long expectedRowVersion,
      long submittedBy, long reviewerId, String reason) {
    if (submittedBy == reviewerId) {
      throw error("QUESTION_SELF_REVIEW_FORBIDDEN",
          "The submitter cannot review the same question version", HttpStatus.FORBIDDEN);
    }
    int updated = jdbcTemplate.update("""
        UPDATE q_question_version
        SET status = 'DRAFT', submitted_by = NULL, submitted_at = NULL,
            review_decision = 'REJECTED', reviewed_by = ?, reviewed_at = ?,
            row_version = row_version + 1, updated_at = ?
        WHERE id = ? AND question_id = ? AND status = 'IN_REVIEW'
          AND submitted_by = ? AND row_version = ? AND deleted = 0
        """, reviewerId, now(), now(), versionId, questionId, submittedBy, expectedRowVersion);
    if (updated != 1) {
      throw conflict("The review candidate changed or is not IN_REVIEW");
    }
    jdbcTemplate.update("""
        INSERT INTO q_question_review (
          question_id, question_version_id, submitted_by, reviewer_id, decision, reason, created_at
        ) VALUES (?, ?, ?, ?, 'REJECTED', ?, ?)
        """, questionId, versionId, submittedBy, reviewerId, reason, now());
    return lockVersion(questionId, versionId);
  }

  public VersionRow publish(LockedQuestion question, VersionRow candidate,
      long expectedRowVersion, long actorId) {
    if (candidate.submittedBy() != null && candidate.submittedBy() == actorId) {
      throw error("QUESTION_SELF_PUBLISH_FORBIDDEN",
          "The submitter cannot publish the same question version", HttpStatus.FORBIDDEN);
    }
    List<Long> ids = new ArrayList<>();
    ids.add(candidate.versionId());
    if (question.currentPublishedVersionId() != null
        && !question.currentPublishedVersionId().equals(candidate.versionId())) {
      ids.add(question.currentPublishedVersionId());
    }
    ids.stream().sorted().forEach(id -> jdbcTemplate.queryForObject(
        "SELECT id FROM q_question_version WHERE id = ? AND question_id = ? FOR UPDATE",
        Long.class, id, question.questionId()));
    if (question.currentPublishedVersionId() != null
        && !question.currentPublishedVersionId().equals(candidate.versionId())) {
      int retired = jdbcTemplate.update("""
          UPDATE q_question_version
          SET status = 'RETIRED', retired_by = ?, retired_at = ?,
              row_version = row_version + 1, updated_at = ?
          WHERE id = ? AND question_id = ? AND status = 'PUBLISHED' AND deleted = 0
          """, actorId, now(), now(), question.currentPublishedVersionId(), question.questionId());
      if (retired != 1) {
        throw conflict("Current published version could not be retired atomically");
      }
    }
    int published = jdbcTemplate.update("""
        UPDATE q_question_version
        SET status = 'PUBLISHED', published_by = ?, published_at = ?,
            row_version = row_version + 1, updated_at = ?
        WHERE id = ? AND question_id = ? AND status = 'IN_REVIEW'
          AND review_decision = 'APPROVED' AND reviewed_by IS NOT NULL
          AND row_version = ? AND deleted = 0
        """, actorId, now(), now(), candidate.versionId(), question.questionId(),
        expectedRowVersion);
    if (published != 1) {
      throw conflict("Only an approved IN_REVIEW version may be published");
    }
    PublishedMetadata metadata = jdbcTemplate.queryForObject("""
        SELECT title, year_no, difficulty, source_type, source_ref
        FROM q_question_version WHERE id = ? AND question_id = ?
        """, (rs, rowNum) -> new PublishedMetadata(rs.getString("title"),
            nullableInt(rs, "year_no"), rs.getString("difficulty"),
            rs.getString("source_type"), rs.getString("source_ref")),
        candidate.versionId(), question.questionId());
    int pointed = jdbcTemplate.update("""
        UPDATE q_question
        SET current_published_version_id = ?, current_working_version_id = NULL,
            status = 'PUBLISHED', content_version = ?, title = ?, year_no = ?,
            difficulty = ?, source_type = ?, source_ref = ?, updated_at = ?, updated_by = ?
        WHERE id = ? AND deleted = 0
        """, candidate.versionId(), candidate.versionNo(), metadata.title(), metadata.yearNo(),
        metadata.difficulty(), metadata.sourceType(), metadata.sourceRef(), now(), actorId,
        question.questionId());
    if (pointed != 1) {
      throw conflict("Published question pointer was not updated");
    }
    return lockVersion(question.questionId(), candidate.versionId());
  }

  public VersionRow retire(LockedQuestion question, VersionRow version,
      long expectedRowVersion, long actorId) {
    int retired = jdbcTemplate.update("""
        UPDATE q_question_version
        SET status = 'RETIRED', retired_by = ?, retired_at = ?,
            row_version = row_version + 1, updated_at = ?
        WHERE id = ? AND question_id = ? AND status = 'PUBLISHED'
          AND row_version = ? AND deleted = 0
        """, actorId, now(), now(), version.versionId(), question.questionId(),
        expectedRowVersion);
    if (retired != 1) {
      throw conflict("Only the current PUBLISHED version may be retired");
    }
    int pointed = jdbcTemplate.update("""
        UPDATE q_question
        SET current_published_version_id = NULL, status = 'RETIRED',
            updated_at = ?, updated_by = ?
        WHERE id = ? AND current_published_version_id = ? AND deleted = 0
        """, now(), actorId, question.questionId(), version.versionId());
    if (pointed != 1) {
      throw conflict("Retired question pointer was not cleared");
    }
    return lockVersion(question.questionId(), version.versionId());
  }

  public void appendAudit(long questionId, Long versionId, String eventType,
      long actorId, UUID clientMutationId, Object payload) {
    Long sequence = jdbcTemplate.queryForObject(
        "SELECT next_event_sequence FROM q_question WHERE id = ? FOR UPDATE", Long.class, questionId);
    if (sequence == null) {
      throw notFound();
    }
    jdbcTemplate.update("UPDATE q_question SET next_event_sequence = ? WHERE id = ?",
        sequence + 1, questionId);
    jdbcTemplate.update("""
        INSERT INTO q_question_audit_event (
          question_id, question_version_id, event_sequence, event_type,
          actor_user_id, client_mutation_id, event_payload_json, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, questionId, versionId, sequence, eventType, actorId,
        clientMutationId.toString(), payload == null ? null : json(payload), now());
  }

  public QuestionVersionDetailDto detail(long questionId, long versionId) {
    try {
      return jdbcTemplate.queryForObject("""
          SELECT qv.question_id, qv.id AS version_id, qv.version_no, qv.status,
                 qv.row_version, qv.author_id, qv.submitted_by, qv.reviewed_by,
                 qv.review_decision, qv.published_by, qv.retired_by, qv.content_hash,
                 qv.source_file, qv.source_sha256, qv.source_locator,
                 qv.original_question_no, qv.paper_kind, qv.copyright_status,
                 qv.usage_restriction, qv.created_at, qv.updated_at, qv.content_json
          FROM q_question_version qv
          JOIN q_question q ON q.id = qv.question_id AND q.deleted = 0
          WHERE qv.question_id = ? AND qv.id = ?
          """, this::detailRow, questionId, versionId);
    } catch (EmptyResultDataAccessException exception) {
      throw notFound();
    }
  }

  public VersionSource versionSource(long questionId, long versionId, String contentHash) {
    try {
      return jdbcTemplate.queryForObject("""
          SELECT qv.external_key, qv.source_type, qv.source_ref, qv.source_file,
                 qv.source_sha256, qv.source_locator, qv.original_question_no,
                 qv.paper_kind, qv.syllabus_basis, qv.copyright_status,
                 qv.usage_restriction
          FROM q_question_version qv
          JOIN q_question q ON q.id = qv.question_id AND q.deleted = 0
          WHERE qv.question_id = ? AND qv.id = ? AND qv.deleted = 0
          """, (rs, rowNum) -> new VersionSource(rs.getString("external_key"),
              rs.getString("source_type"), rs.getString("source_ref"),
              rs.getString("source_file"), rs.getString("source_sha256"),
              rs.getString("source_locator"), nullableInt(rs, "original_question_no"),
              rs.getString("paper_kind"), rs.getString("syllabus_basis"),
              rs.getString("copyright_status"), rs.getString("usage_restriction"),
              contentHash, null), questionId, versionId);
    } catch (EmptyResultDataAccessException exception) {
      throw notFound();
    }
  }

  public PageResponse<QuestionVersionSummaryDto> list(long pageNo, long pageSize,
      String status, Long questionId, Long qualificationId, Long subjectId, String keyword, String questionType,
      String sourceType, String copyrightStatus) {
    StringBuilder where = new StringBuilder("""
        WHERE q.deleted = 0 AND qv.deleted = 0
          AND qv.id = COALESCE(q.current_working_version_id, q.current_published_version_id, latest.latest_id)
        """);
    List<Object> args = new ArrayList<>();
    if (status != null && !status.isBlank()) {
      where.append(" AND qv.status = ?");
      args.add(upper(status));
    }
    if (questionId != null) {
      where.append(" AND q.id = ?");
      args.add(questionId);
    }
    if (qualificationId != null) {
      where.append(" AND q.qualification_id = ?");
      args.add(qualificationId);
    }
    if (subjectId != null) {
      where.append(" AND q.subject_id = ?");
      args.add(subjectId);
    }
    if (keyword != null && !keyword.isBlank()) {
      String pattern = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
      where.append("""
           AND (LOWER(COALESCE(qv.title, '')) LIKE ?
             OR LOWER(COALESCE(qv.source_ref, '')) LIKE ?
             OR EXISTS (
               SELECT 1 FROM q_question_stem stem
               WHERE stem.question_version_id = qv.id AND stem.deleted = 0
                 AND LOWER(stem.content) LIKE ?
             )
             OR EXISTS (
               SELECT 1 FROM q_sub_question sub
               WHERE sub.question_version_id = qv.id AND sub.deleted = 0
                 AND LOWER(sub.content) LIKE ?
             ))
          """);
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    if (questionType != null && !questionType.isBlank()) {
      where.append(" AND q.question_type = ?");
      args.add(upper(questionType));
    }
    if (sourceType != null && !sourceType.isBlank()) {
      where.append(" AND qv.source_type = ?");
      args.add(upper(sourceType));
    }
    if (copyrightStatus != null && !copyrightStatus.isBlank()) {
      where.append(" AND qv.copyright_status = ?");
      args.add(upper(copyrightStatus));
    }
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM q_question q "
        + "JOIN (SELECT question_id, MAX(id) AS latest_id FROM q_question_version WHERE deleted = 0 GROUP BY question_id) latest ON latest.question_id = q.id "
        + "JOIN q_question_version qv ON qv.id = COALESCE(q.current_working_version_id, q.current_published_version_id, latest.latest_id) "
        + where, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(pageSize);
    pageArgs.add((pageNo - 1) * pageSize);
    List<QuestionVersionSummaryDto> items = jdbcTemplate.query("""
        SELECT q.id AS question_id, qv.id AS version_id, qv.version_no,
               q.qualification_id, q.subject_id, q.question_type, qv.title, qv.year_no,
               qv.difficulty, qv.status, qv.row_version, qv.author_id, qv.submitted_by,
               qv.reviewed_by, qv.review_decision, qv.published_by, qv.retired_by,
               qv.source_type, qv.paper_kind, qv.copyright_status, qv.usage_restriction,
               NULL AS primary_stem,
               CASE WHEN q.current_working_version_id = qv.id THEN 1 ELSE 0 END AS current_working,
               CASE WHEN q.current_published_version_id = qv.id THEN 1 ELSE 0 END AS current_published,
               qv.updated_at
        FROM q_question q
        JOIN (SELECT question_id, MAX(id) AS latest_id FROM q_question_version WHERE deleted = 0 GROUP BY question_id) latest ON latest.question_id = q.id
        JOIN q_question_version qv ON qv.id = COALESCE(q.current_working_version_id, q.current_published_version_id, latest.latest_id)
        %s ORDER BY qv.updated_at DESC, qv.id DESC LIMIT ? OFFSET ?
        """.formatted(where), this::summaryRow, pageArgs.toArray());
    long total = count == null ? 0 : count;
    return new PageResponse<>(items, pageNo, pageSize, total,
        (pageNo - 1) * pageSize + items.size() < total);
  }

  public List<QuestionVersionHistoryDto> versionHistory(long questionId) {
    requireQuestionExists(questionId);
    return jdbcTemplate.query("""
        SELECT qv.question_id, qv.id AS version_id, qv.version_no, qv.status,
               qv.row_version, qv.author_id, qv.submitted_by, qv.reviewed_by,
               qv.review_decision, qv.published_by, qv.retired_by,
               CASE WHEN q.current_working_version_id = qv.id THEN 1 ELSE 0 END AS current_working,
               CASE WHEN q.current_published_version_id = qv.id THEN 1 ELSE 0 END AS current_published,
               qv.deleted, qv.created_at, qv.updated_at
        FROM q_question_version qv
        JOIN q_question q ON q.id = qv.question_id AND q.deleted = 0
        WHERE qv.question_id = ?
        ORDER BY qv.version_no DESC, qv.id DESC
        """, (rs, rowNum) -> new QuestionVersionHistoryDto(rs.getLong("question_id"),
            rs.getLong("version_id"), rs.getInt("version_no"), rs.getString("status"),
            rs.getLong("row_version"), nullableLong(rs, "author_id"),
            nullableLong(rs, "submitted_by"), nullableLong(rs, "reviewed_by"),
            rs.getString("review_decision"), nullableLong(rs, "published_by"),
            nullableLong(rs, "retired_by"), rs.getBoolean("current_working"),
            rs.getBoolean("current_published"), rs.getBoolean("deleted"),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))),
        questionId);
  }

  public List<QuestionReviewRecordDto> reviewRecords(long questionId, long versionId) {
    return jdbcTemplate.query("""
        SELECT id, question_id, question_version_id, submitted_by, reviewer_id,
               decision, reason, created_at
        FROM q_question_review
        WHERE question_id = ? AND question_version_id = ?
        ORDER BY created_at DESC, id DESC
        """, (rs, rowNum) -> new QuestionReviewRecordDto(rs.getLong("id"),
            rs.getLong("question_id"), rs.getLong("question_version_id"),
            rs.getLong("submitted_by"), rs.getLong("reviewer_id"),
            rs.getString("decision"), rs.getString("reason"),
            instant(rs.getTimestamp("created_at"))), questionId, versionId);
  }

  public List<QuestionAuditEventDto> auditEvents(long questionId) {
    requireQuestionExists(questionId);
    return jdbcTemplate.query("""
        SELECT id, question_id, question_version_id, event_sequence, event_type,
               actor_user_id, client_mutation_id, event_payload_json, created_at
        FROM q_question_audit_event
        WHERE question_id = ?
        ORDER BY event_sequence DESC
        """, (rs, rowNum) -> new QuestionAuditEventDto(rs.getLong("id"),
            rs.getLong("question_id"), nullableLong(rs, "question_version_id"),
            rs.getLong("event_sequence"), rs.getString("event_type"),
            rs.getLong("actor_user_id"), rs.getString("client_mutation_id"),
            rs.getString("event_payload_json"), instant(rs.getTimestamp("created_at"))),
        questionId);
  }

  public QuestionAdminDashboardDto dashboard() {
    long total = count("SELECT COUNT(*) FROM q_question WHERE deleted = 0");
    long drafts = count("""
        SELECT COUNT(*) FROM q_question q
        JOIN q_question_version qv ON qv.id = q.current_working_version_id
        WHERE q.deleted = 0 AND qv.deleted = 0 AND qv.status = 'DRAFT'
        """);
    long inReview = count("""
        SELECT COUNT(*) FROM q_question q
        JOIN q_question_version qv ON qv.id = q.current_working_version_id
        WHERE q.deleted = 0 AND qv.deleted = 0 AND qv.status = 'IN_REVIEW'
        """);
    long published = count("""
        SELECT COUNT(*) FROM q_question q
        JOIN q_question_version qv ON qv.id = q.current_published_version_id
        WHERE q.deleted = 0 AND qv.deleted = 0 AND qv.status = 'PUBLISHED'
        """);
    long retired = count("""
        SELECT COUNT(*) FROM q_question
        WHERE deleted = 0 AND status = 'RETIRED' AND current_published_version_id IS NULL
        """);
    List<QuestionAdminDashboardDto.QualificationCount> byQualification = jdbcTemplate.query("""
        SELECT qualification_id, COUNT(*) AS item_count
        FROM q_question WHERE deleted = 0
        GROUP BY qualification_id ORDER BY item_count DESC, qualification_id
        """, (rs, rowNum) -> new QuestionAdminDashboardDto.QualificationCount(
            rs.getLong("qualification_id"), rs.getLong("item_count")));
    List<QuestionAdminDashboardDto.QuestionTypeCount> byQuestionType = jdbcTemplate.query("""
        SELECT question_type, COUNT(*) AS item_count
        FROM q_question WHERE deleted = 0
        GROUP BY question_type ORDER BY item_count DESC, question_type
        """, (rs, rowNum) -> new QuestionAdminDashboardDto.QuestionTypeCount(
            rs.getString("question_type"), rs.getLong("item_count")));
    List<QuestionImportBatchSummaryDto> recentImports = listImportBatches(1, 5, null).items();
    List<QuestionVersionSummaryDto> pendingReviews = list(1, 10, "IN_REVIEW", null, null, null,
        null, null, null, null).items();
    return new QuestionAdminDashboardDto(total, drafts, inReview, published, retired,
        byQualification, byQuestionType, recentImports, pendingReviews);
  }

  public PageResponse<QuestionImportBatchSummaryDto> listImportBatches(long pageNo, long pageSize,
      String status) {
    String where = status == null || status.isBlank() ? "" : " WHERE status = ?";
    Object[] args = status == null || status.isBlank()
        ? new Object[0] : new Object[] {upper(status)};
    Long totalValue = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM q_question_import_batch" + where, Long.class, args);
    List<Object> pageArgs = new ArrayList<>(List.of(args));
    pageArgs.add(pageSize);
    pageArgs.add((pageNo - 1) * pageSize);
    List<QuestionImportBatchSummaryDto> items = jdbcTemplate.query("""
        SELECT id, actor_user_id, client_mutation_id, paper_id, input_path, input_sha256,
               source_file_name, source_file_sha256, schema_version, rights_status,
               usage_restriction, status, total_count, eligible_count, imported_count,
               created_at, completed_at, rolled_back_at, reviewed_by, reviewed_at,
               review_note, published_at
        FROM q_question_import_batch%s
        ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
        """.formatted(where), this::importBatchSummaryRow, pageArgs.toArray());
    long total = totalValue == null ? 0 : totalValue;
    return new PageResponse<>(items, pageNo, pageSize, total,
        (pageNo - 1) * pageSize + items.size() < total);
  }

  public QuestionImportBatchDetailDto importBatchDetail(long batchId) {
    QuestionImportBatchSummaryDto batch;
    try {
      batch = jdbcTemplate.queryForObject("""
          SELECT id, actor_user_id, client_mutation_id, paper_id, input_path, input_sha256,
                 source_file_name, source_file_sha256, schema_version, rights_status,
                 usage_restriction, status, total_count, eligible_count, imported_count,
                 created_at, completed_at, rolled_back_at, reviewed_by, reviewed_at,
                 review_note, published_at
          FROM q_question_import_batch WHERE id = ?
          """, this::importBatchSummaryRow, batchId);
    } catch (EmptyResultDataAccessException exception) {
      throw error("QUESTION_IMPORT_BATCH_NOT_FOUND", "Question import batch does not exist",
          HttpStatus.NOT_FOUND);
    }
    List<QuestionImportItemDto> items = jdbcTemplate.query("""
        SELECT id, line_no, external_key, content_hash, import_eligible, item_status,
               question_id, question_version_id, error_code, created_at
        FROM q_question_import_item
        WHERE import_batch_id = ? ORDER BY line_no, id
        """, (rs, rowNum) -> new QuestionImportItemDto(rs.getLong("id"),
            rs.getLong("line_no"), rs.getString("external_key"),
            rs.getString("content_hash"), rs.getBoolean("import_eligible"),
            rs.getString("item_status"), nullableLong(rs, "question_id"),
            nullableLong(rs, "question_version_id"), rs.getString("error_code"),
            instant(rs.getTimestamp("created_at"))), batchId);
    return new QuestionImportBatchDetailDto(batch, items);
  }

  public long insertImportBatch(long actorId, UUID mutationId, String path, String sha256,
      int totalCount, int eligibleCount, String originalSourceFileName,
      String originalSourceFileSha256) {
    String sourceFileName = originalSourceFileName == null || originalSourceFileName.isBlank()
        ? Path.of(path).getFileName().toString() : originalSourceFileName;
    String sourceFileSha256 =
        originalSourceFileSha256 == null || originalSourceFileSha256.isBlank()
            ? sha256 : originalSourceFileSha256;
    return insertAndReturnKey("""
        INSERT INTO q_question_import_batch (
          actor_user_id, client_mutation_id, input_path, input_sha256, schema_version,
          source_file_name, source_file_sha256, rights_status, usage_restriction, status,
          total_count, eligible_count, imported_count, row_version, created_at
        ) VALUES (?, ?, ?, ?, 'question-import/1.0', ?, ?,
                  'UNVERIFIED', 'INTERNAL_ONLY', 'PENDING_REVIEW', ?, ?, 0, 0, ?)
        """, actorId, mutationId.toString(), path, sha256, sourceFileName, sourceFileSha256,
        totalCount, eligibleCount, now());
  }

  public Long findImportBatchIdByPaper(long paperId) {
    try {
      return jdbcTemplate.queryForObject("""
          SELECT id
          FROM q_question_import_batch
          WHERE paper_id = ?
          ORDER BY id DESC
          LIMIT 1
          """, Long.class, paperId);
    } catch (EmptyResultDataAccessException exception) {
      return null;
    }
  }

  public void linkImportPaper(long batchId, long paperId) {
    int updated = jdbcTemplate.update("""
        UPDATE q_question_import_batch
        SET paper_id = ?, row_version = row_version + 1
        WHERE id = ? AND status = 'PENDING_REVIEW' AND paper_id IS NULL
        """, paperId, batchId);
    if (updated != 1) {
      throw conflict("Import batch paper link failed");
    }
  }

  public void insertImportItem(long batchId, int lineNo, String externalKey,
      String contentHash, boolean eligible, String status, Long questionId,
      Long questionVersionId, String canonicalJson, String errorCode) {
    jdbcTemplate.update("""
        INSERT INTO q_question_import_item (
          import_batch_id, line_no, external_key, content_hash, import_eligible,
          item_status, question_id, question_version_id, canonical_json, error_code, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, batchId, lineNo, externalKey, contentHash, eligible ? 1 : 0, status,
        questionId, questionVersionId, canonicalJson, errorCode, now());
  }

  public void completeImportBatch(long batchId, int importedCount) {
    int updated = jdbcTemplate.update("""
        UPDATE q_question_import_batch
        SET imported_count = ?, completed_at = ?, row_version = row_version + 1
        WHERE id = ? AND status = 'PENDING_REVIEW'
        """, importedCount, now(), batchId);
    if (updated != 1) {
      throw conflict("Import batch completion failed");
    }
  }

  public ImportBatch lockImportBatch(long batchId) {
    try {
      return jdbcTemplate.queryForObject("""
          SELECT id, actor_user_id, paper_id, status, row_version, imported_count
          FROM q_question_import_batch WHERE id = ? FOR UPDATE
          """, (rs, rowNum) -> new ImportBatch(rs.getLong("id"), rs.getLong("actor_user_id"),
              nullableLong(rs, "paper_id"), rs.getString("status"), rs.getLong("row_version"),
              rs.getInt("imported_count")),
          batchId);
    } catch (EmptyResultDataAccessException exception) {
      throw error("QUESTION_IMPORT_BATCH_NOT_FOUND", "Question import batch does not exist",
          HttpStatus.NOT_FOUND);
    }
  }

  public List<ImportItem> lockImportedItems(long batchId) {
    return jdbcTemplate.query("""
        SELECT id, question_id, question_version_id, content_hash
        FROM q_question_import_item
        WHERE import_batch_id = ? AND item_status = 'IMPORTED_DRAFT'
        ORDER BY question_id ASC, id ASC FOR UPDATE
        """, (rs, rowNum) -> new ImportItem(rs.getLong("id"), rs.getLong("question_id"),
            rs.getLong("question_version_id"), rs.getString("content_hash")), batchId);
  }

  public void rollbackImportedDraft(ImportItem item, long batchId, long actorId) {
    LockedQuestion question = lockQuestion(item.questionId());
    VersionRow version = lockVersion(item.questionId(), item.questionVersionId());
    if (!"DRAFT".equals(version.status())
        || !item.contentHash().equals(version.contentHash())
        || version.importBatchId() == null || version.importBatchId() != batchId
        || !item.questionVersionId().equals(question.currentWorkingVersionId())
        || question.currentPublishedVersionId() != null) {
      throw error("QUESTION_IMPORT_ROLLBACK_NOT_PRISTINE",
          "Rollback is allowed only for unchanged imported DRAFT questions",
          HttpStatus.CONFLICT);
    }
    jdbcTemplate.update("""
        UPDATE q_question_version SET deleted = 1, updated_at = ?, row_version = row_version + 1
        WHERE id = ? AND status = 'DRAFT' AND content_hash = ? AND import_batch_id = ?
        """, now(), item.questionVersionId(), item.contentHash(), batchId);
    jdbcTemplate.update("""
        UPDATE q_question SET deleted = 1, current_working_version_id = NULL,
            updated_at = ?, updated_by = ? WHERE id = ? AND current_published_version_id IS NULL
        """, now(), actorId, item.questionId());
    jdbcTemplate.update("""
        UPDATE q_question_import_item SET item_status = 'ROLLED_BACK'
        WHERE id = ? AND item_status = 'IMPORTED_DRAFT'
        """, item.itemId());
  }

  public void publishImportedDraft(ImportItem item, long batchId, long importerId, long actorId) {
    LockedQuestion question = lockQuestion(item.questionId());
    VersionRow version = lockVersion(item.questionId(), item.questionVersionId());
    if (!"DRAFT".equals(version.status()) || !item.contentHash().equals(version.contentHash())
        || version.importBatchId() == null || version.importBatchId() != batchId
        || !item.questionVersionId().equals(question.currentWorkingVersionId())
        || question.currentPublishedVersionId() != null) {
      throw error("QUESTION_IMPORT_PUBLISH_NOT_PRISTINE",
          "Only unchanged imported DRAFT questions may be batch published", HttpStatus.CONFLICT);
    }
    Timestamp timestamp = now();
    int published = jdbcTemplate.update("""
        UPDATE q_question_version
        SET status = 'PUBLISHED', submitted_by = ?, submitted_at = ?, reviewed_by = ?,
            reviewed_at = ?, review_decision = 'APPROVED', published_by = ?, published_at = ?,
            row_version = row_version + 1, updated_at = ?
        WHERE id = ? AND question_id = ? AND status = 'DRAFT' AND import_batch_id = ?
          AND content_hash = ? AND deleted = 0
        """, importerId, timestamp, actorId, timestamp, actorId, timestamp, timestamp,
        item.questionVersionId(), item.questionId(), batchId, item.contentHash());
    if (published != 1) throw conflict("Imported question changed before batch publication");
    jdbcTemplate.update("""
        INSERT INTO q_question_review (
          question_id, question_version_id, submitted_by, reviewer_id, decision, reason, created_at
        ) VALUES (?, ?, ?, ?, 'APPROVED', 'Validated import batch release', ?)
        """, item.questionId(), item.questionVersionId(), importerId, actorId, timestamp);
    PublishedMetadata metadata = jdbcTemplate.queryForObject("""
        SELECT title, year_no, difficulty, source_type, source_ref
        FROM q_question_version WHERE id = ? AND question_id = ?
        """, (rs, rowNum) -> new PublishedMetadata(rs.getString("title"),
            nullableInt(rs, "year_no"), rs.getString("difficulty"), rs.getString("source_type"),
            rs.getString("source_ref")), item.questionVersionId(), item.questionId());
    int pointed = jdbcTemplate.update("""
        UPDATE q_question
        SET current_published_version_id = ?, current_working_version_id = NULL,
            status = 'PUBLISHED', content_version = ?, title = ?, year_no = ?, difficulty = ?,
            source_type = ?, source_ref = ?, updated_at = ?, updated_by = ?
        WHERE id = ? AND current_working_version_id = ? AND deleted = 0
        """, item.questionVersionId(), version.versionNo(), metadata.title(), metadata.yearNo(),
        metadata.difficulty(), metadata.sourceType(), metadata.sourceRef(), timestamp, actorId,
        item.questionId(), item.questionVersionId());
    if (pointed != 1) throw conflict("Imported question publication pointer could not be updated");
    jdbcTemplate.update("""
        UPDATE q_question_import_item SET item_status = 'PUBLISHED'
        WHERE id = ? AND item_status = 'IMPORTED_DRAFT'
        """, item.itemId());
  }

  public void insertImportAsset(long batchId, String sourceRelationId, int sourceOrder,
      String contentSha256, String storedFileName, String mediaType, long byteSize,
      Integer width, Integer height, Integer boundQuestionNo) {
    jdbcTemplate.update("""
        INSERT INTO q_question_import_asset (
          import_batch_id, source_relation_id, source_order, content_sha256,
          stored_file_name, media_type, byte_size, width_px, height_px,
          bound_question_no, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, batchId, sourceRelationId, sourceOrder, contentSha256, storedFileName,
        mediaType, byteSize, width, height, boundQuestionNo, now());
  }

  public void markImportPublished(long batchId, long reviewerId, String reviewNote) {
    int updated = jdbcTemplate.update("""
        UPDATE q_question_import_batch
        SET status = 'PUBLISHED', reviewed_by = ?, reviewed_at = ?, review_note = ?,
            published_at = ?, row_version = row_version + 1
        WHERE id = ? AND status = 'PENDING_REVIEW'
        """, reviewerId, now(), reviewNote, now(), batchId);
    if (updated != 1) {
      throw conflict("Import batch publication state changed");
    }
  }

  public void markImportRejected(long batchId, long reviewerId, String reason) {
    int updated = jdbcTemplate.update("""
        UPDATE q_question_import_batch
        SET status = 'REJECTED', reviewed_by = ?, reviewed_at = ?, review_note = ?,
            row_version = row_version + 1
        WHERE id = ? AND status = 'PENDING_REVIEW'
        """, reviewerId, now(), reason, batchId);
    if (updated != 1) {
      throw conflict("Import batch rejection state changed");
    }
  }

  public List<QuestionAnomalyDto> listAnomalies(String anomalyType, String status) {
    String type = anomalyType == null || anomalyType.isBlank() || "ALL".equals(anomalyType) ? null : anomalyType;
    String state = status == null || status.isBlank() ? "OPEN" : status;
    return jdbcTemplate.query("""
        SELECT a.id, a.question_id, a.question_version_id, q.question_type, v.title,
               a.anomaly_type, a.status, v.original_question_no, a.updated_at
        FROM q_question_anomaly a
        JOIN q_question q ON q.id = a.question_id
        JOIN q_question_version v ON v.id = a.question_version_id
        WHERE (? IS NULL OR a.anomaly_type = ?)
          AND (? = 'ALL' OR a.status = ?)
        ORDER BY FIELD(a.status, 'OPEN','IN_PROGRESS','FIXED','IGNORED','FALSE_POSITIVE'), a.updated_at DESC, a.id DESC
        """, (rs, rowNum) -> new QuestionAnomalyDto(rs.getLong("id"), rs.getLong("question_id"),
            rs.getLong("question_version_id"), rs.getString("question_type"), rs.getString("title"),
            rs.getString("anomaly_type"), rs.getString("status"),
            (Integer) rs.getObject("original_question_no"), rs.getTimestamp("updated_at").toInstant()),
        type, type, state, state);
  }

  public long countOpenAnomalies() {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM q_question_anomaly WHERE status IN ('OPEN','IN_PROGRESS')", Long.class);
    return count == null ? 0 : count;
  }

  public void refreshAnomalies() {
    jdbcTemplate.update("DELETE FROM q_question_anomaly WHERE status = 'OPEN'");
    jdbcTemplate.update("""
        INSERT IGNORE INTO q_question_anomaly (question_id, question_version_id, anomaly_type)
        SELECT q.id, v.id, x.anomaly_type
        FROM q_question q JOIN q_question_version v ON v.id = q.current_published_version_id
        JOIN (
          SELECT v2.id, 'MISSING_QUESTION_NO' anomaly_type FROM q_question_version v2 WHERE v2.original_question_no IS NULL
          UNION ALL
          SELECT v2.id, 'INCOMPLETE_STEM' FROM q_question_version v2
            WHERE COALESCE(v2.title, '') = '' OR v2.title REGEXP '^[[:space:]]*[\\(（][[:space:]]*[\\)）]'
          UNION ALL
          SELECT v2.id, 'INCOMPLETE_OPTION' FROM q_question_version v2 JOIN q_question q2 ON q2.current_published_version_id = v2.id
            WHERE q2.question_type IN ('SINGLE_CHOICE','MULTI_CHOICE')
              AND (SELECT COUNT(*) FROM q_choice_option o JOIN q_sub_question s ON s.id=o.sub_question_id WHERE s.question_version_id=v2.id AND o.deleted=0) < 4
          UNION ALL
          SELECT v2.id, 'MISSING_IMAGE' FROM q_question_version v2
            WHERE v2.content_json LIKE '%[图片:%'
        ) x ON x.id = v.id
        WHERE v.status='PUBLISHED' AND v.deleted=0
        """);
  }

  public void resolveAnomaly(long anomalyId, String status, String note, long userId) {
    int changed = jdbcTemplate.update("""
        UPDATE q_question_anomaly SET status = ?, resolution_note = ?, resolved_by = ?,
          resolved_at = CASE WHEN ? IN ('FIXED','IGNORED','FALSE_POSITIVE') THEN CURRENT_TIMESTAMP(3) ELSE NULL END,
          updated_at = CURRENT_TIMESTAMP(3) WHERE id = ?
        """, status, note, userId, status, anomalyId);
    if (changed == 0) throw error("QUESTION_ANOMALY_NOT_FOUND", "Question anomaly not found", HttpStatus.NOT_FOUND);
  }

  public void markImportRolledBack(long batchId) {
    int updated = jdbcTemplate.update("""
        UPDATE q_question_import_batch
        SET status = 'ROLLED_BACK', rolled_back_at = ?, row_version = row_version + 1
        WHERE id = ? AND status = 'PENDING_REVIEW'
        """, now(), batchId);
    if (updated != 1) {
      throw conflict("Import batch is not rollback eligible");
    }
  }

  private long insertVersion(long questionId, int versionNo, VersionContent content,
      VersionSource source, long actorId, Long importBatchId) {
    return insertAndReturnKey("""
        INSERT INTO q_question_version (
          question_id, version_no, status, external_key, title, year_no, difficulty,
          source_type, source_ref, source_file, source_sha256, source_locator,
          original_question_no, paper_kind, syllabus_basis, copyright_status,
          usage_restriction, content_hash, content_json, import_batch_id, author_id,
          row_version, created_at, updated_at, deleted
        ) VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 0)
        """, questionId, versionNo, source.externalKey(), content.payload().title(),
        content.payload().yearNo(), content.payload().difficulty(), source.sourceType(),
        source.sourceRef(), source.sourceFile(), source.sourceSha256(), source.sourceLocator(),
        source.originalQuestionNo(), source.paperKind(), source.syllabusBasis(),
        source.copyrightStatus(), source.usageRestriction(), source.contentHash(),
        json(content.payload()), importBatchId, actorId, now(), now());
  }

  private void insertVersionChildren(long questionId, long versionId, VersionContent content,
      long actorId) {
    QuestionCreateRequest payload = content.payload();
    int stemIndex = 1;
    for (QuestionCreateRequest.StemRequest stem : safe(payload.stems())) {
      jdbcTemplate.update("""
          INSERT INTO q_question_stem (
            question_id, question_version_id, stem_key, stem_type, content,
            attachment_url, resource_id, sort_order, created_at, updated_at,
            created_by, updated_by, deleted
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
          """, questionId, versionId,
          stem.stemKey() == null || stem.stemKey().isBlank() ? "S" + stemIndex : stem.stemKey(),
          upper(stem.stemType()), stem.content(), stem.attachmentUrl(), stem.resourceId(),
          sort(stem.sortOrder()), now(), now(), actorId, actorId);
      stemIndex++;
    }
    for (QuestionCreateRequest.SubQuestionRequest sub : safe(payload.subQuestions())) {
      long subQuestionId = insertAndReturnKey("""
          INSERT INTO q_sub_question (
            question_id, question_version_id, sub_question_no, sub_question_type,
            content, score, answer_format, grading_mode, sort_order, metadata_json,
            created_at, updated_at, created_by, updated_by, deleted
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
          """, questionId, versionId, sub.subQuestionNo(), upper(sub.subQuestionType()),
          sub.content(), sub.score(), normalizeAnswerFormat(sub.answerFormat()),
          upper(sub.gradingMode()), sort(sub.sortOrder()), sub.metadataJson(), now(), now(),
          actorId, actorId);
      for (QuestionCreateRequest.OptionRequest option : safe(sub.options())) {
        jdbcTemplate.update("""
            INSERT INTO q_choice_option (
              sub_question_id, option_key, option_content, sort_order,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
            """, subQuestionId, upper(option.optionKey()), option.optionContent(),
            sort(option.sortOrder()), now(), now(), actorId, actorId);
      }
      for (QuestionCreateRequest.AnswerKeyRequest answer : safe(sub.answerKeys())) {
        jdbcTemplate.update("""
            INSERT INTO q_answer_key (
              sub_question_id, answer_format, answer_value, answer_json,
              version_no, current_flag, created_at, updated_at,
              created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, 1, 1, ?, ?, ?, ?, 0)
            """, subQuestionId, normalizeAnswerFormat(answer.answerFormat()),
            answer.answerValue(), answer.answerJson(), now(), now(), actorId, actorId);
      }
      for (QuestionCreateRequest.SolutionRequest solution : safe(sub.solutions())) {
        jdbcTemplate.update("""
            INSERT INTO q_solution (
              question_id, question_version_id, sub_question_id, solution_type,
              content, source_type, sort_order, version_no, created_at, updated_at,
              created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, 0)
            """, questionId, versionId, subQuestionId, normalizeSolutionType(solution.solutionType()),
            solution.content(), "SOURCE_DOCUMENT_IMPORT".equals(sourceType(payload))
                ? "SOURCE_DOCUMENT_IMPORT" : "MANUAL",
            sort(solution.sortOrder()), now(), now(), actorId, actorId);
      }
      for (QuestionCreateRequest.ScoringRuleRequest rule : safe(sub.scoringRules())) {
        jdbcTemplate.update("""
            INSERT INTO q_scoring_rule (
              sub_question_id, rule_type, rule_json, sort_order, version_no, current_flag,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, 1, 1, ?, ?, ?, ?, 0)
            """, subQuestionId, upper(rule.ruleType()), rule.ruleJson(),
            sort(rule.sortOrder()), now(), now(), actorId, actorId);
      }
      for (ScoringPoint point : content.scoringPoints().getOrDefault(sub.subQuestionNo(), List.of())) {
        jdbcTemplate.update("""
            INSERT INTO q_scoring_point (
              question_version_id, sub_question_id, point_code, point_text,
              score, sort_order, created_at, created_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
            """, versionId, subQuestionId, point.pointCode(), point.pointText(), point.score(),
            point.sortOrder(), now(), actorId);
      }
      for (QuestionCreateRequest.KnowledgeBindingRequest binding : safe(sub.knowledgeBindings())) {
        insertBinding(questionId, versionId, subQuestionId, "SUB_QUESTION", subQuestionId,
            binding, actorId);
      }
    }
    for (QuestionCreateRequest.KnowledgeBindingRequest binding : safe(payload.knowledgeBindings())) {
      insertBinding(questionId, versionId, null, "QUESTION", questionId, binding, actorId);
    }
  }

  private void insertBinding(long questionId, long versionId, Long subQuestionId,
      String targetType, long targetId, QuestionCreateRequest.KnowledgeBindingRequest binding,
      long actorId) {
    jdbcTemplate.update("""
        INSERT INTO q_question_knowledge_binding (
          question_id, question_version_id, sub_question_id, target_type, target_id,
          knowledge_type, knowledge_id, knowledge_code, weight, sort_order, created_at, updated_at,
          created_by, updated_by, deleted
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """, questionId, versionId, subQuestionId, targetType, targetId,
        upper(binding.knowledgeType()), binding.knowledgeId(), binding.knowledgeCode(),
        binding.weight() == null ? BigDecimal.ONE : binding.weight(), sort(binding.sortOrder()),
        now(), now(), actorId, actorId);
  }

  private Optional<MutationRow> findMutation(long actorId, String operation, String scope,
      UUID clientMutationId, boolean lock) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          SELECT request_hash, status, response_json
          FROM q_question_mutation
          WHERE actor_user_id = ? AND operation_code = ? AND target_scope_id = ?
            AND client_mutation_id = ?%s
          """.formatted(lock ? " FOR UPDATE" : ""),
          (rs, rowNum) -> new MutationRow(rs.getString("request_hash"), rs.getString("status"),
              rs.getString("response_json")), actorId, operation, scope,
          clientMutationId.toString()));
    } catch (EmptyResultDataAccessException exception) {
      return Optional.empty();
    }
  }

  private Optional<QuestionActionResult> replayMutation(MutationRow existing, String requestHash) {
    if (!existing.requestHash().equals(requestHash)) {
      throw error("QUESTION_MUTATION_CONFLICT",
          "clientMutationId was already used with different content", HttpStatus.CONFLICT);
    }
    if (!"COMPLETED".equals(existing.status()) || existing.responseJson() == null) {
      throw error("QUESTION_MUTATION_IN_PROGRESS",
          "The matching mutation is still processing", HttpStatus.CONFLICT);
    }
    try {
      return Optional.of(objectMapper.readValue(existing.responseJson(), QuestionActionResult.class)
          .replayedAt(Instant.now()));
    } catch (JsonProcessingException notQuestionAction) {
      return Optional.empty();
    }
  }

  public Optional<QuestionImportResultDto> beginImportMutation(long actorId, String operation,
      String scope, UUID mutationId, String requestHash) {
    MutationRow row = findMutation(actorId, operation, scope, mutationId, true).orElse(null);
    if (row == null) {
      try {
        jdbcTemplate.update("""
            INSERT INTO q_question_mutation (
              actor_user_id, operation_code, target_scope_id, client_mutation_id,
              request_hash, status, created_at
            ) VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?)
            """, actorId, operation, scope, mutationId.toString(), requestHash, now());
        return Optional.empty();
      } catch (DuplicateKeyException race) {
        row = findMutation(actorId, operation, scope, mutationId, true).orElseThrow(() -> race);
      }
    }
    if (!row.requestHash().equals(requestHash)) {
      throw error("QUESTION_MUTATION_CONFLICT",
          "clientMutationId was already used with a different import", HttpStatus.CONFLICT);
    }
    if (!"COMPLETED".equals(row.status())) {
      throw conflict("Matching import confirmation is still processing");
    }
    try {
      return Optional.of(objectMapper.readValue(row.responseJson(), QuestionImportResultDto.class).asReplay());
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot deserialize import idempotency response", exception);
    }
  }

  private int nextVersionNo(long questionId) {
    Integer max = jdbcTemplate.queryForObject(
        "SELECT COALESCE(MAX(version_no), 0) FROM q_question_version WHERE question_id = ?",
        Integer.class, questionId);
    return (max == null ? 0 : max) + 1;
  }

  private QuestionVersionDetailDto detailRow(ResultSet rs, int rowNum) throws SQLException {
    long questionId = rs.getLong("question_id");
    long versionId = rs.getLong("version_id");
    return new QuestionVersionDetailDto(questionId, versionId,
        rs.getInt("version_no"), rs.getString("status"), rs.getLong("row_version"),
        nullableLong(rs, "author_id"), nullableLong(rs, "submitted_by"),
        nullableLong(rs, "reviewed_by"), rs.getString("review_decision"),
        nullableLong(rs, "published_by"), nullableLong(rs, "retired_by"),
        rs.getString("content_hash"), rs.getString("source_file"),
        rs.getString("source_sha256"), rs.getString("source_locator"),
        nullableInt(rs, "original_question_no"), rs.getString("paper_kind"),
        rs.getString("copyright_status"), rs.getString("usage_restriction"),
        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
        readPayload(rs.getString("content_json"), questionId, versionId));
  }

  private QuestionVersionSummaryDto summaryRow(ResultSet rs, int rowNum) throws SQLException {
    return new QuestionVersionSummaryDto(rs.getLong("question_id"), rs.getLong("version_id"),
        rs.getInt("version_no"), rs.getLong("qualification_id"), rs.getLong("subject_id"),
        rs.getString("question_type"), displayTitle(rs.getString("title"), rs.getString("primary_stem")),
        nullableInt(rs, "year_no"),
        rs.getString("difficulty"), rs.getString("status"), rs.getLong("row_version"),
        nullableLong(rs, "author_id"), nullableLong(rs, "submitted_by"),
        nullableLong(rs, "reviewed_by"), rs.getString("review_decision"),
        nullableLong(rs, "published_by"), nullableLong(rs, "retired_by"),
        rs.getString("source_type"), rs.getString("paper_kind"),
        rs.getString("copyright_status"), rs.getString("usage_restriction"),
        rs.getBoolean("current_working"), rs.getBoolean("current_published"),
        instant(rs.getTimestamp("updated_at")));
  }

  private static String displayTitle(String title, String primaryStem) {
    if (title == null || !title.trim().matches("(?i)^MOCK[ _]PAPER(?:[ _].*)?$")) {
      return title;
    }
    if (primaryStem == null || primaryStem.isBlank()) {
      return title;
    }
    String compact = primaryStem.replaceAll("\\s+", " ").trim();
    return compact.length() <= 180 ? compact : compact.substring(0, 177) + "...";
  }

  private QuestionImportBatchSummaryDto importBatchSummaryRow(ResultSet rs, int rowNum)
      throws SQLException {
    return new QuestionImportBatchSummaryDto(rs.getLong("id"), rs.getLong("actor_user_id"),
        rs.getString("client_mutation_id"), nullableLong(rs, "paper_id"),
        rs.getString("input_path"), rs.getString("input_sha256"),
        rs.getString("source_file_name"), rs.getString("source_file_sha256"),
        rs.getString("schema_version"),
        rs.getString("rights_status"), rs.getString("usage_restriction"),
        rs.getString("status"), rs.getInt("total_count"), rs.getInt("eligible_count"),
        rs.getInt("imported_count"), instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("completed_at")), instant(rs.getTimestamp("rolled_back_at")),
        nullableLong(rs, "reviewed_by"), instant(rs.getTimestamp("reviewed_at")),
        rs.getString("review_note"), instant(rs.getTimestamp("published_at")));
  }

  private LockedQuestion questionRow(ResultSet rs, int rowNum) throws SQLException {
    return new LockedQuestion(rs.getLong("id"), rs.getLong("qualification_id"),
        rs.getLong("subject_id"), rs.getString("question_type"),
        nullableLong(rs, "current_working_version_id"),
        nullableLong(rs, "current_published_version_id"));
  }

  private VersionRow versionRow(ResultSet rs, int rowNum) throws SQLException {
    return new VersionRow(rs.getLong("id"), rs.getLong("question_id"),
        rs.getInt("version_no"), rs.getString("status"), nullableLong(rs, "author_id"),
        nullableLong(rs, "submitted_by"), nullableLong(rs, "reviewed_by"),
        rs.getString("review_decision"), rs.getLong("row_version"),
        rs.getString("content_hash"), nullableLong(rs, "import_batch_id"));
  }

  private long insertAndReturnKey(String sql, Object... arguments) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    int inserted = jdbcTemplate.update(connection -> {
      PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
      for (int index = 0; index < arguments.length; index++) {
        statement.setObject(index + 1, arguments[index]);
      }
      return statement;
    }, keyHolder);
    Number key = keyHolder.getKey();
    if (inserted != 1 || key == null) {
      throw new IllegalStateException("Database did not return a generated question key");
    }
    return key.longValue();
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize question payload", exception);
    }
  }

  private QuestionCreateRequest readPayload(String value, long questionId, long versionId) {
    try {
      return withDisplayTitle(objectMapper.readValue(value, QuestionCreateRequest.class));
    } catch (JsonProcessingException exception) {
      return withDisplayTitle(readRelationalPayload(questionId, versionId));
    }
  }

  private QuestionCreateRequest withDisplayTitle(QuestionCreateRequest payload) {
    String primaryStem = payload.stems() == null ? null : payload.stems().stream()
        .map(QuestionCreateRequest.StemRequest::content)
        .filter(content -> content != null && !content.isBlank())
        .findFirst()
        .orElse(null);
    String title = displayTitle(payload.title(), primaryStem);
    if (java.util.Objects.equals(title, payload.title())) {
      return payload;
    }
    return new QuestionCreateRequest(payload.qualificationId(), payload.subjectId(),
        payload.questionType(), title, payload.yearNo(), payload.difficulty(),
        payload.sourceType(), payload.sourceRef(), payload.stems(), payload.subQuestions(),
        payload.knowledgeBindings());
  }

  private QuestionCreateRequest readRelationalPayload(long questionId, long versionId) {
    LegacyMetadata metadata = jdbcTemplate.queryForObject("""
        SELECT q.qualification_id, q.subject_id, q.question_type,
               qv.title, qv.year_no, qv.difficulty, qv.source_type, qv.source_ref
        FROM q_question q
        JOIN q_question_version qv ON qv.question_id=q.id
        WHERE q.id=? AND qv.id=? AND q.deleted=0 AND qv.deleted=0
        """, (rs, rowNum) -> new LegacyMetadata(rs.getLong("qualification_id"),
            rs.getLong("subject_id"), rs.getString("question_type"), rs.getString("title"),
            nullableInt(rs, "year_no"), rs.getString("difficulty"), rs.getString("source_type"),
            rs.getString("source_ref")), questionId, versionId);
    List<QuestionCreateRequest.StemRequest> stems = jdbcTemplate.query("""
        SELECT stem_key, stem_type, content, attachment_url, resource_id, sort_order
        FROM q_question_stem
        WHERE question_id=? AND question_version_id=? AND deleted=0
        ORDER BY sort_order,id
        """, (rs, rowNum) -> new QuestionCreateRequest.StemRequest(rs.getString("stem_type"),
            rs.getString("content"), rs.getString("attachment_url"), rs.getInt("sort_order"),
            rs.getString("stem_key"), nullableLong(rs, "resource_id")), questionId, versionId);
    List<LegacySubQuestion> subRows = jdbcTemplate.query("""
        SELECT id, sub_question_no, sub_question_type, content, score, answer_format,
               grading_mode, sort_order, metadata_json
        FROM q_sub_question
        WHERE question_id=? AND question_version_id=? AND deleted=0
        ORDER BY sort_order,sub_question_no,id
        """, (rs, rowNum) -> new LegacySubQuestion(rs.getLong("id"),
            rs.getInt("sub_question_no"), rs.getString("sub_question_type"),
            rs.getString("content"), rs.getBigDecimal("score"), rs.getString("answer_format"),
            rs.getString("grading_mode"), rs.getInt("sort_order"), rs.getString("metadata_json")),
        questionId, versionId);
    List<QuestionCreateRequest.SubQuestionRequest> subQuestions = new ArrayList<>();
    for (LegacySubQuestion sub : subRows) {
      List<QuestionCreateRequest.OptionRequest> options = jdbcTemplate.query("""
          SELECT option_key, option_content, sort_order FROM q_choice_option
          WHERE sub_question_id=? AND deleted=0 ORDER BY sort_order,id
          """, (rs, rowNum) -> new QuestionCreateRequest.OptionRequest(rs.getString("option_key"),
              rs.getString("option_content"), rs.getInt("sort_order")), sub.id());
      List<QuestionCreateRequest.AnswerKeyRequest> answers = jdbcTemplate.query("""
          SELECT answer_format, answer_value, answer_json FROM q_answer_key
          WHERE sub_question_id=? AND current_flag=1 AND deleted=0 ORDER BY id
          """, (rs, rowNum) -> new QuestionCreateRequest.AnswerKeyRequest(
              requestAnswerFormat(rs.getString("answer_format")), rs.getString("answer_value"),
              rs.getString("answer_json")), sub.id());
      List<QuestionCreateRequest.SolutionRequest> solutions = jdbcTemplate.query("""
          SELECT solution_type, content, sort_order FROM q_solution
          WHERE question_id=? AND question_version_id=? AND sub_question_id=? AND deleted=0
          ORDER BY sort_order,id
          """, (rs, rowNum) -> new QuestionCreateRequest.SolutionRequest(
              rs.getString("solution_type"), rs.getString("content"), rs.getInt("sort_order")),
          questionId, versionId, sub.id());
      List<QuestionCreateRequest.ScoringRuleRequest> rules = jdbcTemplate.query("""
          SELECT rule_type, rule_json, sort_order FROM q_scoring_rule
          WHERE sub_question_id=? AND current_flag=1 AND deleted=0 ORDER BY sort_order,id
          """, (rs, rowNum) -> new QuestionCreateRequest.ScoringRuleRequest(
              rs.getString("rule_type"), rs.getString("rule_json"), rs.getInt("sort_order")), sub.id());
      List<QuestionCreateRequest.ScoringPointRequest> points = jdbcTemplate.query("""
          SELECT point_code, point_text, score, sort_order FROM q_scoring_point
          WHERE question_version_id=? AND sub_question_id=? AND deleted=0 ORDER BY sort_order,id
          """, (rs, rowNum) -> new QuestionCreateRequest.ScoringPointRequest(
              rs.getString("point_code"), rs.getString("point_text"), rs.getBigDecimal("score"),
              rs.getInt("sort_order")), versionId, sub.id());
      List<QuestionCreateRequest.KnowledgeBindingRequest> bindings = knowledgeBindings(
          questionId, versionId, sub.id());
      subQuestions.add(new QuestionCreateRequest.SubQuestionRequest(sub.number(), sub.type(),
          sub.content(), sub.score(), requestAnswerFormat(sub.answerFormat()), sub.gradingMode(),
          sub.sortOrder(), sub.metadataJson(), options, answers, solutions, rules, points, bindings));
    }
    return new QuestionCreateRequest(metadata.qualificationId(), metadata.subjectId(),
        metadata.questionType(), metadata.title(), metadata.yearNo(), metadata.difficulty(),
        metadata.sourceType(), metadata.sourceRef(), stems, subQuestions,
        knowledgeBindings(questionId, versionId, null));
  }

  private List<QuestionCreateRequest.KnowledgeBindingRequest> knowledgeBindings(long questionId,
      long versionId, Long subQuestionId) {
    String predicate = subQuestionId == null ? "sub_question_id IS NULL" : "sub_question_id = ?";
    List<Object> arguments = new ArrayList<>(List.of(questionId, versionId));
    if (subQuestionId != null) {
      arguments.add(subQuestionId);
    }
    return jdbcTemplate.query("""
        SELECT target_type, target_id, knowledge_type, knowledge_id, knowledge_code, weight, sort_order
        FROM q_question_knowledge_binding
        WHERE question_id=? AND question_version_id=? AND %s AND deleted=0
        ORDER BY sort_order,id
        """.formatted(predicate), (rs, rowNum) -> new QuestionCreateRequest.KnowledgeBindingRequest(
            rs.getString("target_type"), rs.getLong("target_id"), rs.getString("knowledge_type"),
            rs.getLong("knowledge_id"), rs.getString("knowledge_code"), rs.getBigDecimal("weight"),
            rs.getInt("sort_order")), arguments.toArray());
  }

  private String requestAnswerFormat(String value) {
    return "OPTION".equals(upper(value)) ? "OPTION_KEYS" : upper(value);
  }

  private String sourceType(QuestionCreateRequest payload) {
    return upper(payload.sourceType() == null ? "MANUAL" : payload.sourceType());
  }

  private String normalizeAnswerFormat(String value) {
    return "OPTION_KEYS".equals(upper(value)) ? "OPTION" : upper(value);
  }

  private String normalizeSolutionType(String value) {
    String normalized = upper(value);
    return Set.of("REFERENCE_ANSWER", "ANALYSIS").contains(normalized) ? "STANDARD" : normalized;
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private int sort(Integer value) {
    return value == null ? 0 : value;
  }

  private String upper(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private long count(String sql) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class);
    return value == null ? 0 : value;
  }

  private void requireQuestionExists(long questionId) {
    List<Long> ids = jdbcTemplate.queryForList(
        "SELECT id FROM q_question WHERE id = ? AND deleted = 0", Long.class, questionId);
    if (ids.size() != 1) {
      throw notFound();
    }
  }

  private Integer nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private BusinessException notFound() {
    return error("QUESTION_VERSION_NOT_FOUND", "Question version does not exist", HttpStatus.NOT_FOUND);
  }

  private BusinessException conflict(String message) {
    return error("QUESTION_VERSION_CONFLICT", message, HttpStatus.CONFLICT);
  }

  private BusinessException error(String code, String message, HttpStatus status) {
    return new BusinessException(code, message, status);
  }

  public record Scope(long qualificationId, long subjectId) {
  }

  public record ScoringPoint(String pointCode, String pointText, BigDecimal score, int sortOrder) {
  }

  public record VersionContent(QuestionCreateRequest payload,
                               Map<Integer, List<ScoringPoint>> scoringPoints,
                               List<PendingKnowledgeBinding> pendingKnowledgeBindings) {
    public VersionContent(QuestionCreateRequest payload,
        Map<Integer, List<ScoringPoint>> scoringPoints) {
      this(payload, scoringPoints, List.of());
    }

    public VersionContent {
      scoringPoints = scoringPoints == null ? Map.of() : Map.copyOf(scoringPoints);
      pendingKnowledgeBindings = pendingKnowledgeBindings == null
          ? List.of() : List.copyOf(pendingKnowledgeBindings);
    }
  }

  public record PendingKnowledgeBinding(Integer subQuestionNo, String knowledgeType,
                                        String knowledgeCode, BigDecimal weight, int sortOrder) {
  }

  public record VersionSource(String externalKey, String sourceType, String sourceRef,
                              String sourceFile, String sourceSha256, String sourceLocator,
                              Integer originalQuestionNo, String paperKind, String syllabusBasis,
                              String copyrightStatus, String usageRestriction, String contentHash,
                              String importIdentityHash) {
    public static VersionSource manual(QuestionCreateRequest payload, String contentHash) {
      return manual(payload, null, null, contentHash);
    }

    public static VersionSource manual(QuestionCreateRequest payload, String copyrightStatus,
        String usageRestriction, String contentHash) {
      String normalizedSourceType = payload.sourceType() == null
          ? "MANUAL" : payload.sourceType().trim().toUpperCase(Locale.ROOT);
      String normalizedCopyright = copyrightStatus == null || copyrightStatus.isBlank()
          ? switch (normalizedSourceType) {
            case "ORIGINAL" -> "ORIGINAL_DECLARED";
            case "AUTHORIZED_IMPORT" -> "AUTHORIZED";
            default -> "UNVERIFIED";
          }
          : copyrightStatus.trim().toUpperCase(Locale.ROOT);
      String normalizedRestriction = usageRestriction == null || usageRestriction.isBlank()
          ? "INTERNAL_ONLY" : usageRestriction.trim();
      return new VersionSource(null,
          normalizedSourceType, payload.sourceRef(),
          null, null, null, null, null, "MANUAL", normalizedCopyright, normalizedRestriction,
          contentHash, null);
    }
  }

  public record CreatedVersion(long questionId, long versionId, int versionNo, String status,
                               long rowVersion, String contentHash, Instant changedAt) {
    public QuestionActionResult result(boolean replayed) {
      return new QuestionActionResult(questionId, versionId, versionNo, status, rowVersion,
          replayed, changedAt);
    }
  }

  public record LockedQuestion(long questionId, long qualificationId, long subjectId,
                               String questionType, Long currentWorkingVersionId,
                               Long currentPublishedVersionId) {
  }

  public record VersionRow(long versionId, long questionId, int versionNo, String status,
                           Long authorId, Long submittedBy, Long reviewedBy,
                           String reviewDecision, long rowVersion, String contentHash,
                           Long importBatchId) {
    public QuestionActionResult result() {
      return new QuestionActionResult(questionId, versionId, versionNo, status, rowVersion,
          false, Instant.now());
    }
  }

  public record ImportBatch(long batchId, long actorId, Long paperId, String status,
                            long rowVersion, int importedCount) {
  }

  public record ImportItem(long itemId, Long questionId, Long questionVersionId,
                           String contentHash) {
  }

  public List<BulkPublishCandidate> bulkPublishCandidates(List<Long> importBatchIds,
      String copyrightStatus, String usageRestriction, long afterQuestionId, int limit) {
    if (importBatchIds == null || importBatchIds.isEmpty()) {
      return List.of();
    }
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }
    List<Object> arguments = new ArrayList<>(importBatchIds);
    arguments.add(copyrightStatus);
    arguments.add(usageRestriction);
    arguments.add(afterQuestionId);
    arguments.add(limit);
    return jdbcTemplate.query("""
        SELECT q.id AS question_id, q.current_working_version_id, q.current_published_version_id,
               qv.id AS question_version_id, qv.status, qv.row_version, qv.author_id,
               qv.submitted_by, qv.reviewed_by, qv.review_decision,
               qv.copyright_status, qv.usage_restriction
        FROM q_question q
        JOIN q_question_version qv
          ON qv.question_id = q.id
         AND qv.id = COALESCE(q.current_working_version_id, q.current_published_version_id)
        WHERE q.deleted = 0 AND qv.deleted = 0
          AND qv.import_batch_id IN (%s)
          AND qv.copyright_status = ? AND qv.usage_restriction = ?
          AND q.id > ?
          AND qv.status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED')
        ORDER BY q.id
        LIMIT ?
        """.formatted(String.join(",", Collections.nCopies(importBatchIds.size(), "?"))),
        (rs, rowNum) -> new BulkPublishCandidate(
            rs.getLong("question_id"), rs.getLong("question_version_id"),
            rs.getString("status"), rs.getLong("row_version"), nullableLong(rs, "author_id"),
            nullableLong(rs, "submitted_by"), nullableLong(rs, "reviewed_by"),
            rs.getString("review_decision"), rs.getString("copyright_status"),
            rs.getString("usage_restriction"), nullableLong(rs, "current_working_version_id"),
            nullableLong(rs, "current_published_version_id")),
        arguments.toArray());
  }

  public BulkPublishTargetState bulkPublishTargetState(List<Long> importBatchIds,
      String copyrightStatus, String usageRestriction) {
    if (importBatchIds == null || importBatchIds.isEmpty()) {
      return new BulkPublishTargetState(0, 0, Map.of(), List.of());
    }
    String placeholders = String.join(",", Collections.nCopies(importBatchIds.size(), "?"));
    List<Object> baseArguments = new ArrayList<>(importBatchIds);
    baseArguments.add(copyrightStatus);
    baseArguments.add(usageRestriction);
    Long total = jdbcTemplate.queryForObject("""
        SELECT COUNT(DISTINCT q.id)
        FROM q_question_version imported
        JOIN q_question q ON q.id = imported.question_id AND q.deleted = 0
        WHERE imported.import_batch_id IN (%s)
          AND imported.copyright_status = ? AND imported.usage_restriction = ?
          AND imported.deleted = 0
        """.formatted(placeholders), Long.class, baseArguments.toArray());
    Long pointerMismatch = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM q_question_version imported
        JOIN q_question q ON q.id = imported.question_id AND q.deleted = 0
        LEFT JOIN q_question_version current
          ON current.id = COALESCE(q.current_working_version_id, q.current_published_version_id)
         AND current.question_id = q.id AND current.deleted = 0
        WHERE imported.import_batch_id IN (%s)
          AND imported.copyright_status = ? AND imported.usage_restriction = ?
          AND imported.deleted = 0
          AND (current.id IS NULL OR current.id <> imported.id)
        """.formatted(placeholders), Long.class, baseArguments.toArray());
    Map<String, Long> statusCounts = new LinkedHashMap<>();
    jdbcTemplate.query("""
        SELECT COALESCE(current.status, '<NULL>') AS status_code, COUNT(*) AS amount
        FROM q_question_version imported
        JOIN q_question q ON q.id = imported.question_id AND q.deleted = 0
        LEFT JOIN q_question_version current
          ON current.id = COALESCE(q.current_working_version_id, q.current_published_version_id)
         AND current.question_id = q.id AND current.deleted = 0
        WHERE imported.import_batch_id IN (%s)
          AND imported.copyright_status = ? AND imported.usage_restriction = ?
          AND imported.deleted = 0
        GROUP BY COALESCE(current.status, '<NULL>')
        ORDER BY status_code
        """.formatted(placeholders), rs -> {
          statusCounts.put(rs.getString("status_code"), rs.getLong("amount"));
        }, baseArguments.toArray());
    List<Object> sampleArguments = new ArrayList<>(baseArguments);
    sampleArguments.addAll(List.of("DRAFT", "IN_REVIEW", "PUBLISHED"));
    List<BulkPublishUnsafeTarget> unsafeTargets = jdbcTemplate.query("""
        SELECT q.id AS question_id, imported.id AS imported_version_id,
               current.id AS current_version_id, current.status AS current_status
        FROM q_question_version imported
        JOIN q_question q ON q.id = imported.question_id AND q.deleted = 0
        LEFT JOIN q_question_version current
          ON current.id = COALESCE(q.current_working_version_id, q.current_published_version_id)
         AND current.question_id = q.id AND current.deleted = 0
        WHERE imported.import_batch_id IN (%s)
          AND imported.copyright_status = ? AND imported.usage_restriction = ?
          AND imported.deleted = 0
          AND (current.id IS NULL OR current.id <> imported.id
               OR current.status NOT IN (?, ?, ?))
        ORDER BY q.id
        LIMIT 10
        """.formatted(placeholders), (rs, rowNum) -> new BulkPublishUnsafeTarget(
            rs.getLong("question_id"), rs.getLong("imported_version_id"),
            nullableLong(rs, "current_version_id"), rs.getString("current_status")),
        sampleArguments.toArray());
    return new BulkPublishTargetState(total == null ? 0 : total,
        pointerMismatch == null ? 0 : pointerMismatch, Map.copyOf(statusCounts),
        List.copyOf(unsafeTargets));
  }

  public record ExistingImportTarget(long questionId, long questionVersionId, String status,
                                     String copyrightStatus, String usageRestriction) {
  }

  public record BulkPublishCandidate(long questionId, long questionVersionId, String status,
                                     long rowVersion, Long authorId, Long submittedBy,
                                     Long reviewedBy, String reviewDecision,
                                     String copyrightStatus, String usageRestriction,
                                     Long currentWorkingVersionId,
                                     Long currentPublishedVersionId) {
  }

  public record BulkPublishTargetState(long totalCount, long pointerMismatchCount,
                                       Map<String, Long> statusCounts,
                                       List<BulkPublishUnsafeTarget> unsafeTargets) {
  }

  public record BulkPublishUnsafeTarget(long questionId, long importedVersionId,
                                        Long currentVersionId, String currentStatus) {
  }

  private record MutationRow(String requestHash, String status, String responseJson) {
  }

  private record ExistingImportIdentity(long questionId, String externalKey,
                                        String contentHash, Long versionId) {
  }

  private record PublishedMetadata(String title, Integer yearNo, String difficulty,
                                    String sourceType, String sourceRef) {
  }

  private record LegacyMetadata(long qualificationId, long subjectId, String questionType,
                                String title, Integer yearNo, String difficulty,
                                String sourceType, String sourceRef) {
  }

  private record LegacySubQuestion(long id, int number, String type, String content,
                                   BigDecimal score, String answerFormat, String gradingMode,
                                   int sortOrder, String metadataJson) {
  }
}
