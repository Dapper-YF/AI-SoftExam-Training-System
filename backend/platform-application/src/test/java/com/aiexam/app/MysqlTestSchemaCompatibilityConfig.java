package com.aiexam.app;

import java.util.Locale;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("mysql")
class MysqlTestSchemaCompatibilityConfig {
  @Bean
  ApplicationRunner mysqlSchemaCompatibilityRunner(JdbcTemplate jdbcTemplate) {
    return args -> {
      requireTestDatabase(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));

      if (tableExists(jdbcTemplate, "q_question_knowledge_binding")) {
        addColumnIfMissing(
            jdbcTemplate,
            "q_question_knowledge_binding",
            "sub_question_id",
            "ALTER TABLE q_question_knowledge_binding ADD COLUMN sub_question_id BIGINT NULL AFTER question_id");
        addColumnIfMissing(
            jdbcTemplate,
            "q_question_knowledge_binding",
            "updated_at",
            "ALTER TABLE q_question_knowledge_binding ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER created_at");
        jdbcTemplate.execute("""
            ALTER TABLE q_question_knowledge_binding
            MODIFY updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
            """);
      }

      if (tableExists(jdbcTemplate, "q_solution")) {
        addColumnIfMissing(
            jdbcTemplate,
            "q_solution",
            "ai_task_id",
            "ALTER TABLE q_solution ADD COLUMN ai_task_id BIGINT NULL AFTER content");
        addColumnIfMissing(
            jdbcTemplate,
            "q_solution",
            "prompt_version",
            "ALTER TABLE q_solution ADD COLUMN prompt_version VARCHAR(64) NULL AFTER ai_task_id");
        addColumnIfMissing(
            jdbcTemplate,
            "q_solution",
            "model_code",
            "ALTER TABLE q_solution ADD COLUMN model_code VARCHAR(64) NULL AFTER prompt_version");
      }
    };
  }

  static void requireTestDatabase(String databaseName) {
    if (databaseName == null
        || !databaseName.toLowerCase(Locale.ROOT).endsWith("_test")) {
      throw new IllegalStateException(
          "MySQL profile tests require a database name ending with _test; current database: "
              + databaseName);
    }
  }

  private void addColumnIfMissing(
      JdbcTemplate jdbcTemplate,
      String tableName,
      String columnName,
      String alterSql
  ) {
    if (columnMissing(jdbcTemplate, tableName, columnName)) {
      jdbcTemplate.execute(alterSql);
    }
  }

  private boolean columnMissing(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
    Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = ?
              AND column_name = ?
            """,
        Integer.class,
        tableName,
        columnName);
    return count == null || count == 0;
  }

  private boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
    Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name = ?
            """,
        Integer.class,
        tableName);
    return count != null && count > 0;
  }
}
