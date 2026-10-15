package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

class ExamEventSequenceV7MigrationGateTest {
  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
  private static final Path V7 = MIGRATION_DIR.resolve("V7__exam_event_sequence.sql");
  private static final String AUTHORITATIVE_UNIQUE_KEY =
      "uk_exam_event_session_sequence";
  private static final Map<String, String> FROZEN_NORMALIZED_LF_SHA256 = Map.of(
      "V1__foundation.sql", "59b70df9001f3630bc208afd05e4907fd362ef562fd2da915d352cf32c6fc7e1",
      "V2__question_practice.sql", "e4ac4222f1a5edff4c0a4bd1a9b4407dbcbde4313e5b5f47a8a76b57c83a854b",
      "V3__essay_training.sql", "4f65f764474e291b17bb43b70772cf21df22d9405340100e66d9c4749e781c56",
      "V4__ai_capability.sql", "cb3b2de5a9d6703590b39ebc59d495f9aa2dd05341ebcc4426c7a837aa0db2f7",
      "V5__seed_data.sql", "a17e09d580470dd691bde9d635004a1fef299e2b99b829241f38cf5c5ff25d49",
      "V6__exam_simulator_session.sql", "e2a0435e787e513a2a8b2b2c271ae4c60eb078497ea94bdf254343b5672044ef");

  @Test
  void hasExactlyTheFrozenV1ThroughV7BoundaryEvenWhenNewerMigrationsExist() throws Exception {
    List<String> migrations;
    try (var files = Files.list(MIGRATION_DIR)) {
      migrations = files.map(path -> path.getFileName().toString()).sorted().toList();
    }

    List<String> migrationsThroughV7 = migrations.stream()
        .filter(name -> migrationVersion(name) <= 7)
        .toList();

    assertThat(migrationsThroughV7).containsExactly(
        "V1__foundation.sql",
        "V2__question_practice.sql",
        "V3__essay_training.sql",
        "V4__ai_capability.sql",
        "V5__seed_data.sql",
        "V6__exam_simulator_session.sql",
        "V7__exam_event_sequence.sql");
    assertThat(migrations.stream().filter(name -> name.startsWith("V7__")).toList())
        .containsExactly("V7__exam_event_sequence.sql");
    for (var frozen : FROZEN_NORMALIZED_LF_SHA256.entrySet()) {
      assertThat(normalizedLfSha256(MIGRATION_DIR.resolve(frozen.getKey())))
          .as(frozen.getKey() + " checksum")
          .isEqualTo(frozen.getValue());
    }
  }

  @Test
  void normalizedLfChecksumIsPortableAcrossLineEndingsAndDetectsContentChanges()
      throws Exception {
    String lf = "first line\nsecond line\n";
    String crlf = "first line\r\nsecond line\r\n";
    String changed = "first line\nchanged line\n";

    assertThat(normalizedLfSha256(lf)).isEqualTo(normalizedLfSha256(crlf));
    assertThat(normalizedLfSha256(lf)).isNotEqualTo(normalizedLfSha256(changed));
  }

  @Test
  void v7ContainsTheFrozenBackfillAndConstraintContractOnly() throws IOException {
    assertThat(V7).exists();
    String ddl = Files.readString(V7);
    String normalized = ddl.replaceAll("\\s+", " ").toLowerCase();
    String authoritativeDatabaseDesign = Files.readString(
        Path.of("").toAbsolutePath()
            .resolve("../../docs/数据库设计/p0-logical-data-model-ddl-2026-07-09.md")
            .normalize());

    assertThat(authoritativeDatabaseDesign).contains(
        "ADD UNIQUE KEY " + AUTHORITATIVE_UNIQUE_KEY
            + " (session_id, event_sequence)");

    assertThat(normalized).contains(
        "next_event_sequence bigint not null default 1",
        "check (next_event_sequence >= 1)",
        "event_sequence bigint null",
        "row_number() over (partition by session_id order by occurred_at, id)",
        "coalesce(max(event_sequence), 0) + 1",
        "modify column event_sequence bigint not null",
        "unique key " + AUTHORITATIVE_UNIQUE_KEY + " (session_id, event_sequence)",
        "key idx_exam_event_subject_sequence (session_subject_id, event_sequence)");
    assertThat(normalized)
        .contains("practice_exam_event", "practice_exam_session")
        .doesNotContain("essay_knowledge", "create table practice_exam", "foreign key");
    assertThat(normalized.indexOf("orphan_count"))
        .isLessThan(normalized.indexOf("add column next_event_sequence"));
  }

  private static String normalizedLfSha256(Path path) throws Exception {
    return normalizedLfSha256(Files.readString(path, StandardCharsets.UTF_8));
  }

  private static int migrationVersion(String name) {
    int marker = name.indexOf("__");
    return Integer.parseInt(name.substring(1, marker));
  }

  private static String normalizedLfSha256(String content) throws Exception {
    String normalizedLf = content.replace("\r\n", "\n").replace('\r', '\n');
    return sha256(normalizedLf.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(content));
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Mysql8Gate {
    private static final String EMPTY_SCHEMA = "ai_exam_v7_empty_test";
    private static final String UPGRADE_SCHEMA = "ai_exam_v7_upgrade_test";
    private static final String ORPHAN_SCHEMA = "ai_exam_v7_orphan_test";
    private final Mysql mysql = Mysql.fromEnvironment();
    private String developmentFingerprint;
    private boolean mysqlReady;

    @BeforeAll
    void requireExplicitMysql8Gate() throws Exception {
      assumeTrue(
          "true".equalsIgnoreCase(System.getenv("V7_MYSQL_GATE")),
          "Set V7_MYSQL_GATE=true to run the destructive, _test-only MySQL 8 Gate");
      assertThat(mysql.serverUrl()).startsWith("jdbc:mysql:");
      try (Connection connection = mysql.connectServer()) {
        assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);
      }
      developmentFingerprint = mysql.developmentFingerprint();
      mysqlReady = true;
    }

    @AfterAll
    void leavesNoGateSchemasAndDoesNotChangeDevelopmentSchema() throws Exception {
      if (!mysqlReady) {
        return;
      }
      mysql.dropSchema(EMPTY_SCHEMA);
      mysql.dropSchema(UPGRADE_SCHEMA);
      mysql.dropSchema(ORPHAN_SCHEMA);
      assertThat(mysql.developmentFingerprint()).isEqualTo(developmentFingerprint);
      assertThat(mysql.gateSchemas()).isEmpty();
    }

    @Test
    void emptySchemaMigratesV1ThroughV7ThenValidatesAtSevenOfSeven() throws Exception {
      mysql.recreateSchema(EMPTY_SCHEMA);
      try {
        Flyway flyway = mysql.flyway(EMPTY_SCHEMA, "7");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(7);
        flyway.validate();
        assertThat(flyway.info().applied()).hasSize(7);
        assertThat(mysql.queryLong(EMPTY_SCHEMA,
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")).isEqualTo(7);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
      } finally {
        mysql.dropSchema(EMPTY_SCHEMA);
      }
    }

    @Test
    void upgradesV6DataWithDeterministicSequencesCountersAndIndexes() throws Exception {
      mysql.recreateSchema(UPGRADE_SCHEMA);
      try {
        mysql.flyway(UPGRADE_SCHEMA, "6").migrate();
        insertUpgradeFixture();
        Flyway flyway = mysql.flyway(UPGRADE_SCHEMA, "7");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();

        assertThat(mysql.queryRows(UPGRADE_SCHEMA,
            "SELECT id, event_sequence FROM practice_exam_event ORDER BY session_id, event_sequence"))
            .containsExactly("101|1", "102|2", "103|3", "201|1");
        assertThat(mysql.queryRows(UPGRADE_SCHEMA,
            "SELECT id, next_event_sequence FROM practice_exam_session ORDER BY id"))
            .containsExactly("1|4", "2|2", "3|1");
        assertThat(mysql.queryString(UPGRADE_SCHEMA, """
            SELECT IS_NULLABLE FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'practice_exam_event'
              AND column_name = 'event_sequence'
            """)).isEqualTo("NO");
        assertThat(mysql.indexColumns(UPGRADE_SCHEMA, AUTHORITATIVE_UNIQUE_KEY))
            .containsExactly("session_id", "event_sequence");
        assertThat(mysql.indexColumns(UPGRADE_SCHEMA, "idx_exam_event_subject_sequence"))
            .containsExactly("session_subject_id", "event_sequence");
        assertThat(mysql.indexColumns(UPGRADE_SCHEMA, "idx_exam_event_session_time"))
            .containsExactly("session_id", "occurred_at");
        assertThat(mysql.indexColumns(UPGRADE_SCHEMA, "idx_exam_event_subject_time"))
            .containsExactly("session_subject_id", "occurred_at");
      } finally {
        mysql.dropSchema(UPGRADE_SCHEMA);
      }
    }

    @Test
    void orphanEventFailsClosedBeforeAnyPermanentV7Ddl() throws Exception {
      mysql.recreateSchema(ORPHAN_SCHEMA);
      try {
        mysql.flyway(ORPHAN_SCHEMA, "6").migrate();
        mysql.execute(ORPHAN_SCHEMA, eventInsert(900, 999, null, "2026-07-11 01:00:00.000"));

        assertThatThrownBy(() -> mysql.flyway(ORPHAN_SCHEMA, "7").migrate())
            .hasMessageContaining("V7__exam_event_sequence.sql");
        assertThat(mysql.columnCount(ORPHAN_SCHEMA, "practice_exam_session", "next_event_sequence"))
            .isZero();
        assertThat(mysql.columnCount(ORPHAN_SCHEMA, "practice_exam_event", "event_sequence"))
            .isZero();
        assertThat(mysql.queryLong(ORPHAN_SCHEMA,
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '7' AND success = 1"))
            .isZero();
      } finally {
        mysql.dropSchema(ORPHAN_SCHEMA);
      }
    }

    @Test
    void rejectsDuplicateAndNullEventSequencesAndCounterBelowOne() throws Exception {
      mysql.recreateSchema(UPGRADE_SCHEMA);
      try {
        mysql.flyway(UPGRADE_SCHEMA, "7").migrate();
        mysql.execute(UPGRADE_SCHEMA, sessionInsert(10));
        mysql.execute(UPGRADE_SCHEMA, eventInsert(1001, 10, 1L, "2026-07-11 02:00:00.000"));

        assertThatThrownBy(() -> mysql.execute(UPGRADE_SCHEMA,
            eventInsert(1002, 10, 1L, "2026-07-11 02:00:01.000")))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> mysql.execute(UPGRADE_SCHEMA,
            eventInsert(1003, 10, null, "2026-07-11 02:00:02.000")))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> mysql.execute(UPGRADE_SCHEMA,
            "UPDATE practice_exam_session SET next_event_sequence = 0 WHERE id = 10"))
            .isInstanceOf(SQLException.class);
      } finally {
        mysql.dropSchema(UPGRADE_SCHEMA);
      }
    }

    @Test
    void concurrentWritesOfTheSameSessionSequenceAllowExactlyOneCommit() throws Exception {
      mysql.recreateSchema(UPGRADE_SCHEMA);
      try {
        mysql.flyway(UPGRADE_SCHEMA, "7").migrate();
        mysql.execute(UPGRADE_SCHEMA, sessionInsert(20));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
          Future<Boolean> first = executor.submit(
              () -> concurrentInsert(ready, start, eventInsert(
                  2001, 20, 1L, "2026-07-11 03:00:00.000")));
          Future<Boolean> second = executor.submit(
              () -> concurrentInsert(ready, start, eventInsert(
                  2002, 20, 1L, "2026-07-11 03:00:00.000")));
          assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
          start.countDown();
          assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
          executor.shutdownNow();
        }
        assertThat(mysql.queryLong(UPGRADE_SCHEMA,
            "SELECT COUNT(*) FROM practice_exam_event WHERE session_id = 20 AND event_sequence = 1"))
            .isEqualTo(1);
      } finally {
        mysql.dropSchema(UPGRADE_SCHEMA);
      }
    }

    private boolean concurrentInsert(CountDownLatch ready, CountDownLatch start, String sql)
        throws Exception {
      ready.countDown();
      start.await();
      try {
        mysql.execute(UPGRADE_SCHEMA, sql);
        return true;
      } catch (SQLException expectedUniqueConflict) {
        return false;
      }
    }

    private void insertUpgradeFixture() throws Exception {
      mysql.execute(UPGRADE_SCHEMA, sessionInsert(1));
      mysql.execute(UPGRADE_SCHEMA, sessionInsert(2));
      mysql.execute(UPGRADE_SCHEMA, sessionInsert(3));
      mysql.execute(UPGRADE_SCHEMA, eventInsert(103, 1, null, "2026-07-11 01:00:01.000"));
      mysql.execute(UPGRADE_SCHEMA, eventInsert(102, 1, null, "2026-07-11 01:00:00.000"));
      mysql.execute(UPGRADE_SCHEMA, eventInsert(101, 1, null, "2026-07-11 01:00:00.000"));
      mysql.execute(UPGRADE_SCHEMA, eventInsert(201, 2, null, "2026-07-11 01:00:00.000"));
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
            'V7-GATE', 'LINKED', 'STRICT', 1,
            1, '2026-07-11 00:00:00.000', '2026-07-11 04:00:00.000',
            UNHEX(SHA2('token-%d', 256)), '1', '2026-07-11 00:00:00.000',
            '2026-07-11 06:00:00.000', '00000000-0000-0000-0001-%012d',
            '2026-07-11 00:00:00.000', '2026-07-11 00:00:00.000',
            '2026-07-11 00:00:00.000'
          )
          """.formatted(id, id, id, id, id);
    }

    private String eventInsert(long id, long sessionId, Long sequence, String occurredAt) {
      String columns = sequence == null
          ? "id, session_id, event_type, actor_type, trace_id, occurred_at"
          : "id, session_id, event_type, actor_type, trace_id, occurred_at, event_sequence";
      String values = sequence == null
          ? "%d, %d, 'V7_GATE', 'SYSTEM', 'trace-%d', '%s'".formatted(
              id, sessionId, id, occurredAt)
          : "%d, %d, 'V7_GATE', 'SYSTEM', 'trace-%d', '%s', %d".formatted(
              id, sessionId, id, occurredAt, sequence);
      return "INSERT INTO practice_exam_event (" + columns + ") VALUES (" + values + ")";
    }
  }

  private record Mysql(String serverUrl, String username, String password) {
    static Mysql fromEnvironment() {
      String testUrl = valueOrDefault(
          "MYSQL_TEST_URL",
          "jdbc:mysql://127.0.0.1:33307/ai_exam_platform_test"
              + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
              + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
      int query = testUrl.indexOf('?');
      String options = query >= 0 ? testUrl.substring(query) : "";
      String withoutOptions = query >= 0 ? testUrl.substring(0, query) : testUrl;
      int slash = withoutOptions.lastIndexOf('/');
      String server = withoutOptions.substring(0, slash + 1) + options;
      return new Mysql(
          server,
          valueOrDefault("MYSQL_TEST_USERNAME", "root"),
          valueOrDefault("MYSQL_TEST_PASSWORD", ""));
    }

    Connection connectServer() throws SQLException {
      return DriverManager.getConnection(serverUrl, username, password);
    }

    Connection connect(String schema) throws SQLException {
      requireGateSchema(schema);
      int query = serverUrl.indexOf('?');
      String url = query >= 0
          ? serverUrl.substring(0, query) + schema + serverUrl.substring(query)
          : serverUrl + schema;
      return DriverManager.getConnection(url, username, password);
    }

    Flyway flyway(String schema, String... target) {
      requireGateSchema(schema);
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
            values.add(result.getString(column));
          }
          rows.add(String.join("|", values));
        }
        return rows;
      }
    }

    long columnCount(String schema, String table, String column) throws SQLException {
      return queryLong(schema, """
          SELECT COUNT(*) FROM information_schema.columns
          WHERE table_schema = DATABASE()
            AND table_name = '%s'
            AND column_name = '%s'
          """.formatted(table, column));
    }

    List<String> indexColumns(String schema, String index) throws SQLException {
      return queryRows(schema, """
          SELECT column_name FROM information_schema.statistics
          WHERE table_schema = DATABASE()
            AND table_name = 'practice_exam_event'
            AND index_name = '%s'
          ORDER BY seq_in_index
          """.formatted(index));
    }

    List<String> gateSchemas() throws SQLException {
      try (Connection connection = connectServer();
           Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery("""
               SELECT schema_name FROM information_schema.schemata
               WHERE schema_name LIKE 'ai\\_exam\\_v7\\_%\\_test'
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
            rows.add(String.join("|",
                result.getString(1), result.getString(2), result.getString(3),
                result.getString(4), result.getString(5), result.getString(6)));
          }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(String.join("\n", rows).getBytes(StandardCharsets.UTF_8)));
      }
    }

    private static void requireGateSchema(String schema) {
      if (!schema.matches("ai_exam_v7_[a-z0-9_]+_test")) {
        throw new IllegalArgumentException("Refusing non-V7 test schema: " + schema);
      }
    }

    private static String valueOrDefault(String name, String fallback) {
      String value = System.getenv(name);
      return value == null || value.isBlank() ? fallback : value;
    }
  }
}
