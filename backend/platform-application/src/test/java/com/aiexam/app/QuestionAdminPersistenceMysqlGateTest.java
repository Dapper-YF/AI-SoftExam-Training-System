package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.question.admin.application.QuestionAdminApplicationService;
import com.aiexam.question.admin.application.QuestionAdminCurrentUser;
import com.aiexam.question.admin.application.QuestionImportApplicationService;
import com.aiexam.question.admin.dto.QuestionImportRequest;
import com.aiexam.question.admin.dto.QuestionReviewRequest;
import com.aiexam.question.admin.dto.QuestionVersionWriteRequest;
import com.aiexam.question.admin.dto.QuestionWorkflowRequest;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository;
import com.aiexam.question.dto.request.QuestionCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "QUESTION_ADMIN_MYSQL_GATE", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuestionAdminPersistenceMysqlGateTest {
  private static final String SCHEMA = "question_admin_" + ProcessHandle.current().pid()
      + "_" + System.currentTimeMillis() + "_test";
  private static final Mysql MYSQL = Mysql.fromEnvironment();
  private static final List<String> PERMISSIONS = List.of(
      "question:create", "question:update", "question:submit-review", "question:review",
      "question:publish", "question:read", "question:import");

  private JdbcTemplate jdbc;
  private JdbcQuestionAdminRepository repository;
  private QuestionAdminApplicationService adminService;
  private QuestionImportApplicationService importService;
  private AtomicReference<PlatformPrincipal> principal;
  private Path importRoot;

  @BeforeAll
  void createIsolatedSchemaAndMigrate() throws Exception {
    requireTestSchema(SCHEMA);
    MYSQL.createSchema(SCHEMA);
    try {
      flyway("10").migrate();
      var dataSource = new DriverManagerDataSource(MYSQL.url(SCHEMA), MYSQL.username(), MYSQL.password());
      jdbc = new JdbcTemplate(dataSource);
      assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(SCHEMA);
      seedScopeAndLegacyQuestion();

      var migrated = flyway("11").migrate();
      assertThat(migrated.migrationsExecuted).isEqualTo(1);
      flyway("11").validate();
      assertThat(flyway("11").migrate().migrationsExecuted).isZero();

      ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
      repository = new JdbcQuestionAdminRepository(jdbc, new DataSourceTransactionManager(dataSource), mapper);
      principal = new AtomicReference<>(user(881001L));
      CurrentUserProvider provider = () -> Optional.ofNullable(principal.get());
      QuestionAdminCurrentUser currentUser = new QuestionAdminCurrentUser(provider);
      adminService = new QuestionAdminApplicationService(repository, currentUser, mapper);
      importRoot = Files.createTempDirectory("question-admin-mysql-gate-");
      importService = new QuestionImportApplicationService(repository, currentUser, mapper, importRoot);
    } catch (Exception exception) {
      MYSQL.dropSchema(SCHEMA);
      throw exception;
    }
  }

  @AfterAll
  void cleanUp() throws Exception {
    try {
      MYSQL.dropSchema(SCHEMA);
    } finally {
      if (importRoot != null && Files.exists(importRoot)
          && importRoot.getFileName().toString().startsWith("question-admin-mysql-gate-")) {
        try (var paths = Files.walk(importRoot)) {
          paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (Exception exception) {
              throw new IllegalStateException(exception);
            }
          });
        }
      }
    }
  }

  @Test
  void provesLegacyBackfillWorkflowAndAtomicImportOnRealMysql() throws Exception {
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM flyway_schema_history WHERE version='11' AND success=1
        """, Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question_version WHERE question_id=880001 AND status='PUBLISHED'
          AND copyright_status='UNVERIFIED' AND usage_restriction='INTERNAL_ONLY'
        """, Integer.class)).isEqualTo(1);
    Long legacyVersion = jdbc.queryForObject(
        "SELECT current_published_version_id FROM q_question WHERE id=880001", Long.class);
    assertThat(legacyVersion).isNotNull();
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question_stem
        WHERE question_id=880001 AND question_version_id=?
        """, Integer.class, legacyVersion)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_sub_question
        WHERE question_id=880001 AND question_version_id=?
        """, Integer.class, legacyVersion)).isEqualTo(1);
    assertThat(repository.detail(880001L, legacyVersion).payload().stems()).singleElement()
        .satisfies(stem -> assertThat(stem.content()).isEqualTo("legacy stem"));
    assertThat(jdbc.queryForObject("SELECT status FROM q_question WHERE id=880002", String.class))
        .isEqualTo("IN_REVIEW");
    assertThat(jdbc.queryForObject("SELECT status FROM q_question WHERE id=880003", String.class))
        .isEqualTo("RETIRED");

    var created = adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null,
        payload("draft-v1")));
    var revised = adminService.createRevision(created.questionId(),
        new QuestionVersionWriteRequest(UUID.randomUUID(), created.questionVersionId(), payload("draft-v2")));
    assertThat(jdbc.queryForObject("SELECT deleted FROM q_question_version WHERE id=?",
        Integer.class, created.questionVersionId())).isEqualTo(1);
    var submitted = adminService.submitReview(created.questionId(), revised.questionVersionId(),
        workflow(0));
    assertThatThrownBy(() -> adminService.review(created.questionId(), revised.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), submitted.rowVersion(), "APPROVED", "self")))
        .hasMessageContaining("submitter cannot review");
    principal.set(user(881002L));
    var approved = adminService.review(created.questionId(), revised.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), submitted.rowVersion(), "APPROVED", "checked"));
    principal.set(user(881003L));
    var published = adminService.publish(created.questionId(), revised.questionVersionId(),
        workflow(approved.rowVersion()));
    assertThat(published.status()).isEqualTo("PUBLISHED");

    principal.set(user(881001L));
    var next = adminService.createRevision(created.questionId(),
        new QuestionVersionWriteRequest(UUID.randomUUID(), revised.questionVersionId(), payload("draft-v3")));
    assertThat(jdbc.queryForObject("SELECT title FROM q_question WHERE id=?", String.class,
        created.questionId())).isEqualTo("draft-v2");
    var nextSubmitted = adminService.submitReview(created.questionId(), next.questionVersionId(), workflow(0));
    principal.set(user(881002L));
    var nextApproved = adminService.review(created.questionId(), next.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), nextSubmitted.rowVersion(), "APPROVED", "checked"));
    principal.set(user(881003L));
    adminService.publish(created.questionId(), next.questionVersionId(), workflow(nextApproved.rowVersion()));
    assertThat(jdbc.queryForObject("SELECT status FROM q_question_version WHERE id=?", String.class,
        revised.questionVersionId())).isEqualTo("RETIRED");
    assertThat(jdbc.queryForObject("SELECT current_published_version_id FROM q_question WHERE id=?",
        Long.class, created.questionId())).isEqualTo(next.questionVersionId());

    principal.set(user(881001L));
    Path evidence = Files.createDirectory(importRoot.resolve("canonical"));
    Files.writeString(evidence.resolve("canonical-questions.jsonl"), canonicalJson(),
        StandardCharsets.UTF_8);
    int questionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class);
    var preflight = importService.preflight(new QuestionImportRequest(evidence.toString(), null));
    assertThat(preflight.eligibleCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class))
        .isEqualTo(questionsBefore);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_import_batch", Integer.class)).isZero();
    UUID mutation = UUID.randomUUID();
    var confirmed = importService.confirm(new QuestionImportRequest(evidence.toString(), mutation));
    var replayed = importService.confirm(new QuestionImportRequest(evidence.toString(), mutation));
    assertThat(confirmed.importedCount()).isEqualTo(1);
    assertThat(replayed.replayed()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question WHERE import_external_key='MYSQL-GATE-1'",
        Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question_version
        WHERE source_sha256=? AND paper_kind='REAL_PAPER' AND copyright_status='UNVERIFIED'
        """, Integer.class, "d".repeat(64))).isEqualTo(1);
    importService.rollback(confirmed.batchId(), new QuestionImportRequest.Rollback(UUID.randomUUID()));
    assertThat(jdbc.queryForObject("SELECT deleted FROM q_question WHERE import_external_key='MYSQL-GATE-1'",
        Integer.class)).isEqualTo(1);
  }

  private Flyway flyway(String target) {
    return Flyway.configure().dataSource(MYSQL.url(SCHEMA), MYSQL.username(), MYSQL.password())
        .locations("classpath:db/migration").target(target).load();
  }

  private void seedScopeAndLegacyQuestion() {
    jdbc.update("""
        INSERT INTO exam_qualification
          (id, qualification_code, qualification_name, level_code, status, sort_order,
           created_at, updated_at, deleted)
        VALUES (8801,'MYSQL_GATE_QUAL','MySQL gate qualification','MIDDLE','ENABLED',99,NOW(3),NOW(3),0)
        """);
    jdbc.update("""
        INSERT INTO exam_subject
          (id, qualification_id, subject_code, subject_name, subject_type, sort_order, status,
           created_at, updated_at, deleted)
        VALUES (8811,8801,'MYSQL_GATE_SUBJECT','MySQL gate subject','KNOWLEDGE',1,'ENABLED',NOW(3),NOW(3),0)
        """);
    jdbc.update("""
        INSERT INTO q_question
          (id,qualification_id,subject_id,question_type,title,year_no,difficulty,source_type,
           source_ref,status,content_version,created_at,updated_at,created_by,updated_by,deleted)
        VALUES (880001,8801,8811,'SINGLE_CHOICE','legacy published',2020,'EASY','MANUAL',
                'legacy','PUBLISHED',2,NOW(3),NOW(3),881001,881001,0),
               (880002,8801,8811,'SINGLE_CHOICE','legacy review',2020,'EASY','MANUAL',
                'legacy','REVIEWING',1,NOW(3),NOW(3),881001,881001,0),
               (880003,8801,8811,'SINGLE_CHOICE','legacy archived',2020,'EASY','MANUAL',
                'legacy','ARCHIVED',1,NOW(3),NOW(3),881001,881001,0)
        """);
    jdbc.update("""
        INSERT INTO q_question_stem
          (id,question_id,stem_type,content,sort_order,created_at,updated_at,deleted)
        VALUES (880101,880001,'MAIN_STEM','legacy stem',1,NOW(3),NOW(3),0)
        """);
    jdbc.update("""
        INSERT INTO q_sub_question
          (id,question_id,sub_question_no,sub_question_type,content,score,answer_format,
           grading_mode,sort_order,created_at,updated_at,deleted)
        VALUES (880201,880001,1,'SINGLE_CHOICE','legacy sub',1.00,'OPTION','AUTO',1,NOW(3),NOW(3),0)
        """);
    jdbc.update("""
        INSERT INTO q_choice_option
          (id,sub_question_id,option_key,option_content,sort_order,created_at,updated_at,deleted)
        VALUES (880301,880201,'A','yes',1,NOW(3),NOW(3),0),
               (880302,880201,'B','no',2,NOW(3),NOW(3),0)
        """);
    jdbc.update("""
        INSERT INTO q_answer_key
          (id,sub_question_id,answer_format,answer_value,answer_json,version_no,current_flag,
           created_at,updated_at,deleted)
        VALUES (880401,880201,'OPTION','A','[\"A\"]',1,1,NOW(3),NOW(3),0)
        """);
    jdbc.update("""
        INSERT INTO q_solution
          (id,question_id,sub_question_id,solution_type,content,source_type,version_no,
           created_at,updated_at,deleted)
        VALUES (880501,880001,880201,'STANDARD','legacy explanation','MANUAL',1,NOW(3),NOW(3),0)
        """);
    jdbc.update("""
        INSERT INTO q_scoring_rule
          (id,sub_question_id,rule_type,rule_json,version_no,current_flag,created_at,updated_at,deleted)
        VALUES (880601,880201,'AUTO','{\"mode\":\"EXACT\"}',1,1,NOW(3),NOW(3),0)
        """);
  }

  private QuestionCreateRequest payload(String title) {
    var stem = new QuestionCreateRequest.StemRequest("MAIN_STEM", title + " stem", null, 1,
        "S1", null);
    var sub = new QuestionCreateRequest.SubQuestionRequest(1, "SINGLE_CHOICE", title + " sub",
        new BigDecimal("1.00"), "OPTION_KEYS", "AUTO", 1, null,
        List.of(new QuestionCreateRequest.OptionRequest("A", "yes", 1),
            new QuestionCreateRequest.OptionRequest("B", "no", 2)),
        List.of(new QuestionCreateRequest.AnswerKeyRequest("OPTION_KEYS", "A", "[\"A\"]")),
        List.of(new QuestionCreateRequest.SolutionRequest("STANDARD", "why", 1)),
        List.of(new QuestionCreateRequest.ScoringRuleRequest("AUTO", "{\"mode\":\"EXACT\"}", 1)),
        List.of(), List.of());
    return new QuestionCreateRequest(8801L, 8811L, "SINGLE_CHOICE", title, 2026, "EASY",
        "MANUAL", "mysql-gate", List.of(stem), List.of(sub), List.of());
  }

  private String canonicalJson() {
    String sourcePath = importRoot.resolve("source.pdf").toString().replace("\\", "\\\\");
    return """
        {"schemaVersion":"question-import/1.0","recordType":"QUESTION_PREFLIGHT",
         "importStatus":"PREFLIGHT","rightsStatus":"UNVERIFIED",
         "qualificationCode":"MYSQL_GATE_QUAL","subjectCode":"MYSQL_GATE_SUBJECT",
         "quality":{"importEligible":true,"validationErrors":[]},
         "paper":{"paperKind":"REAL_PAPER","sourceFileName":"source.pdf","yearNo":2020},
         "sourceTrace":{"documentSha256":"%s","locator":"page:1:question:1",
           "questionNo":1,"sourcePath":"%s"},
         "question":{"externalKey":"MYSQL-GATE-1","contentHash":"%s",
           "questionType":"SINGLE_CHOICE","title":"imported","yearNo":2020,
           "difficulty":"EASY","knowledgeBindings":[],
           "source":{"sourceType":"SOURCE_DOCUMENT_IMPORT","sourceRef":"sha256:%s",
             "syllabusBasis":"UNVERIFIED_PREFLIGHT","copyrightStatus":"UNVERIFIED",
             "usageRestriction":"local development/internal acceptance only; publication prohibited"},
           "stems":[{"stemKey":"S1","stemType":"MAIN_STEM","content":"import stem",
             "resourceId":null,"sortOrder":1}],
           "subQuestions":[{"subQuestionNo":1,"subQuestionType":"SINGLE_CHOICE",
             "content":"import sub","score":1.00,"answerFormat":"OPTION_KEYS",
             "gradingMode":"AUTO","sortOrder":1,"knowledgeBindings":[],
             "options":[{"optionKey":"A","content":"yes","sortOrder":1},
                        {"optionKey":"B","content":"no","sortOrder":2}],
             "answers":[{"answerKind":"OPTION_KEYS","optionKeys":["A"]}],
             "solutions":[{"solutionType":"REFERENCE_ANSWER","content":"why","sortOrder":1}],
             "scoringRules":[{"ruleType":"AUTO","ruleJson":{"mode":"EXACT"},"sortOrder":1}],
             "scoringPoints":[]}]}}
        """.formatted("d".repeat(64), sourcePath, "e".repeat(64), "d".repeat(64))
        .replaceAll("\\R", "");
  }

  private QuestionWorkflowRequest workflow(long rowVersion) {
    return new QuestionWorkflowRequest(UUID.randomUUID(), rowVersion, null);
  }

  private PlatformPrincipal user(long id) {
    return new PlatformPrincipal(id, "gate-" + id, "gate-session-" + id,
        List.of(), PERMISSIONS, 1);
  }

  private static void requireTestSchema(String schema) {
    if (schema == null || !schema.matches("question_admin_[0-9]+_[0-9]+_test")) {
      throw new IllegalStateException("Refusing non-test schema: " + schema);
    }
  }

  private record Mysql(String baseUrl, String username, String password) {
    static Mysql fromEnvironment() {
      return new Mysql(value("MYSQL_TEST_URL",
          "jdbc:mysql://127.0.0.1:33307/ai_exam_platform_test?useUnicode=true&characterEncoding=utf8"
              + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"),
          value("MYSQL_TEST_USERNAME", "root"), value("MYSQL_TEST_PASSWORD", ""));
    }

    String serverUrl() {
      int query = baseUrl.indexOf('?');
      String plain = query < 0 ? baseUrl : baseUrl.substring(0, query);
      String suffix = query < 0 ? "" : baseUrl.substring(query);
      return plain.substring(0, plain.lastIndexOf('/') + 1) + suffix;
    }

    String url(String schema) {
      requireTestSchema(schema);
      int query = baseUrl.indexOf('?');
      String plain = query < 0 ? baseUrl : baseUrl.substring(0, query);
      String suffix = query < 0 ? "" : baseUrl.substring(query);
      return plain.substring(0, plain.lastIndexOf('/') + 1) + schema + suffix;
    }

    void createSchema(String schema) throws Exception {
      requireTestSchema(schema);
      try (Connection connection = DriverManager.getConnection(serverUrl(), username, password);
           Statement statement = connection.createStatement()) {
        statement.execute("CREATE DATABASE `" + schema
            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      }
    }

    void dropSchema(String schema) throws Exception {
      requireTestSchema(schema);
      try (Connection connection = DriverManager.getConnection(serverUrl(), username, password);
           Statement statement = connection.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
      }
    }

    private static String value(String name, String fallback) {
      String value = System.getenv(name);
      return value == null || value.isBlank() ? fallback : value;
    }
  }
}
