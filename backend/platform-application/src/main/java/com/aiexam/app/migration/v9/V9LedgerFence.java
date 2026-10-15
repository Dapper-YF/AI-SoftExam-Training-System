package com.aiexam.app.migration.v9;

import com.aiexam.app.migration.deploymentcontrol.AttemptState;
import com.aiexam.app.migration.deploymentcontrol.DeploymentControlClient;
import com.aiexam.app.migration.deploymentcontrol.DeploymentControlClient.AttemptRecord;
import com.aiexam.app.migration.deploymentcontrol.DeploymentControlClient.TransitionCommand;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

public final class V9LedgerFence implements V9ExecutionFence {
  private final DeploymentControlClient control;
  private final UUID approvalId;
  private final UUID attemptId;
  private final long fencingToken;
  private final Connection guardConnection;
  private final String lockName;
  private final long guardConnectionId;
  private final V9ExecutionApproval approval;
  private long stateVersion;

  public V9LedgerFence(DeploymentControlClient control, UUID approvalId, UUID attemptId,
      long fencingToken, long stateVersion, Connection guardConnection, String lockName,
      long guardConnectionId, V9ExecutionApproval approval) {
    this.control = Objects.requireNonNull(control, "control");
    this.approvalId = Objects.requireNonNull(approvalId, "approvalId");
    this.attemptId = Objects.requireNonNull(attemptId, "attemptId");
    this.fencingToken = fencingToken;
    this.stateVersion = stateVersion;
    this.guardConnection = Objects.requireNonNull(guardConnection, "guardConnection");
    this.lockName = Objects.requireNonNull(lockName, "lockName");
    this.guardConnectionId = guardConnectionId;
    this.approval = Objects.requireNonNull(approval, "approval");
  }

  @Override
  public synchronized void verifyBoundary(String boundary, AttemptState expectedState) {
    AttemptRecord record = strongRead();
    if (record.state() != expectedState || record.fencingToken() != fencingToken
        || record.stateVersion() != stateVersion) {
      throw new V9MigrationException(V9ErrorCode.V9_FENCE_REJECTED,
          "deployment ledger fence rejected a migration boundary");
    }
    try {
      if (guardConnection.isClosed() || usedLockOwner() != guardConnectionId) {
        throw new V9MigrationException(V9ErrorCode.V9_GUARD_LOCK_LOST,
            "V9 guard lock is no longer owned by the guard connection");
      }
      String database = scalar("SELECT DATABASE()");
      String principal = scalar("SELECT CURRENT_USER()");
      if (!approval.targetSchema().equals(database)
          || !approval.migrationDbPrincipal().equals(principal)
          || record.flywayChecksumInt() != approval.flywayChecksumInt()
          || !approval.fullManifestSha256().equals(record.fullManifestSha256())
          || !approval.artifactDigest().equals(record.artifactDigest())) {
        throw new V9MigrationException(V9ErrorCode.V9_FENCE_REJECTED,
            "database or checksum fence changed during migration");
      }
    } catch (SQLException exception) {
      throw new V9MigrationException(V9ErrorCode.V9_GUARD_LOCK_LOST,
          "V9 guard connection cannot prove lock ownership", exception);
    }
  }

  @Override
  public synchronized void transition(AttemptState from, AttemptState to,
      String evidenceDigest) {
    verifyBoundary("TRANSITION_" + from + "_TO_" + to, from);
    UUID operationId = UUID.nameUUIDFromBytes((approvalId + ":" + attemptId + ":"
        + from + ":" + to).getBytes(StandardCharsets.UTF_8));
    AttemptRecord result;
    try {
      result = control.transitionAttempt(new TransitionCommand(approvalId, attemptId, from, to,
          stateVersion, fencingToken, operationId, evidenceDigest));
    } catch (RuntimeException exception) {
      AttemptRecord recovered = strongRead();
      if (recovered.state() != to || recovered.fencingToken() != fencingToken) {
        throw new V9MigrationException(V9ErrorCode.V9_CONTROL_PLANE_UNAVAILABLE,
            "deployment-control mutation result is uncertain", exception);
      }
      result = recovered;
    }
    AttemptRecord confirmed = control.getAttemptStrong(approvalId, attemptId);
    if (confirmed.state() != to || confirmed.stateVersion() != result.stateVersion()
        || confirmed.fencingToken() != fencingToken) {
      throw new V9MigrationException(V9ErrorCode.V9_LEDGER_UNAVAILABLE,
          "deployment-control strong read did not confirm the transition");
    }
    stateVersion = confirmed.stateVersion();
  }

  public long stateVersion() {
    return stateVersion;
  }

  private AttemptRecord strongRead() {
    try {
      AttemptRecord record = control.getAttemptStrong(approvalId, attemptId);
      if (record == null) {
        throw new IllegalStateException("missing attempt");
      }
      return record;
    } catch (V9MigrationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new V9MigrationException(V9ErrorCode.V9_LEDGER_UNAVAILABLE,
          "deployment ledger strong read failed", exception);
    }
  }

  private long usedLockOwner() throws SQLException {
    try (PreparedStatement statement = guardConnection.prepareStatement("SELECT IS_USED_LOCK(?)")) {
      statement.setString(1, lockName);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private String scalar(String sql) throws SQLException {
    try (PreparedStatement statement = guardConnection.prepareStatement(sql);
         ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getString(1);
    }
  }
}
