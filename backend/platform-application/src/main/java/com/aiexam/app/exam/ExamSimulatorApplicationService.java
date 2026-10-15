package com.aiexam.app.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.foundation.profile.repository.UserProfileRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamSimulatorApplicationService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final CurrentUserProvider currentUserProvider;
  private final UserProfileRepository userProfileRepository;

  public ExamSimulatorApplicationService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this(jdbc, objectMapper, null, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ExamSimulatorApplicationService(JdbcTemplate jdbc, ObjectMapper objectMapper, CurrentUserProvider currentUserProvider, UserProfileRepository userProfileRepository) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.currentUserProvider = currentUserProvider;
    this.userProfileRepository = userProfileRepository;
  }

  @Transactional
  public LoginResult login(LoginCommand command) {
    var qualification = jdbc.queryForMap("""
        SELECT id, qualification_code, qualification_name, level_code
        FROM exam_qualification
        WHERE qualification_code = ? AND status = 'ENABLED' AND deleted = 0
        """, command.qualificationCode());
    validatePlatformQualification(((Number) qualification.get("id")).longValue());
    String year = command.ruleVersion() == null || command.ruleVersion().isBlank()
        ? String.valueOf(Instant.now().atZone(java.time.ZoneOffset.UTC).getYear())
        : command.ruleVersion();
    var candidate = jdbc.queryForMap("""
        SELECT c.id candidate_id, c.entry_id, c.entry_code, c.admission_ticket_masked,
               c.identity_masked, c.candidate_name, c.gender, c.avatar_url, c.seat_no,
               e.batch_id, e.exam_rule_id, e.exam_rule_version, e.config_version entry_config_version,
               e.exam_mode, e.total_duration_seconds, e.title, e.description,
               b.batch_code, b.config_version batch_config_version, b.scheduled_start_at,
               b.qualification_id
        FROM practice_exam_candidate c
        JOIN practice_exam_entry e ON e.id = c.entry_id AND e.status = 'PUBLISHED'
        JOIN practice_exam_batch b ON b.id = e.batch_id AND b.status = 'PUBLISHED'
        WHERE c.entry_code = ? AND c.qualification_id = ?
          AND c.admission_ticket_lookup_hmac = ? AND c.identity_lookup_hmac = ?
          AND c.credential_status = 'ACTIVE'
        ORDER BY b.exam_year DESC, c.id DESC
        LIMIT 1
        """, command.entryCode(), qualification.get("id"),
        credentialHmac("ADMISSION", command.admissionTicket()), credentialHmac("IDENTITY", command.identityNumber()));
    if (!qualification.get("id").equals(candidate.get("qualification_id"))) {
      throw new ExamSimulatorException("EXAM_QUALIFICATION_MISMATCH", "资格与考试入口不匹配");
    }

    Instant now = Instant.now();
    String sessionNo = UUID.randomUUID().toString();
    String token = UUID.randomUUID() + UUID.randomUUID().toString();
    String tokenHash = sha256(token);
    long sessionId = reserveIds("practice_exam_session", 1);
    jdbc.update("""
        INSERT INTO practice_exam_session (
          id, session_no, candidate_id, batch_id, entry_id, qualification_id,
          exam_batch_code, entry_code, exam_mode, batch_config_version, entry_config_version,
          state, active_subject_order, scheduled_start_at, linked_deadline_at,
          token_hash, token_key_version, token_version, token_issued_at, token_expires_at,
          last_client_login_id, last_login_at, version, next_event_sequence, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CANDIDATE_LOGGED_IN', 1, ?, ?, ?, 'SESSION_V1', 1, ?, ?, ?, ?, 0, 1, ?, ?)
        """, sessionId, sessionNo, candidate.get("candidate_id"), candidate.get("batch_id"),
        candidate.get("entry_id"), qualification.get("id"), candidate.get("batch_code"),
        command.entryCode(), candidate.get("exam_mode"), candidate.get("batch_config_version"),
        candidate.get("entry_config_version"), candidate.get("scheduled_start_at"),
        Timestamp.from(now.plusSeconds(((Number) candidate.get("total_duration_seconds")).longValue())),
        hexBytes(tokenHash), Timestamp.from(now), Timestamp.from(now.plusSeconds(8 * 3600)),
        command.clientLoginId(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));

    String ruleJson = jdbc.queryForObject("SELECT rule_json FROM exam_rule WHERE id = ?", String.class, candidate.get("exam_rule_id"));
    String documentVersion = candidate.get("exam_rule_version") + "-" + year;
    String snapshotJson = objectMapper.createObjectNode()
        .put("qualificationCode", command.qualificationCode())
        .put("qualificationLevel", String.valueOf(qualification.get("level_code")))
        .put("ruleVersion", documentVersion)
        .put("sourceRuleJson", ruleJson == null ? "{}" : ruleJson)
        .toString();
    jdbc.update("""
        INSERT INTO practice_exam_rule_snapshot
          (id, session_id, source_exam_rule_id, source_entry_id, entry_config_version,
           exam_rule_version, document_version, snapshot_json, snapshot_hash, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, reserveIds("practice_exam_rule_snapshot", 1), sessionId, candidate.get("exam_rule_id"),
        candidate.get("entry_id"), candidate.get("entry_config_version"), candidate.get("exam_rule_version"),
        documentVersion, snapshotJson, hexBytes(sha256(snapshotJson)), Timestamp.from(now));

    var subjects = jdbc.queryForList("""
        SELECT subject_id, subject_code, paper_id, paper_content_version, subject_order,
               min_submit_offset_seconds, force_submit_mode, force_submit_offset_seconds,
               allow_manual_submit
        FROM practice_exam_entry_subject WHERE entry_id = ? ORDER BY subject_order
        """, candidate.get("entry_id"));
    if (subjects.isEmpty()) {
      throw new ExamSimulatorException("EXAM_ENTRY_SUBJECT_NOT_CONFIGURED", "考试入口尚未绑定科目和试卷");
    }
    for (var subject : subjects) {
      requireCompleteRealPaper(subject);
    }
    long subjectId = reserveIds("practice_exam_session_subject", subjects.size());
    for (var subject : subjects) {
      jdbc.update("""
          INSERT INTO practice_exam_session_subject
            (id, session_id, subject_id, subject_code, paper_id, paper_content_version,
             paper_snapshot_hash, subject_order, state, scheduled_start_at, linked_deadline_at,
             min_submit_offset_seconds, force_submit_mode, force_submit_offset_seconds,
             allow_manual_submit, subject_min_submit_at, subject_force_submit_at, version, created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, UNHEX(SHA2(CONCAT(?, ':', ?), 256)), ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
          """, subjectId++, sessionId, subject.get("subject_id"), subject.get("subject_code"),
          subject.get("paper_id"), subject.get("paper_content_version"), subject.get("paper_id"),
          subject.get("paper_content_version"), subject.get("subject_order"), Timestamp.from(now),
          candidate.get("scheduled_start_at"), subject.get("min_submit_offset_seconds"),
          subject.get("force_submit_mode"), subject.get("force_submit_offset_seconds"),
          subject.get("allow_manual_submit"), Timestamp.from(now),
          Timestamp.from(now.plusSeconds(((Number) candidate.get("total_duration_seconds")).longValue())),
          Timestamp.from(now), Timestamp.from(now));
    }

    return new LoginResult(sessionNo, token, "CANDIDATE_LOGGED_IN", command.entryCode(), command.qualificationCode());
  }

  private void validatePlatformQualification(Long qualificationId) {
    if (currentUserProvider == null || userProfileRepository == null) return;
    currentUserProvider.currentPrincipal().ifPresent(principal -> {
      boolean privileged = principal.permissions().contains("admin:home:view") || principal.permissions().contains("question:admin");
      if (privileged) return;
      Long bound = userProfileRepository.findByUserId(principal.userId()).map(profile -> profile.preparationQualificationId()).orElse(null);
      if (bound == null) throw new ExamSimulatorException("PREPARATION_PROFILE_REQUIRED", "请先完成备考资格和考区选择");
      if (!bound.equals(qualificationId)) throw new ExamSimulatorException("QUALIFICATION_NOT_BOUND", "当前账号未选择该考试资格");
    });
  }

  /** Machine-exam entries must never silently fall back to mock or partial question sets. */
  private void requireCompleteRealPaper(Map<String, Object> subject) {
    Long paperId = ((Number) subject.get("paper_id")).longValue();
    Long contentVersion = ((Number) subject.get("paper_content_version")).longValue();
    Long complete = jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM q_paper p
        WHERE p.id = ? AND p.deleted = 0 AND p.status = 'PUBLISHED'
          AND p.paper_type = 'REAL' AND p.content_version = ?
          AND EXISTS (
            SELECT 1 FROM q_paper_question pq
            JOIN q_question q ON q.id = pq.question_id AND q.deleted = 0 AND q.status = 'PUBLISHED'
            WHERE pq.paper_id = p.id AND pq.deleted = 0
          )
        """, Long.class, paperId, contentVersion);
    if (complete == null || complete == 0) {
      throw new ExamSimulatorException("EXAM_REAL_PAPER_REQUIRED",
          "机考入口必须绑定已发布、版本匹配且题目完整的真题试卷");
    }
  }

  public Map<String, Object> candidate(String sessionNo) {
    return jdbc.queryForMap("""
        SELECT c.admission_ticket_masked maskedAdmissionTicket, c.candidate_name name,
               COALESCE(c.gender, '') gender, c.seat_no stationNo,
               q.qualification_name qualification, e.title subject,
               c.identity_masked maskedIdentityNumber, c.avatar_url avatarUrl
        FROM practice_exam_session s
        JOIN practice_exam_candidate c ON c.id = s.candidate_id
        JOIN exam_qualification q ON q.id = s.qualification_id
        JOIN practice_exam_entry e ON e.id = s.entry_id
        WHERE s.session_no = ?
        """, sessionNo);
  }

  public void authorize(String sessionNo, String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new ExamSimulatorException("EXAM_TOKEN_REQUIRED", "缺少考试会话令牌");
    }
    String token = authorization.substring("Bearer ".length()).trim();
    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM practice_exam_session WHERE session_no = ? AND token_hash = ? AND token_revoked_at IS NULL AND token_expires_at > ?",
        Long.class, sessionNo, hexBytes(sha256(token)), Timestamp.from(Instant.now()));
    if (count == null || count != 1) throw new ExamSimulatorException("EXAM_TOKEN_INVALID", "考试会话令牌无效或已过期");
  }

  public Map<String, Object> state(String sessionNo) {
    Map<String, Object> result = jdbc.queryForMap("""
        SELECT s.session_no sessionId, s.state, s.scheduled_start_at scheduledStartAt,
               s.actual_start_at actualStartAt, s.linked_deadline_at deadlineAt,
               COALESCE(s.active_subject_order, 1) subjectOrder,
               es.subject_code subjectCode, es.subject_order, es.state subjectState,
               e.entry_code entryCode, s.entry_code sessionEntryCode,
               CASE WHEN es.subject_code = 'ESSAY' THEN 'ESSAY'
                    WHEN q.level_code = 'INTERMEDIATE' THEN 'MIDDLE_UNION'
                    ELSE 'SENIOR_UNION' END workspaceType,
               TIMESTAMPDIFF(SECOND, s.scheduled_start_at, s.linked_deadline_at) durationSeconds
        FROM practice_exam_session s
        JOIN exam_qualification q ON q.id = s.qualification_id
        JOIN practice_exam_entry e ON e.id = s.entry_id
        LEFT JOIN practice_exam_session_subject es
          ON es.session_id = s.id AND es.subject_order = COALESCE(s.active_subject_order, 1)
        WHERE s.session_no = ?
        """, sessionNo);
    result.put("serverNow", Instant.now().toString());
    result.put("paperLoaded", "IN_PROGRESS".equals(result.get("state")));
    Long subjectCount = jdbc.queryForObject("SELECT COUNT(*) FROM practice_exam_session_subject es JOIN practice_exam_session s ON s.id = es.session_id WHERE s.session_no = ?", Long.class, sessionNo);
    result.put("subjectCount", subjectCount == null ? 0 : subjectCount.intValue());
    int order = ((Number) result.get("subjectOrder")).intValue();
    result.put("canContinue", "SUBJECT_TRANSITION".equals(result.get("state")) && subjectCount != null && order < subjectCount);
    return result;
  }

  public Map<String, Object> rules(String sessionNo) {
    Map<String, Object> row = jdbc.queryForMap("""
        SELECT rs.document_version, rs.snapshot_json, b.region_name, b.exam_year
        FROM practice_exam_rule_snapshot rs
        JOIN practice_exam_session s ON s.id = rs.session_id
        JOIN practice_exam_batch b ON b.id = s.batch_id
        WHERE s.session_no = ?
        """, sessionNo);
    return Map.of("version", row.get("document_version"), "region", row.get("region_name"), "examYear", row.get("exam_year"),
        "requireScrollToEnd", false,
        "tabs", java.util.List.of(Map.of("code", "RULES", "title", "考场规则", "paragraphs", java.util.List.of("考试时间以服务器时间为准。")),
            Map.of("code", "GUIDE", "title", "操作指南", "paragraphs", java.util.List.of("答案会自动保存，交卷后不可修改。"))));
  }

  public Map<String, Object> paper(String sessionNo) {
    var paper = jdbc.queryForMap("""
        SELECT p.id paperId, p.paper_name title, q.qualification_name qualification,
               sub.subject_name subject, p.duration_minutes durationMinutes,
               es.subject_code subjectCode,
               CASE WHEN es.subject_code = 'ESSAY' THEN 'ESSAY'
                    WHEN q.level_code = 'INTERMEDIATE' THEN 'MIDDLE_UNION'
                    ELSE 'SENIOR_UNION' END workspaceType
        FROM practice_exam_session s
        JOIN exam_qualification q ON q.id = s.qualification_id
        JOIN practice_exam_session_subject es ON es.session_id = s.id
          AND es.subject_order = COALESCE(s.active_subject_order, 1)
        JOIN exam_subject sub ON sub.id = es.subject_id
        JOIN q_paper p ON p.id = es.paper_id AND p.content_version = es.paper_content_version
        WHERE s.session_no = ?
        """, sessionNo);
    var questions = jdbc.queryForList("""
        SELECT pq.question_id questionId, sq.id subQuestionId, pq.question_no displayNo,
               CASE WHEN sq.answer_format = 'OPTION' THEN 'SINGLE_CHOICE' ELSE 'ESSAY_WRITING' END questionType,
               CASE WHEN q.question_type = 'CASE' THEN COALESCE(q.title, '') ELSE '' END groupName,
               sq.content prompt, COALESCE(sq.score, pq.score, 1) score
        FROM practice_exam_session s
        JOIN practice_exam_session_subject es ON es.session_id = s.id
          AND es.subject_order = COALESCE(s.active_subject_order, 1)
        JOIN q_paper_question pq ON pq.paper_id = es.paper_id AND pq.deleted = 0
        JOIN q_question q ON q.id = pq.question_id AND q.deleted = 0 AND q.status = 'PUBLISHED'
        JOIN q_sub_question sq ON sq.question_id = q.id
          AND sq.question_version_id = q.current_published_version_id AND sq.deleted = 0
        WHERE s.session_no = ?
        ORDER BY pq.question_no, sq.sort_order, sq.id
        """, sessionNo);
    for (var question : questions) {
      question.put("options", optionsFor(((Number) question.get("subQuestionId")).longValue()));
    }
    paper.put("questions", questions);
    paper.put("instructions", java.util.List.of("考试过程中不显示答案和解析"));
    return paper;
  }

  private List<Map<String, Object>> optionsFor(Long subQuestionId) {
    return jdbc.queryForList("""
        SELECT option_key `key`, option_content content
        FROM q_choice_option
        WHERE sub_question_id = ? AND deleted = 0
        ORDER BY sort_order, id
        """, subQuestionId);
  }

  @Transactional
  public Map<String, Object> confirmCandidate(String sessionNo) {
    return transition(sessionNo, "CANDIDATE_LOGGED_IN", "RULE_READING", "CANDIDATE_CONFIRMED", null);
  }

  @Transactional
  public Map<String, Object> acknowledgeRules(String sessionNo) {
    return transition(sessionNo, "RULE_READING", "WAITING_START", "RULES_ACKNOWLEDGED", null);
  }

  @Transactional
  public Map<String, Object> enter(String sessionNo) {
    Map<String, Object> session = lockedSession(sessionNo);
    String state = String.valueOf(session.get("state"));
    if (!"WAITING_START".equals(state) && !"IN_PROGRESS".equals(state)) {
      throw new ExamSimulatorException("EXAM_STATE_CONFLICT", "当前会话不能进入考试");
    }
    Instant now = Instant.now();
    jdbc.update("UPDATE practice_exam_session SET state = 'IN_PROGRESS', actual_start_at = COALESCE(actual_start_at, ?), updated_at = ? WHERE id = ?",
        Timestamp.from(now), Timestamp.from(now), session.get("id"));
    jdbc.update("UPDATE practice_exam_session_subject SET state = 'IN_PROGRESS', actual_start_at = COALESCE(actual_start_at, ?), updated_at = ? WHERE session_id = ? AND subject_order = ?",
        Timestamp.from(now), Timestamp.from(now), session.get("id"), session.get("active_subject_order"));
    event(session, "PAPER_ENTERED", null, now);
    return state(sessionNo);
  }

  @Transactional
  public Map<String, Object> submit(String sessionNo, String mode) {
    Map<String, Object> session = lockedSession(sessionNo);
    if (!"IN_PROGRESS".equals(session.get("state"))) {
      throw new ExamSimulatorException("EXAM_STATE_CONFLICT", "当前会话不能交卷");
    }
    Map<String, Object> subject = jdbc.queryForMap("SELECT * FROM practice_exam_session_subject WHERE session_id = ? AND subject_order = ? FOR UPDATE",
        session.get("id"), session.get("active_subject_order"));
    Instant now = Instant.now();
    boolean hasNext = jdbc.queryForObject("SELECT COUNT(*) FROM practice_exam_session_subject WHERE session_id = ? AND subject_order > ?", Long.class,
        session.get("id"), session.get("active_subject_order")) > 0;
    jdbc.update("UPDATE practice_exam_session_subject SET state = 'SUBMITTED', submitted_at = ?, submission_mode = ?, answers_locked_at = ?, updated_at = ? WHERE id = ?",
        Timestamp.from(now), mode == null ? "MANUAL" : mode, Timestamp.from(now), Timestamp.from(now), subject.get("id"));
    collectObjectiveMistakes(session, subject);
    jdbc.update("UPDATE practice_exam_session SET state = ?, completed_at = CASE WHEN ? THEN NULL ELSE ? END, updated_at = ? WHERE id = ?",
        hasNext ? "SUBJECT_TRANSITION" : "COMPLETED", hasNext, Timestamp.from(now), Timestamp.from(now), session.get("id"));
    event(session, hasNext ? "SUBJECT_SUBMITTED" : "EXAM_COMPLETED", ((Number) subject.get("id")).longValue(), now);
    return Map.of("state", hasNext ? "SUBMITTED" : "SUBMITTED", "submittedAt", now.toString(), "canContinue", hasNext);
  }

  @Transactional
  public Map<String, Object> continueNextSubject(String sessionNo) {
    Map<String, Object> session = lockedSession(sessionNo);
    if (!"SUBJECT_TRANSITION".equals(session.get("state"))) {
      throw new ExamSimulatorException("EXAM_STATE_CONFLICT", "当前会话没有可进入的下一科目");
    }
    int currentOrder = ((Number) session.get("active_subject_order")).intValue();
    Map<String, Object> next = jdbc.queryForMap("SELECT * FROM practice_exam_session_subject WHERE session_id = ? AND subject_order = ? FOR UPDATE",
        session.get("id"), currentOrder + 1);
    Instant now = Instant.now();
    jdbc.update("UPDATE practice_exam_session_subject SET state = 'IN_PROGRESS', actual_start_at = ?, updated_at = ? WHERE id = ?",
        Timestamp.from(now), Timestamp.from(now), next.get("id"));
    jdbc.update("UPDATE practice_exam_session SET state = 'IN_PROGRESS', active_subject_order = ?, updated_at = ? WHERE id = ?",
        currentOrder + 1, Timestamp.from(now), session.get("id"));
    event(session, "SUBJECT_CONTINUED", ((Number) next.get("id")).longValue(), now);
    return state(sessionNo);
  }

  @Transactional
  public Map<String, Object> saveAnswer(String sessionNo, long subQuestionId, String value) {
    Map<String, Object> session = lockedSession(sessionNo);
    Map<String, Object> subject = activeSubject(session);
    if (!"IN_PROGRESS".equals(subject.get("state")) || subject.get("answers_locked_at") != null) {
      throw new ExamSimulatorException("ANSWERS_LOCKED", "当前科目已锁定答案");
    }
    Long questionId = jdbc.queryForObject("""
        SELECT pq.question_id
        FROM q_paper_question pq
        JOIN q_sub_question sq ON sq.question_id = pq.question_id
          AND sq.question_version_id = (SELECT qq.current_published_version_id FROM q_question qq WHERE qq.id = pq.question_id)
          AND sq.id = ? AND sq.deleted = 0
        WHERE pq.paper_id = ? AND pq.deleted = 0
        """, Long.class, subQuestionId, subject.get("paper_id"));
    if (questionId == null) throw new ExamSimulatorException("SUB_QUESTION_NOT_FOUND", "题目不属于当前试卷");
    long attemptId = ensureAttempt(session, subject);
    Instant now = Instant.now();
    Long answerId = jdbc.query("SELECT id FROM practice_attempt_answer WHERE attempt_id = ? AND sub_question_id = ?", rs -> rs.next() ? rs.getLong(1) : null, attemptId, subQuestionId);
    if (answerId == null) {
      jdbc.update("""
          INSERT INTO practice_attempt_answer (id, attempt_id, question_id, sub_question_id, answer_content, answer_json,
            answer_version, marked, viewed_analysis, created_at, updated_at, saved_at, deleted)
          VALUES (?, ?, ?, ?, ?, JSON_OBJECT('value', ?), 1, 0, 0, ?, ?, ?, 0)
          """, reserveIds("practice_attempt_answer", 1), attemptId, questionId, subQuestionId, value, value,
          Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    } else {
      jdbc.update("""
          UPDATE practice_attempt_answer SET answer_content = ?, answer_json = JSON_OBJECT('value', ?),
            answer_version = answer_version + 1, saved_at = ?, updated_at = ? WHERE id = ?
          """, value, value, Timestamp.from(now), Timestamp.from(now), answerId);
    }
    Long version = jdbc.queryForObject("SELECT answer_version FROM practice_attempt_answer WHERE attempt_id = ? AND sub_question_id = ?", Long.class, attemptId, subQuestionId);
    return Map.of("sessionId", sessionNo, "subQuestionId", subQuestionId, "answerVersion", version,
        "savedAt", now.toString(), "answered", value != null && !value.isBlank(), "serverNow", now.toString());
  }

  @Transactional
  public Map<String, Object> changeMark(String sessionNo, long subQuestionId, boolean marked) {
    Map<String, Object> session = lockedSession(sessionNo);
    Map<String, Object> subject = activeSubject(session);
    if (!"IN_PROGRESS".equals(subject.get("state")) || subject.get("answers_locked_at") != null) {
      throw new ExamSimulatorException("ANSWERS_LOCKED", "当前科目已锁定答案");
    }
    long attemptId = ensureAttempt(session, subject);
    int updated = jdbc.update("UPDATE practice_attempt_answer SET marked = ?, updated_at = ? WHERE attempt_id = ? AND sub_question_id = ?",
        marked, Timestamp.from(Instant.now()), attemptId, subQuestionId);
    if (updated == 0) {
      Long questionId = jdbc.queryForObject("""
          SELECT pq.question_id
          FROM q_paper_question pq
          JOIN q_sub_question sq ON sq.question_id = pq.question_id
            AND sq.question_version_id = (SELECT qq.current_published_version_id FROM q_question qq WHERE qq.id = pq.question_id)
            AND sq.id = ? AND sq.deleted = 0
          WHERE pq.paper_id = ? AND pq.deleted = 0
          """, Long.class, subQuestionId, subject.get("paper_id"));
      if (questionId == null) throw new ExamSimulatorException("SUB_QUESTION_NOT_FOUND", "题目不属于当前试卷");
      Instant now = Instant.now();
      jdbc.update("INSERT INTO practice_attempt_answer (id, attempt_id, question_id, sub_question_id, answer_version, marked, viewed_analysis, created_at, updated_at, saved_at, deleted) VALUES (?, ?, ?, ?, 0, ?, 0, ?, ?, ?, 0)",
          reserveIds("practice_attempt_answer", 1), attemptId, questionId, subQuestionId, marked, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }
    return Map.of("sessionId", sessionNo, "subQuestionId", subQuestionId, "marked", marked, "serverNow", Instant.now().toString());
  }

  private Map<String, Object> activeSubject(Map<String, Object> session) {
    return jdbc.queryForMap("SELECT * FROM practice_exam_session_subject WHERE session_id = ? AND subject_order = ? FOR UPDATE", session.get("id"), session.get("active_subject_order"));
  }

  private long ensureAttempt(Map<String, Object> session, Map<String, Object> subject) {
    if (subject.get("attempt_id") != null) return ((Number) subject.get("attempt_id")).longValue();
    long attemptId = reserveIds("practice_attempt", 1);
    Instant now = Instant.now();
    jdbc.update("INSERT INTO practice_attempt (id, user_id, qualification_id, subject_id, paper_id, exam_session_subject_id, mode, status, started_at, duration_seconds, created_at, updated_at, deleted) VALUES (?, NULL, ?, ?, ?, ?, 'MOCK_EXAM', 'IN_PROGRESS', ?, ?, ?, ?, 0)",
        attemptId, session.get("qualification_id"), subject.get("subject_id"), subject.get("paper_id"), subject.get("id"), Timestamp.from(now), 0, Timestamp.from(now), Timestamp.from(now));
    jdbc.update("UPDATE practice_exam_session_subject SET attempt_id = ? WHERE id = ?", attemptId, subject.get("id"));
    return attemptId;
  }

  private Map<String, Object> transition(String sessionNo, String expected, String target, String eventType, Long subjectId) {
    Map<String, Object> session = lockedSession(sessionNo);
    if (!expected.equals(session.get("state"))) throw new ExamSimulatorException("EXAM_STATE_CONFLICT", "会话状态不允许该操作");
    Instant now = Instant.now();
    String timeColumn = "CANDIDATE_CONFIRMED".equals(eventType) ? "candidate_confirmed_at" : "rules_acknowledged_at";
    jdbc.update("UPDATE practice_exam_session SET state = ?, " + timeColumn + " = ?, updated_at = ? WHERE id = ?",
        target, Timestamp.from(now), Timestamp.from(now), session.get("id"));
    event(session, eventType, subjectId, now);
    return state(sessionNo);
  }

  private Map<String, Object> lockedSession(String sessionNo) {
    return jdbc.queryForMap("SELECT id, candidate_id, qualification_id, state, active_subject_order, next_event_sequence FROM practice_exam_session WHERE session_no = ? FOR UPDATE", sessionNo);
  }

  private void collectObjectiveMistakes(Map<String, Object> session, Map<String, Object> subject) {
    if (subject.get("attempt_id") == null) return;
    var rows = jdbc.queryForList("""
        SELECT a.question_id, a.answer_content, ak.answer_value
        FROM practice_attempt_answer a
        JOIN q_answer_key ak ON ak.sub_question_id = a.sub_question_id
          AND ak.current_flag = 1 AND ak.deleted = 0
        WHERE a.attempt_id = ? AND a.deleted = 0 AND a.answer_content IS NOT NULL
          AND ak.answer_format IN ('OPTION', 'BOOLEAN', 'NUMBER')
        """, subject.get("attempt_id"));
    Instant now = Instant.now();
    for (var row : rows) {
      if (java.util.Objects.equals(String.valueOf(row.get("answer_content")).trim(), String.valueOf(row.get("answer_value")).trim())) continue;
      Long existing = jdbc.query("SELECT id FROM practice_mistake WHERE user_id = ? AND question_id = ? AND deleted = 0",
          rs -> rs.next() ? rs.getLong(1) : null, session.get("candidate_id"), row.get("question_id"));
      if (existing == null) {
        jdbc.update("""
            INSERT INTO practice_mistake (id, user_id, question_id, qualification_id, subject_id,
              first_attempt_id, last_attempt_id, mistake_count, status, reason_code, created_at, updated_at, deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'ACTIVE', 'EXAM_OBJECTIVE_WRONG', ?, ?, 0)
            """, reserveIds("practice_mistake", 1), session.get("candidate_id"), row.get("question_id"),
            session.get("qualification_id"), subject.get("subject_id"), subject.get("attempt_id"), subject.get("attempt_id"),
            Timestamp.from(now), Timestamp.from(now));
      } else {
        jdbc.update("UPDATE practice_mistake SET last_attempt_id = ?, mistake_count = mistake_count + 1, updated_at = ? WHERE id = ?",
            subject.get("attempt_id"), Timestamp.from(now), existing);
      }
    }
  }

  private void event(Map<String, Object> session, String type, Long sessionSubjectId, Instant now) {
    long sequence = ((Number) session.get("next_event_sequence")).longValue();
    jdbc.update("UPDATE practice_exam_session SET next_event_sequence = next_event_sequence + 1 WHERE id = ?", session.get("id"));
    jdbc.update("INSERT INTO practice_exam_event (id, session_id, session_subject_id, event_sequence, event_type, actor_type, trace_id, occurred_at) VALUES (?, ?, ?, ?, ?, 'CANDIDATE', 'exam-runtime', ?)",
        reserveIds("practice_exam_event", 1), session.get("id"), sessionSubjectId, sequence, type, Timestamp.from(now));
  }

  private long reserveIds(String entityName, int count) {
    if (count < 1) throw new IllegalArgumentException("count must be positive");
    int changed = jdbc.update("UPDATE app_id_sequence SET next_id = LAST_INSERT_ID(next_id + ?) WHERE entity_name = ?", count, entityName);
    if (changed != 1) throw new IllegalStateException("missing id sequence for " + entityName);
    Long exclusiveEnd = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    if (exclusiveEnd == null) throw new IllegalStateException("failed to reserve id sequence for " + entityName);
    return exclusiveEnd - count;
  }

  private byte[] credentialHmac(String domain, String value) {
    if (value == null || value.isBlank()) throw new ExamSimulatorException("EXAM_INVALID_CREDENTIALS", "准考证号或证件号无效");
    String key = System.getenv("EXAM_CREDENTIAL_KEY_V1");
    if (key == null || key.isBlank()) throw new ExamSimulatorException("EXAM_CREDENTIAL_KEY_UNAVAILABLE", "考试凭据服务暂不可用");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(("AIEXAM|LOOKUP|V1|" + domain + "|" + value.trim()).getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) { throw new IllegalStateException("credential hmac unavailable", exception); }
  }

  private static String sha256(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }

  private static byte[] hexBytes(String hex) { return HexFormat.of().parseHex(hex); }

  public record LoginCommand(String qualificationCode, String entryCode, String ruleVersion, String clientLoginId,
                             String admissionTicket, String identityNumber) {}
  public record LoginResult(String sessionId, String sessionToken, String state, String entryCode, String qualificationCode) {}

  public static final class ExamSimulatorException extends RuntimeException {
    private final String code;
    public ExamSimulatorException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
  }
}
