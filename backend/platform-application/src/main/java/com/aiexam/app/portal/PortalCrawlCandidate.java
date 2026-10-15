package com.aiexam.app.portal;

import java.time.Instant;

record PortalCrawlCandidate(String title, String summary, String sourceUrl, Instant publishedAt) {}
