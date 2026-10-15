package com.aiexam.app.migration.v9;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aiexam.app.AiExamPlatformApplication;
import com.aiexam.app.migration.V9RuntimeSchemaValidator;
import com.aiexam.app.migration.deploymentcontrol.AttemptState;
import com.aiexam.app.migration.deploymentcontrol.DeploymentControlClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.assertj.core.api.SoftAssertions;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V9MysqlJavaMigrationGateTest {
  private String baseUrl;
  private String username;
  private String password;
  private String suffix;
  private String targetSchema;
  private String controlSchema;

  @BeforeAll
  void requireMysql8() throws Exception {
    assumeTrue("true".equalsIgnoreCase(System.getenv("V9_MYSQL_GATE")));
    String configured = required("MYSQL_TEST_URL");
    username = required("MYSQL_TEST_USERNAME");
    password = required("MYSQL_TEST_PASSWORD");
    int query = configured.indexOf('?');
    String options = query >= 0 ? configured.substring(query) : "";
    String withoutOptions = query >= 0 ? configured.substring(0, query) : configured;
    int slash = withoutOptions.lastIndexOf('/');
    baseUrl = withoutOptions.substring(0, slash + 1) + options;
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    targetSchema = "ai_exam_v9_empty_" + suffix + "_test";
    controlSchema = "ai_exam_v9_control_" + suffix + "_test";
    try (Connection connection = serverConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
      assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);
      execute(connection, "CREATE DATABASE `" + targetSchema
          + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      execute(connection, "CREATE DATABASE `" + controlSchema
          + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    }
  }

  @AfterAll
  void cleanSchemas() throws Exception {
    if (baseUrl == null) {
      return;
    }
    try (Connection connection = serverConnection()) {
      execute(connection, "DROP DATABASE IF EXISTS `" + targetSchema + "`");
      execute(connection, "DROP DATABASE IF EXISTS `" + controlSchema + "`");
    }
  }

  @Test
  void emptyMysqlV1ThroughV9HasOneJdbcHistoryAndNoSecondMigration() throws Exception {
    Flyway v8 = Flyway.configure().dataSource(url(targetSchema), username, password)
        .locations("filesystem:src/main/resources/db/migration")
        .target("8").baselineOnMigrate(false).cleanDisabled(true).load();
    assertThat(v8.migrate().migrationsExecuted).isEqualTo(8);
    try (Connection v8Only = targetConnection()) {
      assertThatThrownBy(() -> new V9RuntimeSchemaValidator().validate(v8Only))
          .hasMessageContaining("Flyway history is not the unique V1-V9 JDBC baseline");
    }

    V9ManifestBundle bundle = V9ManifestBundle.load();
    UUID approvalId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    long fencingToken = 101;
    String artifact = "sha256:" + "c".repeat(64);
    byte[] nonce = "mysql-gate-single-use-nonce".getBytes(
        java.nio.charset.StandardCharsets.UTF_8);
    Instant now = Instant.parse("2026-07-14T02:30:00Z");
    var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String principal;
    try (Connection connection = targetConnection(); Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT CURRENT_USER()")) {
      result.next();
      principal = result.getString(1);
    }
    PersistentContractLedger ledger = new PersistentContractLedger(
        url(controlSchema), username, password);
    ledger.initialize();
    String envelope = signedEnvelope(keyPair, approvalId, nonce, bundle, artifact, targetSchema,
        "d".repeat(64), principal, now);
    ledger.insertApproval(approvalId, envelope, JcsCanonicalizer.sha256Hex(nonce), artifact);
    ledger.insertAttempt(approvalId, attemptId, fencingToken, AttemptState.APPROVED, 1,
        bundle, artifact);
    V9BackupEvidence backup = new V9BackupEvidence("backup-test", now.minusSeconds(30),
        "1".repeat(64), "2".repeat(64), "restore-drill-test", now.minusSeconds(10),
        "3".repeat(64), approvalId);
    V9DeploymentCoordinator coordinator = new V9DeploymentCoordinator(ledger,
        url(targetSchema), username, password, "d".repeat(64), artifact,
        Clock.fixed(now, ZoneOffset.UTC));

    coordinator.execute(approvalId, attemptId, fencingToken, envelope, nonce,
        keyPair.getPublic(), true, true, backup);

    assertThat(ledger.approvalReadCount()).isPositive();
    try (Connection verification = targetConnection()) {
      assertThat(queryLong(verification, """
          SELECT COUNT(*) FROM flyway_schema_history
          WHERE version='9' AND type='JDBC' AND success=1
          """)).isEqualTo(1);
      assertThat(queryLong(verification,
          "SELECT COUNT(*) FROM exam_qualification WHERE deleted=0")).isEqualTo(20);
      assertThat(queryLong(verification,
          "SELECT COUNT(*) FROM exam_subject WHERE deleted=0")).isEqualTo(45);
      new V9RuntimeSchemaValidator().validate(verification);
      execute(verification, """
          INSERT INTO exam_qualification
            (id,qualification_code,domain_code,qualification_name,official_name,level_code,
             source_url,verified_at,verification_status,catalog_version,catalog_status,status,
             sort_order,created_at,updated_at,deleted)
          VALUES
            (990001,'RUNTIME_MANAGED','SOFT_EXAM','运行时维护资格','运行时维护资格',
             'INTERMEDIATE',NULL,NULL,'THIRD_PARTY_CANDIDATE','2026-CUSTOM','ACTIVE',
             'DRAFT',990001,NOW(3),NOW(3),0)
          """);
      execute(verification, """
          INSERT INTO exam_subject
            (id,qualification_id,subject_code,subject_name,official_name,subject_type,
             sort_order,status,created_at,updated_at,deleted)
          VALUES
            (990001,990001,'RUNTIME_APPLICATION','运行时应用技术','运行时应用技术',
             'APPLICATION',1,'DRAFT',NOW(3),NOW(3),0)
          """);
      assertThatCode(() -> new V9RuntimeSchemaValidator().validate(verification))
          .doesNotThrowAnyException();

      execute(verification,
          "ALTER TABLE exam_qualification DROP CHECK ck_exam_qualification_domain");
      execute(verification, "UPDATE exam_qualification SET domain_code='SOFT'");
      execute(verification, """
          ALTER TABLE exam_qualification
          ADD CONSTRAINT ck_exam_qualification_domain CHECK (domain_code = 'SOFT')
          """);
      RuntimeProbeResult shortenedDomainLiteral = runRuntimeProbe();
      execute(verification,
          "ALTER TABLE exam_qualification DROP CHECK ck_exam_qualification_domain");
      execute(verification, "UPDATE exam_qualification SET domain_code='SOFT_EXAM'");
      execute(verification, """
          ALTER TABLE exam_qualification
          ADD CONSTRAINT ck_exam_qualification_domain CHECK (domain_code = 'SOFT_EXAM')
          """);

      execute(verification,
          "ALTER TABLE exam_qualification DROP CHECK ck_exam_qualification_operational_status");
      execute(verification, """
          ALTER TABLE exam_qualification
          ADD CONSTRAINT ck_exam_qualification_operational_status
          CHECK ((status IN ('DRAFT','ENABLED','DISABLED')
                 AND catalog_status <> 'RETIRED') OR status <> 'ENABLED')
          """);
      RuntimeProbeResult changedLogicalGrouping = runRuntimeProbe();
      execute(verification,
          "ALTER TABLE exam_qualification DROP CHECK ck_exam_qualification_operational_status");
      execute(verification, """
          ALTER TABLE exam_qualification
          ADD CONSTRAINT ck_exam_qualification_operational_status
          CHECK (status IN ('DRAFT','ENABLED','DISABLED')
                 AND NOT (catalog_status = 'RETIRED' AND status = 'ENABLED'))
          """);

      execute(verification, "ALTER TABLE exam_subject DROP CHECK ck_exam_subject_type");
      execute(verification, """
          ALTER TABLE exam_subject
          ADD CONSTRAINT ck_exam_subject_type CHECK (subject_type IS NOT NULL)
          """);
      RuntimeProbeResult looseCheck = runRuntimeProbe();
      execute(verification, "ALTER TABLE exam_subject DROP CHECK ck_exam_subject_type");
      execute(verification, """
          ALTER TABLE exam_subject
          ADD CONSTRAINT ck_exam_subject_type
          CHECK (subject_type IN ('COMPREHENSIVE','CASE','ESSAY','APPLICATION'))
          """);

      execute(verification, "ALTER TABLE exam_subject DROP INDEX idx_exam_subject_tree");
      execute(verification, """
          CREATE INDEX idx_exam_subject_tree ON exam_subject(status,id)
          """);
      RuntimeProbeResult wrongIndex = runRuntimeProbe();
      execute(verification, "ALTER TABLE exam_subject DROP INDEX idx_exam_subject_tree");
      execute(verification, """
          CREATE INDEX idx_exam_subject_tree
          ON exam_subject(qualification_id,status,sort_order,id)
          """);

      assertSoftly(softly -> {
        assertCheckClauseNormalizationVectors(softly);
        assertRuntimeProbeRejected(softly, shortenedDomainLiteral,
            "CHECK definition drift: exam_qualification.ck_exam_qualification_domain");
        assertRuntimeProbeRejected(softly, changedLogicalGrouping,
            "CHECK definition drift: exam_qualification."
                + "ck_exam_qualification_operational_status");
        assertRuntimeProbeRejected(softly, looseCheck,
            "CHECK definition drift: exam_subject.ck_exam_subject_type");
        assertRuntimeProbeRejected(softly, wrongIndex,
            "index definition drift: exam_subject.idx_exam_subject_tree");
      });
      assertThatCode(() -> new V9RuntimeSchemaValidator().validate(verification))
          .doesNotThrowAnyException();
    }
    assertThat(ledger.getAttemptStrong(approvalId, attemptId).state())
        .isEqualTo(AttemptState.COMPLETED);
    Flyway secondRun = V9MigrationOnlyRunner.build(url(targetSchema), username, password,
        (connection, manifest) -> {
          throw new AssertionError("a completed V9 must not execute twice");
        });
    secondRun.validate();
    assertThat(secondRun.migrate().migrationsExecuted).isZero();
  }

  private RuntimeProbeResult runRuntimeProbe() throws Exception {
    Path output = Files.createTempFile("v9-runtime-validator-", ".log");
    try {
      ProcessBuilder builder = new ProcessBuilder(
          javaExecutable(),
          "-Dspring.profiles.active=mysql",
          "-cp",
          System.getProperty("java.class.path"),
          RuntimeValidatorProbe.class.getName(),
          "--server.port=0",
          "--spring.main.banner-mode=off").redirectErrorStream(true).redirectOutput(output.toFile());
      Map<String, String> environment = builder.environment();
      environment.put("MYSQL_URL", url(targetSchema));
      environment.put("MYSQL_USERNAME", username);
      environment.put("MYSQL_PASSWORD", password);
      environment.put("MYSQL_TEST_URL", url(targetSchema));
      environment.put("MYSQL_TEST_USERNAME", username);
      environment.put("MYSQL_TEST_PASSWORD", password);
      Process process = builder.start();
      boolean finished = process.waitFor(45, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
      return new RuntimeProbeResult(finished, process.exitValue(),
          Files.readString(output, StandardCharsets.UTF_8));
    } finally {
      Files.deleteIfExists(output);
    }
  }

  private void assertRuntimeProbeRejected(SoftAssertions softly, RuntimeProbeResult result,
      String expectedFailure) {
    softly.assertThat(result.finished()).as("runtime probe must terminate").isTrue();
    softly.assertThat(result.exitCode()).as("runtime probe exit code").isNotZero();
    softly.assertThat(result.output())
        .contains(expectedFailure)
        .doesNotContain("V9_RUNTIME_EVIDENCE_REGISTERED=true", "V9_RUNTIME_PORT_STARTED=");
  }

  private void assertCheckClauseNormalizationVectors(SoftAssertions softly) {
    softly.assertThat(normalizeCheckClause("domain_code = 'SOFT_EXAM'"))
        .as("different string literals must not collide")
        .isNotEqualTo(normalizeCheckClause("domain_code = 'SOFT'"));
    softly.assertThat(normalizeCheckClause("domain_code = 'SOFT_EXAM'"))
        .as("string literal case must be preserved")
        .isNotEqualTo(normalizeCheckClause("domain_code = 'soft_exam'"));
    softly.assertThat(normalizeCheckClause("""
        status IN ('DRAFT','ENABLED','DISABLED')
        AND (catalog_status <> 'RETIRED' OR status <> 'ENABLED')
        """))
        .as("different logical grouping must not collide")
        .isNotEqualTo(normalizeCheckClause("""
            (status IN ('DRAFT','ENABLED','DISABLED')
             AND catalog_status <> 'RETIRED') OR status <> 'ENABLED'
            """));
    softly.assertThat(normalizeCheckClause("""
        (( `domain_code` = _utf8mb4'SOFT_EXAM' ))
        """))
        .as("database wrapper and insignificant whitespace are equivalent")
        .isEqualTo(normalizeCheckClause("domain_code='SOFT_EXAM'"));
  }

  private String normalizeCheckClause(String clause) {
    try {
      var method = V9RuntimeSchemaValidator.class.getDeclaredMethod(
          "normalizeCheckClause", String.class);
      method.setAccessible(true);
      return (String) method.invoke(null, clause);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("Unable to invoke CHECK canonicalizer", exception);
    }
  }

  private static String javaExecutable() {
    String executable = System.getProperty("os.name").toLowerCase().contains("win")
        ? "java.exe" : "java";
    return Path.of(System.getProperty("java.home"), "bin", executable).toString();
  }

  public static final class RuntimeValidatorProbe {
    private RuntimeValidatorProbe() {
    }

    public static void main(String[] args) {
      try (var context = SpringApplication.run(AiExamPlatformApplication.class, args)) {
        System.out.println("V9_RUNTIME_EVIDENCE_REGISTERED="
            + context.containsBean("v9RuntimeSchemaValidated"));
        if (context instanceof ServletWebServerApplicationContext webContext) {
          System.out.println("V9_RUNTIME_PORT_STARTED=" + webContext.getWebServer().getPort());
        }
      }
    }
  }

  private record RuntimeProbeResult(boolean finished, int exitCode, String output) {
  }

  @Test
  void persistentLedgerProvidesUniqueOperationIdCasConcurrencyAndRestart() throws Exception {
    PersistentContractLedger first = new PersistentContractLedger(
        url(controlSchema), username, password);
    first.initialize();
    V9ManifestBundle bundle = V9ManifestBundle.load();
    UUID approvalId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    first.insertAttempt(approvalId, attemptId, 202, AttemptState.FLYWAY_STARTED, 1,
        bundle, "sha256:" + "f".repeat(64));

    UUID operationId = UUID.randomUUID();
    DeploymentControlClient.TransitionCommand command = new DeploymentControlClient.TransitionCommand(
        approvalId, attemptId, AttemptState.FLYWAY_STARTED, AttemptState.EXPAND_COMPLETED,
        1, 202, operationId, "a".repeat(64));
    assertThat(first.transitionAttempt(command).state()).isEqualTo(AttemptState.EXPAND_COMPLETED);
    assertThat(first.transitionAttempt(command).state()).isEqualTo(AttemptState.EXPAND_COMPLETED);
    assertThatThrownBy(() -> first.transitionAttempt(new DeploymentControlClient.TransitionCommand(
        approvalId, attemptId, AttemptState.FLYWAY_STARTED, AttemptState.DML_STARTED,
        1, 202, operationId, "b".repeat(64)))).isInstanceOf(IllegalStateException.class);

    PersistentContractLedger restarted = new PersistentContractLedger(
        url(controlSchema), username, password);
    assertThat(restarted.getAttemptStrong(approvalId, attemptId).state())
        .isEqualTo(AttemptState.EXPAND_COMPLETED);

    UUID secondApproval = UUID.randomUUID();
    UUID secondAttempt = UUID.randomUUID();
    restarted.insertAttempt(secondApproval, secondAttempt, 303, AttemptState.FLYWAY_STARTED, 1,
        bundle, "sha256:" + "1".repeat(64));
    AtomicInteger successes = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      for (int index = 0; index < 2; index++) {
        executor.submit(() -> {
          try {
            start.await();
            restarted.transitionAttempt(new DeploymentControlClient.TransitionCommand(
                secondApproval, secondAttempt, AttemptState.FLYWAY_STARTED,
                AttemptState.EXPAND_COMPLETED, 1, 303, UUID.randomUUID(), "c".repeat(64)));
            successes.incrementAndGet();
          } catch (Exception ignored) {
          }
        });
      }
      start.countDown();
    } finally {
      executor.shutdown();
      assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
    assertThat(successes).hasValue(1);
  }

  @Test
  void persistentLedgerImplementsApprovalNonceAndFailurePortsAcrossRestart() throws Exception {
    PersistentContractLedger first = new PersistentContractLedger(
        url(controlSchema), username, password);
    first.initialize();
    V9ManifestBundle bundle = V9ManifestBundle.load();
    UUID approvalId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    String nonceHash = "7".repeat(64);
    String artifact = "sha256:" + "8".repeat(64);
    first.insertApproval(approvalId, "signed-test-envelope", nonceHash, artifact);
    first.insertAttempt(approvalId, attemptId, 404, AttemptState.LOCKED, 2,
        bundle, artifact);

    assertThat(first.getApproval(approvalId).nonceState()).isEqualTo("ISSUED");
    UUID consumeOperation = UUID.randomUUID();
    DeploymentControlClient.NonceCommand consume = new DeploymentControlClient.NonceCommand(
        approvalId, nonceHash, attemptId, 404, 2, consumeOperation);
    assertThat(first.consumeNonce(consume).state()).isEqualTo(AttemptState.NONCE_CONSUMED);
    assertThat(first.consumeNonce(consume).state()).isEqualTo(AttemptState.NONCE_CONSUMED);
    assertThat(first.getApproval(approvalId).nonceState()).isEqualTo("CONSUMED");
    assertThatThrownBy(() -> first.consumeNonce(new DeploymentControlClient.NonceCommand(
        approvalId, "9".repeat(64), attemptId, 404, 2, consumeOperation)))
        .isInstanceOf(IllegalStateException.class);

    PersistentContractLedger restarted = new PersistentContractLedger(
        url(controlSchema), username, password);
    DeploymentControlClient.AttemptRecord consumed = restarted.getAttemptStrong(
        approvalId, attemptId);
    UUID failureOperation = UUID.randomUUID();
    UUID failureId = UUID.randomUUID();
    DeploymentControlClient.FailureCommand failure = new DeploymentControlClient.FailureCommand(
        approvalId, attemptId, consumed.state(), AttemptState.FAILED_B_RESTORE_REQUIRED,
        consumed.stateVersion(), 404, failureOperation, failureId, "a".repeat(64));
    assertThat(restarted.recordFailure(failure).failureId()).isEqualTo(failureId);
    assertThat(restarted.recordFailure(failure).state())
        .isEqualTo(AttemptState.FAILED_B_RESTORE_REQUIRED);
  }

  @Test
  void v8UpgradePreservesHighPmOperationsAndV6V8RepresentativeRows() throws Exception {
    String schema = "ai_exam_v9_upgrade_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_test";
    createSchema(schema);
    try {
      Flyway v8 = Flyway.configure().dataSource(url(schema), username, password)
          .locations("filesystem:src/main/resources/db/migration")
          .target("8").baselineOnMigrate(false).cleanDisabled(true).load();
      assertThat(v8.migrate().migrationsExecuted).isEqualTo(8);
      String questionTypeBefore;
      try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
        execute(connection, """
            UPDATE exam_qualification
            SET qualification_name='运营展示名',status='DISABLED',sort_order=777
            WHERE qualification_code='HIGH_PM'
            """);
        execute(connection, """
            INSERT INTO practice_exam_batch
              (id,batch_code,qualification_id,exam_year,region_code,region_name,login_open_at,
               login_close_at,scheduled_start_at,status,config_version,version,created_by,
               updated_by,created_at,updated_at)
            VALUES (9001,'V9-UPGRADE-BATCH',1,2026,'CN','全国',NOW(3),DATE_ADD(NOW(3),INTERVAL 1 DAY),
                    DATE_ADD(NOW(3),INTERVAL 2 DAY),'DRAFT',1,0,1,1,NOW(3),NOW(3))
            """);
        execute(connection, """
            INSERT INTO essay_knowledge_source
              (id,source_code,source_name,scope_type,owner_id,qualification_id,subject_id,
               source_type,copyright_type,authorization_basis,source_url,status,created_at,
               updated_at,deleted)
            VALUES (9001,'V9-UPGRADE-SOURCE','升级保护','PUBLIC',NULL,1,NULL,'OFFICIAL',
                    'AUTHORIZED','test','https://example.test/source','ENABLED',NOW(3),NOW(3),0)
            """);
        questionTypeBefore = scalar(connection, """
            SELECT column_type FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='q_question'
              AND column_name='question_type'
            """);
      }

      runAuthorizedMigration(schema);

      try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
        assertThat(queryLong(connection, """
            SELECT COUNT(*) FROM exam_qualification
            WHERE id=1 AND qualification_code='HIGH_PM' AND qualification_name='运营展示名'
              AND status='DISABLED' AND sort_order=777
            """)).isEqualTo(1);
        assertThat(queryLong(connection,
            "SELECT COUNT(*) FROM practice_exam_batch WHERE id=9001 AND batch_code='V9-UPGRADE-BATCH'"))
            .isEqualTo(1);
        assertThat(queryLong(connection,
            "SELECT COUNT(*) FROM essay_knowledge_source WHERE id=9001 AND source_code='V9-UPGRADE-SOURCE'"))
            .isEqualTo(1);
        assertThat(scalar(connection, """
            SELECT column_type FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='q_question'
              AND column_name='question_type'
            """)).isEqualTo(questionTypeBefore);
        assertThat(queryLong(connection, """
            SELECT COUNT(*) FROM exam_qualification
            WHERE level_code IN ('HIGH','MIDDLE','LOW')
            """)).isZero();
        assertThat(queryLong(connection, """
            SELECT COUNT(*) FROM exam_subject
            WHERE subject_type NOT IN ('COMPREHENSIVE','CASE','ESSAY','APPLICATION')
            """)).isZero();
        new com.aiexam.app.migration.V9RuntimeSchemaValidator().validate(connection);
        execute(connection, """
            UPDATE exam_qualification SET official_name='漂移'
            WHERE qualification_code='HIGH_PM'
            """);
        assertThatCode(() ->
            new com.aiexam.app.migration.V9RuntimeSchemaValidator().validate(connection))
            .doesNotThrowAnyException();
        execute(connection, """
            UPDATE exam_qualification SET official_name='信息系统项目管理师'
            WHERE qualification_code='HIGH_PM'
            """);
        execute(connection, "ALTER TABLE exam_subject DROP INDEX idx_exam_subject_tree");
        assertThatThrownBy(() ->
            new com.aiexam.app.migration.V9RuntimeSchemaValidator().validate(connection))
            .isInstanceOf(V9MigrationException.class);
      }
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void partialHardenStateFailsClosedInsteadOfSkippingRemainingObjects() throws Exception {
    String schema = "ai_exam_v9_failure_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_test";
    createSchema(schema);
    try {
      Flyway.configure().dataSource(url(schema), username, password)
          .locations("filesystem:src/main/resources/db/migration")
          .target("8").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
      V9ManifestBundle bundle = V9ManifestBundle.load();
      try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
        execute(connection, new String(bundle.resourceBytes(
            "db/migration-payload/v9/01_qualification_expand.sql"),
            java.nio.charset.StandardCharsets.UTF_8));
        execute(connection, """
            ALTER TABLE exam_qualification
            ADD CONSTRAINT ck_exam_qualification_domain CHECK (domain_code = 'SOFT_EXAM')
            """);
      }

      assertThatThrownBy(() -> runDirectMigration(schema, bundle))
          .hasRootCauseInstanceOf(V9MigrationException.class);
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void fullyNamedButStructurallyWrongExpandColumnsFailBeforeDataPhase() throws Exception {
    String schema = "ai_exam_v9_failure_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_test";
    createSchema(schema);
    try {
      Flyway.configure().dataSource(url(schema), username, password)
          .locations("filesystem:src/main/resources/db/migration")
          .target("8").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
      try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
        execute(connection, """
            ALTER TABLE exam_qualification
              ADD COLUMN domain_code VARCHAR(32) NULL AFTER qualification_code,
              ADD COLUMN official_name VARCHAR(96) NULL AFTER qualification_name,
              ADD COLUMN source_url VARCHAR(511) NULL AFTER level_code,
              ADD COLUMN verified_at DATETIME(3) NULL AFTER source_url,
              ADD COLUMN verification_status VARCHAR(32) NULL AFTER verified_at,
              ADD COLUMN catalog_version VARCHAR(32) NULL AFTER verification_status,
              ADD COLUMN catalog_status VARCHAR(24) NULL AFTER catalog_version
            """);
      }

      AuthorizedRun run = prepareAuthorizedMigration(schema);
      assertThatThrownBy(run::execute).isInstanceOf(V9MigrationException.class);
      assertThat(run.ledger().getAttemptStrong(run.approvalId(), run.attemptId()).state())
          .isEqualTo(AttemptState.FAILED_B_RESTORE_REQUIRED);
      try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
        assertThat(queryLong(connection,
            "SELECT COUNT(*) FROM exam_qualification WHERE domain_code IS NOT NULL")).isZero();
      }
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void legacyRuleWithoutApprovedManifestFailsBeforeFirstPermanentDdl() throws Exception {
    String schema = "ai_exam_v9_legacy_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_test";
    createSchema(schema);
    try {
      Flyway.configure().dataSource(url(schema), username, password)
          .locations("filesystem:src/main/resources/db/migration")
          .target("8").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
      try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
        execute(connection, """
            INSERT INTO exam_rule
              (id,qualification_id,subject_id,rule_code,rule_name,rule_version,rule_json,status,
               effective_at,created_at,updated_at,deleted)
            VALUES (9101,1,1,'LEGACY_SUBJECT_RULE','历史规则','v1',JSON_OBJECT('schemaVersion','0.9'),
                    'DRAFT',NULL,NOW(3),NOW(3),0)
            """);
      }

      assertThatThrownBy(() -> runAuthorizedMigration(schema))
          .isInstanceOf(V9MigrationException.class);
      try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
        assertThat(queryLong(connection, """
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema=DATABASE() AND (
              (table_name='exam_qualification' AND column_name='domain_code') OR
              (table_name='exam_subject' AND column_name='official_name') OR
              (table_name='exam_rule' AND column_name='period_code'))
            """)).isZero();
      }
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void nonceConsumedButFlywayStartFailurePersistsPreFlywayAbortedWithoutDdl() throws Exception {
    String schema = "ai_exam_v9_failure_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_test";
    createSchema(schema);
    try {
      Flyway.configure().dataSource(url(schema), username, password)
          .locations("filesystem:src/main/resources/db/migration")
          .target("8").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
      AuthorizedRun run = prepareAuthorizedMigration(schema);
      run.ledger().failTransitionTo(AttemptState.FLYWAY_STARTED);

      assertThatThrownBy(run::execute).isInstanceOf(V9MigrationException.class);
      assertThat(run.ledger().getAttemptStrong(run.approvalId(), run.attemptId()).state())
          .isEqualTo(AttemptState.PRE_FLYWAY_ABORTED);
      assertThat(run.ledger().getApproval(run.approvalId()).nonceState()).isEqualTo("CONSUMED");
      try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
        assertThat(queryLong(connection,
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version='9'")).isZero();
        assertThat(queryLong(connection, """
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='exam_qualification'
              AND column_name='domain_code'
            """)).isZero();
      }
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void dmlStartedMarkerFailureIsRestoreRequiredAndEmitsNoDataWrites() throws Exception {
    String schema = "ai_exam_v9_failure_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_test";
    createSchema(schema);
    try {
      Flyway.configure().dataSource(url(schema), username, password)
          .locations("filesystem:src/main/resources/db/migration")
          .target("8").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
      AuthorizedRun run = prepareAuthorizedMigration(schema);
      run.ledger().failTransitionTo(AttemptState.DML_STARTED);

      assertThatThrownBy(run::execute).isInstanceOf(V9MigrationException.class);
      assertThat(run.ledger().getAttemptStrong(run.approvalId(), run.attemptId()).state())
          .isEqualTo(AttemptState.FAILED_B_RESTORE_REQUIRED);
      try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
        assertThat(queryLong(connection,
            "SELECT COUNT(*) FROM exam_qualification WHERE domain_code IS NOT NULL")).isZero();
        assertThat(queryLong(connection,
            "SELECT COUNT(*) FROM exam_subject WHERE official_name IS NOT NULL")).isZero();
      }
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void ledgerFenceRejectsStaleFenceAndLostMysqlGuardLock() throws Exception {
    V9ManifestBundle bundle = V9ManifestBundle.load();
    UUID approvalId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    String artifact = "sha256:" + "4".repeat(64);
    PersistentContractLedger ledger = new PersistentContractLedger(
        url(controlSchema), username, password);
    ledger.initialize();
    ledger.insertAttempt(approvalId, attemptId, 808, AttemptState.FLYWAY_STARTED, 1,
        bundle, artifact);
    try (Connection guard = targetConnection()) {
      String lockName = "aiexam:v9:fence-test-" + suffix;
      assertThat(queryLong(guard, "SELECT GET_LOCK('" + lockName + "',0)")).isEqualTo(1);
      long guardId = queryLong(guard, "SELECT CONNECTION_ID()");
      String principal = scalar(guard, "SELECT CURRENT_USER()");
      V9ExecutionApproval approval = new V9ExecutionApproval(approvalId,
          bundle.fullManifestSha256(), bundle.flywayChecksum(), artifact, targetSchema,
          "d".repeat(64), principal, Instant.now(), Instant.now().plusSeconds(60),
          "e".repeat(64), "mysql-gate-key");
      V9LedgerFence stale = new V9LedgerFence(ledger, approvalId, attemptId, 809, 1,
          guard, lockName, guardId, approval);
      assertThatThrownBy(() -> stale.verifyBoundary("STALE", AttemptState.FLYWAY_STARTED))
          .isInstanceOfSatisfying(V9MigrationException.class,
              failure -> assertThat(failure.errorCode()).isEqualTo(V9ErrorCode.V9_FENCE_REJECTED));

      V9LedgerFence current = new V9LedgerFence(ledger, approvalId, attemptId, 808, 1,
          guard, lockName, guardId, approval);
      assertThat(queryLong(guard, "SELECT RELEASE_LOCK('" + lockName + "')")).isEqualTo(1);
      assertThatThrownBy(() -> current.verifyBoundary("LOST", AttemptState.FLYWAY_STARTED))
          .isInstanceOfSatisfying(V9MigrationException.class,
              failure -> assertThat(failure.errorCode()).isEqualTo(
                  V9ErrorCode.V9_GUARD_LOCK_LOST));
    }
  }

  @Test
  void reviewedLegacyManifestMigratesNullEffectiveAtWithoutGuessingDates() throws Exception {
    String schema = "ai_exam_v9_legacy_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_test";
    createSchema(schema);
    try {
      Flyway.configure().dataSource(url(schema), username, password)
          .locations("filesystem:src/main/resources/db/migration")
          .target("8").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
      String targetJson = """
          {"schemaVersion":"1.0","ruleVersion":"2026-H1-v1",
           "qualificationCode":"HIGH_PM","examCycle":"2026","periodCode":"2026-H1",
           "effectiveFrom":"2026-01-01T00:00:00+08:00",
           "effectiveTo":"2026-07-01T00:00:00+08:00","enabled":false,
           "totalDurationMinutes":150,"deadlinePolicy":"INDEPENDENT_SUBJECTS",
           "subjects":[{"subjectCode":"COMPREHENSIVE_KNOWLEDGE",
             "subjectType":"COMPREHENSIVE","displayOrder":1,"durationMinutes":150,
             "fullScore":75,"passScore":45,"questionTypes":["SINGLE_CHOICE"],
             "earliestSubmitMinutes":120,"forceSubmitMinutes":150,
             "feedbackPolicy":"AFTER_EXAM"}],
           "verification":{"sourceUrl":"https://official.example/rule",
             "verifiedAt":"2026-01-01T00:00:00+08:00","sourceTitle":"official notice",
             "sourceHash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}
          """;
      String legacyJson = "{\"schemaVersion\":\"0.9\",\"targetRule\":" + targetJson + "}";
      String contentFingerprint = legacyFingerprint(9201, 1, 1L, "LEGACY_SUBJECT_RULE", "v1",
          "DRAFT", null, legacyJson);
      V9ManifestBundle bundle;
      try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO exam_rule
              (id,qualification_id,subject_id,rule_code,rule_name,rule_version,rule_json,status,
               effective_at,created_at,updated_at,deleted)
            VALUES (9201,1,1,'LEGACY_SUBJECT_RULE','历史规则','v1',
                    CAST(? AS JSON),
                    'DRAFT',NULL,NOW(3),NOW(3),0)
            """)) {
          statement.setString(1, legacyJson);
          statement.executeUpdate();
        }
        execute(connection, """
            CREATE TABLE soft_exam_v9_legacy_manifest_header (
              manifest_version VARCHAR(64) NOT NULL,
              source_snapshot_hash CHAR(64) NOT NULL,
              target_schema_version VARCHAR(8) NOT NULL,
              generated_at DATETIME(3) NOT NULL,
              manifest_sha256 CHAR(64) NOT NULL,
              reviewer_arch VARCHAR(64) NOT NULL,
              reviewer_data VARCHAR(64) NOT NULL,
              PRIMARY KEY (manifest_version)
            ) ENGINE=InnoDB
            """);
        execute(connection, """
            CREATE TABLE soft_exam_v9_legacy_manifest_mapping (
              source_rule_id BIGINT NOT NULL,
              source_rule_code VARCHAR(96) NOT NULL,
              source_qualification_id BIGINT NOT NULL,
              source_subject_id BIGINT NULL,
              source_schema_version VARCHAR(32) NOT NULL,
              content_fingerprint CHAR(64) NOT NULL,
              expected_effective_at DATETIME(3) NULL,
              target_qualification_id BIGINT NOT NULL,
              target_period_code VARCHAR(32) NOT NULL,
              target_effective_from DATETIME(3) NOT NULL,
              target_effective_to DATETIME(3) NOT NULL,
              target_rule_code VARCHAR(96) NOT NULL,
              target_rule_version VARCHAR(32) NOT NULL,
              merge_group VARCHAR(64) NOT NULL,
              merge_order INT NOT NULL,
              target_rule_json JSON NOT NULL,
              converted_rule_json JSON NOT NULL,
              converter_id VARCHAR(64) NOT NULL,
              converter_version VARCHAR(32) NOT NULL,
              PRIMARY KEY (source_rule_id)
            ) ENGINE=InnoDB
            """);
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO soft_exam_v9_legacy_manifest_mapping VALUES
              (9201,'LEGACY_SUBJECT_RULE',1,1,'0.9',?,NULL,1,'2026-H1',
               '2026-01-01 00:00:00.000','2026-07-01 00:00:00.000','EXAM_PERIOD_RULE',
               '2026-H1-v1','GROUP-1',1,CAST(? AS JSON),CAST(? AS JSON),
               'SOFT_EXAM_LEGACY_RULE','LEGACY_RULE_V1')
            """)) {
          statement.setString(1, contentFingerprint);
          statement.setString(2, targetJson);
          statement.setString(3, targetJson);
          statement.executeUpdate();
        }
        String sourceSnapshotHash = V9LegacyRuleSupport.computeSourceSnapshotSha256(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO soft_exam_v9_legacy_manifest_header VALUES
              ('LEGACY-TEST-V1',?,'9','2026-07-14 03:00:00.000',?,
               'architect','data-owner')
            """)) {
          statement.setString(1, sourceSnapshotHash);
          statement.setString(2, "0".repeat(64));
          statement.executeUpdate();
        }
        String legacyManifestHash = V9LegacyRuleSupport.computeManifestSha256(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE soft_exam_v9_legacy_manifest_header SET manifest_sha256=?
            """)) {
          statement.setString(1, legacyManifestHash);
          statement.executeUpdate();
        }
        bundle = nonEmptyLegacyBundle("LEGACY-TEST-V1", legacyManifestHash);
        V9LegacyManifestPreflight.validate(connection, bundle);
      }

      runDirectMigration(schema, bundle);

      try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
        assertThat(queryLong(connection, """
            SELECT COUNT(*) FROM exam_rule
            WHERE id=9201 AND subject_id IS NULL AND rule_code='EXAM_PERIOD_RULE'
              AND period_code='2026-H1' AND effective_from='2026-01-01 00:00:00.000'
              AND effective_to='2026-07-01 00:00:00.000' AND effective_at IS NULL
            """)).isEqualTo(1);
      }
    } finally {
      dropSchema(schema);
    }
  }

  private Connection serverConnection() throws SQLException {
    return DriverManager.getConnection(baseUrl, username, password);
  }

  private Connection targetConnection() throws SQLException {
    return DriverManager.getConnection(url(targetSchema), username, password);
  }

  private Connection uncheckedConnection(String jdbcUrl) {
    try {
      return DriverManager.getConnection(jdbcUrl, username, password);
    } catch (SQLException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String url(String schema) {
    int query = baseUrl.indexOf('?');
    return query >= 0
        ? baseUrl.substring(0, query) + schema + baseUrl.substring(query)
        : baseUrl + schema;
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private void createSchema(String schema) throws SQLException {
    try (Connection connection = serverConnection()) {
      execute(connection, "CREATE DATABASE `" + schema
          + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    }
  }

  private void dropSchema(String schema) throws SQLException {
    try (Connection connection = serverConnection()) {
      execute(connection, "DROP DATABASE IF EXISTS `" + schema + "`");
    }
  }

  private void runAuthorizedMigration(String schema) throws Exception {
    prepareAuthorizedMigration(schema).execute();
  }

  private AuthorizedRun prepareAuthorizedMigration(String schema) throws Exception {
    V9ManifestBundle bundle = V9ManifestBundle.load();
    UUID approvalId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    long fencingToken = Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong()) + 1;
    String artifact = "sha256:" + "c".repeat(64);
    byte[] nonce = ("nonce-" + approvalId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Instant now = Instant.parse("2026-07-14T02:30:00Z");
    var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String principal;
    try (Connection connection = DriverManager.getConnection(url(schema), username, password)) {
      principal = scalar(connection, "SELECT CURRENT_USER()");
    }
    PersistentContractLedger ledger = new PersistentContractLedger(
        url(controlSchema), username, password);
    ledger.initialize();
    String envelope = signedEnvelope(keyPair, approvalId, nonce, bundle, artifact, schema,
        "d".repeat(64), principal, now);
    ledger.insertApproval(approvalId, envelope, JcsCanonicalizer.sha256Hex(nonce), artifact);
    ledger.insertAttempt(approvalId, attemptId, fencingToken, AttemptState.APPROVED, 1,
        bundle, artifact);
    V9BackupEvidence backup = new V9BackupEvidence("backup-" + approvalId, now.minusSeconds(30),
        "1".repeat(64), "2".repeat(64), "restore-" + approvalId, now.minusSeconds(10),
        "3".repeat(64), approvalId);
    V9DeploymentCoordinator coordinator = new V9DeploymentCoordinator(ledger, url(schema),
        username, password, "d".repeat(64), artifact, Clock.fixed(now, ZoneOffset.UTC));
    return new AuthorizedRun(coordinator, ledger, approvalId, attemptId, fencingToken, envelope,
        nonce, keyPair, backup);
  }

  private record AuthorizedRun(V9DeploymentCoordinator coordinator,
                               PersistentContractLedger ledger, UUID approvalId,
                               UUID attemptId, long fencingToken, String envelope,
                               byte[] nonce, java.security.KeyPair keyPair,
                               V9BackupEvidence backup) {
    void execute() {
      coordinator.execute(approvalId, attemptId, fencingToken, envelope, nonce,
          keyPair.getPublic(), true, true, backup);
    }
  }

  private void runDirectMigration(String schema, V9ManifestBundle bundle) throws Exception {
    UUID approvalId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    long fencingToken = 707;
    String artifact = "sha256:" + "6".repeat(64);
    PersistentContractLedger ledger = new PersistentContractLedger(
        url(controlSchema), username, password);
    ledger.initialize();
    ledger.insertAttempt(approvalId, attemptId, fencingToken, AttemptState.FLYWAY_STARTED, 4,
        bundle, artifact);
    try (Connection guard = DriverManager.getConnection(url(schema), username, password)) {
      String lockName = "aiexam:v9:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
          JcsCanonicalizer.sha256(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      assertThat(queryLong(guard, "SELECT GET_LOCK('" + lockName + "',0)")).isEqualTo(1);
      long guardId = queryLong(guard, "SELECT CONNECTION_ID()");
      String principal = scalar(guard, "SELECT CURRENT_USER()");
      V9ExecutionApproval approval = new V9ExecutionApproval(approvalId,
          bundle.fullManifestSha256(), bundle.flywayChecksum(), artifact, schema,
          "d".repeat(64), principal, Instant.now(), Instant.now().plusSeconds(60),
          "e".repeat(64), "mysql-gate-key");
      V9LedgerFence fence = new V9LedgerFence(ledger, approvalId, attemptId, fencingToken, 4,
          guard, lockName, guardId, approval);
      V9AuthorizedDatabaseOrchestrator orchestrator = new V9AuthorizedDatabaseOrchestrator(
          fence, () -> uncheckedConnection(url(schema)));
      V9MigrationOnlyRunner.build(url(schema), username, password, bundle, orchestrator).migrate();
    }
  }

  private static String signedEnvelope(java.security.KeyPair keyPair, UUID approvalId,
      byte[] nonce, V9ManifestBundle bundle, String artifact, String schema,
      String hostFingerprint, String principal, Instant now) throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var envelope = mapper.createObjectNode();
    envelope.put("migrationVersion", 9);
    envelope.put("fullManifestSha256", bundle.fullManifestSha256());
    envelope.put("flywayChecksumInt", bundle.flywayChecksum());
    envelope.put("artifactDigest", artifact);
    envelope.put("targetSchema", schema);
    envelope.put("targetDbHostFingerprint", hostFingerprint);
    envelope.put("migrationDbPrincipal", principal);
    envelope.put("approvalId", approvalId.toString());
    envelope.put("issuedAt", now.toString());
    envelope.put("expiresAt", now.plusSeconds(60).toString());
    envelope.put("singleUseNonceHash", JcsCanonicalizer.sha256Hex(nonce));
    envelope.put("signingKeyId", "mysql-gate-test-key");
    envelope.put("signatureAlgorithm", "Ed25519");
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(keyPair.getPrivate());
    signature.update(JcsCanonicalizer.canonicalize(envelope));
    envelope.put("approvalSignature",
        Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    return mapper.writeValueAsString(envelope);
  }

  private static V9ManifestBundle nonEmptyLegacyBundle(String version, String hash)
      throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
        V9MysqlJavaMigrationGateTest.class.getClassLoader().getResourceAsStream(
            V9ManifestBundle.MANIFEST_RESOURCE));
    root.put("legacyManifestVersion", version);
    root.put("legacyManifestSha256", hash);
    return V9ManifestBundle.parse(mapper.writeValueAsBytes(root));
  }

  private static String legacyFingerprint(long id, long qualificationId, Long subjectId,
      String ruleCode, String ruleVersion, String status, Instant effectiveAt, String ruleJson)
      throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var source = mapper.createObjectNode();
    source.put("id", id);
    source.put("qualificationId", qualificationId);
    if (subjectId == null) {
      source.putNull("subjectId");
    } else {
      source.put("subjectId", subjectId);
    }
    source.put("ruleCode", ruleCode);
    source.put("ruleVersion", ruleVersion);
    source.put("status", status);
    if (effectiveAt == null) {
      source.putNull("effectiveAt");
    } else {
      source.put("effectiveAt", effectiveAt.toString());
    }
    source.set("ruleJson", mapper.readTree(ruleJson));
    return JcsCanonicalizer.sha256Hex(JcsCanonicalizer.canonicalize(source));
  }

  private static long queryLong(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private static String scalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getString(1);
    }
  }

  private static final class PersistentContractLedger implements DeploymentControlClient {
    private final String url;
    private final String username;
    private final String password;
    private final AtomicInteger approvalReads = new AtomicInteger();
    private volatile AttemptState failedTransition;

    private PersistentContractLedger(String url, String username, String password) {
      this.url = url;
      this.username = username;
      this.password = password;
    }

    void initialize() throws SQLException {
      try (Connection connection = connect(); Statement statement = connection.createStatement()) {
        statement.execute("""
            CREATE TABLE IF NOT EXISTS v9_approval (
              approval_id CHAR(36) NOT NULL,
              signed_envelope TEXT NOT NULL,
              nonce_hash CHAR(64) NOT NULL,
              nonce_state VARCHAR(16) NOT NULL,
              artifact_digest VARCHAR(71) NOT NULL,
              approval_state VARCHAR(16) NOT NULL,
              PRIMARY KEY (approval_id),
              UNIQUE KEY uk_v9_approval_nonce (nonce_hash)
            ) ENGINE=InnoDB
            """);
        statement.execute("""
            CREATE TABLE IF NOT EXISTS v9_attempt (
              approval_id CHAR(36) NOT NULL,
              attempt_id CHAR(36) NOT NULL,
              fencing_token BIGINT NOT NULL,
              state VARCHAR(40) NOT NULL,
              state_version BIGINT NOT NULL,
              full_manifest_sha CHAR(64) NOT NULL,
              flyway_checksum INT NOT NULL,
              artifact_digest VARCHAR(71) NOT NULL,
              failure_id CHAR(36) NULL,
              updated_at DATETIME(3) NOT NULL,
              PRIMARY KEY (approval_id, attempt_id)
            ) ENGINE=InnoDB
            """);
        statement.execute("""
            CREATE TABLE IF NOT EXISTS v9_operation (
              operation_id CHAR(36) NOT NULL,
              request_hash CHAR(64) NOT NULL,
              approval_id CHAR(36) NOT NULL,
              attempt_id CHAR(36) NOT NULL,
              result_state VARCHAR(40) NOT NULL,
              result_version BIGINT NOT NULL,
              PRIMARY KEY (operation_id)
            ) ENGINE=InnoDB
            """);
      }
    }

    void insertApproval(UUID approvalId, String envelope, String nonceHash, String artifact)
        throws SQLException {
      try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO v9_approval
            (approval_id,signed_envelope,nonce_hash,nonce_state,artifact_digest,approval_state)
          VALUES (?,?,?,'ISSUED',?,'APPROVED')
          """)) {
        statement.setString(1, approvalId.toString());
        statement.setString(2, envelope);
        statement.setString(3, nonceHash);
        statement.setString(4, artifact);
        statement.executeUpdate();
      }
    }

    void insertAttempt(UUID approvalId, UUID attemptId, long fence, AttemptState state,
        long version, V9ManifestBundle bundle, String artifact) throws SQLException {
      try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO v9_attempt
            (approval_id,attempt_id,fencing_token,state,state_version,full_manifest_sha,
             flyway_checksum,artifact_digest,failure_id,updated_at)
          VALUES (?,?,?,?,?,?,?,?,NULL,NOW(3))
          """)) {
        statement.setString(1, approvalId.toString());
        statement.setString(2, attemptId.toString());
        statement.setLong(3, fence);
        statement.setString(4, state.name());
        statement.setLong(5, version);
        statement.setString(6, bundle.fullManifestSha256());
        statement.setInt(7, bundle.flywayChecksum());
        statement.setString(8, artifact);
        statement.executeUpdate();
      }
    }

    @Override
    public DeploymentApproval getApproval(UUID approvalId) {
      approvalReads.incrementAndGet();
      try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
          SELECT signed_envelope,nonce_state,artifact_digest,approval_state
          FROM v9_approval WHERE approval_id=?
          """)) {
        statement.setString(1, approvalId.toString());
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) {
            throw new IllegalStateException("approval missing");
          }
          return new DeploymentApproval(approvalId, result.getString(1), result.getString(2),
              result.getString(3), result.getString(4));
        }
      } catch (SQLException exception) {
        throw new IllegalStateException(exception);
      }
    }

    int approvalReadCount() {
      return approvalReads.get();
    }

    void failTransitionTo(AttemptState state) {
      failedTransition = state;
    }

    @Override
    public AttemptRecord consumeNonce(NonceCommand command) {
      String requestHash = requestHash(command);
      try (Connection connection = connect()) {
        connection.setAutoCommit(false);
        try {
          AttemptRecord replay = replay(connection, command.operationId(), requestHash,
              command.approvalId(), command.attemptId());
          if (replay != null) {
            connection.rollback();
            return replay;
          }
          int nonceChanged;
          try (PreparedStatement update = connection.prepareStatement("""
              UPDATE v9_approval SET nonce_state='CONSUMED'
              WHERE approval_id=? AND nonce_hash=? AND nonce_state='ISSUED'
              """)) {
            update.setString(1, command.approvalId().toString());
            update.setString(2, command.nonceHash());
            nonceChanged = update.executeUpdate();
          }
          int attemptChanged = updateAttempt(connection, command.approvalId(), command.attemptId(),
              AttemptState.LOCKED, AttemptState.NONCE_CONSUMED,
              command.expectedStateVersion(), command.fencingToken(), null);
          if (nonceChanged != 1 || attemptChanged != 1) {
            connection.rollback();
            throw new IllegalStateException("nonce or attempt CAS conflict");
          }
          storeOperation(connection, command.operationId(), requestHash, command.approvalId(),
              command.attemptId(), AttemptState.NONCE_CONSUMED,
              command.expectedStateVersion() + 1);
          connection.commit();
          return getAttemptStrong(command.approvalId(), command.attemptId());
        } catch (RuntimeException | SQLException exception) {
          connection.rollback();
          throw new IllegalStateException(exception);
        }
      } catch (SQLException exception) {
        throw new IllegalStateException(exception);
      }
    }

    @Override
    public AttemptRecord transitionAttempt(TransitionCommand command) {
      if (command.toState() == failedTransition) {
        failedTransition = null;
        throw new IllegalStateException("injected transition acknowledgement failure");
      }
      String requestHash = JcsCanonicalizer.sha256Hex(command.toString().getBytes(
          java.nio.charset.StandardCharsets.UTF_8));
      try (Connection connection = connect()) {
        connection.setAutoCommit(false);
        try {
          try (PreparedStatement existing = connection.prepareStatement(
              "SELECT request_hash FROM v9_operation WHERE operation_id=?")) {
            existing.setString(1, command.operationId().toString());
            try (ResultSet result = existing.executeQuery()) {
              if (result.next()) {
                if (!requestHash.equals(result.getString(1))) {
                  throw new IllegalStateException("operationId payload conflict");
                }
                connection.rollback();
                return getAttemptStrong(command.approvalId(), command.attemptId());
              }
            }
          }
          int changed;
          try (PreparedStatement update = connection.prepareStatement("""
              UPDATE v9_attempt SET state=?, state_version=state_version+1, updated_at=NOW(3)
              WHERE approval_id=? AND attempt_id=? AND state=? AND state_version=?
                AND fencing_token=?
              """)) {
            update.setString(1, command.toState().name());
            update.setString(2, command.approvalId().toString());
            update.setString(3, command.attemptId().toString());
            update.setString(4, command.fromState().name());
            update.setLong(5, command.expectedStateVersion());
            update.setLong(6, command.fencingToken());
            changed = update.executeUpdate();
          }
          if (changed != 1) {
            connection.rollback();
            throw new IllegalStateException("CAS conflict");
          }
          try (PreparedStatement operation = connection.prepareStatement("""
              INSERT INTO v9_operation VALUES (?,?,?,?,?,?)
              """)) {
            operation.setString(1, command.operationId().toString());
            operation.setString(2, requestHash);
            operation.setString(3, command.approvalId().toString());
            operation.setString(4, command.attemptId().toString());
            operation.setString(5, command.toState().name());
            operation.setLong(6, command.expectedStateVersion() + 1);
            operation.executeUpdate();
          }
          connection.commit();
          return getAttemptStrong(command.approvalId(), command.attemptId());
        } catch (RuntimeException | SQLException exception) {
          connection.rollback();
          throw new IllegalStateException(exception);
        }
      } catch (SQLException exception) {
        throw new IllegalStateException(exception);
      }
    }

    @Override
    public AttemptRecord getAttemptStrong(UUID approvalId, UUID attemptId) {
      try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
          SELECT fencing_token,state,state_version,full_manifest_sha,flyway_checksum,
                 artifact_digest,updated_at,failure_id
          FROM v9_attempt WHERE approval_id=? AND attempt_id=?
          """)) {
        statement.setString(1, approvalId.toString());
        statement.setString(2, attemptId.toString());
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) {
            throw new IllegalStateException("attempt missing");
          }
          return new AttemptRecord(approvalId, attemptId, result.getLong(1),
              AttemptState.valueOf(result.getString(2)), result.getLong(3),
              "0".repeat(64), "0".repeat(64), "backup-test", result.getString(4),
              result.getInt(5), result.getString(6), null, null,
              result.getTimestamp(7).toInstant(),
              result.getString(8) == null ? null : UUID.fromString(result.getString(8)));
        }
      } catch (SQLException exception) {
        throw new IllegalStateException(exception);
      }
    }

    @Override
    public AttemptRecord recordFailure(FailureCommand command) {
      String requestHash = requestHash(command);
      try (Connection connection = connect()) {
        connection.setAutoCommit(false);
        try {
          AttemptRecord replay = replay(connection, command.operationId(), requestHash,
              command.approvalId(), command.attemptId());
          if (replay != null) {
            connection.rollback();
            return replay;
          }
          int changed = updateAttempt(connection, command.approvalId(), command.attemptId(),
              command.fromState(), command.failureState(), command.expectedStateVersion(),
              command.fencingToken(), command.failureId());
          if (changed != 1) {
            connection.rollback();
            throw new IllegalStateException("failure CAS conflict");
          }
          storeOperation(connection, command.operationId(), requestHash, command.approvalId(),
              command.attemptId(), command.failureState(), command.expectedStateVersion() + 1);
          connection.commit();
          return getAttemptStrong(command.approvalId(), command.attemptId());
        } catch (RuntimeException | SQLException exception) {
          connection.rollback();
          throw new IllegalStateException(exception);
        }
      } catch (SQLException exception) {
        throw new IllegalStateException(exception);
      }
    }

    private AttemptRecord replay(Connection connection, UUID operationId, String requestHash,
        UUID approvalId, UUID attemptId) throws SQLException {
      try (PreparedStatement existing = connection.prepareStatement(
          "SELECT request_hash FROM v9_operation WHERE operation_id=?")) {
        existing.setString(1, operationId.toString());
        try (ResultSet result = existing.executeQuery()) {
          if (!result.next()) {
            return null;
          }
          if (!requestHash.equals(result.getString(1))) {
            throw new IllegalStateException("operationId payload conflict");
          }
          return getAttemptStrong(approvalId, attemptId);
        }
      }
    }

    private static int updateAttempt(Connection connection, UUID approvalId, UUID attemptId,
        AttemptState from, AttemptState to, long expectedVersion, long fence, UUID failureId)
        throws SQLException {
      try (PreparedStatement update = connection.prepareStatement("""
          UPDATE v9_attempt SET state=?, state_version=state_version+1, failure_id=?,
            updated_at=NOW(3)
          WHERE approval_id=? AND attempt_id=? AND state=? AND state_version=?
            AND fencing_token=?
          """)) {
        update.setString(1, to.name());
        if (failureId == null) {
          update.setNull(2, java.sql.Types.CHAR);
        } else {
          update.setString(2, failureId.toString());
        }
        update.setString(3, approvalId.toString());
        update.setString(4, attemptId.toString());
        update.setString(5, from.name());
        update.setLong(6, expectedVersion);
        update.setLong(7, fence);
        return update.executeUpdate();
      }
    }

    private static void storeOperation(Connection connection, UUID operationId,
        String requestHash, UUID approvalId, UUID attemptId, AttemptState state, long version)
        throws SQLException {
      try (PreparedStatement operation = connection.prepareStatement("""
          INSERT INTO v9_operation VALUES (?,?,?,?,?,?)
          """)) {
        operation.setString(1, operationId.toString());
        operation.setString(2, requestHash);
        operation.setString(3, approvalId.toString());
        operation.setString(4, attemptId.toString());
        operation.setString(5, state.name());
        operation.setLong(6, version);
        operation.executeUpdate();
      }
    }

    private static String requestHash(Object command) {
      return JcsCanonicalizer.sha256Hex(command.toString().getBytes(
          java.nio.charset.StandardCharsets.UTF_8));
    }

    private Connection connect() throws SQLException {
      return DriverManager.getConnection(url, username, password);
    }
  }
}
