package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MysqlTestDatabaseGuardTest {

  @Test
  void acceptsDedicatedTestDatabase() {
    assertThatCode(() -> MysqlTestSchemaCompatibilityConfig.requireTestDatabase("ai_exam_platform_test"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsDevelopmentDatabase() {
    assertThatThrownBy(
            () -> MysqlTestSchemaCompatibilityConfig.requireTestDatabase("ai_exam_platform"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("_test")
        .hasMessageContaining("ai_exam_platform");
  }

  @Test
  void rejectsMissingDatabaseName() {
    assertThatThrownBy(() -> MysqlTestSchemaCompatibilityConfig.requireTestDatabase(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("_test");
  }
}
