package com.aiexam.question.practice.attempt.infra;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ResourceNotFoundException;
import com.aiexam.question.practice.attempt.domain.AttemptStatus;
import com.aiexam.question.practice.attempt.domain.FeedbackMode;
import com.aiexam.question.practice.attempt.domain.GradingStatus;
import com.aiexam.question.practice.attempt.domain.PracticeAnswer;
import com.aiexam.question.practice.attempt.domain.PracticeAnswerCanonicalizer;
import com.aiexam.question.practice.attempt.domain.PracticeAnswerPayload;
import com.aiexam.question.practice.attempt.domain.PracticeAttempt;
import com.aiexam.question.practice.attempt.domain.PracticeQuestionSnapshot;
import com.aiexam.question.practice.attempt.domain.QuestionSettlementStatus;
import com.aiexam.question.practice.attempt.dto.PracticeAnswerSaveResultDto;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptSubmitResultDto;
import com.aiexam.question.practice.attempt.repository.PracticeAttemptRepository;
import com.aiexam.question.practice.attempt.repository.PracticeAttemptRepository.AttemptHistoryProjection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPracticeAttemptRepository implements PracticeAttemptRepository {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
  };

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  public JdbcPracticeAttemptRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<PracticeQuestionSnapshot> selectQuestions(QuestionSelection selection) {
    List<QuestionRow> rows = switch (selection.sourceType()) {
      case "PAPER" -> publishedPaperQuestions(selection);
      case "YEAR" -> publishedYearQuestions(selection);
      case "KNOWLEDGE" -> publishedKnowledgeQuestions(selection);
      case "DAILY" -> publishedDefaultQuestions(selection);
      default -> List.of();
    };
    List<PracticeQuestionSnapshot> snapshots = new ArrayList<>();
    int order = 1;
    for (QuestionRow row : rows) {
      snapshots.add(snapshot(row, order++));
    }
    return snapshots;
  }

  @Override
  public Optional<PracticeQuestionSnapshot> selectPublishedQuestionById(QuestionSelection selection,
                                                                        Long questionId) {
    return optional("""
            SELECT q.id, q.qualification_id, q.subject_id, q.question_type,
                   qv.title, qv.version_no AS content_version, qv.id AS question_version_id
            FROM q_question q
            JOIN q_question_version qv
              ON qv.id = q.current_published_version_id
             AND qv.question_id = q.id AND qv.status = 'PUBLISHED' AND qv.deleted = 0
            WHERE q.id = ? AND q.qualification_id = ? AND q.subject_id = ?
              AND q.status = 'PUBLISHED' AND q.deleted = 0
            """, this::questionRow, questionId, selection.qualificationId(), selection.subjectId())
        .map(row -> snapshot(row, 1));
  }

  @Override
  public PracticeAttempt createAttempt(NewAttempt attempt) {
    Timestamp startedAt = timestamp(attempt.startedAt());
    BigDecimal maxScore = attempt.questions().stream()
        .flatMap(question -> question.subQuestions().stream())
        .map(PracticeQuestionSnapshot.SubQuestion::score)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    long attemptId = insertAndReturnKey("""
            INSERT INTO practice_attempt (
              user_id, qualification_id, subject_id, paper_id, mode, source_type, source_spec_json,
              feedback_mode, requested_count, question_count, partial_flag, max_score,
              pending_review_count, version, status, started_at, total_score, correct_count, wrong_count,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 'IN_PROGRESS', ?, 0, 0, 0, ?, ?, ?, ?, 0)
            """,
        attempt.userId(),
        attempt.qualificationId(),
        attempt.subjectId(),
        paperId(attempt.sourceSpec()),
        attempt.mode(),
        attempt.sourceType(),
        json(attempt.sourceSpec()),
        attempt.feedbackMode(),
        attempt.requestedCount(),
        attempt.questions().size(),
        attempt.partial() ? 1 : 0,
        maxScore,
        startedAt,
        startedAt,
        startedAt,
        attempt.userId(),
        attempt.userId());
    for (PracticeQuestionSnapshot question : attempt.questions()) {
      insertAttemptQuestion(attemptId, question, startedAt);
    }
    return findAttempt(attempt.userId(), attemptId)
        .orElseThrow(() -> new ResourceNotFoundException("练习记录不存在"));
  }

  @Override
  public Optional<PracticeAttempt> findAttempt(Long userId, Long attemptId) {
    Optional<AttemptRow> row = optional("""
            SELECT id, user_id, qualification_id, subject_id, mode, source_type, source_spec_json,
                   feedback_mode, status, started_at, submitted_at, total_score, correct_count,
                   wrong_count, requested_count, question_count, partial_flag, max_score,
                   pending_review_count, version
            FROM practice_attempt
            WHERE id = ? AND user_id = ? AND deleted = 0 AND exam_session_subject_id IS NULL
            """, this::attemptRow, attemptId, userId);
    return row.map(this::attempt);
  }

  @Override
  public PageResponse<PracticeAttempt> findAttempts(
      Long userId,
      Long qualificationId,
      Long subjectId,
      AttemptStatus status,
      long pageNo,
      long pageSize
  ) {
    StringBuilder where = new StringBuilder(
        " WHERE user_id = ? AND deleted = 0 AND exam_session_subject_id IS NULL");
    List<Object> parameters = new ArrayList<>();
    parameters.add(userId);
    if (qualificationId != null) {
      where.append(" AND qualification_id = ?");
      parameters.add(qualificationId);
    }
    if (subjectId != null) {
      where.append(" AND subject_id = ?");
      parameters.add(subjectId);
    }
    if (status != null) {
      where.append(" AND status = ?");
      parameters.add(status.name());
    }
    Long total = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM practice_attempt" + where, Long.class, parameters.toArray());
    List<Object> pageParameters = new ArrayList<>(parameters);
    pageParameters.add(pageSize);
    pageParameters.add((pageNo - 1) * pageSize);
    List<AttemptRow> rows = jdbcTemplate.query("""
            SELECT id, user_id, qualification_id, subject_id, mode, source_type, source_spec_json,
                   feedback_mode, status, started_at, submitted_at, total_score, correct_count,
                   wrong_count, requested_count, question_count, partial_flag, max_score,
                   pending_review_count, version
            FROM practice_attempt
            """ + where + "\n" + """
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """, this::attemptRow, pageParameters.toArray());
    List<PracticeAttempt> items = rows.stream().map(this::attempt).toList();
    long safeTotal = total == null ? 0 : total;
    return new PageResponse<>(items, pageNo, pageSize, safeTotal,
        (pageNo - 1) * pageSize + items.size() < safeTotal);
  }

  @Override
  public PageResponse<AttemptHistoryProjection> findAttemptHistory(
      Long userId,
      Long qualificationId,
      Long subjectId,
      AttemptStatus status,
      long pageNo,
      long pageSize
  ) {
    StringBuilder where = new StringBuilder(
        " WHERE user_id = ? AND deleted = 0 AND exam_session_subject_id IS NULL");
    List<Object> parameters = new ArrayList<>();
    parameters.add(userId);
    if (qualificationId != null) {
      where.append(" AND qualification_id = ?");
      parameters.add(qualificationId);
    }
    if (subjectId != null) {
      where.append(" AND subject_id = ?");
      parameters.add(subjectId);
    }
    if (status != null) {
      where.append(" AND status = ?");
      parameters.add(status.name());
    }
    Long total = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM practice_attempt" + where, Long.class, parameters.toArray());
    List<Object> pageParameters = new ArrayList<>(parameters);
    pageParameters.add(pageSize);
    pageParameters.add((pageNo - 1) * pageSize);
    List<AttemptHistoryProjection> items = jdbcTemplate.query("""
            SELECT id, status, COALESCE(source_type, 'LEGACY') AS source_type, source_spec_json,
                   COALESCE(question_count, 0) AS question_count,
                   started_at, submitted_at, duration_seconds,
                   CASE WHEN status IN ('SUBMITTED','PENDING_REVIEW') THEN total_score ELSE NULL END total_score,
                   CASE WHEN status IN ('SUBMITTED','PENDING_REVIEW') THEN max_score ELSE NULL END max_score
            FROM practice_attempt
            """ + where + "\n" + """
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """, (rs, rowNum) -> new AttemptHistoryProjection(
                rs.getLong("id"),
                AttemptStatus.valueOf(rs.getString("status")),
                rs.getString("source_type"),
                readMap(rs.getString("source_spec_json")),
                rs.getInt("question_count"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("submitted_at")),
                (Integer) rs.getObject("duration_seconds"),
                rs.getBigDecimal("total_score"),
                rs.getBigDecimal("max_score")),
        pageParameters.toArray());
    long safeTotal = total == null ? 0 : total;
    return new PageResponse<>(items, pageNo, pageSize, safeTotal,
        (pageNo - 1) * pageSize + items.size() < safeTotal);
  }

  @Override
  public PracticeAttempt saveAttempt(PracticeAttempt attempt) {
    PracticeAttempt current = findAttempt(attempt.userId(), attempt.attemptId())
        .orElseThrow(() -> new ResourceNotFoundException("练习记录不存在"));
    if (attempt.version() < current.version() || attempt.version() > current.version() + 1) {
      throw conflict("attempt version conflict");
    }
    if (attempt.version() == current.version() + 1) {
      int updated = jdbcTemplate.update("""
              UPDATE practice_attempt
              SET status = ?, submitted_at = ?,
                  duration_seconds = CASE WHEN ? IS NULL THEN duration_seconds
                    ELSE GREATEST(0, TIMESTAMPDIFF(SECOND, started_at, ?)) END,
                  total_score = ?, correct_count = ?, wrong_count = ?,
                  pending_review_count = ?, version = ?, updated_at = ?
              WHERE id = ? AND user_id = ? AND version = ? AND deleted = 0
              """,
          attempt.status().name(),
          timestamp(attempt.submittedAt()),
          timestamp(attempt.submittedAt()),
          timestamp(attempt.submittedAt()),
          attempt.totalScore(),
          attempt.correctCount(),
          attempt.wrongCount(),
          attempt.pendingReviewCount(),
          attempt.version(),
          now(),
          attempt.attemptId(),
          attempt.userId(),
          current.version());
      if (updated != 1) {
        throw conflict("attempt version conflict");
      }
    }
    saveQuestionsAndMistakes(attempt, current);
    saveAnswers(attempt, current);
    jdbcTemplate.update("""
            UPDATE practice_attempt
            SET updated_at = ?, updated_by = ?
            WHERE id = ? AND user_id = ? AND deleted = 0
            """, now(), attempt.userId(), attempt.attemptId(), attempt.userId());
    return findAttempt(attempt.userId(), attempt.attemptId())
        .orElseThrow(() -> new ResourceNotFoundException("练习记录不存在"));
  }

  @Override
  public <T> T runIdempotent(MutationCommand command, Class<T> resultType, Supplier<T> action) {
    byte[] requestHash = HexFormat.of().parseHex(command.requestHash());
    String ownerToken = UUID.randomUUID().toString();
    Timestamp createdAt = timestamp(command.now());
    Long idempotencyId;
    try {
      jdbcTemplate.update("""
              INSERT INTO practice_attempt_idempotency (
                user_id, attempt_scope_id, operation_code, client_mutation_id, request_hash,
                status, owner_token, execution_epoch, lease_expires_at, created_at, expires_at
              ) VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, 1, ?, ?, ?)
              """,
          command.userId(),
          command.attemptScopeId(),
          command.operationCode(),
          command.clientMutationId().toString(),
          requestHash,
          ownerToken,
          timestamp(command.now().plusSeconds(30)),
          createdAt,
          timestamp(command.now().plusSeconds(30L * 24 * 3600)));
      idempotencyId = jdbcTemplate.queryForObject("""
              SELECT id
              FROM practice_attempt_idempotency
              WHERE user_id = ? AND operation_code = ? AND attempt_scope_id = ? AND client_mutation_id = ?
              """, Long.class, command.userId(), command.operationCode(), command.attemptScopeId(),
          command.clientMutationId().toString());
    } catch (DuplicateKeyException exception) {
      IdempotencyRow existing = jdbcTemplate.queryForObject("""
              SELECT id, request_hash, status, response_snapshot_json
              FROM practice_attempt_idempotency
              WHERE user_id = ? AND operation_code = ? AND attempt_scope_id = ? AND client_mutation_id = ?
              """, this::idempotencyRow, command.userId(), command.operationCode(), command.attemptScopeId(),
          command.clientMutationId().toString());
      if (existing == null || !Arrays.equals(existing.requestHash(), requestHash)) {
        throw new BusinessException("PRACTICE_IDEMPOTENCY_KEY_REUSED",
            "clientMutationId was already used with different request", HttpStatus.CONFLICT);
      }
      if ("COMPLETED".equals(existing.status())) {
        return markReplay(read(existing.responseSnapshotJson(), resultType));
      }
      throw new BusinessException("PRACTICE_REQUEST_PROCESSING",
          "same request is still processing", HttpStatus.CONFLICT);
    }

    T result;
    try {
      result = action.get();
    } catch (RuntimeException exception) {
      jdbcTemplate.update("""
              DELETE FROM practice_attempt_idempotency
              WHERE id = ? AND owner_token = ? AND status = 'PROCESSING'
              """, idempotencyId, ownerToken);
      throw exception;
    }
    int completed = jdbcTemplate.update("""
            UPDATE practice_attempt_idempotency
            SET status = 'COMPLETED', attempt_id = ?, http_status = 200, response_code = 'OK',
                response_snapshot_json = ?, completed_at = ?
            WHERE id = ? AND owner_token = ? AND status = 'PROCESSING'
            """,
        resultAttemptId(result),
        json(result),
        now(),
        idempotencyId,
        ownerToken);
    if (completed != 1) {
      throw conflict("practice idempotency ownership lost");
    }
    return result;
  }

  private List<QuestionRow> publishedDefaultQuestions(QuestionSelection selection) {
    return jdbcTemplate.query("""
            SELECT q.id, q.qualification_id, q.subject_id, q.question_type,
                   qv.title, qv.version_no AS content_version, qv.id AS question_version_id
            FROM q_question q
            JOIN q_question_version qv
              ON qv.id = q.current_published_version_id
             AND qv.question_id = q.id AND qv.status = 'PUBLISHED' AND qv.deleted = 0
            WHERE q.qualification_id = ? AND q.subject_id = ?
              AND q.status = 'PUBLISHED' AND q.deleted = 0
              AND NOT EXISTS (
                SELECT 1 FROM practice_attempt pa
                JOIN practice_attempt_question paq ON paq.attempt_id = pa.id
                WHERE pa.user_id = ? AND pa.source_type = 'DAILY' AND pa.deleted = 0
                  AND paq.question_id = q.id
                  AND CAST(pa.started_at AS DATE) = ?
              )
            ORDER BY qv.year_no DESC, q.id ASC
            LIMIT ?
        """, this::questionRow, selection.qualificationId(), selection.subjectId(),
        selection.userId(), selection.sourceSpec().get("practiceDate"), selection.count());
  }

  private List<QuestionRow> publishedYearQuestions(QuestionSelection selection) {
    return jdbcTemplate.query("""
            SELECT q.id, q.qualification_id, q.subject_id, q.question_type,
                   qv.title, qv.version_no AS content_version, qv.id AS question_version_id
            FROM q_question q
            JOIN q_question_version qv
              ON qv.id = q.current_published_version_id
             AND qv.question_id = q.id AND qv.status = 'PUBLISHED' AND qv.deleted = 0
            WHERE q.qualification_id = ? AND q.subject_id = ? AND qv.year_no = ?
              AND q.status = 'PUBLISHED' AND q.deleted = 0
            ORDER BY q.id ASC
            LIMIT ?
            """, this::questionRow, selection.qualificationId(), selection.subjectId(),
        selection.sourceSpec().get("yearNo"), selection.count());
  }

  private List<QuestionRow> publishedPaperQuestions(QuestionSelection selection) {
    return jdbcTemplate.query("""
            SELECT q.id, q.qualification_id, q.subject_id, q.question_type,
                   qv.title, qv.version_no AS content_version, qv.id AS question_version_id
            FROM q_paper_question pq
            JOIN q_question q ON q.id = pq.question_id AND q.deleted = 0 AND q.status = 'PUBLISHED'
            JOIN q_question_version qv
              ON qv.id = q.current_published_version_id
             AND qv.question_id = q.id AND qv.status = 'PUBLISHED' AND qv.deleted = 0
            JOIN q_paper p ON p.id = pq.paper_id AND p.deleted = 0 AND p.status = 'PUBLISHED'
            WHERE pq.paper_id = ?
              AND p.qualification_id = ?
              AND (? IS NULL OR p.subject_id IS NULL OR p.subject_id = ?)
              AND (? <> 'PAST_EXAM' OR p.paper_type = 'REAL')
              AND EXISTS (
                SELECT 1
                FROM q_paper_question all_pq
                JOIN q_question all_q
                  ON all_q.id = all_pq.question_id
                 AND all_q.deleted = 0
                 AND all_q.status = 'PUBLISHED'
                JOIN q_question_version all_qv
                  ON all_qv.id = all_q.current_published_version_id
                 AND all_qv.question_id = all_q.id
                 AND all_qv.status = 'PUBLISHED'
                 AND all_qv.deleted = 0
                WHERE all_pq.paper_id = p.id
                  AND all_pq.deleted = 0
                  AND all_pq.question_snapshot IS NOT NULL
                GROUP BY all_pq.paper_id
                HAVING MIN(all_pq.question_no) = 1
                   AND MAX(all_pq.question_no) = COUNT(*)
                   AND COUNT(DISTINCT all_pq.question_no) = COUNT(*)
                   AND COUNT(*) = (
                     SELECT COUNT(*)
                     FROM q_paper_question expected_pq
                     WHERE expected_pq.paper_id = p.id
                       AND expected_pq.deleted = 0
                   )
                   AND COUNT(*) <= ?
              )
            ORDER BY pq.question_no ASC, q.id ASC
            LIMIT ?
            """, this::questionRow, selection.sourceSpec().get("paperId"),
        selection.qualificationId(), selection.subjectId(), selection.subjectId(),
        selection.mode(), selection.count(), selection.count());
  }

  private List<QuestionRow> publishedKnowledgeQuestions(QuestionSelection selection) {
    Object ids = selection.sourceSpec().get("knowledgeIds");
    if (!(ids instanceof List<?> knowledgeIds) || knowledgeIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(knowledgeIds.size(), "?"));
    List<Object> arguments = new ArrayList<>();
    arguments.add(selection.qualificationId());
    arguments.add(selection.subjectId());
    String knowledgeType = String.valueOf(selection.sourceSpec().get("knowledgeType"));
    arguments.add(knowledgeType);
    arguments.add(knowledgeType);
    arguments.addAll(knowledgeIds);
    arguments.add(selection.count());
    return jdbcTemplate.query("""
            SELECT DISTINCT q.id, q.qualification_id, q.subject_id, q.question_type,
                            qv.title, qv.version_no AS content_version,
                            qv.id AS question_version_id
            FROM q_question q
            JOIN q_question_version qv
              ON qv.id = q.current_published_version_id
             AND qv.question_id = q.id AND qv.status = 'PUBLISHED' AND qv.deleted = 0
            JOIN q_question_knowledge_binding kb
              ON kb.question_id = q.id AND kb.question_version_id = qv.id AND kb.deleted = 0
            WHERE q.qualification_id = ? AND q.subject_id = ? AND q.status = 'PUBLISHED' AND q.deleted = 0
              AND ((? = 'CHAPTER_NODE' AND kb.knowledge_type IN ('CHAPTER', 'CHAPTER_NODE'))
                   OR kb.knowledge_type = ?)
              AND kb.knowledge_id IN (%s)
            ORDER BY q.id ASC
            LIMIT ?
            """.formatted(placeholders), this::questionRow, arguments.toArray());
  }

  private PracticeQuestionSnapshot snapshot(QuestionRow question, int order) {
    List<PracticeQuestionSnapshot.Stem> stems = jdbcTemplate.query("""
            SELECT id, stem_type, sort_order, content
            FROM q_question_stem
            WHERE question_id = ? AND question_version_id = ? AND deleted = 0
            ORDER BY sort_order ASC, id ASC
            """, (rs, rowNum) -> new PracticeQuestionSnapshot.Stem(
        rs.getLong("id"), rs.getString("stem_type"), rs.getInt("sort_order"), rs.getString("content")),
        question.id(), question.versionId());
    List<PracticeQuestionSnapshot.SubQuestion> subQuestions = jdbcTemplate.query("""
            SELECT id, question_id, sub_question_type, answer_format, content, metadata_json, score
            FROM q_sub_question
            WHERE question_id = ? AND question_version_id = ? AND deleted = 0
            ORDER BY sort_order ASC, sub_question_no ASC, id ASC
            """, this::subQuestionRow, question.id(), question.versionId())
        .stream().map(row -> subQuestion(row, question.versionId())).toList();
    List<PracticeQuestionSnapshot.KnowledgeBinding> bindings = jdbcTemplate.query("""
            SELECT knowledge_type, knowledge_id
            FROM q_question_knowledge_binding
            WHERE question_id = ? AND question_version_id = ? AND deleted = 0
            ORDER BY id ASC
            """, (rs, rowNum) -> new PracticeQuestionSnapshot.KnowledgeBinding(
        rs.getString("knowledge_type"), rs.getLong("knowledge_id"), null),
        question.id(), question.versionId());
    return new PracticeQuestionSnapshot(question.id(), question.versionId(), order, question.contentVersion(),
        question.questionType(), question.title(), stems, subQuestions, bindings, QuestionSettlementStatus.UNSETTLED, 0,
        BigDecimal.ZERO, 0, 0, 0);
  }

  private PracticeQuestionSnapshot.SubQuestion subQuestion(SubQuestionRow row, long questionVersionId) {
    List<PracticeQuestionSnapshot.Option> options = jdbcTemplate.query("""
            SELECT option_key, option_content, sort_order
            FROM q_choice_option
            WHERE sub_question_id = ? AND deleted = 0
            ORDER BY sort_order ASC, id ASC
            """, (rs, rowNum) -> new PracticeQuestionSnapshot.Option(
        rs.getString("option_key"), rs.getString("option_content"), rs.getInt("sort_order")), row.id());
    PracticeAnswerPayload standardAnswer = optional("""
            SELECT answer_format, answer_value, answer_json
            FROM q_answer_key
            WHERE sub_question_id = ? AND current_flag = 1 AND deleted = 0
            ORDER BY version_no DESC, id DESC
            LIMIT 1
            """, this::answerPayload, row.id())
        .map(answer -> PracticeAnswerCanonicalizer.canonicalize(row.subQuestionType(), answer))
        .orElse(null);
    String solution = optional("""
            SELECT content
            FROM q_solution
            WHERE question_id = ? AND (sub_question_id = ? OR sub_question_id IS NULL)
              AND question_version_id = ?
              AND solution_type IN ('STANDARD', 'REFERENCE_ANSWER', 'ANALYSIS') AND deleted = 0
            ORDER BY CASE WHEN sub_question_id = ? THEN 0 ELSE 1 END, version_no DESC, id DESC
            LIMIT 1
            """, (rs, rowNum) -> rs.getString("content"), row.questionId(), row.id(),
        questionVersionId, row.id()).orElse(null);
    List<PracticeQuestionSnapshot.ScoringPoint> scoringPoints = jdbcTemplate.query("""
            SELECT id, point_code, point_text, score, sort_order
            FROM q_scoring_point
            WHERE question_version_id = ? AND sub_question_id = ? AND deleted = 0
            ORDER BY sort_order ASC, id ASC
            """, (rs, rowNum) -> new PracticeQuestionSnapshot.ScoringPoint(
        rs.getLong("id"),
        rs.getString("point_code"),
        rs.getString("point_text"),
        nullToZero(rs.getBigDecimal("score")),
        rs.getInt("sort_order")), questionVersionId, row.id());
    return new PracticeQuestionSnapshot.SubQuestion(row.id(), row.subQuestionType(), row.answerFormat(),
        row.content(), row.metadata(), options, standardAnswer, solution, scoringPoints, row.score());
  }

  private void insertAttemptQuestion(long attemptId, PracticeQuestionSnapshot question, Timestamp createdAt) {
    String snapshot = json(question);
    jdbcTemplate.update("""
            INSERT INTO practice_attempt_question (
              attempt_id, question_id, question_order, question_content_version, question_type, title, max_score,
              question_snapshot_json, grading_snapshot_json, solution_snapshot_json, knowledge_snapshot_json,
              settlement_status, settlement_version, snapshot_hash, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNSETTLED', 0, ?, ?, ?)
            """,
        attemptId,
        question.questionId(),
        question.questionOrder(),
        question.contentVersion(),
        question.questionType(),
        question.title(),
        question.subQuestions().stream().map(PracticeQuestionSnapshot.SubQuestion::score)
            .reduce(BigDecimal.ZERO, BigDecimal::add),
        snapshot,
        snapshot,
        snapshot,
        json(question.knowledgeBindings()),
        sha256(snapshot),
        createdAt,
        createdAt);
  }

  private PracticeAttempt attempt(AttemptRow row) {
    List<PracticeQuestionSnapshot> questions = jdbcTemplate.query("""
            SELECT question_id, question_content_version, question_snapshot_json, settlement_status, settlement_version, earned_score,
                   correct_count, wrong_count, pending_review_count
            FROM practice_attempt_question
            WHERE attempt_id = ?
            ORDER BY question_order ASC
            """, this::attemptQuestion, row.id());
    Map<Long, PracticeAnswer> answers = new LinkedHashMap<>();
    jdbcTemplate.query("""
            SELECT sub_question_id, question_id, answer_json, marked, used_seconds, answer_version,
                   grading_status, is_correct, saved_at
            FROM practice_attempt_answer
            WHERE attempt_id = ? AND deleted = 0
            ORDER BY sub_question_id ASC
            """, rs -> {
          PracticeAnswer answer = answer(rs);
          answers.put(answer.subQuestionId(), answer);
        }, row.id());
    return new PracticeAttempt(row.id(), row.userId(), row.qualificationId(), row.subjectId(), row.mode(),
        row.sourceType(), row.sourceSpec(), row.feedbackMode(), row.status(), row.requestedCount(),
        row.partial(), row.startedAt(), row.submittedAt(), row.version(), row.totalScore(), row.maxScore(),
        row.correctCount(), row.wrongCount(), row.pendingReviewCount(), questions, answers);
  }

  private void saveQuestionsAndMistakes(PracticeAttempt attempt, PracticeAttempt current) {
    Map<Long, PracticeQuestionSnapshot> beforeByQuestion = current.questions().stream()
        .collect(java.util.stream.Collectors.toMap(PracticeQuestionSnapshot::questionId, question -> question));
    for (PracticeQuestionSnapshot question : attempt.questions()) {
      PracticeQuestionSnapshot before = beforeByQuestion.get(question.questionId());
      if (question.settlementStatus() != QuestionSettlementStatus.SETTLED || before == null
          || before.settlementStatus() == QuestionSettlementStatus.SETTLED) {
        continue;
      }
      int updated = jdbcTemplate.update("""
              UPDATE practice_attempt_question
              SET settlement_status = 'SETTLED', settlement_version = ?, settled_at = ?, earned_score = ?,
                  correct_count = ?, wrong_count = ?, pending_review_count = ?, updated_at = ?
              WHERE attempt_id = ? AND question_id = ? AND settlement_status = 'UNSETTLED'
                AND settlement_version = ?
              """,
          question.settlementVersion(),
          now(),
          question.earnedScore(),
          question.correctCount(),
          question.wrongCount(),
          question.pendingReviewCount(),
          now(),
          attempt.attemptId(),
          question.questionId(),
          before.settlementVersion());
      if (updated != 1) {
        throw conflict("question settlement version conflict");
      }
      if (question.wrongCount() > 0) {
        upsertMistake(attempt, question);
      }
    }
  }

  private void saveAnswers(PracticeAttempt attempt, PracticeAttempt current) {
    for (PracticeAnswer answer : attempt.answers().values()) {
      PracticeAnswer before = current.answers().get(answer.subQuestionId());
      Optional<AnswerVersionRow> stored = optional("""
              SELECT id, answer_version
              FROM practice_attempt_answer
              WHERE attempt_id = ? AND sub_question_id = ? AND deleted = 0
              """, (rs, rowNum) -> new AnswerVersionRow(rs.getLong("id"), rs.getLong("answer_version")),
          attempt.attemptId(), answer.subQuestionId());
      if (stored.isEmpty()) {
        insertAnswer(attempt, answer);
        continue;
      }
      if (before != null && answer.equals(before)) {
        continue;
      }
      long databaseVersion = stored.get().answerVersion();
      if (answer.answerVersion() == databaseVersion + 1) {
        updateAnswer(attempt, answer, stored.get(), databaseVersion, true);
      } else if (answer.answerVersion() == databaseVersion
          && before != null && sameAnswerStateExceptGrading(answer, before)) {
        updateAnswer(attempt, answer, stored.get(), databaseVersion, false);
      } else {
        throw conflict("answer version conflict");
      }
    }
  }

  private void insertAnswer(PracticeAttempt attempt, PracticeAnswer answer) {
    if (answer.answerVersion() != 1) {
      throw conflict("answer version conflict");
    }
    try {
      insertAndReturnKey("""
              INSERT INTO practice_attempt_answer (
                attempt_id, question_id, sub_question_id, answer_json, grading_status, answer_version,
                is_correct, used_seconds, marked, saved_at, created_at, updated_at, created_by, updated_by, deleted
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
              """,
          attempt.attemptId(),
          answer.questionId(),
          answer.subQuestionId(),
          json(answer.answerPayload()),
          answer.gradingStatus().name(),
          answer.answerVersion(),
          correctness(answer.gradingStatus()),
          answer.usedSeconds(),
          answer.marked() ? 1 : 0,
          timestamp(answer.savedAt()),
          now(),
          now(),
          attempt.userId(),
          attempt.userId());
    } catch (DuplicateKeyException exception) {
      throw conflict("answer version conflict");
    }
  }

  private void updateAnswer(PracticeAttempt attempt, PracticeAnswer answer, AnswerVersionRow stored,
                            long expectedVersion, boolean payloadChanged) {
    int updated;
    if (payloadChanged) {
      updated = jdbcTemplate.update("""
              UPDATE practice_attempt_answer
              SET answer_json = ?, grading_status = ?, answer_version = ?, is_correct = ?,
                  used_seconds = ?, marked = ?, saved_at = ?, updated_at = ?, updated_by = ?
              WHERE id = ? AND answer_version = ? AND deleted = 0
              """,
          json(answer.answerPayload()),
          answer.gradingStatus().name(),
          answer.answerVersion(),
          correctness(answer.gradingStatus()),
          answer.usedSeconds(),
          answer.marked() ? 1 : 0,
          timestamp(answer.savedAt()),
          now(),
          attempt.userId(),
          stored.id(),
          expectedVersion);
    } else {
      updated = jdbcTemplate.update("""
              UPDATE practice_attempt_answer
              SET grading_status = ?, is_correct = ?, updated_at = ?, updated_by = ?
              WHERE id = ? AND answer_version = ? AND deleted = 0
              """,
          answer.gradingStatus().name(),
          correctness(answer.gradingStatus()),
          now(),
          attempt.userId(),
          stored.id(),
          expectedVersion);
    }
    if (updated != 1) {
      throw conflict("answer version conflict");
    }
  }

  private void upsertMistake(PracticeAttempt attempt, PracticeQuestionSnapshot question) {
    int updated = jdbcTemplate.update("""
            UPDATE practice_mistake
            SET last_attempt_id = ?, mistake_count = mistake_count + 1, last_wrong_at = ?,
                status = 'ACTIVE', updated_at = ?, updated_by = ?, deleted = 0
            WHERE user_id = ? AND question_id = ?
            """,
        attempt.attemptId(), now(), now(), attempt.userId(), attempt.userId(), question.questionId());
    if (updated == 1) {
      return;
    }
    try {
      insertAndReturnKey("""
              INSERT INTO practice_mistake (
                user_id, question_id, qualification_id, subject_id, first_attempt_id, last_attempt_id,
                mistake_count, last_wrong_at, status, created_at, updated_at, created_by, updated_by, deleted
              ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, 'ACTIVE', ?, ?, ?, ?, 0)
              """,
          attempt.userId(),
          question.questionId(),
          attempt.qualificationId(),
          attempt.subjectId(),
          attempt.attemptId(),
          attempt.attemptId(),
          now(),
          now(),
          now(),
          attempt.userId(),
          attempt.userId());
    } catch (DuplicateKeyException exception) {
      int retried = jdbcTemplate.update("""
              UPDATE practice_mistake
              SET last_attempt_id = ?, mistake_count = mistake_count + 1, last_wrong_at = ?,
                  status = 'ACTIVE', updated_at = ?, updated_by = ?, deleted = 0
              WHERE user_id = ? AND question_id = ?
              """,
          attempt.attemptId(), now(), now(), attempt.userId(), attempt.userId(), question.questionId());
      if (retried != 1) {
        throw exception;
      }
    }
  }

  private AttemptRow attemptRow(ResultSet rs, int rowNum) throws SQLException {
    String sourceType = rs.getString("source_type");
    String feedbackMode = rs.getString("feedback_mode");
    int questionCount = nullableInt(rs, "question_count");
    int requestedCount = nullableInt(rs, "requested_count");
    return new AttemptRow(
        rs.getLong("id"),
        rs.getLong("user_id"),
        rs.getLong("qualification_id"),
        nullableLong(rs, "subject_id"),
        rs.getString("mode"),
        sourceType == null ? "LEGACY" : sourceType,
        readMap(rs.getString("source_spec_json")),
        feedbackMode == null ? FeedbackMode.WHOLE_PAPER : FeedbackMode.valueOf(feedbackMode),
        AttemptStatus.valueOf(rs.getString("status")),
        instant(rs.getTimestamp("started_at")),
        instant(rs.getTimestamp("submitted_at")),
        nullToZero(rs.getBigDecimal("total_score")),
        nullableInt(rs, "correct_count"),
        nullableInt(rs, "wrong_count"),
        requestedCount == 0 ? questionCount : requestedCount,
        questionCount,
        rs.getInt("partial_flag") == 1,
        nullToZero(rs.getBigDecimal("max_score")),
        nullableInt(rs, "pending_review_count"),
        rs.getLong("version"));
  }

  private QuestionRow questionRow(ResultSet rs, int rowNum) throws SQLException {
    return new QuestionRow(rs.getLong("id"), rs.getLong("qualification_id"), rs.getLong("subject_id"),
        rs.getString("question_type"), rs.getString("title"), rs.getInt("content_version"),
        rs.getLong("question_version_id"));
  }

  private SubQuestionRow subQuestionRow(ResultSet rs, int rowNum) throws SQLException {
    return new SubQuestionRow(rs.getLong("id"), rs.getLong("question_id"),
        rs.getString("sub_question_type"), rs.getString("answer_format"), rs.getString("content"),
        readMap(rs.getString("metadata_json")), nullToZero(rs.getBigDecimal("score")));
  }

  private PracticeAnswerPayload answerPayload(ResultSet rs, int rowNum) throws SQLException {
    String format = rs.getString("answer_format");
    String value = rs.getString("answer_value");
    String json = rs.getString("answer_json");
    if (json != null && !json.isBlank()) {
      JsonNode node = readTree(json);
      if (node.isObject()) {
        return read(json, PracticeAnswerPayload.class);
      }
      if (node.isArray()) {
        List<String> keys = new ArrayList<>();
        node.forEach(item -> keys.add(item.asText()));
        return new PracticeAnswerPayload(null, keys, null, null);
      }
      if (node.isBoolean()) {
        return new PracticeAnswerPayload(null, null, node.asBoolean(), null);
      }
      if (node.isTextual()) {
        value = node.asText();
      }
    }
    if ("BOOLEAN".equals(format)) {
      return new PracticeAnswerPayload(null, null, Boolean.valueOf(value), null);
    }
    if ("OPTION".equals(format) && value != null && value.matches(".*[,|;].*")) {
      return new PracticeAnswerPayload(null,
          Arrays.stream(value.split("[,|;]"))
              .map(String::trim)
              .filter(item -> !item.isEmpty())
              .sorted()
              .toList(), null, null);
    }
    if ("OPTION".equals(format)) {
      return new PracticeAnswerPayload(value, null, null, null);
    }
    return new PracticeAnswerPayload(null, null, null, value);
  }

  private PracticeQuestionSnapshot attemptQuestion(ResultSet rs, int rowNum) throws SQLException {
    String snapshotJson = rs.getString("question_snapshot_json");
    JsonNode snapshotNode = readTree(snapshotJson);
    long questionVersionId = snapshotQuestionVersionId(
        snapshotNode, rs.getLong("question_id"), rs.getInt("question_content_version"));
    PracticeQuestionSnapshot snapshot = canonicalizeMissingSnapshotAnswerFormats(
        read(snapshotJson, PracticeQuestionSnapshot.class), snapshotNode);
    return new PracticeQuestionSnapshot(snapshot.questionId(), questionVersionId,
        snapshot.questionOrder(), snapshot.contentVersion(), snapshot.questionType(), snapshot.title(), snapshot.stems(),
        snapshot.subQuestions(),
        snapshot.knowledgeBindings(), QuestionSettlementStatus.valueOf(rs.getString("settlement_status")),
        rs.getLong("settlement_version"), nullToZero(rs.getBigDecimal("earned_score")),
        nullableInt(rs, "correct_count"), nullableInt(rs, "wrong_count"),
        nullableInt(rs, "pending_review_count"));
  }

  private PracticeQuestionSnapshot canonicalizeMissingSnapshotAnswerFormats(
      PracticeQuestionSnapshot snapshot,
      JsonNode snapshotNode
  ) {
    JsonNode subQuestionNodes = snapshotNode.get("subQuestions");
    if (subQuestionNodes == null || !subQuestionNodes.isArray()
        || subQuestionNodes.size() != snapshot.subQuestions().size()) {
      throw invalidSnapshotAnswerFormat();
    }
    List<PracticeQuestionSnapshot.SubQuestion> subQuestions = new ArrayList<>();
    for (int index = 0; index < snapshot.subQuestions().size(); index++) {
      subQuestions.add(canonicalizeMissingSnapshotAnswerFormat(
          snapshot.subQuestions().get(index), subQuestionNodes.get(index)));
    }
    return new PracticeQuestionSnapshot(snapshot.questionId(), snapshot.questionVersionId(),
        snapshot.questionOrder(), snapshot.contentVersion(), snapshot.questionType(), snapshot.title(),
        snapshot.stems(), subQuestions, snapshot.knowledgeBindings(), snapshot.settlementStatus(),
        snapshot.settlementVersion(), snapshot.earnedScore(), snapshot.correctCount(), snapshot.wrongCount(),
        snapshot.pendingReviewCount());
  }

  private PracticeQuestionSnapshot.SubQuestion canonicalizeMissingSnapshotAnswerFormat(
      PracticeQuestionSnapshot.SubQuestion subQuestion,
      JsonNode subQuestionNode
  ) {
    if (subQuestionNode == null || !subQuestionNode.isObject()) {
      throw invalidSnapshotAnswerFormat();
    }
    JsonNode answerFormatNode = subQuestionNode.get("answerFormat");
    String canonical = answerFormatNode == null
        ? missingSnapshotAnswerFormat(subQuestion.subQuestionType())
        : formalSnapshotAnswerFormat(answerFormatNode);
    if (canonical.equals(subQuestion.answerFormat())) {
      return subQuestion;
    }
    return new PracticeQuestionSnapshot.SubQuestion(subQuestion.subQuestionId(), subQuestion.subQuestionType(),
        canonical, subQuestion.content(), subQuestion.metadata(), subQuestion.options(),
        subQuestion.standardAnswer(), subQuestion.solution(), subQuestion.scoringPoints(), subQuestion.score());
  }

  private long snapshotQuestionVersionId(JsonNode snapshotNode, long questionId, int questionContentVersion) {
    if (!snapshotNode.has("questionVersionId")) {
      return resolveQuestionVersionId(questionId, questionContentVersion);
    }
    JsonNode versionIdNode = snapshotNode.get("questionVersionId");
    if (!versionIdNode.isIntegralNumber() || !versionIdNode.canConvertToLong()
        || versionIdNode.longValue() <= 0) {
      throw invalidSnapshotQuestionVersionId();
    }
    return versionIdNode.longValue();
  }

  private long resolveQuestionVersionId(long questionId, int questionContentVersion) {
    List<Long> versionIds = jdbcTemplate.query("""
            SELECT id
            FROM q_question_version
            WHERE question_id = ? AND version_no = ? AND deleted = 0
            """, (rs, rowNum) -> rs.getLong("id"), questionId, questionContentVersion);
    if (versionIds.size() != 1) {
      throw invalidSnapshotQuestionVersionId();
    }
    return versionIds.get(0);
  }

  private String formalSnapshotAnswerFormat(JsonNode answerFormatNode) {
    if (!answerFormatNode.isTextual()) {
      throw invalidSnapshotAnswerFormat();
    }
    String format = answerFormatNode.asText();
    return switch (format) {
      case "OPTION", "BOOLEAN", "TEXT", "NUMBER", "JSON" -> format;
      default -> throw invalidSnapshotAnswerFormat();
    };
  }

  private String missingSnapshotAnswerFormat(String subQuestionType) {
    String type = subQuestionType == null ? "" : subQuestionType.trim().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "SINGLE_CHOICE", "MULTI_CHOICE", "MULTIPLE_CHOICE" -> "OPTION";
      case "JUDGE" -> "BOOLEAN";
      case "SHORT_ANSWER", "ESSAY_WRITING" -> "TEXT";
      case "CALCULATION" -> "NUMBER";
      default -> throw invalidSnapshotAnswerFormat();
    };
  }

  private PracticeAnswer answer(ResultSet rs) throws SQLException {
    GradingStatus status;
    if (rs.getString("grading_status") != null) {
      status = GradingStatus.valueOf(rs.getString("grading_status"));
    } else {
      int correct = rs.getInt("is_correct");
      status = rs.wasNull() ? GradingStatus.SAVED : (correct == 1 ? GradingStatus.CORRECT : GradingStatus.WRONG);
    }
    return new PracticeAnswer(rs.getLong("sub_question_id"), rs.getLong("question_id"),
        read(rs.getString("answer_json"), PracticeAnswerPayload.class), rs.getInt("marked") == 1,
        nullableInt(rs, "used_seconds"), rs.getLong("answer_version"), status,
        instant(rs.getTimestamp("saved_at")));
  }

  private IdempotencyRow idempotencyRow(ResultSet rs, int rowNum) throws SQLException {
    return new IdempotencyRow(rs.getLong("id"), rs.getBytes("request_hash"), rs.getString("status"),
        rs.getString("response_snapshot_json"));
  }

  private Long resultAttemptId(Object result) {
    if (result instanceof com.aiexam.question.practice.attempt.dto.PracticeAttemptDetailDto dto) {
      return dto.attemptId();
    }
    if (result instanceof PracticeAnswerSaveResultDto dto) {
      return dto.attemptId();
    }
    if (result instanceof PracticeAttemptSubmitResultDto dto) {
      return dto.attemptId();
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private <T> T markReplay(T value) {
    if (value instanceof PracticeAnswerSaveResultDto dto) {
      return (T) new PracticeAnswerSaveResultDto(dto.attemptId(), dto.subQuestionId(), dto.answerVersion(),
          dto.savedAt(), true);
    }
    if (value instanceof PracticeAttemptSubmitResultDto dto) {
      return (T) new PracticeAttemptSubmitResultDto(dto.attemptId(), dto.questionId(), dto.status(),
          dto.totalScore(), dto.maxScore(), dto.correctCount(), dto.wrongCount(), dto.pendingReviewCount(),
          dto.version(), dto.submittedAt(), true);
    }
    return value;
  }

  private boolean sameAnswerStateExceptGrading(PracticeAnswer left, PracticeAnswer right) {
    return Objects.equals(left.subQuestionId(), right.subQuestionId())
        && Objects.equals(left.questionId(), right.questionId())
        && Objects.equals(left.answerPayload(), right.answerPayload())
        && left.marked() == right.marked()
        && left.usedSeconds() == right.usedSeconds()
        && left.answerVersion() == right.answerVersion()
        && Objects.equals(left.savedAt(), right.savedAt());
  }

  private Integer correctness(GradingStatus status) {
    return switch (status) {
      case CORRECT -> 1;
      case WRONG -> 0;
      case SAVED, PENDING_REVIEW -> null;
    };
  }

  private <T> Optional<T> optional(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(sql, mapper, args));
    } catch (EmptyResultDataAccessException exception) {
      return Optional.empty();
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("cannot serialize practice attempt payload", exception);
    }
  }

  private <T> T read(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("cannot read practice attempt payload", exception);
    }
  }

  private JsonNode readTree(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("cannot read answer payload", exception);
    }
  }

  private Map<String, Object> readMap(String json) {
    try {
      return json == null ? Map.of() : objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("cannot read source spec", exception);
    }
  }

  private byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private BigDecimal nullToZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
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
      throw new IllegalStateException("database did not return a generated practice key");
    }
    return key.longValue();
  }

  private Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  private Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private int nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? 0 : value;
  }

  private Long paperId(Map<String, Object> sourceSpec) {
    Object value = sourceSpec.get("paperId");
    return value instanceof Number number ? number.longValue() : null;
  }

  private BusinessException conflict(String message) {
    return new BusinessException("CONFLICT", message, HttpStatus.CONFLICT);
  }

  private BusinessException invalidSnapshotAnswerFormat() {
    return new BusinessException("PRACTICE_ATTEMPT_SNAPSHOT_CONTRACT_INVALID",
        "练习题目快照答案格式不受支持，请重新进入练习或联系管理员", HttpStatus.UNPROCESSABLE_ENTITY);
  }

  private BusinessException invalidSnapshotQuestionVersionId() {
    return new BusinessException("PRACTICE_ATTEMPT_SNAPSHOT_CONTRACT_INVALID",
        "练习题目快照版本信息缺失，请重新进入练习或联系管理员",
        HttpStatus.UNPROCESSABLE_ENTITY);
  }

  private record AttemptRow(
      Long id,
      Long userId,
      Long qualificationId,
      Long subjectId,
      String mode,
      String sourceType,
      Map<String, Object> sourceSpec,
      FeedbackMode feedbackMode,
      AttemptStatus status,
      Instant startedAt,
      Instant submittedAt,
      BigDecimal totalScore,
      int correctCount,
      int wrongCount,
      int requestedCount,
      int questionCount,
      boolean partial,
      BigDecimal maxScore,
      int pendingReviewCount,
      long version
  ) {
  }

  private record QuestionRow(Long id, Long qualificationId, Long subjectId, String questionType, String title,
                             int contentVersion, long versionId) {
  }

  private record SubQuestionRow(Long id, Long questionId, String subQuestionType, String answerFormat,
                                String content, Map<String, Object> metadata, BigDecimal score) {
  }

  private record AnswerVersionRow(Long id, long answerVersion) {
  }

  private record IdempotencyRow(Long id, byte[] requestHash, String status, String responseSnapshotJson) {
  }
}
