package com.aiexam.app.migration.v9;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class V9ApprovalVerifier {
  private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private static final Pattern TEST_SCHEMA = Pattern.compile(
      "^ai_exam_v9_(empty|upgrade|legacy|failure)_[0-9a-f]{12}_test$");
  private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> FIELDS = Set.of(
      "migrationVersion", "fullManifestSha256", "flywayChecksumInt", "artifactDigest",
      "targetSchema", "targetDbHostFingerprint", "migrationDbPrincipal", "approvalId",
      "issuedAt", "expiresAt", "singleUseNonceHash", "signingKeyId",
      "signatureAlgorithm", "approvalSignature");

  public V9ExecutionApproval verify(String envelopeJson, byte[] nonce, PublicKey signingKey,
      Clock clock, boolean clockHealthy, V9ManifestBundle bundle, String artifactDigest,
      String targetSchema, String hostFingerprint, String principal, String mysqlUsername,
      boolean testApproval) {
    if (envelopeJson == null || envelopeJson.isBlank() || nonce == null || nonce.length == 0) {
      throw failure(V9ErrorCode.V9_APPROVAL_MISSING, "approval envelope or nonce is missing");
    }
    try {
      JsonNode parsed = MAPPER.readTree(envelopeJson);
      if (!parsed.isObject() || !fieldNames(parsed).equals(FIELDS)) {
        throw failure(V9ErrorCode.V9_APPROVAL_INVALID, "approval fields are invalid");
      }
      ObjectNode envelope = (ObjectNode) parsed;
      if (envelope.path("migrationVersion").asInt(-1) != 9
          || !"Ed25519".equals(text(envelope, "signatureAlgorithm"))) {
        throw failure(V9ErrorCode.V9_APPROVAL_INVALID, "approval identity is invalid");
      }
      verifySignature(envelope, signingKey);
      Instant issuedAt = Instant.parse(text(envelope, "issuedAt"));
      Instant expiresAt = Instant.parse(text(envelope, "expiresAt"));
      verifyClock(clock.instant(), issuedAt, expiresAt, clockHealthy);
      String manifestHash = text(envelope, "fullManifestSha256");
      int checksum = envelope.path("flywayChecksumInt").intValue();
      if (!manifestHash.equals(bundle.fullManifestSha256())
          || checksum != bundle.flywayChecksum()) {
        throw failure(V9ErrorCode.V9_CHECKSUM_MISMATCH, "manifest checksum mismatch");
      }
      if (!text(envelope, "artifactDigest").equals(artifactDigest)) {
        throw failure(V9ErrorCode.V9_ARTIFACT_MISMATCH, "artifact digest mismatch");
      }
      if (!text(envelope, "targetSchema").equals(targetSchema)
          || !text(envelope, "targetDbHostFingerprint").equals(hostFingerprint)) {
        throw failure(V9ErrorCode.V9_TARGET_MISMATCH, "database target mismatch");
      }
      if ("ai_exam_platform".equals(targetSchema)) {
        throw failure(V9ErrorCode.V9_DEVELOPMENT_DB_DENIED, "development database is denied");
      }
      if (testApproval && !TEST_SCHEMA.matcher(targetSchema).matches()) {
        throw failure(V9ErrorCode.V9_TEST_SCHEMA_INVALID, "test schema is outside the allowlist");
      }
      String envelopePrincipal = text(envelope, "migrationDbPrincipal");
      int hostSeparator = principal.indexOf('@');
      if (!envelopePrincipal.equals(principal)
          || hostSeparator <= 0
          || !principal.substring(0, hostSeparator).equals(mysqlUsername)) {
        throw failure(V9ErrorCode.V9_PRINCIPAL_MISMATCH, "migration principal mismatch");
      }
      String nonceHash = JcsCanonicalizer.sha256Hex(nonce);
      if (!nonceHash.equals(text(envelope, "singleUseNonceHash"))) {
        throw failure(V9ErrorCode.V9_APPROVAL_INVALID, "nonce hash mismatch");
      }
      return new V9ExecutionApproval(
          UUID.fromString(text(envelope, "approvalId")), manifestHash, checksum,
          artifactDigest, targetSchema, hostFingerprint, principal, issuedAt, expiresAt,
          nonceHash, text(envelope, "signingKeyId"));
    } catch (V9MigrationException exception) {
      throw exception;
    } catch (DateTimeParseException | IllegalArgumentException exception) {
      throw failure(V9ErrorCode.V9_APPROVAL_INVALID, "approval value is invalid", exception);
    } catch (Exception exception) {
      throw failure(V9ErrorCode.V9_APPROVAL_INVALID, "approval verification failed", exception);
    }
  }

  private static void verifySignature(ObjectNode envelope, PublicKey key) throws Exception {
    String encodedSignature = text(envelope, "approvalSignature");
    ObjectNode payload = envelope.deepCopy();
    payload.remove("approvalSignature");
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(key);
    verifier.update(JcsCanonicalizer.canonicalize(payload));
    if (!verifier.verify(Base64.getUrlDecoder().decode(encodedSignature))) {
      throw failure(V9ErrorCode.V9_APPROVAL_INVALID, "approval signature is invalid");
    }
  }

  private static void verifyClock(Instant now, Instant issuedAt, Instant expiresAt,
      boolean clockHealthy) {
    Duration ttl = Duration.between(issuedAt, expiresAt);
    if (!clockHealthy || ttl.isNegative() || ttl.isZero()
        || ttl.compareTo(Duration.ofMinutes(15)) > 0
        || issuedAt.isAfter(now.plusSeconds(60))
        || !expiresAt.isAfter(now.minusSeconds(60))) {
      throw failure(V9ErrorCode.V9_CLOCK_INVALID, "approval clock window is invalid");
    }
  }

  private static Set<String> fieldNames(JsonNode node) {
    java.util.HashSet<String> names = new java.util.HashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw failure(V9ErrorCode.V9_APPROVAL_INVALID, "approval text field is missing");
    }
    String text = value.textValue();
    if ((field.endsWith("Sha256") || field.endsWith("Fingerprint"))
        && !HEX64.matcher(text).matches()) {
      throw failure(V9ErrorCode.V9_APPROVAL_INVALID, "approval hash field is invalid");
    }
    if ("artifactDigest".equals(field)
        && !text.matches("sha256:[0-9a-f]{64}")) {
      throw failure(V9ErrorCode.V9_APPROVAL_INVALID, "artifact digest is invalid");
    }
    return text;
  }

  private static V9MigrationException failure(V9ErrorCode code, String message) {
    return new V9MigrationException(code, message);
  }

  private static V9MigrationException failure(V9ErrorCode code, String message,
      Throwable cause) {
    return new V9MigrationException(code, message, cause);
  }
}
