package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MigrationBaselineTest {
  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

  @Test
  void migrationFilesAreSplitByDocumentedModules() {
    assertThat(MIGRATION_DIR.resolve("V1__foundation.sql")).exists();
    assertThat(MIGRATION_DIR.resolve("V2__question_practice.sql")).exists();
    assertThat(MIGRATION_DIR.resolve("V3__essay_training.sql")).exists();
    assertThat(MIGRATION_DIR.resolve("V4__ai_capability.sql")).exists();
    assertThat(MIGRATION_DIR.resolve("V5__seed_data.sql")).exists();
  }

  @Test
  void questionPracticeMigrationContainsDetailedQuestionBankTables() throws IOException {
    String ddl = read("V2__question_practice.sql");

    assertThat(ddl).contains(
        "CREATE TABLE q_question",
        "CREATE TABLE q_question_stem",
        "CREATE TABLE q_sub_question",
        "CREATE TABLE q_choice_option",
        "CREATE TABLE q_answer_key",
        "CREATE TABLE q_solution",
        "CREATE TABLE q_scoring_rule",
        "CREATE TABLE q_question_knowledge_binding");
  }

  @Test
  void questionPracticeMigrationMatchesRepositoryWriteContract() throws IOException {
    String ddl = read("V2__question_practice.sql").replace("\r\n", "\n");

    assertThat(ddl).contains(
        """
          content LONGTEXT NOT NULL,
          ai_task_id BIGINT NULL,
          prompt_version VARCHAR(64) NULL,
          model_code VARCHAR(64) NULL,
          source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
        """,
        """
          question_id BIGINT NOT NULL,
          sub_question_id BIGINT NULL,
          knowledge_type VARCHAR(32) NOT NULL COMMENT 'SYLLABUS/CHAPTER/TAG',
        """,
        "updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)");
  }

  @Test
  void foundationMigrationContainsKnowledgePointTables() throws IOException {
    String ddl = read("V1__foundation.sql");

    assertThat(ddl).contains(
        "CREATE TABLE kp_syllabus_node",
        "CREATE TABLE kp_chapter_node",
        "CREATE TABLE kp_tag",
        "CREATE TABLE kp_relation",
        "CREATE TABLE kp_mastery_stat");
  }

  @Test
  void migrationsDoNotCreatePhysicalForeignKeys() throws IOException {
    for (String filename : List.of(
        "V1__foundation.sql",
        "V2__question_practice.sql",
        "V3__essay_training.sql",
        "V4__ai_capability.sql")) {
      assertThat(read(filename).toLowerCase()).doesNotContain("foreign key");
    }
  }

  @Test
  void aiTaskUsesLogicalBusinessSourceOnly() throws IOException {
    String ddl = read("V4__ai_capability.sql");

    assertThat(ddl).contains("biz_type VARCHAR(64) NOT NULL");
    assertThat(ddl).contains("biz_id BIGINT NULL");
    assertThat(ddl).contains("scenario VARCHAR(96) NOT NULL");
  }

  @Test
  void questionAndEssayMigrationsDoNotCrossReferenceEachOther() throws IOException {
    String questionDdl = read("V2__question_practice.sql").toLowerCase();
    String essayDdl = read("V3__essay_training.sql").toLowerCase();

    assertThat(Pattern.compile("\\bessay_(project|material|draft|review)[a-z0-9_]*\\b").matcher(questionDdl).find()).isFalse();
    assertThat(Pattern.compile("\\bq_(question|sub_question|paper|choice|answer|solution|scoring|explanation)[a-z0-9_]*\\b")
        .matcher(essayDdl).find()).isFalse();
    assertThat(Pattern.compile("\\bpractice_(attempt|exam|mistake|favorite|note)[a-z0-9_]*\\b").matcher(essayDdl).find()).isFalse();
  }

  private String read(String filename) throws IOException {
    return Files.readString(MIGRATION_DIR.resolve(filename));
  }
}
