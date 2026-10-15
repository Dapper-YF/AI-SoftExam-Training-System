package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.foundation.application.FoundationApplicationService;
import com.aiexam.foundation.infra.JdbcFoundationRepository;
import com.aiexam.question.practice.attempt.application.PracticeAttemptApplicationService;
import com.aiexam.question.practice.attempt.application.PracticePrincipal;
import com.aiexam.question.practice.attempt.domain.PracticeAnswerPayload;
import com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest;
import com.aiexam.question.practice.attempt.dto.SavePracticeAnswerRequest;
import com.aiexam.question.practice.attempt.dto.SubmitPracticeAttemptRequest;
import com.aiexam.question.practice.attempt.infra.JdbcPracticeAttemptRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "PRACTICE_ATTEMPT_MYSQL_GATE", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PracticeAttemptPersistenceMysqlGateTest {
  private static final String SCHEMA = "practice_attempt_" + ProcessHandle.current().pid()
      + "_" + System.currentTimeMillis() + "_test";
  private static final Mysql MYSQL = Mysql.fromEnvironment();

  private JdbcTemplate jdbcTemplate;
  private PracticeAttemptApplicationService service;

  @BeforeAll
  void createIsolatedDatabase() throws Exception {
    requireTestSchema(SCHEMA);
    MYSQL.createSchema(SCHEMA);
    try {
      assertThat(flyway("10").migrate().migrationsExecuted).isEqualTo(9);
      assertThat(flyway("11").migrate().migrationsExecuted).isEqualTo(1);
      assertThat(flyway("12").migrate().migrationsExecuted).isEqualTo(1);
      flyway("12").validate();
      assertThat(flyway("12").migrate().migrationsExecuted).isZero();
      DataSource dataSource = new DriverManagerDataSource(
          MYSQL.url(SCHEMA), MYSQL.username(), MYSQL.password());
      jdbcTemplate = new JdbcTemplate(dataSource);
      assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(SCHEMA);
      seedPublishedQuestion();
      service = new PracticeAttemptApplicationService(new JdbcPracticeAttemptRepository(jdbcTemplate),
          () -> new PracticePrincipal(970002L, "practice-gate-student", Set.of()),
          new FoundationApplicationService(new JdbcFoundationRepository(jdbcTemplate)),
          Clock.fixed(Instant.parse("2026-07-16T04:00:00Z"), ZoneOffset.UTC));
    } catch (Exception exception) {
      MYSQL.dropSchema(SCHEMA);
      throw exception;
    }
  }

  @AfterAll
  void removeIsolatedDatabase() throws Exception {
    MYSQL.dropSchema(SCHEMA);
  }

  @Test
  void migratesV12AndPersistsThePracticeLoopWithoutTouchingTheSharedDatabase() {
    assertThat(jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success = 1
        """, Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN ('practice_attempt_question', 'practice_attempt_idempotency')
        """, Integer.class)).isEqualTo(2);

    var createRequest = new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 970001L, 970011L, "PRACTICE", "WHOLE_PAPER", 1,
        new CreatePracticeAttemptRequest.Source("KNOWLEDGE", "CHAPTER", List.of(970101L), null, null));
    var created = service.create(createRequest);
    var createReplay = service.create(createRequest);
    assertThat(createReplay.attemptId()).isEqualTo(created.attemptId());
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM practice_attempt WHERE user_id = 970002", Integer.class)).isEqualTo(1);
    assertThat(created.totalScore()).isNull();
    assertThat(created.maxScore()).isNull();
    var unrevealed = created.questions().get(0).subQuestions().get(0);
    assertThat(unrevealed.grading()).isNull();
    assertThat(unrevealed.solution()).isNull();
    assertThatThrownBy(() -> service.explanation(created.attemptId(), 9700011L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("解析不可见");

    var saveRequest = new SavePracticeAnswerRequest(
        UUID.randomUUID(), 0, new PracticeAnswerPayload("B", null, null, null), false, 7);
    var saved = service.saveAnswer(created.attemptId(), 970901L, saveRequest);
    var saveReplay = service.saveAnswer(created.attemptId(), 970901L, saveRequest);
    assertThat(saved.answerVersion()).isEqualTo(1);
    assertThat(saveReplay.idempotentReplay()).isTrue();
    assertThat(saveReplay.answerVersion()).isEqualTo(1);
    assertThatThrownBy(() -> service.saveAnswer(created.attemptId(), 970901L,
        new SavePracticeAnswerRequest(UUID.randomUUID(), 0,
            new PracticeAnswerPayload("A", null, null, null), false, 8)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("version conflict");
    assertThat(jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM practice_attempt_answer
        WHERE attempt_id = ? AND sub_question_id = 970901 AND answer_version = 1
        """, Integer.class, created.attemptId())).isEqualTo(1);

    var submitRequest = new SubmitPracticeAttemptRequest(UUID.randomUUID(), 0);
    var submitted = service.submit(created.attemptId(), submitRequest);
    var submitReplay = service.submit(created.attemptId(), submitRequest);

    assertThat(submitted.wrongCount()).isEqualTo(1);
    assertThat(submitReplay.idempotentReplay()).isTrue();
    assertThat(submitReplay.version()).isEqualTo(submitted.version());
    assertThat(service.explanation(created.attemptId(), 9700011L).subQuestions()).hasSize(1);
    assertThat(jdbcTemplate.queryForObject("""
        SELECT mistake_count FROM practice_mistake WHERE user_id = 970002 AND question_id = 9700011
        """, Integer.class)).isEqualTo(1);
    assertThat(service.history(1, 20).items()).singleElement().satisfies(history ->
        assertThat(history.maxScore()).isEqualByComparingTo("2.00"));
  }

  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(MYSQL.url(SCHEMA), MYSQL.username(), MYSQL.password())
        .locations("classpath:db/migration")
        .target(target)
        .load();
  }

  private void seedPublishedQuestion() {
    jdbcTemplate.update("""
        INSERT INTO exam_qualification
          (id, qualification_code, qualification_name, level_code, status, sort_order, created_at, updated_at, deleted)
        VALUES (970001, 'PRACTICE_GATE_PM', '练习持久化 Gate', 'HIGH', 'ENABLED', 99, NOW(3), NOW(3), 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO exam_subject
          (id, qualification_id, subject_code, subject_name, subject_type, sort_order, status,
           created_at, updated_at, deleted)
        VALUES (970011, 970001, 'PRACTICE_GATE_COMPREHENSIVE', '综合知识', 'KNOWLEDGE', 1,
                'ENABLED', NOW(3), NOW(3), 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO kp_chapter_node
          (id, qualification_id, subject_id, chapter_code, chapter_name, level_no, sort_order, status,
           created_at, updated_at, deleted)
        VALUES (970101, 970001, 970011, 'PRACTICE-GATE-CH-01', '项目进度管理', 1, 1,
                'ENABLED', NOW(3), NOW(3), 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question
          (id, qualification_id, subject_id, question_type, title, year_no, difficulty,
           status, content_version, current_published_version_id,
           created_at, updated_at, deleted)
        VALUES (9700011, 970001, 970011, 'SINGLE_CHOICE', '进度管理单选题', 2026, 'EASY',
                'PUBLISHED', 3, 9707001, NOW(3), NOW(3), 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_version
          (id, question_id, version_no, status, external_key, title, year_no, difficulty,
           source_type, source_ref, syllabus_basis, copyright_status, usage_restriction,
           content_hash, content_json, author_id, reviewed_by, review_decision,
           published_by, published_at, row_version, created_at, updated_at, deleted)
        VALUES (9707001, 9700011, 3, 'PUBLISHED', 'PRACTICE-GATE-Q1', '进度管理单选题',
                2026, 'EASY', 'MANUAL', 'practice-gate', 'GATE', 'UNVERIFIED',
                'INTERNAL_ONLY', REPEAT('a',64), JSON_OBJECT('gate', true), 970003,
                970004, 'APPROVED', 970005, NOW(3), 0, NOW(3), NOW(3), 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_stem
          (id, question_id, question_version_id, stem_key, stem_type, content, sort_order,
           created_at, updated_at, deleted)
        VALUES (970501, 9700011, 9707001, 'S1', 'MAIN_STEM',
                '以下哪项属于进度管理工具？', 1, NOW(3), NOW(3), 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_sub_question
          (id, question_id, question_version_id, sub_question_no, sub_question_type, content,
           score, answer_format, grading_mode, sort_order, created_at, updated_at, deleted)
        VALUES (970901, 9700011, 9707001, 1, 'SINGLE_CHOICE', '选择最佳答案', 2.00, 'OPTION',
                 'AUTO', 1, NOW(3), NOW(3), 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_choice_option
          (id, sub_question_id, option_key, option_content, sort_order, created_at, updated_at, deleted)
        VALUES
          (971001, 970901, 'A', '甘特图', 1, NOW(3), NOW(3), 0),
          (971002, 970901, 'B', '鱼骨图', 2, NOW(3), NOW(3), 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_answer_key
          (id, sub_question_id, answer_format, answer_value, version_no, current_flag,
           created_at, updated_at, deleted)
        VALUES (972001, 970901, 'OPTION', 'A', 1, 1, NOW(3), NOW(3), 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_solution
          (id, question_id, question_version_id, sub_question_id, solution_type, content,
           source_type, sort_order, version_no, created_at, updated_at, deleted)
        VALUES (973001, 9700011, 9707001, 970901, 'STANDARD',
                '甘特图用于展示计划进度和实际进度。', 'MANUAL', 1, 1,
                NOW(3), NOW(3), 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_knowledge_binding
          (id, target_type, target_id, question_id, question_version_id, sub_question_id,
           knowledge_type, knowledge_id, knowledge_code, weight, sort_order,
           created_at, updated_at, deleted)
        VALUES (974001, 'QUESTION', 9700011, 9700011, 9707001, NULL, 'CHAPTER', 970101,
                'PRACTICE-GATE-CH-01', 1.000, 1, NOW(3), NOW(3), 0)
        """);
  }

  private static void requireTestSchema(String schema) {
    if (schema == null || !schema.matches("[a-z0-9_]+_test")) {
      throw new IllegalStateException("Refusing non-test schema: " + schema);
    }
  }

  private record Mysql(String baseUrl, String username, String password) {
    static Mysql fromEnvironment() {
      return new Mysql(
          valueOrDefault("MYSQL_TEST_URL",
              "jdbc:mysql://127.0.0.1:33307/ai_exam_platform_test?useUnicode=true&characterEncoding=utf8"
                  + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"),
          valueOrDefault("MYSQL_TEST_USERNAME", "root"),
          valueOrDefault("MYSQL_TEST_PASSWORD", ""));
    }

    String serverUrl() {
      int queryAt = baseUrl.indexOf('?');
      String base = queryAt >= 0 ? baseUrl.substring(0, queryAt) : baseUrl;
      String query = queryAt >= 0 ? baseUrl.substring(queryAt) : "";
      return base.substring(0, base.lastIndexOf('/') + 1) + query;
    }

    String url(String schema) {
      requireTestSchema(schema);
      int queryAt = baseUrl.indexOf('?');
      String base = queryAt >= 0 ? baseUrl.substring(0, queryAt) : baseUrl;
      String query = queryAt >= 0 ? baseUrl.substring(queryAt) : "";
      return base.substring(0, base.lastIndexOf('/') + 1) + schema + query;
    }

    void createSchema(String schema) throws Exception {
      requireTestSchema(schema);
      try (Connection connection = DriverManager.getConnection(serverUrl(), username, password);
           Statement statement = connection.createStatement()) {
        statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      }
    }

    void dropSchema(String schema) throws Exception {
      requireTestSchema(schema);
      try (Connection connection = DriverManager.getConnection(serverUrl(), username, password);
           Statement statement = connection.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
      }
    }

    private static String valueOrDefault(String name, String fallback) {
      String value = System.getenv(name);
      return value == null || value.isBlank() ? fallback : value;
    }
  }
}
