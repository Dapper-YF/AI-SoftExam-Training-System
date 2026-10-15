package com.aiexam.app.migration.v9;

import java.time.Instant;
import java.util.UUID;

public record V9BackupEvidence(
    String backupId,
    Instant createdAt,
    String sourceSchemaFingerprint,
    String flywayV1ToV8HistoryFingerprint,
    String restoreDrillId,
    Instant restoreVerifiedAt,
    String backupArtifactHash,
    UUID approvalId) {

  public void validate(UUID expectedApprovalId, Instant now) {
    if (backupId == null || backupId.isBlank() || createdAt == null
        || sourceSchemaFingerprint == null || !sourceSchemaFingerprint.matches("[0-9a-f]{64}")
        || flywayV1ToV8HistoryFingerprint == null
        || !flywayV1ToV8HistoryFingerprint.matches("[0-9a-f]{64}")
        || restoreDrillId == null || restoreDrillId.isBlank() || restoreVerifiedAt == null
        || restoreVerifiedAt.isAfter(now) || backupArtifactHash == null
        || !backupArtifactHash.matches("[0-9a-f]{64}")
        || !expectedApprovalId.equals(approvalId)) {
      throw new V9MigrationException(V9ErrorCode.V9_BACKUP_INVALID,
          "backup evidence does not match the approved V9 execution");
    }
  }
}
