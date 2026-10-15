package com.aiexam.app.migration.deploymentcontrol;

import java.time.Instant;
import java.util.UUID;

public interface DeploymentControlClient {
  DeploymentApproval getApproval(UUID approvalId);

  AttemptRecord consumeNonce(NonceCommand command);

  AttemptRecord transitionAttempt(TransitionCommand command);

  AttemptRecord getAttemptStrong(UUID approvalId, UUID attemptId);

  AttemptRecord recordFailure(FailureCommand command);

  record DeploymentApproval(UUID approvalId, String signedEnvelopeJson, String nonceState,
                            String artifactDigest, String approvalState) {
  }

  record NonceCommand(UUID approvalId, String nonceHash, UUID attemptId, long fencingToken,
                      long expectedStateVersion, UUID operationId) {
  }

  record TransitionCommand(UUID approvalId, UUID attemptId, AttemptState fromState,
                           AttemptState toState, long expectedStateVersion, long fencingToken,
                           UUID operationId, String evidenceDigest) {
  }

  record FailureCommand(UUID approvalId, UUID attemptId, AttemptState fromState,
                        AttemptState failureState, long expectedStateVersion,
                        long fencingToken, UUID operationId, UUID failureId,
                        String evidenceDigest) {
  }

  record AttemptRecord(UUID approvalId, UUID attemptId, long fencingToken,
                       AttemptState state, long stateVersion, String schemaFingerprint,
                       String v1V8HistoryFingerprint, String backupId,
                       String fullManifestSha256, int flywayChecksumInt,
                       String artifactDigest, String seedFingerprint,
                       String businessFingerprint, Instant updatedAt, UUID failureId) {
  }
}
