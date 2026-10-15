package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ExamSimulatorV6MigrationContractTest {
  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
  private static final Path V6 = MIGRATION_DIR.resolve("V6__exam_simulator_session.sql");
  private static final Set<String> NEW_TABLES = Set.of(
      "practice_exam_batch",
      "practice_exam_entry",
      "practice_exam_entry_subject",
      "practice_exam_candidate",
      "practice_exam_session",
      "practice_exam_rule_snapshot",
      "practice_exam_session_subject",
      "practice_exam_event",
      "practice_exam_idempotency");
  private static final Set<String> ALTERED_TABLES = Set.of(
      "q_paper",
      "practice_attempt",
      "practice_attempt_answer",
      "practice_exam_report");

  @Test
  void hasExactlyOneFrozenV6Migration() throws IOException {
    List<String> v6Files;
    try (var files = Files.list(MIGRATION_DIR)) {
      v6Files = files
          .map(path -> path.getFileName().toString())
          .filter(name -> name.startsWith("V6__"))
          .sorted()
          .toList();
    }

    assertThat(v6Files).containsExactly("V6__exam_simulator_session.sql");
  }

  @Test
  void matchesTheFrozenDatabaseDesignSqlBlockExactly() throws IOException {
    assertThat(V6).exists();

    String design = Files.readString(databaseDesignPath());
    int sectionStart = design.indexOf("## 5. 机考模拟 V6 增量模型");
    int sqlStart = design.indexOf("```sql", sectionStart) + "```sql".length();
    int sqlEnd = design.indexOf("```", sqlStart);

    assertThat(sectionStart).isNotNegative();
    assertThat(sqlStart).isGreaterThan("```sql".length() - 1);
    assertThat(sqlEnd).isGreaterThan(sqlStart);
    assertThat(normalizeSql(Files.readString(V6)))
        .isEqualTo(normalizeSql(design.substring(sqlStart, sqlEnd)));
  }

  @Test
  void createsNineTablesAndAppliesFourFrozenAlters() throws IOException {
    String ddl = Files.readString(V6);

    assertThat(extractNames(ddl, "CREATE\\s+TABLE\\s+([a-z0-9_]+)"))
        .containsExactlyInAnyOrderElementsOf(NEW_TABLES);
    assertThat(extractNames(ddl, "ALTER\\s+TABLE\\s+([a-z0-9_]+)"))
        .containsExactlyInAnyOrderElementsOf(ALTERED_TABLES);
  }

  @Test
  void containsFrozenVersioningAndConcurrencyConstraints() throws IOException {
    String ddl = Files.readString(V6);

    assertThat(ddl).contains(
        "ADD COLUMN content_version BIGINT NOT NULL DEFAULT 1",
        "UNIQUE KEY uk_exam_session_active (active_session_key)",
        "UNIQUE KEY uk_exam_subject_submit_key (submit_idempotency_key)",
        "UNIQUE KEY uk_exam_subject_result_no (submit_result_no)",
        "UNIQUE KEY uk_exam_idempotency_mutation (session_id, operation_code, mutation_id)",
        "ADD COLUMN answer_version BIGINT NOT NULL DEFAULT 0",
        "ADD COLUMN last_client_mutation_id CHAR(36) NULL",
        "ADD UNIQUE KEY uk_practice_attempt_exam_subject (exam_session_subject_id)");
    assertThat(ddl.toLowerCase(Locale.ROOT))
        .doesNotContain("foreign key", "password");
  }

  private Set<String> extractNames(String ddl, String regex) {
    Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(ddl);
    Set<String> names = matcher.results()
        .map(result -> result.group(1).toLowerCase(Locale.ROOT))
        .collect(Collectors.toSet());
    assertThat(names).hasSize(matcherCount(ddl, regex));
    return names;
  }

  private int matcherCount(String ddl, String regex) {
    Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(ddl);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  private Path databaseDesignPath() {
    Path moduleDir = Path.of("").toAbsolutePath();
    return moduleDir.resolve("../../docs/数据库设计/p0-logical-data-model-ddl-2026-07-09.md")
        .normalize();
  }

  private String normalizeSql(String sql) {
    return sql.replace("\r\n", "\n").trim();
  }
}
