package com.aiexam.app.portal;

import java.time.Instant;

public record PortalContentAdminDto(
    long id,
    String sourceName,
    String sourceUrl,
    String title,
    String summary,
    String originalTitle,
    String originalSummary,
    Instant publishedAt,
    Instant sourcePublishedAt,
    PortalContentCategory category,
    PortalContentType contentType,
    PortalContentCategory reviewCategory,
    PortalContentType reviewContentType,
    PortalContentStatus status,
    boolean pinned,
    int pinOrder,
    String changeSummary,
    Instant lastCrawledAt,
    Instant updatedAt
) {
}
