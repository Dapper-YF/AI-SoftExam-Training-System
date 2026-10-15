package com.aiexam.app.learning;

import java.time.Instant;

public record LearningResourceVersionDto(
    Long id,
    Long resourceId,
    int versionNo,
    String versionLabel,
    String sourceName,
    String sourceRef,
    String fileUrl,
    String fileName,
    String mimeType,
    long fileSize,
    String fileSha256,
    Integer pageCount,
    String coverUrl,
    String extractedTextStatus,
    String status,
    String usageRestriction,
    Instant createdAt
) {}
