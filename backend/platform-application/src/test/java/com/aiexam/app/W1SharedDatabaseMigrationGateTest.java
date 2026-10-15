package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "W1_SHARED_DB_MIGRATION", matches = "true")
class W1SharedDatabaseMigrationGateTest {
  private static final String EXPECTED_URL_PREFIX =
      "jdbc:mysql://127.0.0.1:33307/ai_exam_platform?";
  private static final String EXPECTED_BACKUP_SHA256 =
      "3DFFA1522FCC64196CC19D4E066CD08A99ED0D6BEE1D4DB2CB37D32441C9958A";
  private static final Map<String, Integer> EXPECTED_V1_TO_V10_CHECKSUMS = checksums();

  @Test
  void verifiesBackupAndBaselineBeforeMigratingSharedDatabaseToV12() throws Exception {
    String url = required("W1_SHARED_DB_URL");
    String username = required("W1_SHARED_DB_USERNAME");
    String password = required("W1_SHARED_DB_PASSWORD");
    String migrationUsername = required("W1_SHARED_DB_MIGRATION_USERNAME");
    String migrationPassword = optional("W1_SHARED_DB_MIGRATION_PASSWORD");
    Path backup = Path.of(required("W1_SHARED_DB_BACKUP"));

    assertThat(url).startsWith(EXPECTED_URL_PREFIX);
    assertThat(Files.isRegularFile(backup)).isTrue();
    assertThat(sha256(backup)).isEqualTo(EXPECTED_BACKUP_SHA256);

    var dataSource = new DriverManagerDataSource(url, username, password);
    var jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
        .isEqualTo("ai_exam_platform");
    assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(33307);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_attempt", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_mistake", Integer.class)).isZero();
    assertThat(tableCount(jdbc, "q_question_version")).isZero();
    assertThat(tableCount(jdbc, "practice_attempt_idempotency")).isZero();

    Map<String, Integer> actualChecksums = new LinkedHashMap<>();
    jdbc.queryForList("""
        SELECT version, checksum
        FROM flyway_schema_history
        WHERE success = 1
        ORDER BY installed_rank
        """).forEach(row -> actualChecksums.put(
            (String) row.get("version"), ((Number) row.get("checksum")).intValue()));
    assertThat(actualChecksums).containsExactlyEntriesOf(EXPECTED_V1_TO_V10_CHECKSUMS);

    int failedV11 = jdbc.queryForObject("""
        SELECT COUNT(*) FROM flyway_schema_history
        WHERE version = '11' AND success = 0
        """, Integer.class);
    assertThat(failedV11).isBetween(0, 1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class))
        .isEqualTo(10 + failedV11);
    if (failedV11 == 1) {
      assertThat(tableCount(jdbc, "q_question_import_batch")).isZero();
      assertThat(columnCount(jdbc, "q_question", "current_published_version_id")).isZero();
    }

    Flyway flyway = Flyway.configure()
        .dataSource(url, migrationUsername, migrationPassword)
        .locations("classpath:db/migration")
        .javaMigrations(new AppliedV9Descriptor())
        .target("12")
        .load();
    if (failedV11 == 1) {
      flyway.repair();
      assertThat(jdbc.queryForObject("""
          SELECT COUNT(*) FROM flyway_schema_history
          WHERE version = '11' AND success = 0
          """, Integer.class)).isZero();
    }
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();

    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM flyway_schema_history
        WHERE version IN ('11', '12') AND success = 1
        """, Integer.class)).isEqualTo(2);
    assertThat(tableCount(jdbc, "q_question_version")).isEqualTo(1);
    assertThat(tableCount(jdbc, "practice_attempt_idempotency")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_attempt", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_mistake", Integer.class)).isZero();

    System.out.println("W1_SHARED_DB_MIGRATION=V1-V10->V11->V12");
    System.out.println("W1_SHARED_DB_SECOND_MIGRATE=0");
    System.out.println("W1_SHARED_DB_FAILED_V11_REPAIRED=" + failedV11);
    System.out.println("W1_SHARED_DB_BACKUP_SHA256=" + EXPECTED_BACKUP_SHA256);
  }

  private int tableCount(JdbcTemplate jdbc, String tableName) {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = ?
        """, Integer.class, tableName);
  }

  private int columnCount(JdbcTemplate jdbc, String tableName, String columnName) {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
        """, Integer.class, tableName, columnName);
  }

  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().withUpperCase().formatHex(digest.digest());
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing environment variable: " + name);
    }
    return value;
  }

  private static String optional(String name) {
    String value = System.getenv(name);
    return value == null ? "" : value;
  }

  private static Map<String, Integer> checksums() {
    Map<String, Integer> checksums = new LinkedHashMap<>();
    checksums.put("1", -132374234);
    checksums.put("2", 1356091701);
    checksums.put("3", -1798192223);
    checksums.put("4", -1977585507);
    checksums.put("5", -609164272);
    checksums.put("6", -1636565222);
    checksums.put("7", -4616090);
    checksums.put("8", 190435648);
    checksums.put("9", 1699861289);
    checksums.put("10", -2063583817);
    return checksums;
  }

  private static final class AppliedV9Descriptor implements JavaMigration {
    @Override
    public MigrationVersion getVersion() {
      return MigrationVersion.fromVersion("9");
    }

    @Override
    public String getDescription() {
      return "soft exam taxonomy rule seed";
    }

    @Override
    public Integer getChecksum() {
      return EXPECTED_V1_TO_V10_CHECKSUMS.get("9");
    }

    @Override
    public boolean canExecuteInTransaction() {
      return false;
    }

    @Override
    public void migrate(Context context) {
      throw new IllegalStateException("V9 must already be present on the shared baseline");
    }
  }
}
