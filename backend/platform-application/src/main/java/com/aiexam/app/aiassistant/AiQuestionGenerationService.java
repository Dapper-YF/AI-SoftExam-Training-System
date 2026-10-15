package com.aiexam.app.aiassistant;

import static com.aiexam.app.aiassistant.AiQuestionGenerationDtos.*;

import com.aiexam.common.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiQuestionGenerationService {
  private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
  private static final Set<String> MODES = Set.of("KNOWLEDGE", "WEAKNESS", "ADAPTIVE");
  private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
  private static final Set<String> KNOWLEDGE_TYPES = Set.of("SYLLABUS", "CHAPTER", "TAG");
  private static final Set<String> FEEDBACK_CODES = Set.of("HELPFUL", "INCORRECT", "AMBIGUOUS", "TOO_EASY", "TOO_HARD");
  private static final Set<String> REVIEW_STATUSES = Set.of("APPROVED", "REJECTED", "OFFLINE");

  private final JdbcTemplate jdbc;
  private final DashscopeClient dashscope;
  private final ObjectMapper json;

  public AiQuestionGenerationService(JdbcTemplate jdbc, DashscopeClient dashscope, ObjectMapper json) {
    this.jdbc = jdbc;
    this.dashscope = dashscope;
    this.json = json;
  }

  @Transactional
  public GeneratedQuestionDto generate(long userId, GenerateRequest request) {
    String mode = requiredEnum(request.generationMode(), MODES, "generationMode");
    String difficulty = "ADAPTIVE".equals(mode)
        ? adaptiveDifficulty(userId, request.qualificationId(), request.subjectId(), request.knowledgeType(), request.knowledgeId()).difficulty()
        : requiredEnum(request.difficulty(), DIFFICULTIES, "difficulty");
    String knowledgeType = requiredEnum(request.knowledgeType(), KNOWLEDGE_TYPES, "knowledgeType");
    WeakPointDto fallback = "WEAKNESS".equals(mode) && trim(request.knowledgeName()) == null
        ? weakest(userId, request.qualificationId(), request.subjectId()) : null;
    String knowledgeName = fallback == null ? required(request.knowledgeName(), "知识点不能为空") : fallback.knowledgeName();
    Long knowledgeId = fallback == null ? request.knowledgeId() : fallback.knowledgeId();
    knowledgeType = fallback == null ? knowledgeType : fallback.knowledgeType();
    Scope scope = scope(request.qualificationId(), request.subjectId());
    String raw = dashscope.answer(prompt(scope, knowledgeName, mode, difficulty, request.weakPointSummary(), request.targetAccuracy()), "");
    if (raw == null || raw.isBlank()) {
      throw new BusinessException("AI_QUESTION_GENERATION_UNAVAILABLE", "智能出题服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE);
    }
    QuestionContent question = parse(raw);
    Instant now = Instant.now();
    long id = nextId();
    String questionJson = write(question);
    jdbc.update("""
        INSERT INTO ai_generated_question
          (id,created_by,qualification_id,subject_id,knowledge_type,knowledge_id,knowledge_name,generation_mode,difficulty,
           weak_point_summary,target_accuracy,prompt_version,model_code,question_json,content_hash,status,created_at,updated_at,deleted)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,0)
        """, id, userId, scope.qualificationId(), scope.subjectId(), knowledgeType, knowledgeId, knowledgeName,
        mode, difficulty, trim(request.weakPointSummary()), request.targetAccuracy(), "v1", "dashscope", questionJson,
        sha256(questionJson), Timestamp.from(now), Timestamp.from(now));
    return dto(id, scope, knowledgeType, knowledgeId, knowledgeName, mode, difficulty, question, "DRAFT", now);
  }

  public List<WeakPointDto> weakPoints(long userId, Long qualificationId, Long subjectId, int limit) {
    int pageSize = Math.max(1, Math.min(limit, 20));
    return jdbc.query("""
        SELECT CASE
                 WHEN kb.knowledge_type IN ('SYLLABUS', 'SYLLABUS_NODE') THEN 'SYLLABUS'
                 WHEN kb.knowledge_type IN ('CHAPTER', 'CHAPTER_NODE') THEN 'CHAPTER'
                 ELSE kb.knowledge_type
               END AS knowledge_type,
               kb.knowledge_id,
               COALESCE(s.node_name,c.chapter_name,t.tag_name,CONCAT(kb.knowledge_type, ':', kb.knowledge_id)) AS knowledge_name,
               SUM(pm.mistake_count) AS mistake_count
        FROM practice_mistake pm
        JOIN q_question q ON q.id=pm.question_id AND q.deleted=0
        JOIN q_question_knowledge_binding kb ON kb.question_id=q.id AND kb.deleted=0
        LEFT JOIN kp_syllabus_node s ON kb.knowledge_type IN ('SYLLABUS', 'SYLLABUS_NODE') AND s.id=kb.knowledge_id AND s.deleted=0
        LEFT JOIN kp_chapter_node c ON kb.knowledge_type IN ('CHAPTER', 'CHAPTER_NODE') AND c.id=kb.knowledge_id AND c.deleted=0
        LEFT JOIN kp_tag t ON kb.knowledge_type='TAG' AND t.id=kb.knowledge_id AND t.deleted=0
        WHERE pm.user_id=? AND pm.status='ACTIVE' AND pm.deleted=0 AND q.qualification_id=? AND q.subject_id=?
        GROUP BY kb.knowledge_type,kb.knowledge_id,s.node_name,c.chapter_name,t.tag_name
        ORDER BY mistake_count DESC,kb.knowledge_type,kb.knowledge_id LIMIT ?
        """, (rs, row) -> new WeakPointDto(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4)),
        userId, qualificationId, subjectId, pageSize);
  }

  public AdaptiveDifficultyDto adaptiveDifficulty(long userId, Long qualificationId, Long subjectId, String knowledgeType, Long knowledgeId) {
    if (knowledgeType == null || knowledgeId == null) return new AdaptiveDifficultyDto("MEDIUM", 0);
    Long mistakes = jdbc.queryForObject("""
        SELECT COALESCE(SUM(pm.mistake_count),0)
        FROM practice_mistake pm JOIN q_question_knowledge_binding kb ON kb.question_id=pm.question_id AND kb.deleted=0
        WHERE pm.user_id=? AND pm.status='ACTIVE' AND pm.deleted=0 AND kb.knowledge_type=? AND kb.knowledge_id=?
        """, Long.class, userId, normalize(knowledgeType), knowledgeId);
    long count = mistakes == null ? 0 : mistakes;
    return new AdaptiveDifficultyDto(count >= 5 ? "EASY" : count >= 2 ? "MEDIUM" : "HARD", count);
  }

  @Transactional
  public void feedback(long userId, long questionId, FeedbackRequest request) {
    requireVisible(questionId);
    String code = requiredEnum(request.feedbackCode(), FEEDBACK_CODES, "feedbackCode");
    jdbc.update("""
        INSERT INTO ai_generated_question_feedback (id,generated_question_id,user_id,feedback_code,comment,created_at)
        VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE feedback_code=VALUES(feedback_code),comment=VALUES(comment),created_at=VALUES(created_at)
        """, nextId(), questionId, userId, code, trim(request.comment()), Timestamp.from(Instant.now()));
  }

  public List<GeneratedQuestionDto> reusable(Long qualificationId, Long subjectId, int limit) {
    int pageSize = Math.max(1, Math.min(limit, 50));
    return jdbc.query("""
        SELECT id,qualification_id,subject_id,knowledge_type,knowledge_id,knowledge_name,generation_mode,difficulty,
               question_json,status,created_at
        FROM ai_generated_question WHERE qualification_id=? AND subject_id=? AND status='APPROVED' AND deleted=0
        ORDER BY updated_at DESC LIMIT ?
        """, (rs, row) -> dto(rs.getLong(1), new Scope(rs.getLong(2), rs.getLong(3)), rs.getString(4), nullableLong(rs, 5),
        rs.getString(6), rs.getString(7), rs.getString(8), read(rs.getString(9)), rs.getString(10), rs.getTimestamp(11).toInstant()),
        qualificationId, subjectId, pageSize);
  }

  public List<GeneratedQuestionDto> adminList(int limit) {
    return jdbc.query("""
        SELECT id,qualification_id,subject_id,knowledge_type,knowledge_id,knowledge_name,generation_mode,difficulty,
               question_json,status,created_at FROM ai_generated_question WHERE deleted=0 ORDER BY updated_at DESC LIMIT ?
        """, (rs, row) -> dto(rs.getLong(1), new Scope(rs.getLong(2), rs.getLong(3)), rs.getString(4), nullableLong(rs, 5),
        rs.getString(6), rs.getString(7), rs.getString(8), read(rs.getString(9)), rs.getString(10), rs.getTimestamp(11).toInstant()),
        Math.max(1, Math.min(limit, 100)));
  }

  @Transactional
  public GeneratedQuestionDto review(long adminId, long id, ReviewRequest request) {
    String status = requiredEnum(request.status(), REVIEW_STATUSES, "status");
    GeneratedQuestionDto current = find(id);
    QuestionContent content = request.question() == null ? current.question() : validate(request.question());
    Instant now = Instant.now();
    jdbc.update("""
        UPDATE ai_generated_question SET question_json=?,content_hash=?,status=?,reviewed_by=?,reviewed_at=?,review_note=?,updated_at=?
        WHERE id=? AND deleted=0
        """, write(content), sha256(write(content)), status, adminId, Timestamp.from(now), trim(request.reviewNote()), Timestamp.from(now), id);
    return dto(id, new Scope(current.qualificationId(), current.subjectId()), current.knowledgeType(), current.knowledgeId(),
        current.knowledgeName(), current.generationMode(), current.difficulty(), content, status, current.createdAt());
  }

  private GeneratedQuestionDto find(long id) {
    try {
      return jdbc.queryForObject("""
          SELECT id,qualification_id,subject_id,knowledge_type,knowledge_id,knowledge_name,generation_mode,difficulty,
                 question_json,status,created_at FROM ai_generated_question WHERE id=? AND deleted=0
          """, (rs, row) -> dto(rs.getLong(1), new Scope(rs.getLong(2), rs.getLong(3)), rs.getString(4), nullableLong(rs, 5),
          rs.getString(6), rs.getString(7), rs.getString(8), read(rs.getString(9)), rs.getString(10), rs.getTimestamp(11).toInstant()), id);
    } catch (EmptyResultDataAccessException exception) { throw notFound(); }
  }

  private void requireVisible(long id) { find(id); }
  private WeakPointDto weakest(long userId, Long qualificationId, Long subjectId) {
    return weakPoints(userId, qualificationId, subjectId, 1).stream().findFirst()
        .orElseThrow(() -> bad("暂无可用于专项练习的薄弱知识点"));
  }
  private Scope scope(Long qualificationId, Long subjectId) {
    if (qualificationId == null || subjectId == null) throw bad("资格和科目不能为空");
    try {
      return jdbc.queryForObject("SELECT qualification_id,id FROM exam_subject WHERE qualification_id=? AND id=? AND status='ENABLED' AND deleted=0",
          (rs, row) -> new Scope(rs.getLong(1), rs.getLong(2)), qualificationId, subjectId);
    } catch (EmptyResultDataAccessException exception) { throw bad("资格或科目不可用"); }
  }
  private String prompt(Scope scope, String knowledge, String mode, String difficulty, String weakness, Double target) {
    return "你是软考命题老师。为资格ID=" + scope.qualificationId() + "、科目ID=" + scope.subjectId()
        + "围绕知识点“" + knowledge + "”生成一道" + difficulty + "难度的四选一选择题。"
        + "模式=" + mode + (weakness == null ? "" : "；薄弱点=" + weakness) + (target == null ? "" : "；目标正确率=" + target)
        + "。错误选项应使用考试中容易混淆的相近概念，但必须只有一个正确答案。"
        + "只输出 JSON：{\"stem\":\"\",\"options\":[{\"key\":\"A\",\"content\":\"\"},{\"key\":\"B\",\"content\":\"\"},{\"key\":\"C\",\"content\":\"\"},{\"key\":\"D\",\"content\":\"\"}],\"answer\":\"A\",\"analysis\":\"\"}";
  }
  private QuestionContent parse(String raw) {
    String text = raw.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    try { return validate(json.readValue(text, QuestionContent.class)); }
    catch (Exception exception) { throw new BusinessException("AI_QUESTION_GENERATION_FORMAT_INVALID", "智能出题结果格式无效", HttpStatus.BAD_GATEWAY); }
  }
  private QuestionContent validate(QuestionContent question) {
    if (question == null || required(question.stem(), "题干不能为空").length() > 2000 || question.options() == null || question.options().size() != 4) throw bad("生成题目不完整");
    List<Option> options = question.options();
    for (int i = 0; i < 4; i++) if (!String.valueOf((char) ('A' + i)).equals(normalize(options.get(i).key())) || required(options.get(i).content(), "选项不能为空").length() > 1000) throw bad("选项格式无效");
    String answer = requiredEnum(question.answer(), Set.of("A", "B", "C", "D"), "answer");
    return new QuestionContent(question.stem().trim(), options.stream().map(item -> new Option(normalize(item.key()), item.content().trim())).toList(), answer, required(question.analysis(), "解析不能为空"));
  }
  private GeneratedQuestionDto dto(long id, Scope scope, String knowledgeType, Long knowledgeId, String knowledgeName, String mode, String difficulty, QuestionContent question, String status, Instant createdAt) {
    return new GeneratedQuestionDto(id, scope.qualificationId(), scope.subjectId(), knowledgeType, knowledgeId, knowledgeName, mode, difficulty, question, status, createdAt);
  }
  private QuestionContent read(String value) { try { return json.readValue(value, QuestionContent.class); } catch (Exception exception) { throw new IllegalStateException("stored question invalid", exception); } }
  private String write(QuestionContent value) { try { return json.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); } }
  private static String required(String value, String message) { if (value == null || value.trim().isEmpty()) throw bad(message); return value.trim(); }
  private static String requiredEnum(String value, Set<String> allowed, String name) { String normalized = normalize(value); if (!allowed.contains(normalized)) throw bad(name + "无效"); return normalized; }
  private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
  private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private static Long nullableLong(java.sql.ResultSet rs, int column) throws java.sql.SQLException { long value = rs.getLong(column); return rs.wasNull() ? null : value; }
  private static long nextId() { return IDS.incrementAndGet(); }
  private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
  private static BusinessException bad(String message) { return new BusinessException("AI_QUESTION_GENERATION_VALIDATION", message, HttpStatus.BAD_REQUEST); }
  private static BusinessException notFound() { return new BusinessException("AI_GENERATED_QUESTION_NOT_FOUND", "智能题目不存在", HttpStatus.NOT_FOUND); }
  private record Scope(long qualificationId, long subjectId) { }
}
