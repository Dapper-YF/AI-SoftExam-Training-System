package com.aiexam.app.migration.v9;

import java.time.Instant;
import java.util.UUID;

public record V9ExecutionApproval(
    UUID approvalId,
    String fullManifestSha256,
    int flywayChecksumInt,
    String artifactDigest,
    String targetSchema,
    String targetDbHostFingerprint,
    String migrationDbPrincipal,
    Instant issuedAt,
    Instant expiresAt,
    String singleUseNonceHash,
    String signingKeyId) {
}
