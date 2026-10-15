package com.aiexam.app.devdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "aiexam.dev-exam-simulator", name = "enabled", havingValue = "true")
public class DevExamSimulatorSeedRunner implements ApplicationRunner {
  private static final String RULE_VERSION = "2026-H2-v1";
  private static final String PERIOD_CODE = "2026-H2";
  private static final long RULE_ID = 900001L;
  private static final long BATCH_ID = 900002L;
  private static final long LINKED_ENTRY_ID = 900010L;
  private static final long ESSAY_ENTRY_ID = 900020L;
  private static final long COMPREHENSIVE_PAPER_ID = 910001L;
  private static final long CASE_PAPER_ID = 910002L;
  private static final long ESSAY_PAPER_ID = 910003L;
  private static final long QUESTION_ROW_BASE = 9200000L;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public DevExamSimulatorSeedRunner(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    Instant now = Instant.now();
    Timestamp timestamp = Timestamp.from(now);
    Timestamp scheduledStart = Timestamp.from(now.minusSeconds(3600));
    Timestamp loginOpen = Timestamp.from(now.minusSeconds(86400));
    Timestamp loginClose = Timestamp.from(now.plusSeconds(31536000));

    seedExamRule(timestamp);

    long comprehensiveSubjectId = subjectId("COMPREHENSIVE_KNOWLEDGE");
    long caseSubjectId = subjectId("CASE_ANALYSIS");
    long essaySubjectId = subjectId("ESSAY");
    List<Long> comprehensiveQuestions = selectRealSingleChoiceQuestions(comprehensiveSubjectId);
    if (comprehensiveQuestions.size() < 75) {
      throw new IllegalStateException("DEV_EXAM_SIMULATOR_INSUFFICIENT_QUESTIONS:"
          + comprehensiveQuestions.size());
    }

    seedPaper(COMPREHENSIVE_PAPER_ID, comprehensiveSubjectId, "2026年信息系统项目管理师综合知识真题",
        75, 150, timestamp);
    seedPaper(CASE_PAPER_ID, caseSubjectId, "2026年信息系统项目管理师案例分析真题",
        null, 150, timestamp);
    seedPaper(ESSAY_PAPER_ID, essaySubjectId, "2026年信息系统项目管理师论文真题",
        75, 120, timestamp);

    jdbc.update("DELETE FROM q_paper_question WHERE paper_id IN (?, ?, ?)",
        COMPREHENSIVE_PAPER_ID, CASE_PAPER_ID, ESSAY_PAPER_ID);
    insertPaperQuestions(COMPREHENSIVE_PAPER_ID, comprehensiveQuestions.subList(0, 75),
        "COMPREHENSIVE_KNOWLEDGE", 1, timestamp);
    insertPaperQuestions(CASE_PAPER_ID, List.of(147L, 148L, 149L),
        "CASE_ANALYSIS", null, timestamp);
    insertPaperQuestions(ESSAY_PAPER_ID, List.of(26435L),
        "ESSAY", 75, timestamp);

    seedBatch(scheduledStart, loginOpen, loginClose, timestamp);
    seedLinkedEntry(timestamp);
    seedEssayEntry(timestamp);
    seedCandidate(900031L, LINKED_ENTRY_ID, "LINKED",
        "202600000001", "110101199001011234", "2026****0001", "110101********1234",
        "测试考生", "男", "001", timestamp);
    seedCandidate(900032L, ESSAY_ENTRY_ID, "ESSAY",
        "202600000002", "110101199001011235", "2026****0002", "110101********1235",
        "论文考生", "女", "002", timestamp);

    for (String entity : List.of(
        "practice_exam_session",
        "practice_exam_rule_snapshot",
        "practice_exam_session_subject",
        "practice_exam_event",
        "practice_exam_idempotency",
        "practice_attempt",
        "practice_attempt_answer",
        "practice_mistake")) {
      ensureRuntimeSequence(entity);
    }
    System.out.println("DEV_EXAM_SIMULATOR_SEED_OK");
  }

  private void seedExamRule(Timestamp timestamp) throws Exception {
    String ruleJson = objectMapper.writeValueAsString(buildRuleJson());
    jdbc.update("""
        INSERT INTO exam_rule (
          id, qualification_id, subject_id, period_code, rule_code, rule_name,
          rule_version, rule_json, status, effective_at, effective_from, effective_to,
          published_at, version, created_at, updated_at, created_by, updated_by, deleted
        ) VALUES (?, 1, NULL, ?, 'EXAM_PERIOD_RULE', '2026 H2 High PM Real Exam',
          ?, ?, 'PUBLISHED', ?, ?, ?, ?, 0, ?, ?, 0, 0, 0)
        ON DUPLICATE KEY UPDATE
          period_code = VALUES(period_code),
          rule_name = VALUES(rule_name),
          rule_version = VALUES(rule_version),
          rule_json = VALUES(rule_json),
          status = VALUES(status),
          effective_from = VALUES(effective_from),
          effective_to = VALUES(effective_to),
          published_at = VALUES(published_at),
          updated_at = VALUES(updated_at),
          deleted = VALUES(deleted)
        """,
        RULE_ID, PERIOD_CODE, RULE_VERSION, ruleJson,
        timestamp, Timestamp.from(Instant.now().minusSeconds(86400)),
        Timestamp.from(Instant.now().plusSeconds(31536000)), timestamp,
        timestamp, timestamp);
  }

  private ObjectNode buildRuleJson() {
    ObjectNode verification = objectMapper.createObjectNode();
    verification.put("sourceUrl", "https://example.com/official/2026-high-pm-h2");
    verification.put("verifiedAt", "2026-07-01T00:00:00+08:00");
    verification.put("sourceTitle", "2026 H2 official notice");
    verification.put("sourceHash", "sha256:" + "a".repeat(64));

    ObjectNode comprehensive = objectMapper.createObjectNode();
    comprehensive.put("subjectCode", "COMPREHENSIVE_KNOWLEDGE");
    comprehensive.put("subjectType", "COMPREHENSIVE");
    comprehensive.put("displayOrder", 1);
    comprehensive.put("durationMinutes", 150);
    comprehensive.put("fullScore", 75);
    comprehensive.put("passScore", 45);
    comprehensive.putArray("questionTypes").add("SINGLE_CHOICE");
    comprehensive.put("earliestSubmitMinutes", 0);
    comprehensive.put("feedbackPolicy", "AFTER_EXAM");
    comprehensive.put("linkedGroup", "HIGH_PM_LINKED_1");
    comprehensive.put("sequence", 1);

    ObjectNode caseSubject = objectMapper.createObjectNode();
    caseSubject.put("subjectCode", "CASE_ANALYSIS");
    caseSubject.put("subjectType", "CASE");
    caseSubject.put("displayOrder", 2);
    caseSubject.put("durationMinutes", 150);
    caseSubject.put("fullScore", 75);
    caseSubject.put("passScore", 45);
    caseSubject.putArray("questionTypes").add("CASE");
    caseSubject.put("earliestSubmitMinutes", 0);
    caseSubject.put("feedbackPolicy", "AFTER_EXAM");
    caseSubject.put("linkedGroup", "HIGH_PM_LINKED_1");
    caseSubject.put("sequence", 2);

    ArrayNode subjects = objectMapper.createArrayNode();
    subjects.add(comprehensive);
    subjects.add(caseSubject);

    ObjectNode rule = objectMapper.createObjectNode();
    rule.put("schemaVersion", "1.0");
    rule.put("ruleVersion", RULE_VERSION);
    rule.put("qualificationCode", "HIGH_PM");
    rule.put("examCycle", "2026");
    rule.put("periodCode", PERIOD_CODE);
    rule.put("effectiveFrom", iso(Instant.now().minusSeconds(86400)));
    rule.put("effectiveTo", iso(Instant.now().plusSeconds(31536000)));
    rule.put("enabled", true);
    rule.put("totalDurationMinutes", 240);
    rule.put("deadlinePolicy", "SHARED_TOTAL_DEADLINE");
    rule.put("sharedForceSubmitMinutes", 240);
    rule.set("subjects", subjects);
    rule.set("verification", verification);
    return rule;
  }

  private List<Long> selectRealSingleChoiceQuestions(long subjectId) {
    return jdbc.queryForList("""
        SELECT q.id
        FROM q_question q
        JOIN q_question_version v ON v.id = q.current_published_version_id
          AND v.question_id = q.id AND v.status = 'PUBLISHED' AND v.deleted = 0
        JOIN q_sub_question sq ON sq.question_id = q.id
          AND sq.question_version_id = q.current_published_version_id AND sq.deleted = 0
        WHERE q.qualification_id = 1 AND q.subject_id = ?
          AND q.question_type = 'SINGLE_CHOICE' AND q.status = 'PUBLISHED' AND q.deleted = 0
          AND sq.answer_format = 'OPTION'
          AND EXISTS (
            SELECT 1 FROM q_choice_option o
            WHERE o.sub_question_id = sq.id AND o.deleted = 0
          )
          AND EXISTS (
            SELECT 1 FROM q_answer_key ak
            WHERE ak.sub_question_id = sq.id AND ak.current_flag = 1 AND ak.deleted = 0
          )
        GROUP BY q.id, q.current_published_version_id
        HAVING COUNT(DISTINCT sq.id) = 1
        ORDER BY q.id
        LIMIT 75
        """, Long.class, subjectId);
  }

  private void seedPaper(long paperId, long subjectId, String paperName,
      Integer totalScore, Integer durationMinutes, Timestamp timestamp) {
    jdbc.update("""
        INSERT INTO q_paper (
          id, qualification_id, subject_id, paper_type, paper_name, year_no,
          total_score, duration_minutes, rule_snapshot, content_version, status,
          created_at, updated_at, created_by, updated_by, deleted
        ) VALUES (?, 1, ?, 'REAL', ?, 2026, ?, ?, NULL, 1, 'PUBLISHED',
          ?, ?, 0, 0, 0)
        ON DUPLICATE KEY UPDATE
          subject_id = VALUES(subject_id),
          paper_type = VALUES(paper_type),
          paper_name = VALUES(paper_name),
          year_no = VALUES(year_no),
          total_score = VALUES(total_score),
          duration_minutes = VALUES(duration_minutes),
          content_version = VALUES(content_version),
          status = VALUES(status),
          updated_at = VALUES(updated_at),
          deleted = VALUES(deleted)
        """,
        paperId, subjectId, paperName, totalScore, durationMinutes,
        timestamp, timestamp);
  }

  private void insertPaperQuestions(long paperId, List<Long> questionIds,
      String sectionCode, Integer score, Timestamp timestamp) {
    int questionNo = 1;
    for (Long questionId : questionIds) {
      jdbc.update("""
          INSERT INTO q_paper_question (
            id, paper_id, question_id, section_code, question_no, score,
            question_snapshot, created_at, updated_at, created_by, updated_by, deleted
          ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, 0, 0, 0)
          """,
          QUESTION_ROW_BASE + paperId * 1000 + questionNo,
          paperId, questionId, sectionCode, questionNo, score,
          timestamp, timestamp);
      questionNo++;
    }
  }

  private void seedBatch(Timestamp scheduledStart, Timestamp loginOpen,
      Timestamp loginClose, Timestamp timestamp) {
    jdbc.update("""
        INSERT INTO practice_exam_batch (
          id, batch_code, qualification_id, exam_year, region_code, region_name,
          login_open_at, login_close_at, scheduled_start_at, status, config_version,
          published_at, closed_at, version, created_by, updated_by, created_at, updated_at
        ) VALUES (?, 'DEV-2026-H2-HIGH-PM', 1, 2026, 'DEV', 'Development',
          ?, ?, ?, 'PUBLISHED', 1, ?, NULL, 0, 0, 0, ?, ?)
        ON DUPLICATE KEY UPDATE
          batch_code = VALUES(batch_code),
          qualification_id = VALUES(qualification_id),
          exam_year = VALUES(exam_year),
          region_code = VALUES(region_code),
          region_name = VALUES(region_name),
          login_open_at = VALUES(login_open_at),
          login_close_at = VALUES(login_close_at),
          scheduled_start_at = VALUES(scheduled_start_at),
          status = VALUES(status),
          config_version = VALUES(config_version),
          published_at = VALUES(published_at),
          updated_at = VALUES(updated_at)
        """,
        BATCH_ID, loginOpen, loginClose, scheduledStart, timestamp,
        timestamp, timestamp);
  }

  private void seedLinkedEntry(Timestamp timestamp) {
    jdbc.update("""
        INSERT INTO practice_exam_entry (
          id, batch_id, entry_code, exam_mode, title, description,
          total_duration_seconds, exam_rule_id, exam_rule_version,
          credential_algorithm_version, credential_key_version, status, config_version,
          published_at, closed_at, version, created_by, updated_by, created_at, updated_at
        ) VALUES (?, ?, 'LINKED', 'STRICT', '综合知识+案例分析', '真实机考联考',
          14400, ?, ?, 'LOOKUP_V1', 'V1', 'PUBLISHED', 1,
          ?, NULL, 0, 0, 0, ?, ?)
        ON DUPLICATE KEY UPDATE
          batch_id = VALUES(batch_id),
          exam_mode = VALUES(exam_mode),
          title = VALUES(title),
          description = VALUES(description),
          total_duration_seconds = VALUES(total_duration_seconds),
          exam_rule_id = VALUES(exam_rule_id),
          exam_rule_version = VALUES(exam_rule_version),
          credential_algorithm_version = VALUES(credential_algorithm_version),
          credential_key_version = VALUES(credential_key_version),
          status = VALUES(status),
          config_version = VALUES(config_version),
          published_at = VALUES(published_at),
          updated_at = VALUES(updated_at)
        """,
        LINKED_ENTRY_ID, BATCH_ID, RULE_ID, RULE_VERSION, timestamp,
        timestamp, timestamp);
    seedEntrySubject(900011L, LINKED_ENTRY_ID, 1, 1, "COMPREHENSIVE_KNOWLEDGE",
        COMPREHENSIVE_PAPER_ID, "LINKED_DEADLINE", null, timestamp);
    seedEntrySubject(900012L, LINKED_ENTRY_ID, 2, 2, "CASE_ANALYSIS",
        CASE_PAPER_ID, "LINKED_DEADLINE", null, timestamp);
  }

  private void seedEssayEntry(Timestamp timestamp) {
    jdbc.update("""
        INSERT INTO practice_exam_entry (
          id, batch_id, entry_code, exam_mode, title, description,
          total_duration_seconds, exam_rule_id, exam_rule_version,
          credential_algorithm_version, credential_key_version, status, config_version,
          published_at, closed_at, version, created_by, updated_by, created_at, updated_at
        ) VALUES (?, ?, 'ESSAY', 'STRICT', '论文', '真实论文机考',
          7200, ?, ?, 'LOOKUP_V1', 'V1', 'PUBLISHED', 1,
          ?, NULL, 0, 0, 0, ?, ?)
        ON DUPLICATE KEY UPDATE
          batch_id = VALUES(batch_id),
          exam_mode = VALUES(exam_mode),
          title = VALUES(title),
          description = VALUES(description),
          total_duration_seconds = VALUES(total_duration_seconds),
          exam_rule_id = VALUES(exam_rule_id),
          exam_rule_version = VALUES(exam_rule_version),
          credential_algorithm_version = VALUES(credential_algorithm_version),
          credential_key_version = VALUES(credential_key_version),
          status = VALUES(status),
          config_version = VALUES(config_version),
          published_at = VALUES(published_at),
          updated_at = VALUES(updated_at)
        """,
        ESSAY_ENTRY_ID, BATCH_ID, RULE_ID, RULE_VERSION, timestamp,
        timestamp, timestamp);
    seedEntrySubject(900021L, ESSAY_ENTRY_ID, 1, 3, "ESSAY",
        ESSAY_PAPER_ID, "FIXED_OFFSET", 7200, timestamp);
  }

  private void seedEntrySubject(long id, long entryId, int subjectOrder,
      long subjectId, String subjectCode, long paperId, String forceMode,
      Integer forceOffset, Timestamp timestamp) {
    jdbc.update("""
        INSERT INTO practice_exam_entry_subject (
          id, entry_id, subject_order, subject_id, subject_code, paper_id,
          paper_content_version, min_submit_offset_seconds, force_submit_mode,
          force_submit_offset_seconds, allow_manual_submit, version, created_by,
          updated_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, 1, 0, ?, ?, 1, 0, 0, 0, ?, ?)
        ON DUPLICATE KEY UPDATE
          entry_id = VALUES(entry_id),
          subject_order = VALUES(subject_order),
          subject_id = VALUES(subject_id),
          subject_code = VALUES(subject_code),
          paper_id = VALUES(paper_id),
          paper_content_version = VALUES(paper_content_version),
          min_submit_offset_seconds = VALUES(min_submit_offset_seconds),
          force_submit_mode = VALUES(force_submit_mode),
          force_submit_offset_seconds = VALUES(force_submit_offset_seconds),
          allow_manual_submit = VALUES(allow_manual_submit),
          updated_at = VALUES(updated_at)
        """,
        id, entryId, subjectOrder, subjectId, subjectCode, paperId,
        forceMode, forceOffset, timestamp, timestamp);
  }

  private void seedCandidate(long id, long entryId, String entryCode,
      String admissionTicket, String identityNumber, String maskedAdmission,
      String maskedIdentity, String name, String gender, String seatNo,
      Timestamp timestamp) {
    jdbc.update("""
        INSERT INTO practice_exam_candidate (
          id, entry_id, entry_code, qualification_id, admission_ticket_lookup_hmac,
          admission_ticket_masked, identity_lookup_hmac, identity_masked,
          credential_origin, credential_algorithm_version, credential_key_version,
          candidate_name, gender, avatar_url, seat_no, credential_status,
          created_at, updated_at
        ) VALUES (?, ?, ?, 1, ?, ?, ?, ?, 'STRICT', 'LOOKUP_V1', 'V1',
          ?, ?, NULL, ?, 'ACTIVE', ?, ?)
        ON DUPLICATE KEY UPDATE
          entry_id = VALUES(entry_id),
          entry_code = VALUES(entry_code),
          qualification_id = VALUES(qualification_id),
          admission_ticket_lookup_hmac = VALUES(admission_ticket_lookup_hmac),
          admission_ticket_masked = VALUES(admission_ticket_masked),
          identity_lookup_hmac = VALUES(identity_lookup_hmac),
          identity_masked = VALUES(identity_masked),
          credential_algorithm_version = VALUES(credential_algorithm_version),
          credential_key_version = VALUES(credential_key_version),
          candidate_name = VALUES(candidate_name),
          gender = VALUES(gender),
          seat_no = VALUES(seat_no),
          credential_status = VALUES(credential_status),
          updated_at = VALUES(updated_at)
        """,
        id, entryId, entryCode,
        credentialHmac("ADMISSION", admissionTicket), maskedAdmission,
        credentialHmac("IDENTITY", identityNumber), maskedIdentity,
        name, gender, seatNo, timestamp, timestamp);
  }

  private long subjectId(String subjectCode) {
    Long id = jdbc.queryForObject("""
        SELECT id FROM exam_subject
        WHERE qualification_id = 1 AND subject_code = ? AND status = 'ENABLED' AND deleted = 0
        """, Long.class, subjectCode);
    if (id == null) {
      throw new IllegalStateException("DEV_EXAM_SIMULATOR_SUBJECT_NOT_FOUND:" + subjectCode);
    }
    return id;
  }

  private void ensureRuntimeSequence(String entityName) {
    jdbc.update("""
        INSERT INTO app_id_sequence (entity_name, next_id)
        SELECT ?, COALESCE(MAX(id), 0) + 1 FROM %s
        ON DUPLICATE KEY UPDATE next_id = GREATEST(next_id, VALUES(next_id))
        """.formatted(entityName), entityName);
  }

  private byte[] credentialHmac(String domain, String value) {
    String key = System.getenv("EXAM_CREDENTIAL_KEY_V1");
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("EXAM_CREDENTIAL_KEY_V1_REQUIRED_FOR_DEV_SEED");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(
          ("AIEXAM|LOOKUP|V1|" + domain + "|" + value.trim()).getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new IllegalStateException("credential hmac unavailable", exception);
    }
  }

  private static String iso(Instant instant) {
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        instant.atZone(ZoneId.of("Asia/Shanghai")));
  }
}
