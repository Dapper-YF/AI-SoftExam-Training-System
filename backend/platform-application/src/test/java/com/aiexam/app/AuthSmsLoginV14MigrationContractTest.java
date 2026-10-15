package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AuthSmsLoginV14MigrationContractTest {
  private static final Path MIGRATION = Path.of(
      "src/main/resources/db/migration/V14__auth_sms_login.sql");

  @Test
  void v14OnlyExtendsExistingAuthConstraintValues() throws Exception {
    String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
    String normalized = sql.toLowerCase(Locale.ROOT);

    assertThat(sql).contains("SMS_LOGIN_CHALLENGE", "SMS_LOGIN");
    assertThat(normalized).contains("alter table sys_auth_idempotency", "alter table sys_auth_challenge");
    assertThat(normalized).doesNotContain("create table");
  }
}
