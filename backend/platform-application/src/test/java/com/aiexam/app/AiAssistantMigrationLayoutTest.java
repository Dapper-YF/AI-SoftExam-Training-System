package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AiAssistantMigrationLayoutTest {
  private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.+\\.sql$");

  @Test
  void flywayMigrationVersionsAreUniqueAndAssistantSchemaUsesV23AndV24() throws Exception {
    Path migrations = Path.of("src/main/resources/db/migration");
    HashSet<String> versions = new HashSet<>();
    try (var files = Files.list(migrations)) {
      files.map(path -> path.getFileName().toString()).forEach(name -> {
        Matcher matcher = VERSION.matcher(name);
        if (matcher.matches()) assertThat(versions.add(matcher.group(1))).as("duplicate migration version %s", matcher.group(1)).isTrue();
      });
    }
    assertThat(Files.exists(migrations.resolve("V23__ai_assistant.sql"))).isTrue();
    assertThat(Files.exists(migrations.resolve("V24__ai_assistant_audit_and_knowledge_version.sql"))).isTrue();
    assertThat(Files.exists(migrations.resolve("V16__ai_assistant_knowledge.sql"))).isFalse();
  }
}
