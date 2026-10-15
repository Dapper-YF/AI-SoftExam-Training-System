package com.aiexam.app.portal;

import java.time.Instant;

public record PortalContentDto(
    Long id,
    String title,
    String summary,
    Instant publishedAt,
    PortalContentCategory category,
    String tag,
    String sourceName,
    String sourceUrl
) {}
