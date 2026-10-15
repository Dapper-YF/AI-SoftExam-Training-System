package com.aiexam.question.infra;

import com.aiexam.common.api.PageResponse;
import com.aiexam.question.dto.QuestionDetailDto;
import com.aiexam.question.dto.QuestionExplanationDto;
import com.aiexam.question.dto.QuestionSummaryDto;
import com.aiexam.question.dto.request.QuestionCreateRequest;
import com.aiexam.question.dto.request.QuestionUpdateRequest;
import com.aiexam.question.repository.QuestionDetailRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuestionRepository implements QuestionDetailRepository {
  private final JdbcTemplate jdbcTemplate;

  public JdbcQuestionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<QuestionDetailDto> findPublishedDetail(Long questionId) {
    return findDetail(questionId, true, false);
  }

  @Override
  public Optional<QuestionDetailDto> findAdminDetail(Long questionId) {
    return findDetail(questionId, false, true);
  }

  @Override
  public Optional<QuestionExplanationDto> findPublishedExplanation(Long questionId) {
    Optional<QuestionRow> question = optional("""
            SELECT q.id, q.qualification_id, q.subject_id, q.question_type,
                   qv.title, qv.status, qv.id AS question_version_id
            FROM q_question q
            JOIN q_question_version qv
              ON qv.id = q.current_published_version_id
             AND qv.question_id = q.id AND qv.status = 'PUBLISHED' AND qv.deleted = 0
            WHERE q.id = ? AND q.deleted = 0 AND q.status = 'PUBLISHED'
            """,
        this::question,
        questionId);
    if (question.isEmpty()) {
      return Optional.empty();
    }
    List<QuestionExplanationDto.SolutionDto> rows = jdbcTemplate.query("""
            SELECT id, question_id, sub_question_id, solution_type, content
            FROM q_solution
            WHERE question_id = ? AND question_version_id = ? AND deleted = 0
              AND solution_type IN ('STANDARD', 'REFERENCE_ANSWER', 'ANALYSIS', 'AI_ENHANCED')
            ORDER BY CASE WHEN sub_question_id IS NULL THEN 0 ELSE 1 END, sub_question_id ASC, id ASC
            """,
        this::explanationSolution,
        questionId, question.get().versionId());
    List<QuestionExplanationDto.SolutionDto> standardSolutions = rows.stream()
        .filter(row -> isStandardSolution(row.solutionType()) && row.subQuestionId() == null)
        .toList();
    Map<Long, List<QuestionExplanationDto.SolutionDto>> standardBySubQuestion = new LinkedHashMap<>();
    for (QuestionExplanationDto.SolutionDto row : rows) {
      if (isStandardSolution(row.solutionType()) && row.subQuestionId() != null) {
        standardBySubQuestion.computeIfAbsent(row.subQuestionId(), ignored -> new ArrayList<>()).add(row);
      }
    }
    List<QuestionExplanationDto.SubQuestionSolutionDto> subQuestionSolutions = standardBySubQuestion.entrySet().stream()
        .map(entry -> new QuestionExplanationDto.SubQuestionSolutionDto(entry.getKey(), entry.getValue()))
        .toList();
    List<QuestionExplanationDto.SolutionDto> aiEnhancedSolutions = rows.stream()
        .filter(row -> "AI_ENHANCED".equals(row.solutionType()))
        .toList();
    return Optional.of(new QuestionExplanationDto(
        question.get().id(),
        question.get().questionType(),
        standardSolutions,
        subQuestionSolutions,
        aiEnhancedSolutions));
  }

  @Override
  public PageResponse<QuestionSummaryDto> findAdminQuestions(long pageNo, long pageSize) {
    long total = count("SELECT COUNT(*) FROM q_question WHERE deleted = 0");
    List<QuestionSummaryDto> items = jdbcTemplate.query("""
            SELECT id, qualification_id, subject_id, question_type, title, year_no, difficulty, status, content_version
            FROM q_question
            WHERE deleted = 0
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """,
        this::summary,
        pageSize,
        offset(pageNo, pageSize));
    return new PageResponse<>(items, pageNo, pageSize, total, offset(pageNo, pageSize) + items.size() < total);
  }

  @Override
  public PageResponse<QuestionSummaryDto> findPublishedQuestions(long pageNo, long pageSize) {
    long total = count("SELECT COUNT(*) FROM q_question WHERE deleted = 0 AND status = 'PUBLISHED'");
    List<QuestionSummaryDto> items = jdbcTemplate.query("""
            SELECT id, qualification_id, subject_id, question_type, title, year_no, difficulty, status, content_version
            FROM q_question
            WHERE deleted = 0 AND status = 'PUBLISHED'
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """,
        this::summary,
        pageSize,
        offset(pageNo, pageSize));
    return new PageResponse<>(items, pageNo, pageSize, total, offset(pageNo, pageSize) + items.size() < total);
  }

  @Override
  public void insertQuestion(Long questionId, QuestionCreateRequest request, String status) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO q_question (
              id, qualification_id, subject_id, question_type, title, year_no, difficulty,
              source_type, source_ref, status, content_version, created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, NULL, NULL, 0)
            """,
        questionId,
        request.qualificationId(),
        request.subjectId(),
        upper(request.questionType()),
        request.title(),
        request.yearNo(),
        upper(request.difficulty()),
        upper(request.sourceType()),
        request.sourceRef(),
        status,
        now,
        now);
  }

  @Override
  public void updateQuestion(Long questionId, QuestionUpdateRequest request) {
    jdbcTemplate.update("""
            UPDATE q_question
            SET title = COALESCE(?, title),
                year_no = COALESCE(?, year_no),
                difficulty = COALESCE(?, difficulty),
                source_type = COALESCE(?, source_type),
                source_ref = COALESCE(?, source_ref),
                content_version = content_version + 1,
                updated_at = ?
            WHERE id = ? AND deleted = 0
            """,
        request.title(),
        request.yearNo(),
        upper(request.difficulty()),
        upper(request.sourceType()),
        request.sourceRef(),
        now(),
        questionId);
  }

  @Override
  public void insertStem(Long stemId, Long questionId, QuestionCreateRequest.StemRequest request) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO q_question_stem (
              id, question_id, stem_type, content, attachment_url, sort_order,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)
            """,
        stemId,
        questionId,
        upper(request.stemType()),
        request.content(),
        request.attachmentUrl(),
        sortOrder(request.sortOrder()),
        now,
        now);
  }

  @Override
  public void insertSubQuestion(Long subQuestionId, Long questionId, QuestionCreateRequest.SubQuestionRequest request) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO q_sub_question (
              id, question_id, sub_question_no, sub_question_type, content, score,
              answer_format, grading_mode, sort_order, metadata_json,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)
            """,
        subQuestionId,
        questionId,
        request.subQuestionNo(),
        upper(request.subQuestionType()),
        request.content(),
        request.score(),
        upper(request.answerFormat()),
        upper(request.gradingMode()),
        sortOrder(request.sortOrder()),
        request.metadataJson(),
        now,
        now);
  }

  @Override
  public void insertOption(Long optionId, Long subQuestionId, QuestionCreateRequest.OptionRequest request) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO q_choice_option (
              id, sub_question_id, option_key, option_content, sort_order,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)
            """,
        optionId,
        subQuestionId,
        request.optionKey(),
        request.optionContent(),
        sortOrder(request.sortOrder()),
        now,
        now);
  }

  @Override
  public void insertAnswerKey(Long answerKeyId, Long subQuestionId, QuestionCreateRequest.AnswerKeyRequest request) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO q_answer_key (
              id, sub_question_id, answer_format, answer_value, answer_json, version_no, current_flag,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, 1, 1, ?, ?, NULL, NULL, 0)
            """,
        answerKeyId,
        subQuestionId,
        upper(request.answerFormat()),
        request.answerValue(),
        request.answerJson(),
        now,
        now);
  }

  @Override
  public void insertSolution(Long solutionId, Long questionId, Long subQuestionId, QuestionCreateRequest.SolutionRequest request) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO q_solution (
              id, question_id, sub_question_id, solution_type, content, ai_task_id, prompt_version, model_code,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, NULL, NULL, 0)
            """,
        solutionId,
        questionId,
        subQuestionId,
        upper(request.solutionType()),
        request.content(),
        now,
        now);
  }

  @Override
  public void insertScoringRule(Long ruleId, Long subQuestionId, QuestionCreateRequest.ScoringRuleRequest request) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO q_scoring_rule (
              id, sub_question_id, rule_type, rule_json,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, 0)
            """,
        ruleId,
        subQuestionId,
        upper(request.ruleType()),
        request.ruleJson(),
        now,
        now);
  }

  @Override
  public void insertKnowledgeBinding(
      Long bindingId,
      Long questionId,
      Long subQuestionId,
      String targetType,
      Long targetId,
      QuestionCreateRequest.KnowledgeBindingRequest request
  ) {
    jdbcTemplate.update("""
            INSERT INTO q_question_knowledge_binding (
              id, question_id, sub_question_id, target_type, target_id, knowledge_type, knowledge_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
        bindingId,
        questionId,
        subQuestionId,
        upper(targetType),
        targetId,
        upper(request.knowledgeType()),
        request.knowledgeId(),
        now());
  }

  @Override
  public void publish(Long questionId) {
    jdbcTemplate.update("""
        UPDATE q_question
        SET status = 'PUBLISHED', updated_at = ?, content_version = content_version + 1
        WHERE id = ? AND deleted = 0
        """, now(), questionId);
  }

  @Override
  public Optional<QuestionSummaryDto> findQuestionSummary(Long questionId) {
    return optional("""
        SELECT id, qualification_id, subject_id, question_type, title, year_no, difficulty, status, content_version
        FROM q_question
        WHERE id = ? AND deleted = 0
        """, this::summary, questionId);
  }

  @Override
  public boolean subQuestionBelongsToQuestion(Long subQuestionId, Long questionId) {
    return count(
        "SELECT COUNT(*) FROM q_sub_question WHERE id = ? AND question_id = ? AND deleted = 0",
        subQuestionId,
        questionId) > 0;
  }

  @Override
  public List<QuestionCreateRequest.KnowledgeBindingRequest> findKnowledgeBindings(Long questionId) {
    return jdbcTemplate.query("""
            SELECT target_type, target_id, knowledge_type, knowledge_id
            FROM q_question_knowledge_binding
            WHERE question_id = ?
            ORDER BY id ASC
            """,
        (rs, rowNum) -> new QuestionCreateRequest.KnowledgeBindingRequest(
            rs.getString("target_type"),
            rs.getLong("target_id"),
            rs.getString("knowledge_type"),
            rs.getLong("knowledge_id"), null, null, null),
        questionId);
  }

  @Override
  public List<SubQuestionValidationRow> findSubQuestionValidationRows(Long questionId) {
    return jdbcTemplate.query("""
            SELECT sq.id,
                   sq.sub_question_type,
                   sq.grading_mode,
                   (SELECT COUNT(*) FROM q_choice_option co WHERE co.sub_question_id = sq.id AND co.deleted = 0) AS option_count,
                   (SELECT COUNT(*) FROM q_answer_key ak WHERE ak.sub_question_id = sq.id AND ak.deleted = 0 AND ak.current_flag = 1) AS answer_key_count
            FROM q_sub_question sq
            WHERE sq.question_id = ? AND sq.deleted = 0
            ORDER BY sq.sort_order ASC, sq.sub_question_no ASC
            """,
        (rs, rowNum) -> new SubQuestionValidationRow(
            rs.getLong("id"),
            rs.getString("sub_question_type"),
            rs.getString("grading_mode"),
            rs.getLong("option_count"),
            rs.getLong("answer_key_count")),
        questionId);
  }

  @Override
  public long countStems(Long questionId) {
    return count("SELECT COUNT(*) FROM q_question_stem WHERE question_id = ? AND deleted = 0", questionId);
  }

  private Optional<QuestionDetailDto> findDetail(Long questionId, boolean publishedOnly, boolean includeSolutions) {
    Optional<QuestionRow> question = publishedOnly
        ? optional("""
            SELECT q.id, q.qualification_id, q.subject_id, q.question_type,
                   qv.title, qv.status, qv.id AS question_version_id
            FROM q_question q
            JOIN q_question_version qv
              ON qv.id = q.current_published_version_id
             AND qv.question_id = q.id AND qv.status = 'PUBLISHED' AND qv.deleted = 0
            WHERE q.id = ? AND q.deleted = 0 AND q.status = 'PUBLISHED'
            """, this::question, questionId)
        : optional("""
            SELECT q.id, q.qualification_id, q.subject_id, q.question_type,
                   qv.title, qv.status, qv.id AS question_version_id
            FROM q_question q
            JOIN q_question_version qv
              ON qv.id = COALESCE(q.current_working_version_id, q.current_published_version_id)
             AND qv.question_id = q.id AND qv.deleted = 0
            WHERE q.id = ? AND q.deleted = 0
            """, this::question, questionId);
    if (question.isEmpty()) {
      return Optional.empty();
    }
    Long versionId = question.get().versionId();
    if (versionId == null) {
      return Optional.empty();
    }
    List<QuestionDetailDto.StemDto> stems = jdbcTemplate.query("""
            SELECT id, stem_type, content, attachment_url, sort_order
            FROM q_question_stem
            WHERE question_id = ? AND question_version_id = ? AND deleted = 0
            ORDER BY sort_order ASC, id ASC
            """,
        this::stem,
        questionId, versionId);
    List<SubQuestionRow> subQuestionRows = jdbcTemplate.query("""
            SELECT id, sub_question_no, sub_question_type, content, score, answer_format
            FROM q_sub_question
            WHERE question_id = ? AND question_version_id = ? AND deleted = 0
            ORDER BY sort_order ASC, sub_question_no ASC, id ASC
            """,
        this::subQuestionRow,
        questionId, versionId);
    List<Long> subQuestionIds = subQuestionRows.stream().map(SubQuestionRow::id).toList();
    Map<Long, List<QuestionDetailDto.OptionDto>> optionsBySubQuestion = optionsBySubQuestion(subQuestionIds);
    List<QuestionDetailDto.SubQuestionDto> subQuestions = subQuestionRows.stream()
        .map(row -> new QuestionDetailDto.SubQuestionDto(
            row.id(),
            row.subQuestionNo(),
            row.subQuestionType(),
            row.content(),
            row.score(),
            row.answerFormat(),
            optionsBySubQuestion.getOrDefault(row.id(), List.of())))
        .toList();
    List<QuestionDetailDto.OptionDto> options = optionsBySubQuestion.values().stream()
        .flatMap(List::stream)
        .toList();
    List<QuestionDetailDto.SolutionDto> solutions = includeSolutions
        ? jdbcTemplate.query("""
                SELECT id, sub_question_id, solution_type, content
                FROM q_solution
                WHERE question_id = ? AND question_version_id = ? AND deleted = 0
                ORDER BY id ASC
                """,
            this::solution,
            questionId, versionId)
        : null;
    List<QuestionDetailDto.KnowledgeBindingDto> bindings = jdbcTemplate.query("""
            SELECT id, target_type, target_id, knowledge_type, knowledge_id
            FROM q_question_knowledge_binding
            WHERE question_id = ? AND question_version_id = ? AND deleted = 0
            ORDER BY id ASC
            """,
        this::binding,
        questionId, versionId);
    QuestionRow row = question.get();
    return Optional.of(new QuestionDetailDto(
        row.id(),
        row.qualificationId(),
        row.subjectId(),
        row.questionType(),
        row.title(),
        stems,
        subQuestions,
        options,
        subQuestions.isEmpty() ? "JSON" : subQuestions.get(0).answerFormat(),
        solutions,
        bindings));
  }

  private Map<Long, List<QuestionDetailDto.OptionDto>> optionsBySubQuestion(List<Long> subQuestionIds) {
    if (subQuestionIds.isEmpty()) {
      return Map.of();
    }
    List<Object> args = new ArrayList<>(subQuestionIds);
    List<OptionRow> rows = jdbcTemplate.query("""
            SELECT id, sub_question_id, option_key, option_content, sort_order
            FROM q_choice_option
            WHERE deleted = 0 AND sub_question_id IN (%s)
            ORDER BY sort_order ASC, id ASC
            """.formatted(placeholders(subQuestionIds.size())),
        this::optionRow,
        args.toArray());
    Map<Long, List<QuestionDetailDto.OptionDto>> result = new LinkedHashMap<>();
    for (OptionRow row : rows) {
      result.computeIfAbsent(row.subQuestionId(), ignored -> new ArrayList<>())
          .add(new QuestionDetailDto.OptionDto(row.id(), row.optionKey(), row.optionContent(), row.sortOrder()));
    }
    return result;
  }

  private QuestionSummaryDto summary(ResultSet rs, int rowNum) throws SQLException {
    return new QuestionSummaryDto(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        rs.getLong("subject_id"),
        rs.getString("question_type"),
        rs.getString("title"),
        nullableInt(rs, "year_no"),
        rs.getString("difficulty"),
        rs.getString("status"),
        rs.getInt("content_version"));
  }

  private QuestionRow question(ResultSet rs, int rowNum) throws SQLException {
    return new QuestionRow(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        rs.getLong("subject_id"),
        rs.getString("question_type"),
        rs.getString("title"),
        rs.getString("status"),
        nullableLong(rs, "question_version_id"));
  }

  private QuestionDetailDto.StemDto stem(ResultSet rs, int rowNum) throws SQLException {
    return new QuestionDetailDto.StemDto(
        rs.getLong("id"),
        rs.getString("stem_type"),
        rs.getString("content"),
        rs.getString("attachment_url"),
        rs.getInt("sort_order"));
  }

  private SubQuestionRow subQuestionRow(ResultSet rs, int rowNum) throws SQLException {
    return new SubQuestionRow(
        rs.getLong("id"),
        rs.getInt("sub_question_no"),
        rs.getString("sub_question_type"),
        rs.getString("content"),
        rs.getBigDecimal("score"),
        rs.getString("answer_format"));
  }

  private OptionRow optionRow(ResultSet rs, int rowNum) throws SQLException {
    return new OptionRow(
        rs.getLong("id"),
        rs.getLong("sub_question_id"),
        rs.getString("option_key"),
        rs.getString("option_content"),
        rs.getInt("sort_order"));
  }

  private QuestionDetailDto.SolutionDto solution(ResultSet rs, int rowNum) throws SQLException {
    return new QuestionDetailDto.SolutionDto(
        rs.getLong("id"),
        nullableLong(rs, "sub_question_id"),
        rs.getString("solution_type"),
        rs.getString("content"));
  }

  private QuestionExplanationDto.SolutionDto explanationSolution(ResultSet rs, int rowNum) throws SQLException {
    return new QuestionExplanationDto.SolutionDto(
        rs.getLong("id"),
        rs.getLong("question_id"),
        nullableLong(rs, "sub_question_id"),
        rs.getString("solution_type"),
        rs.getString("content"));
  }

  private QuestionDetailDto.KnowledgeBindingDto binding(ResultSet rs, int rowNum) throws SQLException {
    return new QuestionDetailDto.KnowledgeBindingDto(
        rs.getLong("id"),
        rs.getString("target_type"),
        rs.getLong("target_id"),
        rs.getString("knowledge_type"),
        rs.getLong("knowledge_id"));
  }

  private long count(String sql, Object... args) {
    Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
    return count == null ? 0 : count;
  }

  private <T> Optional<T> optional(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(sql, mapper, args));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  private long offset(long pageNo, long pageSize) {
    return (pageNo - 1) * pageSize;
  }

  private int sortOrder(Integer sortOrder) {
    return sortOrder == null ? 0 : sortOrder;
  }

  private Integer nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private String upper(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private boolean isStandardSolution(String value) {
    return "STANDARD".equals(value) || "REFERENCE_ANSWER".equals(value) || "ANALYSIS".equals(value);
  }

  private String placeholders(int size) {
    return String.join(",", java.util.Collections.nCopies(size, "?"));
  }

  private Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  private record QuestionRow(Long id, Long qualificationId, Long subjectId, String questionType,
                             String title, String status, Long versionId) {
  }

  private record SubQuestionRow(
      Long id,
      int subQuestionNo,
      String subQuestionType,
      String content,
      BigDecimal score,
      String answerFormat
  ) {
  }

  private record OptionRow(Long id, Long subQuestionId, String optionKey, String optionContent, int sortOrder) {
  }
}
