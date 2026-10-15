package com.aiexam.app.migration.v9;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class V9ApprovalVerifierTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");
  private static final String SCHEMA = "ai_exam_v9_empty_a1b2c3d4e5f6_test";
  private static final String HOST = "a".repeat(64);
  private static final String ARTIFACT = "sha256:" + "b".repeat(64);
  private static KeyPair keyPair;
  private static V9ManifestBundle bundle;

  @BeforeAll
  static void setUp() throws Exception {
    keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    bundle = V9ManifestBundle.load();
  }

  @Test
  void acceptsSignedEnvelopeAtFrozenClockBoundaries() throws Exception {
    byte[] nonce = "single-use-test-nonce".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    ObjectNode envelope = envelope(nonce, NOW.plusSeconds(60), NOW.plusSeconds(61),
        SCHEMA, "migration_test@localhost");

    V9ExecutionApproval approval = verifier().verify(sign(envelope), nonce,
        keyPair.getPublic(), fixedClock(), true, bundle, ARTIFACT, SCHEMA, HOST,
        "migration_test@localhost", "migration_test", true);

    assertThat(approval.targetSchema()).isEqualTo(SCHEMA);
    assertThat(approval.flywayChecksumInt()).isEqualTo(bundle.flywayChecksum());
  }

  @Test
  void rejectsClockOutsideSkewAndTtlWithoutChangingIdentity() throws Exception {
    byte[] nonce = "clock-test-nonce".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    assertCode(sign(envelope(nonce, NOW.plusSeconds(61), NOW.plusSeconds(62),
        SCHEMA, "migration_test@localhost")), nonce, V9ErrorCode.V9_CLOCK_INVALID);
    assertCode(sign(envelope(nonce, NOW.minusSeconds(61), NOW.minusSeconds(60),
        SCHEMA, "migration_test@localhost")), nonce, V9ErrorCode.V9_CLOCK_INVALID);
    assertCode(sign(envelope(nonce, NOW, NOW.plusSeconds(901),
        SCHEMA, "migration_test@localhost")), nonce, V9ErrorCode.V9_CLOCK_INVALID);
  }

  @Test
  void rejectsDevelopmentAndNonAllowlistedTestSchemas() throws Exception {
    byte[] nonce = "target-test-nonce".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertCode(sign(envelope(nonce, NOW, NOW.plusSeconds(60), "ai_exam_platform",
        "migration_test@localhost")), nonce, V9ErrorCode.V9_DEVELOPMENT_DB_DENIED,
        "ai_exam_platform", "migration_test@localhost", "migration_test", true);
    assertCode(sign(envelope(nonce, NOW, NOW.plusSeconds(60), "random_test",
        "migration_test@localhost")), nonce, V9ErrorCode.V9_TEST_SCHEMA_INVALID,
        "random_test", "migration_test@localhost", "migration_test", true);
  }

  @Test
  void malformedPrincipalUsesPrecisePrincipalMismatchCode() throws Exception {
    byte[] nonce = "principal-test-nonce".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String signed = sign(envelope(nonce, NOW, NOW.plusSeconds(60), SCHEMA,
        "missing-host-part"));

    assertCode(signed, nonce, V9ErrorCode.V9_PRINCIPAL_MISMATCH, SCHEMA,
        "missing-host-part", "missing-host-part", true);
  }

  @Test
  void rejectsSignatureChecksumArtifactTargetPrincipalAndNonceDrift() throws Exception {
    byte[] nonce = "negative-approval-nonce".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    ObjectNode checksum = envelope(nonce, NOW, NOW.plusSeconds(60), SCHEMA,
        "migration_test@localhost");
    checksum.put("flywayChecksumInt", bundle.flywayChecksum() + 1);
    assertCode(sign(checksum), nonce, V9ErrorCode.V9_CHECKSUM_MISMATCH);

    ObjectNode artifact = envelope(nonce, NOW, NOW.plusSeconds(60), SCHEMA,
        "migration_test@localhost");
    artifact.put("artifactDigest", "sha256:" + "c".repeat(64));
    assertCode(sign(artifact), nonce, V9ErrorCode.V9_ARTIFACT_MISMATCH);

    ObjectNode host = envelope(nonce, NOW, NOW.plusSeconds(60), SCHEMA,
        "migration_test@localhost");
    host.put("targetDbHostFingerprint", "d".repeat(64));
    assertCode(sign(host), nonce, V9ErrorCode.V9_TARGET_MISMATCH);

    ObjectNode principal = envelope(nonce, NOW, NOW.plusSeconds(60), SCHEMA,
        "other_user@localhost");
    assertCode(sign(principal), nonce, V9ErrorCode.V9_PRINCIPAL_MISMATCH);

    assertCode(sign(envelope(nonce, NOW, NOW.plusSeconds(60), SCHEMA,
        "migration_test@localhost")), "different-nonce".getBytes(
            java.nio.charset.StandardCharsets.UTF_8), V9ErrorCode.V9_APPROVAL_INVALID);

    String signed = sign(envelope(nonce, NOW, NOW.plusSeconds(60), SCHEMA,
        "migration_test@localhost"));
    ObjectNode tampered = (ObjectNode) MAPPER.readTree(signed);
    tampered.put("signingKeyId", "tampered-key");
    assertCode(MAPPER.writeValueAsString(tampered), nonce, V9ErrorCode.V9_APPROVAL_INVALID);
  }

  @Test
  void acceptsSignedFormalTargetButNeverDevelopmentDatabase() throws Exception {
    byte[] nonce = "formal-target-nonce".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String schema = "soft_exam_production";
    String principal = "migration_prod@localhost";
    String signed = sign(envelope(nonce, NOW, NOW.plusSeconds(60), schema, principal));

    V9ExecutionApproval approval = verifier().verify(signed, nonce, keyPair.getPublic(),
        fixedClock(), true, bundle, ARTIFACT, schema, HOST, principal,
        "migration_prod", false);

    assertThat(approval.targetSchema()).isEqualTo(schema);
  }

  private static V9ApprovalVerifier verifier() {
    return new V9ApprovalVerifier();
  }

  private static Clock fixedClock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private static ObjectNode envelope(byte[] nonce, Instant issuedAt, Instant expiresAt,
      String schema, String principal) {
    ObjectNode envelope = MAPPER.createObjectNode();
    envelope.put("migrationVersion", 9);
    envelope.put("fullManifestSha256", bundle.fullManifestSha256());
    envelope.put("flywayChecksumInt", bundle.flywayChecksum());
    envelope.put("artifactDigest", ARTIFACT);
    envelope.put("targetSchema", schema);
    envelope.put("targetDbHostFingerprint", HOST);
    envelope.put("migrationDbPrincipal", principal);
    envelope.put("approvalId", UUID.randomUUID().toString());
    envelope.put("issuedAt", issuedAt.toString());
    envelope.put("expiresAt", expiresAt.toString());
    envelope.put("singleUseNonceHash", JcsCanonicalizer.sha256Hex(nonce));
    envelope.put("signingKeyId", "test-key-1");
    envelope.put("signatureAlgorithm", "Ed25519");
    return envelope;
  }

  private static String sign(ObjectNode envelope) throws Exception {
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(keyPair.getPrivate());
    signature.update(JcsCanonicalizer.canonicalize(envelope));
    envelope.put("approvalSignature",
        Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    return MAPPER.writeValueAsString(envelope);
  }

  private static void assertCode(String envelope, byte[] nonce, V9ErrorCode code) {
    assertCode(envelope, nonce, code, SCHEMA, "migration_test@localhost",
        "migration_test", true);
  }

  private static void assertCode(String envelope, byte[] nonce, V9ErrorCode code,
      String schema, String principal, String username, boolean testApproval) {
    assertThatThrownBy(() -> verifier().verify(envelope, nonce, keyPair.getPublic(),
        fixedClock(), true, bundle, ARTIFACT, schema, HOST, principal, username,
        testApproval))
        .isInstanceOfSatisfying(V9MigrationException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(code));
  }
}
