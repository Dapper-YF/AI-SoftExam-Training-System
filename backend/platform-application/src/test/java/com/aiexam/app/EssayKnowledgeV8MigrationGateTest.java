package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

class EssayKnowledgeV8MigrationGateTest {
  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
  private static final Path V8 = MIGRATION_DIR.resolve("V8__essay_knowledge.sql");
  private static final List<String> KNOWLEDGE_TABLES = List.of(
      "essay_knowledge_source",
      "essay_knowledge_document",
      "essay_knowledge_document_version",
      "essay_knowledge_section",
      "essay_knowledge_fragment",
      "essay_knowledge_fragment_binding",
      "essay_knowledge_lifecycle_event",
      "essay_knowledge_processing_task",
      "essay_knowledge_retrieval_log",
      "essay_knowledge_citation_log");
  private static final Map<String, String> FROZEN_NORMALIZED_LF_SHA256 = Map.of(
      "V1__foundation.sql", "59b70df9001f3630bc208afd05e4907fd362ef562fd2da915d352cf32c6fc7e1",
      "V2__question_practice.sql", "e4ac4222f1a5edff4c0a4bd1a9b4407dbcbde4313e5b5f47a8a76b57c83a854b",
      "V3__essay_training.sql", "4f65f764474e291b17bb43b70772cf21df22d9405340100e66d9c4749e781c56",
      "V4__ai_capability.sql", "cb3b2de5a9d6703590b39ebc59d495f9aa2dd05341ebcc4426c7a837aa0db2f7",
      "V5__seed_data.sql", "a17e09d580470dd691bde9d635004a1fef299e2b99b829241f38cf5c5ff25d49",
      "V6__exam_simulator_session.sql", "e2a0435e787e513a2a8b2b2c271ae4c60eb078497ea94bdf254343b5672044ef",
      "V7__exam_event_sequence.sql", "4765f47b8530018f5d78b0810a892fc6c5615d2e505f6c280639e0db9de38e51");

  @Test
  void hasExactlyOneContinuousV8AndPreservesV1ThroughV7() throws Exception {
    List<String> migrations;
    try (var files = Files.list(MIGRATION_DIR)) {
      migrations = files.map(path -> path.getFileName().toString()).sorted().toList();
    }

    List<String> migrationsThroughV8 = migrations.stream()
        .filter(name -> migrationVersion(name) <= 8)
        .toList();

    assertThat(migrationsThroughV8).containsExactly(
        "V1__foundation.sql",
        "V2__question_practice.sql",
        "V3__essay_training.sql",
        "V4__ai_capability.sql",
        "V5__seed_data.sql",
        "V6__exam_simulator_session.sql",
        "V7__exam_event_sequence.sql",
        "V8__essay_knowledge.sql");
    for (var frozen : FROZEN_NORMALIZED_LF_SHA256.entrySet()) {
      assertThat(normalizedLfSha256(MIGRATION_DIR.resolve(frozen.getKey())))
          .as(frozen.getKey() + " checksum")
          .isEqualTo(frozen.getValue());
    }
  }

  @Test
  void v8CreatesExactlyTheFrozenTenKnowledgeTablesWithoutPhysicalForeignKeys() throws Exception {
    String ddl = Files.readString(V8);
    String normalized = ddl.replaceAll("\\s+", " ").toLowerCase();

    assertThat(count(normalized, "create table essay_knowledge_")).isEqualTo(10);
    for (String table : KNOWLEDGE_TABLES) {
      assertThat(count(normalized, "create table " + table + " "))
          .as(table)
          .isEqualTo(1);
    }
    assertThat(normalized)
        .doesNotContain("foreign key", "create table essay_project_background")
        .contains("raw_content longtext not null")
        .contains("current_version_id bigint null")
        .contains("idx_essay_knowledge_version_status (document_id, version_status, created_at)");
  }

  @Test
  void v8FreezesGeneratedColumnsChecksFulltextAndAuditArchivalContract() throws Exception {
    String normalized = Files.readString(V8).replaceAll("\\s+", " ").toLowerCase();

    assertThat(normalized).contains(
        "owner_scope_key bigint generated always as (if(scope_type = 'public', 0, owner_id)) stored",
        "unique key uk_essay_knowledge_document_code (scope_type, owner_scope_key, document_code)",
        "fulltext key ft_essay_knowledge_fragment_text (normalized_content) with parser ngram",
        "record_kind varchar(16) not null",
        "case when record_kind = 'command' then concat(actor_id, ':', operation_code, ':', request_id) else null end",
        "unique key uk_essay_knowledge_lifecycle_request_dedup (request_dedup_key)",
        "unique key uk_essay_knowledge_lifecycle_resource_sequence (resource_type, resource_id, event_sequence)",
        "related_command_event_id bigint null",
        "constraint chk_essay_knowledge_lifecycle_command check",
        "constraint chk_essay_knowledge_lifecycle_state_event check",
        "task_type varchar(32) not null",
        "constraint chk_essay_knowledge_processing_task_type check (task_type in ('parse_document', 'split_section', 'rebuild_index'))",
        "archived_at datetime(3) null",
        "archive_payload_hash char(64)");
  }

  @Test
  void v8ContainsNoRepositoryServiceControllerPdfChunkingOrEmbeddingImplementation() throws Exception {
    String normalized = Files.readString(V8).toLowerCase();
    assertThat(normalized).doesNotContain(
        "repository", "controller", "pdf", "embedding", "vector", "object_storage");
  }

  private static long count(String content, String token) {
    return (content.length() - content.replace(token, "").length()) / token.length();
  }

  private static int migrationVersion(String name) {
    int marker = name.indexOf("__");
    return Integer.parseInt(name.substring(1, marker));
  }

  private static String normalizedLfSha256(Path path) throws Exception {
    String content = Files.readString(path, StandardCharsets.UTF_8);
    String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(normalized.getBytes(StandardCharsets.UTF_8)));
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Mysql8Gate {
    private static final String EMPTY_SCHEMA = "ai_exam_v8_empty_test";
    private static final String CONTRACT_SCHEMA = "ai_exam_v8_contract_test";
    private static final String UPGRADE_SCHEMA = "ai_exam_v8_upgrade_test";
    private final Mysql mysql = Mysql.fromEnvironment();
    private String developmentFingerprint;
    private boolean mysqlReady;

    @BeforeAll
    void requireExplicitMysql8Gate() throws Exception {
      assumeTrue("true".equalsIgnoreCase(System.getenv("V8_MYSQL_GATE")),
          "Set V8_MYSQL_GATE=true to run the destructive, _test-only MySQL 8 Gate");
      assertThat(mysql.serverUrl()).startsWith("jdbc:mysql:");
      try (Connection connection = mysql.connectServer()) {
        assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);
      }
      developmentFingerprint = mysql.developmentFingerprint();
      mysqlReady = true;
    }

    @AfterAll
    void removesGateSchemasAndPreservesDevelopmentSchema() throws Exception {
      if (!mysqlReady) {
        return;
      }
      mysql.dropSchema(EMPTY_SCHEMA);
      mysql.dropSchema(CONTRACT_SCHEMA);
      mysql.dropSchema(UPGRADE_SCHEMA);
      assertThat(mysql.developmentFingerprint()).isEqualTo(developmentFingerprint);
      assertThat(mysql.gateSchemas()).isEmpty();
    }

    @Test
    void emptySchemaMigratesV1ThroughV8ThenSecondMigrateValidatesAtEight() throws Exception {
      mysql.recreateSchema(EMPTY_SCHEMA);
      try {
        Flyway flyway = mysql.flyway(EMPTY_SCHEMA, "8");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);
        flyway.validate();
        assertThat(flyway.info().applied()).hasSize(8);
        assertThat(mysql.queryLong(EMPTY_SCHEMA,
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")).isEqualTo(8);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(mysql.queryLong(EMPTY_SCHEMA,
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                + "AND table_name LIKE 'essay\\_knowledge\\_%'")).isEqualTo(10);
      } finally {
        mysql.dropSchema(EMPTY_SCHEMA);
      }
    }

    @Test
    void generatedOwnerScopeAndPublicPrivateChecksEnforceUniqueness() throws Exception {
      withContractSchema(() -> {
        insertDocument(1, "PUBLIC", null, "SAME-CODE");
        assertThat(mysql.queryString(CONTRACT_SCHEMA,
            "SELECT owner_scope_key FROM essay_knowledge_document WHERE id = 1")).isEqualTo("0");
        assertSqlRejected(() -> insertDocument(2, "PUBLIC", null, "SAME-CODE"));
        insertDocument(3, "PRIVATE_USER", 101L, "SAME-CODE");
        insertDocument(4, "PRIVATE_USER", 102L, "SAME-CODE");
        assertThat(mysql.queryRows(CONTRACT_SCHEMA,
            "SELECT id, owner_scope_key FROM essay_knowledge_document WHERE id IN (3,4) ORDER BY id"))
            .containsExactly("3|101", "4|102");
        assertSqlRejected(() -> insertDocument(5, "PRIVATE_USER", 101L, "SAME-CODE"));
        assertSqlRejected(() -> insertDocument(6, "PUBLIC", 101L, "BAD-PUBLIC"));
        assertSqlRejected(() -> insertDocument(7, "PRIVATE_USER", null, "BAD-PRIVATE"));
      });
    }

    @Test
    void upgradesV1ThroughV7DataToV8WithoutChangingExistingRows() throws Exception {
      mysql.recreateSchema(UPGRADE_SCHEMA);
      try {
        Flyway v7 = mysql.flyway(UPGRADE_SCHEMA, "7");
        assertThat(v7.migrate().migrationsExecuted).isEqualTo(7);
        v7.validate();
        assertThat(v7.info().applied()).hasSize(7);

        insertExistingV7Data();
        List<String> sessionsBefore = mysql.queryRows(UPGRADE_SCHEMA, """
            SELECT id, next_event_sequence, state
            FROM practice_exam_session
            ORDER BY id
            """);
        List<String> eventsBefore = mysql.queryRows(UPGRADE_SCHEMA, """
            SELECT id, session_id, event_sequence
            FROM practice_exam_event
            ORDER BY id
            """);
        long sessionCountBefore = mysql.queryLong(UPGRADE_SCHEMA,
            "SELECT COUNT(*) FROM practice_exam_session");
        long eventCountBefore = mysql.queryLong(UPGRADE_SCHEMA,
            "SELECT COUNT(*) FROM practice_exam_event");

        Flyway v8 = mysql.flyway(UPGRADE_SCHEMA, "8");
        assertThat(v8.migrate().migrationsExecuted).isEqualTo(1);
        v8.validate();
        assertThat(v8.info().applied()).hasSize(8);
        assertThat(mysql.queryLong(UPGRADE_SCHEMA,
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")).isEqualTo(8);
        assertThat(v8.migrate().migrationsExecuted).isZero();
        v8.validate();

        assertThat(mysql.queryLong(UPGRADE_SCHEMA,
            "SELECT COUNT(*) FROM practice_exam_session")).isEqualTo(sessionCountBefore);
        assertThat(mysql.queryLong(UPGRADE_SCHEMA,
            "SELECT COUNT(*) FROM practice_exam_event")).isEqualTo(eventCountBefore);
        assertThat(mysql.queryRows(UPGRADE_SCHEMA, """
            SELECT id, next_event_sequence, state
            FROM practice_exam_session
            ORDER BY id
            """)).containsExactlyElementsOf(sessionsBefore);
        assertThat(mysql.queryRows(UPGRADE_SCHEMA, """
            SELECT id, session_id, event_sequence
            FROM practice_exam_event
            ORDER BY id
            """)).containsExactlyElementsOf(eventsBefore);
      } finally {
        mysql.dropSchema(UPGRADE_SCHEMA);
      }
    }

    @Test
    void rawContentNullAndInvalidRangesAreRejected() throws Exception {
      withContractSchema(() -> {
        insertDocument(1, "PUBLIC", null, "DOC-1");
        assertSqlRejected(() -> mysql.execute(CONTRACT_SCHEMA, """
            INSERT INTO essay_knowledge_document_version
              (id, document_id, version_no, raw_content, normalized_content, content_hash, created_at, updated_at)
            VALUES (10, 1, 1, NULL, '正文', REPEAT('a', 64), NOW(3), NOW(3))
            """));
        assertSqlRejected(() -> mysql.execute(CONTRACT_SCHEMA, """
            INSERT INTO essay_knowledge_citation_log
              (id, retrieval_id, biz_type, biz_id, citation_scene, document_version_id,
               section_id, fragment_id, start_offset, end_offset, content_hash, rank_no, created_at)
            VALUES (1, 1, 'ESSAY', 1, 'TEST', 1, 1, 1, 3, 3, REPEAT('a', 64), 1, NOW(3))
            """));
      });
    }

    @Test
    void commandDedupAndStateEventSequenceChecksAllowOneCommandToOwnManyEvents() throws Exception {
      withContractSchema(() -> {
        mysql.execute(CONTRACT_SCHEMA, commandInsert(1, 99, "APPROVE_VERSION", "req-1"));
        assertThat(mysql.queryString(CONTRACT_SCHEMA,
            "SELECT request_dedup_key FROM essay_knowledge_lifecycle_event WHERE id = 1"))
            .isEqualTo("99:APPROVE_VERSION:req-1");
        assertSqlRejected(() -> mysql.execute(CONTRACT_SCHEMA,
            commandInsert(2, 99, "APPROVE_VERSION", "req-1")));
        mysql.execute(CONTRACT_SCHEMA, stateInsert(3, 1, "APPROVED", 1));
        mysql.execute(CONTRACT_SCHEMA, stateInsert(4, 1, "PUBLISHED", 2));
        assertThat(mysql.queryRows(CONTRACT_SCHEMA,
            "SELECT id, request_dedup_key, event_sequence FROM essay_knowledge_lifecycle_event "
                + "WHERE record_kind = 'STATE_EVENT' ORDER BY event_sequence"))
            .containsExactly("3|null|1", "4|null|2");
        assertSqlRejected(() -> mysql.execute(CONTRACT_SCHEMA, stateInsert(5, 1, "READY", 2)));
        assertSqlRejected(() -> mysql.execute(CONTRACT_SCHEMA, """
            INSERT INTO essay_knowledge_lifecycle_event
              (id, record_kind, actor_id, operation_code, request_id, request_hash,
               event_code, event_sequence, created_at)
            VALUES (6, 'COMMAND', 99, 'BAD', 'bad', REPEAT('a', 64), 'APPROVED', 1, NOW(3))
            """));
      });
    }

    @Test
    void processingTaskTypeCheckAllowsOnlyFrozenThreeTypes() throws Exception {
      withContractSchema(() -> {
        mysql.execute(CONTRACT_SCHEMA, taskInsert(1, "PARSE_DOCUMENT"));
        mysql.execute(CONTRACT_SCHEMA, taskInsert(2, "SPLIT_SECTION"));
        mysql.execute(CONTRACT_SCHEMA, taskInsert(3, "REBUILD_INDEX"));
        assertSqlRejected(() -> mysql.execute(CONTRACT_SCHEMA, taskInsert(4, "AUDIT_VERSION")));
      });
    }

    @Test
    void ngramFulltextIndexMatchesChineseFragmentText() throws Exception {
      withContractSchema(() -> {
        mysql.execute(CONTRACT_SCHEMA, """
            INSERT INTO essay_knowledge_fragment
              (id, document_version_id, section_id, fragment_no, scope_type, owner_id,
               qualification_id, content, normalized_content, content_hash, start_offset,
               end_offset, created_at, updated_at)
            VALUES (1, 1, 1, 1, 'PUBLIC', NULL, 1, '范围管理与变更控制',
                    '范围管理与变更控制', REPEAT('a', 64), 0, 9, NOW(3), NOW(3))
            """);
        assertThat(mysql.queryLong(CONTRACT_SCHEMA, """
            SELECT COUNT(*) FROM essay_knowledge_fragment
            WHERE MATCH(normalized_content) AGAINST ('变更' IN BOOLEAN MODE)
            """)).isEqualTo(1);
        assertThat(mysql.queryLong(CONTRACT_SCHEMA, """
            SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'essay_knowledge_fragment'
              AND index_name = 'ft_essay_knowledge_fragment_text'
              AND index_type = 'FULLTEXT'
            """)).isEqualTo(1);
      });
    }

    private void withContractSchema(SqlRunnable assertions) throws Exception {
      mysql.recreateSchema(CONTRACT_SCHEMA);
      try {
        mysql.flyway(CONTRACT_SCHEMA, "8").migrate();
        assertions.run();
      } finally {
        mysql.dropSchema(CONTRACT_SCHEMA);
      }
    }

    private void insertExistingV7Data() throws Exception {
      mysql.execute(UPGRADE_SCHEMA, sessionInsert(1));
      mysql.execute(UPGRADE_SCHEMA, sessionInsert(2));
      mysql.execute(UPGRADE_SCHEMA, eventInsert(101, 1, 1L, "2026-07-12 09:00:00.000"));
      mysql.execute(UPGRADE_SCHEMA, eventInsert(102, 1, 2L, "2026-07-12 09:00:01.000"));
      mysql.execute(UPGRADE_SCHEMA,
          "UPDATE practice_exam_session SET next_event_sequence = 3 WHERE id = 1");
    }

    private void insertDocument(long id, String scope, Long ownerId, String code) throws Exception {
      String owner = ownerId == null ? "NULL" : ownerId.toString();
      mysql.execute(CONTRACT_SCHEMA, """
          INSERT INTO essay_knowledge_document
            (id, source_id, document_code, title, scope_type, owner_id, qualification_id,
             document_type, created_at, updated_at)
          VALUES (%d, 1, '%s', 'Gate', '%s', %s, 1, 'TEXT', NOW(3), NOW(3))
          """.formatted(id, code, scope, owner));
    }

    private String commandInsert(long id, long actor, String operation, String request) {
      return """
          INSERT INTO essay_knowledge_lifecycle_event
            (id, record_kind, actor_id, operation_code, request_id, request_hash, created_at)
          VALUES (%d, 'COMMAND', %d, '%s', '%s', REPEAT('a', 64), NOW(3))
          """.formatted(id, actor, operation, request);
    }

    private String stateInsert(long id, long commandId, String event, long sequence) {
      return """
          INSERT INTO essay_knowledge_lifecycle_event
            (id, record_kind, event_code, resource_type, resource_id, event_sequence,
             related_command_event_id, after_status, occurred_at, created_at)
          VALUES (%d, 'STATE_EVENT', '%s', 'DOCUMENT_VERSION', 100, %d,
                  %d, JSON_OBJECT('status', '%s'), NOW(3), NOW(3))
          """.formatted(id, event, sequence, commandId, event);
    }

    private String taskInsert(long id, String type) {
      return """
          INSERT INTO essay_knowledge_processing_task
            (id, task_type, target_type, target_id, operation_code, request_id, request_hash,
             related_command_event_id, idempotency_key, requested_by, created_at, updated_at)
          VALUES (%d, '%s', 'DOCUMENT_VERSION', 1, '%s', 'req-%d', REPEAT('a', 64),
                  1, 'idem-%d', 99, NOW(3), NOW(3))
          """.formatted(id, type, type, id, id);
    }

    private String sessionInsert(long id) {
      return """
          INSERT INTO practice_exam_session (
            id, session_no, candidate_id, batch_id, entry_id, qualification_id,
            exam_batch_code, entry_code, exam_mode, batch_config_version,
            entry_config_version, scheduled_start_at, linked_deadline_at,
            token_hash, token_key_version, token_issued_at, token_expires_at,
            last_client_login_id, last_login_at, created_at, updated_at
          ) VALUES (
            %d, '00000000-0000-0000-0000-%012d', %d, 1, 1, 1,
            'V8-GATE', 'LINKED', 'STRICT', 1,
            1, '2026-07-12 09:00:00.000', '2026-07-12 13:00:00.000',
            UNHEX(SHA2('token-%d', 256)), '1', '2026-07-12 09:00:00.000',
            '2026-07-12 15:00:00.000', '00000000-0000-0000-0001-%012d',
            '2026-07-12 09:00:00.000', '2026-07-12 09:00:00.000',
            '2026-07-12 09:00:00.000'
          )
          """.formatted(id, id, id, id, id);
    }

    private String eventInsert(long id, long sessionId, long sequence, String occurredAt) {
      return """
          INSERT INTO practice_exam_event (
            id, session_id, event_type, actor_type, trace_id, occurred_at, event_sequence
          ) VALUES (%d, %d, 'V8_GATE', 'SYSTEM', 'trace-%d', '%s', %d)
          """.formatted(id, sessionId, id, occurredAt, sequence);
    }

    private void assertSqlRejected(SqlRunnable action) {
      assertThatThrownBy(action::run).isInstanceOf(SQLException.class);
    }
  }

  @FunctionalInterface
  private interface SqlRunnable {
    void run() throws Exception;
  }

  private record Mysql(String serverUrl, String username, String password) {
    static Mysql fromEnvironment() {
      String testUrl = valueOrDefault("MYSQL_TEST_URL",
          "jdbc:mysql://127.0.0.1:33307/ai_exam_platform_test"
              + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
              + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
      int query = testUrl.indexOf('?');
      String options = query >= 0 ? testUrl.substring(query) : "";
      String withoutOptions = query >= 0 ? testUrl.substring(0, query) : testUrl;
      int slash = withoutOptions.lastIndexOf('/');
      return new Mysql(withoutOptions.substring(0, slash + 1) + options,
          valueOrDefault("MYSQL_TEST_USERNAME", "root"),
          valueOrDefault("MYSQL_TEST_PASSWORD", ""));
    }

    Connection connectServer() throws SQLException {
      return DriverManager.getConnection(serverUrl, username, password);
    }

    Connection connect(String schema) throws SQLException {
      return DriverManager.getConnection(schemaUrl(schema), username, password);
    }

    Flyway flyway(String schema, String... target) {
      var configuration = Flyway.configure()
          .dataSource(schemaUrl(schema), username, password)
          .locations("filesystem:src/main/resources/db/migration")
          .cleanDisabled(true);
      if (target.length == 1) {
        configuration.target(target[0]);
      }
      return configuration.load();
    }

    String schemaUrl(String schema) {
      requireGateSchema(schema);
      int query = serverUrl.indexOf('?');
      return query >= 0
          ? serverUrl.substring(0, query) + schema + serverUrl.substring(query)
          : serverUrl + schema;
    }

    void recreateSchema(String schema) throws SQLException {
      requireGateSchema(schema);
      try (Connection connection = connectServer(); Statement statement = connection.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        statement.execute("CREATE DATABASE `" + schema
            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      }
    }

    void dropSchema(String schema) throws SQLException {
      requireGateSchema(schema);
      try (Connection connection = connectServer(); Statement statement = connection.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
      }
    }

    void execute(String schema, String sql) throws SQLException {
      try (Connection connection = connect(schema); Statement statement = connection.createStatement()) {
        statement.execute(sql);
      }
    }

    long queryLong(String schema, String sql) throws SQLException {
      return Long.parseLong(queryRows(schema, sql).get(0));
    }

    String queryString(String schema, String sql) throws SQLException {
      return queryRows(schema, sql).get(0);
    }

    List<String> queryRows(String schema, String sql) throws SQLException {
      try (Connection connection = connect(schema);
           Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery(sql)) {
        List<String> rows = new ArrayList<>();
        int columns = result.getMetaData().getColumnCount();
        while (result.next()) {
          List<String> values = new ArrayList<>();
          for (int column = 1; column <= columns; column++) {
            values.add(String.valueOf(result.getObject(column)));
          }
          rows.add(String.join("|", values));
        }
        return rows;
      }
    }

    List<String> gateSchemas() throws SQLException {
      try (Connection connection = connectServer();
           Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery("""
               SELECT schema_name FROM information_schema.schemata
               WHERE schema_name LIKE 'ai\\_exam\\_v8\\_%\\_test'
               ORDER BY schema_name
               """)) {
        List<String> schemas = new ArrayList<>();
        while (result.next()) {
          schemas.add(result.getString(1));
        }
        return schemas;
      }
    }

    String developmentFingerprint() throws Exception {
      try (Connection connection = connectServer(); Statement statement = connection.createStatement()) {
        List<String> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery("""
            SELECT table_name, column_name, ordinal_position, column_type, is_nullable,
                   COALESCE(column_default, '<NULL>')
            FROM information_schema.columns
            WHERE table_schema = 'ai_exam_platform'
            ORDER BY table_name, ordinal_position
            """)) {
          while (result.next()) {
            rows.add(String.join("|", result.getString(1), result.getString(2),
                result.getString(3), result.getString(4), result.getString(5), result.getString(6)));
          }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(String.join("\n", rows).getBytes(StandardCharsets.UTF_8)));
      }
    }

    private static void requireGateSchema(String schema) {
      if (!schema.matches("ai_exam_v8_[a-z0-9_]+_test")) {
        throw new IllegalArgumentException("Refusing non-V8 test schema: " + schema);
      }
    }

    private static String valueOrDefault(String name, String fallback) {
      String value = System.getenv(name);
      return value == null || value.isBlank() ? fallback : value;
    }
  }
}
