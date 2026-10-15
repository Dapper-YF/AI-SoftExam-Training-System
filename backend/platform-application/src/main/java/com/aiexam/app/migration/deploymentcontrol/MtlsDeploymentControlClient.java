package com.aiexam.app.migration.deploymentcontrol;

import com.aiexam.app.migration.v9.V9ErrorCode;
import com.aiexam.app.migration.v9.V9MigrationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.net.ssl.SSLContext;

public final class MtlsDeploymentControlClient implements DeploymentControlClient {
  private static final Duration TIMEOUT = Duration.ofSeconds(3);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final URI endpoint;
  private final HttpClient client;

  public MtlsDeploymentControlClient(URI endpoint, SSLContext workloadIdentity) {
    if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
      throw new IllegalArgumentException("deployment-control endpoint must use TLS");
    }
    this.endpoint = endpoint;
    this.client = HttpClient.newBuilder()
        .sslContext(workloadIdentity)
        .connectTimeout(TIMEOUT)
        .build();
  }

  @Override
  public DeploymentApproval getApproval(UUID approvalId) {
    JsonNode response = invoke("GET", "/v1/approvals/" + approvalId, null);
    return new DeploymentApproval(approvalId, text(response, "signedEnvelopeJson"),
        text(response, "nonceState"), text(response, "artifactDigest"),
        text(response, "approvalState"));
  }

  @Override
  public AttemptRecord consumeNonce(NonceCommand command) {
    return attempt(invoke("POST", "/v1/approvals/" + command.approvalId()
        + "/consume-nonce", command));
  }

  @Override
  public AttemptRecord transitionAttempt(TransitionCommand command) {
    return attempt(invoke("POST", "/v1/attempts/" + command.attemptId()
        + "/transitions", command));
  }

  @Override
  public AttemptRecord getAttemptStrong(UUID approvalId, UUID attemptId) {
    return attempt(invoke("GET", "/v1/approvals/" + approvalId + "/attempts/" + attemptId,
        null));
  }

  @Override
  public AttemptRecord recordFailure(FailureCommand command) {
    return attempt(invoke("POST", "/v1/attempts/" + command.attemptId()
        + "/failures", command));
  }

  private JsonNode invoke(String method, String path, Object body) {
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(endpoint.resolve(path))
          .timeout(TIMEOUT)
          .header("Accept", "application/json");
      if (body == null) {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        request.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(body)));
      }
      HttpResponse<byte[]> response = client.send(request.build(),
          HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new V9MigrationException(V9ErrorCode.V9_CONTROL_PLANE_UNAVAILABLE,
            "deployment-control rejected the operation with status " + response.statusCode());
      }
      return MAPPER.readTree(response.body());
    } catch (V9MigrationException exception) {
      throw exception;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable(exception);
    } catch (Exception exception) {
      throw unavailable(exception);
    }
  }

  private static AttemptRecord attempt(JsonNode node) {
    return new AttemptRecord(
        UUID.fromString(text(node, "approvalId")),
        UUID.fromString(text(node, "attemptId")),
        node.path("fencingToken").longValue(),
        AttemptState.valueOf(text(node, "state")),
        node.path("stateVersion").longValue(),
        nullableText(node, "schemaFingerprint"),
        nullableText(node, "v1V8HistoryFingerprint"),
        nullableText(node, "backupId"),
        text(node, "fullManifestSha256"),
        node.path("flywayChecksumInt").intValue(),
        text(node, "artifactDigest"),
        nullableText(node, "seedFingerprint"),
        nullableText(node, "businessFingerprint"),
        Instant.parse(text(node, "updatedAt")),
        node.path("failureId").isNull() || node.path("failureId").isMissingNode()
            ? null : UUID.fromString(node.path("failureId").asText()));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new V9MigrationException(V9ErrorCode.V9_CONTROL_PLANE_UNAVAILABLE,
          "deployment-control response is missing a required field");
    }
    return value.textValue();
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() ? value.textValue() : null;
  }

  private static V9MigrationException unavailable(Exception cause) {
    return new V9MigrationException(V9ErrorCode.V9_CONTROL_PLANE_UNAVAILABLE,
        "deployment-control did not provide a confirmed result within three seconds", cause);
  }
}
