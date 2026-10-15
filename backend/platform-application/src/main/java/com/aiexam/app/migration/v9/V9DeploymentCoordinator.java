package com.aiexam.app.migration.v9;

import com.aiexam.app.migration.deploymentcontrol.AttemptState;
import com.aiexam.app.migration.deploymentcontrol.DeploymentControlClient;
import com.aiexam.app.migration.deploymentcontrol.DeploymentControlClient.AttemptRecord;
import com.aiexam.app.migration.deploymentcontrol.DeploymentControlClient.DeploymentApproval;
import com.aiexam.app.migration.deploymentcontrol.DeploymentControlClient.FailureCommand;
import com.aiexam.app.migration.deploymentcontrol.DeploymentControlClient.NonceCommand;
import com.aiexam.app.migration.deploymentcontrol.DeploymentControlClient.TransitionCommand;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;

public final class V9DeploymentCoordinator {
  private final DeploymentControlClient control;
  private final String jdbcUrl;
  private final String username;
  private final String password;
  private final String hostFingerprint;
  private final String artifactDigest;
  private final Clock clock;

  public V9DeploymentCoordinator(DeploymentControlClient control, String jdbcUrl,
      String username, String password, String hostFingerprint, String artifactDigest,
      Clock clock) {
    this.control = Objects.requireNonNull(control, "control");
    this.jdbcUrl = requireText(jdbcUrl, "jdbcUrl");
    this.username = requireText(username, "username");
    this.password = requireText(password, "password");
    this.hostFingerprint = requireText(hostFingerprint, "hostFingerprint");
    this.artifactDigest = requireText(artifactDigest, "artifactDigest");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public void execute(UUID approvalId, UUID attemptId, long fencingToken,
      String envelopeJson, byte[] nonce, PublicKey signingKey, boolean clockHealthy,
      boolean testApproval, V9BackupEvidence backup) {
    V9ManifestBundle bundle = V9ManifestBundle.load();
    DeploymentApproval controlApproval = requireControlApproval(
        approvalId, envelopeJson, artifactDigest);
    try (Connection guard = connect()) {
      String schema = scalar(guard, "SELECT DATABASE()");
      String principal = scalar(guard, "SELECT CURRENT_USER()");
      V9ExecutionApproval approval = new V9ApprovalVerifier().verify(envelopeJson, nonce,
          signingKey, clock, clockHealthy, bundle, artifactDigest, schema, hostFingerprint,
          principal, username, testApproval);
      if (!approval.approvalId().equals(approvalId)) {
        throw new V9MigrationException(V9ErrorCode.V9_APPROVAL_INVALID,
            "approvalId does not match the signed envelope");
      }
      requireV1ToV8Baseline(guard);
      String lockName = lockName(schema);
      if (queryLong(guard, "SELECT GET_LOCK(?, 30)", lockName) != 1) {
        throw new V9MigrationException(V9ErrorCode.V9_DEPLOYMENT_BUSY,
            "another V9 deployment owns the schema lock");
      }
      long guardConnectionId = queryLong(guard, "SELECT CONNECTION_ID()", null);
      try {
        backup.validate(approvalId, clock.instant());
        V9LegacyManifestPreflight.validate(guard, bundle);
        AttemptRecord approved = control.getAttemptStrong(approvalId, attemptId);
        requireState(approved, AttemptState.APPROVED, fencingToken);
        AttemptRecord locked = transition(approved, AttemptState.LOCKED, fencingToken,
            digest("LOCKED:" + backup.backupId()));
        V9MigrationOnlyRunner.build(jdbcUrl, username, password,
            (connection, manifest) -> {
              throw new IllegalStateException("preflight runner must not execute");
            });
        if (!"ISSUED".equals(controlApproval.nonceState())) {
          throw new V9MigrationException(V9ErrorCode.V9_APPROVAL_REPLAYED,
              "approval nonce is no longer issued");
        }
        AttemptRecord consumed = consumeNonce(locked, nonce, fencingToken);
        AttemptRecord started;
        try {
          started = transition(consumed, AttemptState.FLYWAY_STARTED, fencingToken,
              digest("FLYWAY_STARTED:" + bundle.fullManifestSha256()));
        } catch (RuntimeException failure) {
          recordPreFlywayAbort(consumed, fencingToken, failure);
          throw failure;
        }
        V9LedgerFence fence = new V9LedgerFence(control, approvalId, attemptId, fencingToken,
            started.stateVersion(), guard, lockName, guardConnectionId, approval);
        V9AuthorizedDatabaseOrchestrator orchestrator = new V9AuthorizedDatabaseOrchestrator(
            fence, this::uncheckedConnection);
        Flyway flyway = V9MigrationOnlyRunner.build(jdbcUrl, username, password, orchestrator);
        try {
          flyway.migrate();
          flyway.validate();
          new com.aiexam.app.migration.V9RuntimeSchemaValidator().validate(guard);
          AttemptRecord completed = control.getAttemptStrong(approvalId, attemptId);
          if (completed.state() != AttemptState.COMPLETED) {
            throw new V9MigrationException(V9ErrorCode.V9_LEDGER_UNAVAILABLE,
                "V9 completed database work without a confirmed COMPLETED ledger state");
          }
        } catch (RuntimeException failure) {
          classifyAndRecordFailure(approvalId, attemptId, fencingToken, failure);
          throw failure;
        }
      } finally {
        queryLong(guard, "SELECT RELEASE_LOCK(?)", lockName);
      }
    } catch (V9MigrationException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new V9MigrationException(V9ErrorCode.V9_CONTROL_PLANE_UNAVAILABLE,
          "authorized V9 coordinator failed closed", exception);
    }
  }

  private DeploymentApproval requireControlApproval(UUID approvalId, String envelopeJson,
      String expectedArtifactDigest) {
    DeploymentApproval approval;
    try {
      approval = control.getApproval(approvalId);
    } catch (RuntimeException exception) {
      throw new V9MigrationException(V9ErrorCode.V9_CONTROL_PLANE_UNAVAILABLE,
          "deployment approval could not be read strongly", exception);
    }
    if (approval == null || !approvalId.equals(approval.approvalId())
        || !"APPROVED".equals(approval.approvalState())
        || !expectedArtifactDigest.equals(approval.artifactDigest())
        || !Objects.equals(envelopeJson, approval.signedEnvelopeJson())) {
      throw new V9MigrationException(V9ErrorCode.V9_APPROVAL_INVALID,
          "deployment-control approval does not match the injected envelope");
    }
    if (!"ISSUED".equals(approval.nonceState())) {
      throw new V9MigrationException(V9ErrorCode.V9_APPROVAL_REPLAYED,
          "deployment-control reports a consumed approval nonce");
    }
    return approval;
  }

  private void recordPreFlywayAbort(AttemptRecord consumed, long fence,
      RuntimeException failure) {
    UUID failureId = UUID.randomUUID();
    try {
      control.recordFailure(new FailureCommand(consumed.approvalId(), consumed.attemptId(),
          AttemptState.NONCE_CONSUMED, AttemptState.PRE_FLYWAY_ABORTED,
          consumed.stateVersion(), fence, operationId(consumed, "PRE_FLYWAY_ABORTED"),
          failureId, digest("PRE_FLYWAY_ABORTED:" + failure.getClass().getName())));
    } catch (RuntimeException ledgerFailure) {
      failure.addSuppressed(ledgerFailure);
    }
  }

  private AttemptRecord consumeNonce(AttemptRecord locked, byte[] nonce, long fence) {
    UUID operationId = operationId(locked, "CONSUME_NONCE");
    NonceCommand command = new NonceCommand(locked.approvalId(),
        JcsCanonicalizer.sha256Hex(nonce), locked.attemptId(), fence,
        locked.stateVersion(), operationId);
    AttemptRecord result;
    try {
      result = control.consumeNonce(command);
    } catch (RuntimeException failure) {
      result = control.getAttemptStrong(locked.approvalId(), locked.attemptId());
      if (result.state() != AttemptState.NONCE_CONSUMED) {
        throw new V9MigrationException(V9ErrorCode.V9_CONTROL_PLANE_UNAVAILABLE,
            "nonce consumption acknowledgement is uncertain", failure);
      }
    }
    requireState(result, AttemptState.NONCE_CONSUMED, fence);
    return strongConfirm(result);
  }

  private AttemptRecord transition(AttemptRecord current, AttemptState next, long fence,
      String evidence) {
    TransitionCommand command = new TransitionCommand(current.approvalId(), current.attemptId(),
        current.state(), next, current.stateVersion(), fence, operationId(current, next.name()),
        evidence);
    AttemptRecord result;
    try {
      result = control.transitionAttempt(command);
    } catch (RuntimeException failure) {
      result = control.getAttemptStrong(current.approvalId(), current.attemptId());
      if (result.state() != next) {
        throw new V9MigrationException(V9ErrorCode.V9_CONTROL_PLANE_UNAVAILABLE,
            "deployment-control transition acknowledgement is uncertain", failure);
      }
    }
    requireState(result, next, fence);
    return strongConfirm(result);
  }

  private AttemptRecord strongConfirm(AttemptRecord expected) {
    AttemptRecord confirmed = control.getAttemptStrong(expected.approvalId(), expected.attemptId());
    if (confirmed.state() != expected.state()
        || confirmed.stateVersion() != expected.stateVersion()
        || confirmed.fencingToken() != expected.fencingToken()) {
      throw new V9MigrationException(V9ErrorCode.V9_LEDGER_UNAVAILABLE,
          "deployment-control strong read is inconsistent");
    }
    return confirmed;
  }

  private void classifyAndRecordFailure(UUID approvalId, UUID attemptId, long fence,
      RuntimeException failure) {
    AttemptRecord current;
    try {
      current = control.getAttemptStrong(approvalId, attemptId);
    } catch (RuntimeException ledgerFailure) {
      failure.addSuppressed(ledgerFailure);
      return;
    }
    if (current.state() == AttemptState.COMPLETED
        || current.state() == AttemptState.FAILED_A_REPAIRABLE
        || current.state() == AttemptState.FAILED_B_RESTORE_REQUIRED) {
      return;
    }
    boolean forcedRestore = requiresRestore(failure);
    AttemptState target = !forcedRestore && (current.state() == AttemptState.FLYWAY_STARTED
        || current.state() == AttemptState.EXPAND_COMPLETED)
        ? AttemptState.FAILED_A_REPAIRABLE
        : AttemptState.FAILED_B_RESTORE_REQUIRED;
    UUID failureId = UUID.randomUUID();
    UUID operationId = operationId(current, "FAILURE:" + target);
    try {
      control.recordFailure(new FailureCommand(approvalId, attemptId, current.state(), target,
          current.stateVersion(), fence, operationId, failureId,
          digest(failure.getClass().getName() + ":" + target)));
    } catch (RuntimeException ledgerFailure) {
      failure.addSuppressed(ledgerFailure);
    }
  }

  private static boolean requiresRestore(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof V9MigrationException migrationFailure
          && (migrationFailure.errorCode() == V9ErrorCode.V9_RESTORE_REQUIRED
              || migrationFailure.errorCode() == V9ErrorCode.V9_TRANSACTION_STATE_UNKNOWN
              || migrationFailure.errorCode() == V9ErrorCode.V9_DML_FINGERPRINT_MISMATCH
              || migrationFailure.errorCode() == V9ErrorCode.V9_DDL_STATE_DRIFT
              || migrationFailure.errorCode() == V9ErrorCode.V9_LEDGER_UNAVAILABLE
              || migrationFailure.errorCode() == V9ErrorCode.V9_FENCE_REJECTED
              || migrationFailure.errorCode() == V9ErrorCode.V9_GUARD_LOCK_LOST
              || migrationFailure.errorCode() == V9ErrorCode.V9_CHECKSUM_MISMATCH)) {
        return true;
      }
    }
    return false;
  }

  private static void requireState(AttemptRecord record, AttemptState state, long fence) {
    if (record.state() != state || record.fencingToken() != fence) {
      throw new V9MigrationException(V9ErrorCode.V9_FENCE_REJECTED,
          "deployment attempt state or fencing token is stale");
    }
  }

  private static void requireV1ToV8Baseline(Connection connection) throws Exception {
    if (queryLong(connection, """
        SELECT COUNT(*) FROM flyway_schema_history
        WHERE version BETWEEN '1' AND '8' AND success=1
        """, null) != 8 || queryLong(connection,
        "SELECT COUNT(*) FROM flyway_schema_history WHERE version='9'", null) != 0) {
      throw new V9MigrationException(V9ErrorCode.V9_TARGET_MISMATCH,
          "target schema is not the approved V1-V8 baseline");
    }
  }

  private Connection connect() throws Exception {
    return DriverManager.getConnection(jdbcUrl, username, password);
  }

  private Connection uncheckedConnection() {
    try {
      return connect();
    } catch (Exception exception) {
      throw new V9MigrationException(V9ErrorCode.V9_TARGET_MISMATCH,
          "cannot open the post-commit read-only connection", exception);
    }
  }

  private static String scalar(Connection connection, String sql) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getString(1);
    }
  }

  private static long queryLong(Connection connection, String sql, String value)
      throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      if (value != null) {
        statement.setString(1, value);
      }
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static String lockName(String schema) {
    return "aiexam:v9:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
        JcsCanonicalizer.sha256(schema.getBytes(StandardCharsets.UTF_8)));
  }

  private static UUID operationId(AttemptRecord record, String operation) {
    return UUID.nameUUIDFromBytes((record.approvalId() + ":" + record.attemptId() + ":"
        + operation).getBytes(StandardCharsets.UTF_8));
  }

  private static String digest(String value) {
    return JcsCanonicalizer.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
