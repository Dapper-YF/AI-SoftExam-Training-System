package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.JavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

class SoftExamTaxonomyV9MigrationGateTest {
  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
  private static final Path MANIFEST = Path.of(
      "src/main/resources/db/migration-manifest/V9__soft_exam_taxonomy_rule_seed.manifest.json");

  @Test
  void flywaySqlLocationContainsExpectedVersionedMigrations() throws Exception {
    List<String> migrations;
    try (var files = Files.list(MIGRATION_DIR)) {
      migrations = files.filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
          .map(path -> path.getFileName().toString())
          .sorted()
          .toList();
    }
    assertThat(migrations).contains(
        "V10__auth_real_backend.sql", "V11__question_admin_versioning_import.sql",
        "V12__practice_attempt_persistence.sql", "V13__auth_login_identifier.sql",
        "V1__foundation.sql", "V2__question_practice.sql", "V3__essay_training.sql",
        "V4__ai_capability.sql", "V5__seed_data.sql", "V6__exam_simulator_session.sql",
        "V7__exam_event_sequence.sql", "V8__essay_knowledge.sql",
        "V20__learning_resource_p1.sql");
  }

  @Test
  void v9IsOneDirectPublicFinalJavaMigrationWithFiveExplicitMethods() throws Exception {
    Class<?> type = Class.forName(
        "com.aiexam.app.migration.V9SoftExamTaxonomyRuleSeedMigration");

    assertThat(type.getInterfaces()).containsExactly(JavaMigration.class);
    assertThat(Modifier.isPublic(type.getModifiers())).isTrue();
    assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
    assertThat(type.getDeclaredMethod("getVersion").getReturnType())
        .isEqualTo(MigrationVersion.class);
    assertThat(type.getDeclaredMethod("getDescription").getReturnType())
        .isEqualTo(String.class);
    assertThat(type.getDeclaredMethod("getChecksum").getReturnType())
        .isEqualTo(Integer.class);
    assertThat(type.getDeclaredMethod("canExecuteInTransaction").getReturnType())
        .isEqualTo(boolean.class);
    assertThat(type.getDeclaredMethod("migrate", Context.class).getReturnType())
        .isEqualTo(void.class);
  }

  @Test
  void frozenJcsVectorHasRequiredLengthHashAndSignedChecksum() throws Exception {
    String vector = "{\"description\":\"soft exam taxonomy rule seed\","
        + "\"manifestSchemaVersion\":1,\"migrationVersion\":\"9\"}";
    byte[] bytes = vector.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);

    assertThat(bytes).hasSize(95);
    assertThat(HexFormat.of().formatHex(digest))
        .isEqualTo("2637a938ae9a24ba676e5854410e65563211b8128ae26c58ea4e4f09b6265681");
    assertThat(java.nio.ByteBuffer.wrap(digest).getInt()).isEqualTo(641182008);

    Class<?> canonicalizer = Class.forName("com.aiexam.app.migration.v9.JcsCanonicalizer");
    var canonicalize = canonicalizer.getDeclaredMethod(
        "canonicalize", com.fasterxml.jackson.databind.JsonNode.class);
    canonicalize.setAccessible(true);
    byte[] actual = (byte[]) canonicalize.invoke(null,
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(vector));
    assertThat(actual).isEqualTo(bytes);
  }

  @Test
  void canonicalManifestExistsOutsideFlywaySqlLocation() {
    assertThat(Files.isRegularFile(MANIFEST)).isTrue();
    assertThatCode(() -> Class.forName("com.aiexam.app.migration.v9.V9MigrationOnlyRunner"))
        .doesNotThrowAnyException();
  }

  @Test
  void manifestBundleUsesJcsChecksumAndVerifiesAllSevenPayloads() throws Exception {
    Class<?> bundleType = Class.forName("com.aiexam.app.migration.v9.V9ManifestBundle");
    Object bundle = bundleType.getMethod("load").invoke(null);
    String hash = (String) bundleType.getMethod("fullManifestSha256").invoke(bundle);
    int checksum = (int) bundleType.getMethod("flywayChecksum").invoke(bundle);

    assertThat(hash).matches("[0-9a-f]{64}");
    assertThat(checksum).isEqualTo(java.nio.ByteBuffer.wrap(HexFormat.of().parseHex(hash)).getInt());
    var runtimeChecksum = Class.forName("com.aiexam.app.migration.V9RuntimeSchemaValidator")
        .getDeclaredField("EXPECTED_FLYWAY_CHECKSUM");
    runtimeChecksum.setAccessible(true);
    assertThat(runtimeChecksum.getInt(null)).isEqualTo(checksum);
    assertThat(Files.list(Path.of("src/main/resources/db/migration-payload/v9")))
        .hasSize(7);
  }

  @Test
  void errorCodeSetIsExactlyTheFrozenTwentyFourCodes() throws Exception {
    Class<?> errorCode = Class.forName("com.aiexam.app.migration.v9.V9ErrorCode");
    Set<String> actual = java.util.Arrays.stream(errorCode.getEnumConstants())
        .map(Object::toString)
        .collect(java.util.stream.Collectors.toSet());
    assertThat(actual).containsExactlyInAnyOrder(
        "V9_APPROVAL_MISSING", "V9_APPROVAL_INVALID", "V9_APPROVAL_REPLAYED",
        "V9_CLOCK_INVALID", "V9_TARGET_MISMATCH", "V9_PRINCIPAL_MISMATCH",
        "V9_CHECKSUM_MISMATCH", "V9_ARTIFACT_MISMATCH", "V9_MANIFEST_INVALID",
        "V9_DUPLICATE_MIGRATION", "V9_DEVELOPMENT_DB_DENIED", "V9_TEST_SCHEMA_INVALID",
        "V9_BACKUP_INVALID", "V9_DEPLOYMENT_BUSY", "V9_CONTROL_PLANE_UNAVAILABLE",
        "V9_LEDGER_UNAVAILABLE", "V9_FENCE_REJECTED", "V9_GUARD_LOCK_LOST",
        "V9_TRANSACTION_STATE_UNKNOWN", "V9_DDL_STATE_DRIFT",
        "V9_DML_FINGERPRINT_MISMATCH", "V9_REPAIR_FORBIDDEN",
        "V9_RESTORE_REQUIRED", "V9_RUNTIME_SCHEMA_NOT_READY");
  }

  @Test
  void normalApplicationDisablesFlywayAndUsesReadOnlyRuntimeValidator() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
    String application = Files.readString(Path.of(
        "src/main/java/com/aiexam/app/AiExamPlatformApplication.java"));
    String startupValidation = Files.readString(Path.of(
        "src/main/java/com/aiexam/app/migration/MysqlRuntimeSchemaValidationConfiguration.java"));
    String validator = Files.readString(Path.of(
        "src/main/java/com/aiexam/app/migration/V9RuntimeSchemaValidator.java"));

    assertThat(yaml).contains("flyway:", "enabled: false").doesNotContain("target: 8");
    assertThat(application).doesNotContain("V9SoftExamTaxonomyRuleSeedMigration");
    assertThat(startupValidation).contains("V9RuntimeSchemaValidator");
    assertThat(validator).doesNotContain("V9ManifestBundle");
    assertThat(validator).doesNotContain("V9DatabaseFingerprints", "!= 20", "!= 45");
    assertThatCode(() -> Class.forName("com.aiexam.app.migration.V9RuntimeSchemaValidator"))
        .doesNotThrowAnyException();
  }

  @Test
  void authorizedDatabaseOrchestratorAndDeploymentControlPortsExist() {
    assertThatCode(() -> Class.forName(
        "com.aiexam.app.migration.v9.V9AuthorizedDatabaseOrchestrator"))
        .doesNotThrowAnyException();
    assertThatCode(() -> Class.forName(
        "com.aiexam.app.migration.deploymentcontrol.DeploymentControlClient"))
        .doesNotThrowAnyException();
    assertThatCode(() -> Class.forName(
        "com.aiexam.app.migration.v9.V9ApprovalVerifier"))
        .doesNotThrowAnyException();
  }

  @Test
  void manifestRejectsUnknownNestedFieldsAndDefinitionHashDrift() throws Exception {
    Class<?> bundleType = Class.forName("com.aiexam.app.migration.v9.V9ManifestBundle");
    var parse = bundleType.getMethod("parse", byte[].class);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var original = mapper.readTree(Files.readAllBytes(MANIFEST));

    var unknown = original.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) unknown.path("steps").get(0))
        .put("unknown", true);
    assertThatThrownBy(() -> parse.invoke(null, mapper.writeValueAsBytes(unknown)))
        .hasRootCauseInstanceOf(RuntimeException.class);

    var drift = original.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) drift.path("targetDefinitions").get(0))
        .put("canonicalDefinition", "drift");
    assertThatThrownBy(() -> parse.invoke(null, mapper.writeValueAsBytes(drift)))
        .hasRootCauseInstanceOf(RuntimeException.class);

    var fingerprintDrift = original.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) fingerprintDrift)
        .put("expectedSeedFingerprint", "f".repeat(64));
    assertThatThrownBy(() -> parse.invoke(null, mapper.writeValueAsBytes(fingerprintDrift)))
        .hasRootCauseInstanceOf(RuntimeException.class);
  }

  @Test
  void manifestContainsNoPlaceholderFingerprints() throws Exception {
    var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(MANIFEST));
    String zeroHash = "0".repeat(64);

    assertThat(root.path("dmlBatches").findValuesAsText("expectedBatchFingerprint"))
        .doesNotContain(zeroHash);
    assertThat(List.of("legacyConverterSha256", "legacyManifestSha256",
        "expectedFinalSchemaFingerprint", "expectedSeedFingerprint",
        "expectedBusinessFingerprint"))
        .allSatisfy(field -> assertThat(root.path(field).asText()).isNotEqualTo(zeroHash));
  }
}
