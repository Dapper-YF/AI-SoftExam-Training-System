package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthRealV10MigrationContractTest {
  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
  private static final Path V10 = MIGRATION_DIR.resolve("V10__auth_real_backend.sql");
  private static final Path V13 = MIGRATION_DIR.resolve("V13__auth_login_identifier.sql");

  @Test
  void hasExactlyOneV10SqlMigrationAndDoesNotRewriteV1ThroughV8() throws Exception {
    List<String> migrations;
    try (var files = Files.list(MIGRATION_DIR)) {
      migrations = files.map(path -> path.getFileName().toString())
          .sorted(java.util.Comparator.comparingInt(AuthRealV10MigrationContractTest::migrationVersion))
          .toList();
    }

    assertThat(migrations.stream().filter(name -> name.startsWith("V10__")).toList())
        .containsExactly("V10__auth_real_backend.sql");
    assertThat(migrations.stream().filter(name -> migrationVersion(name) <= 10).toList())
        .containsExactly(
            "V1__foundation.sql",
            "V2__question_practice.sql",
            "V3__essay_training.sql",
            "V4__ai_capability.sql",
            "V5__seed_data.sql",
            "V6__exam_simulator_session.sql",
            "V7__exam_event_sequence.sql",
            "V8__essay_knowledge.sql",
            "V10__auth_real_backend.sql");
  }

  @Test
  void v10ContainsRealAuthPersistenceObjectsAndNoPhysicalForeignKeys() throws Exception {
    String ddl = normalized();

    assertThat(ddl).contains(
        "create table sys_auth_credential",
        "create table sys_auth_session",
        "create table sys_auth_idempotency",
        "create table sys_auth_challenge",
        "create table sys_login_audit",
        "mobile_verified_at datetime(3) null",
        "password_changed_at datetime(3) null",
        "auth_version bigint not null default 0",
        "active_credential_key varchar(192) generated always as",
        "active_family_key varchar(36) generated always as (if(status = 'active', family_id, null)) stored")
        .doesNotContain("foreign key");
  }

  @Test
  void v10FreezesOperationalUniquenessChecksAndFailClosedMobilePreflight() throws Exception {
    String ddl = normalized();

    assertThat(ddl).contains(
        "auth_v10_validate_mobile",
        "signal sqlstate '45000' set message_text = 'auth_v10_mobile_precheck_failed'",
        "unique key uk_sys_user_mobile (mobile)",
        "unique key uk_auth_credential_active (active_credential_key)",
        "unique key uk_auth_session_active_family (active_family_key)",
        "unique key uk_auth_session_refresh_token_hash (refresh_token_hash)",
        "unique key uk_auth_idempotency_scope (operation, actor_scope, request_id)",
        "unique key uk_auth_challenge_idempotency (idempotency_id)",
        "constraint ck_auth_session_status_times check",
        "constraint ck_auth_idempotency_status_payload check",
        "constraint ck_auth_challenge_delivery_synthetic check",
        "key idx_login_audit_outcome_time (outcome, occurred_at)");
  }

  @Test
  void hasExactlyOneV13SqlMigrationForCanonicalLoginIdentifiers() throws Exception {
    List<String> migrations;
    try (var files = Files.list(MIGRATION_DIR)) {
      migrations = files.map(path -> path.getFileName().toString())
          .sorted(java.util.Comparator.comparingInt(AuthRealV10MigrationContractTest::migrationVersion))
          .toList();
    }

    assertThat(migrations.stream().filter(name -> name.startsWith("V13__")).toList())
        .containsExactly("V13__auth_login_identifier.sql");
  }

  @Test
  void v13DefinesPhoneEmailIdentifierTableAndDeterministicBackfill() throws Exception {
    String ddl = normalizedV13();

    assertThat(ddl).contains(
        "auth_v13_validate_login_identifiers",
        "signal sqlstate '45000' set message_text = 'auth_v13_login_identifier_precheck_failed'",
        "create table sys_auth_login_identifier",
        "identifier_type varchar(16) not null",
        "normalized_identifier varchar(320) collate utf8mb4_0900_bin not null",
        "unique key uk_auth_login_identifier_type_value (identifier_type, normalized_identifier)",
        "unique key uk_auth_login_identifier_user_type (user_id, identifier_type)",
        "foreign key (user_id) references sys_user(id)",
        "constraint ck_auth_login_identifier_type check (identifier_type in ('mobile', 'email'))",
        "char_length(normalized_identifier) <= 254",
        "char_length(substring_index(normalized_identifier, '@', 1)) <= 64",
        "substring_index(normalized_identifier, '@', 1) not like '.%'",
        "substring_index(normalized_identifier, '@', 1) not like '%.'",
        "substring_index(normalized_identifier, '@', 1) not like '%..%'",
        "insert into sys_auth_login_identifier",
        "row_number() over (order by id)",
        "'mobile'",
        "'email'",
        "lower(substring_index(trim(email), '@', -1))")
        .doesNotContain("credential_subject");
  }

  private static String normalized() throws Exception {
    return Files.readString(V10).replaceAll("\\s+", " ").toLowerCase();
  }

  private static String normalizedV13() throws Exception {
    return Files.readString(V13).replaceAll("\\s+", " ").toLowerCase();
  }

  private static int migrationVersion(String name) {
    int marker = name.indexOf("__");
    return Integer.parseInt(name.substring(1, marker));
  }
}
