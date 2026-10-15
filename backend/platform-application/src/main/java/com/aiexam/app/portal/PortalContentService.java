package com.aiexam.app.portal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import com.aiexam.common.api.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PortalContentService {
  private static final Logger log = LoggerFactory.getLogger(PortalContentService.class);
  private static final int MAX_PAGE_SIZE = 12;
  private final PortalContentRepository repository;
  private final PortalOfficialCrawler crawler;
  private final PortalContentClassifier classifier;
  private final PortalCrawlerProperties properties;

  public PortalContentService(
      PortalContentRepository repository,
      PortalOfficialCrawler crawler,
      PortalContentClassifier classifier,
      PortalCrawlerProperties properties) {
    this.repository = repository;
    this.crawler = crawler;
    this.classifier = classifier;
    this.properties = properties;
  }

  public List<PortalContentDto> news(PortalContentCategory category, int pageSize) {
    if (category == PortalContentCategory.OFFICIAL_RESOURCE) {
      throw new IllegalArgumentException("Official resources must use the resources endpoint");
    }
    return repository.findPublished(PortalContentType.NEWS, category, boundedPageSize(pageSize));
  }

  public List<PortalContentDto> resources(int pageSize) {
    return repository.findPublished(PortalContentType.RESOURCE, PortalContentCategory.OFFICIAL_RESOURCE, boundedPageSize(pageSize));
  }

  public PageResponse<PortalContentAdminDto> reviewQueue(PortalContentStatus status, PortalContentCategory category, String keyword, long pageNo, long pageSize) {
    return repository.findForReview(status, category, keyword, Math.max(pageNo, 1), Math.min(Math.max(pageSize, 1), 100));
  }

  public PortalContentAdminDto reviewDetail(long id) {
    return repository.findForReview(id);
  }

  public PortalContentAdminDto saveReview(long id, PortalContentReviewRequest request) {
    return repository.saveReview(id, request, Instant.now());
  }

  public PortalContentAdminDto publish(long id) {
    return repository.changeStatus(id, PortalContentStatus.PUBLISHED, Instant.now());
  }

  public PortalContentAdminDto reject(long id) {
    return repository.changeStatus(id, PortalContentStatus.REJECTED, Instant.now());
  }

  public PortalContentAdminDto offline(long id) {
    return repository.changeStatus(id, PortalContentStatus.OFFLINE, Instant.now());
  }

  public PortalContentAdminDto pin(long id) { return repository.setPinned(id, true, Instant.now()); }

  public PortalContentAdminDto unpin(long id) { return repository.setPinned(id, false, Instant.now()); }

  public PortalSyncResult synchronize() {
    if (!properties.isEnabled()) return new PortalSyncResult(0, 0, 0, false, "Portal crawler is disabled");
    Instant startedAt = Instant.now();
    long runId = repository.startRun(startedAt);
    int fetched = 0;
    int created = 0;
    int updated = 0;
    try {
      Instant cutoff = startedAt.minus(properties.getInitialLookbackDays(), ChronoUnit.DAYS);
      for (PortalCrawlCandidate candidate : crawler.fetchSince(cutoff)) {
        fetched++;
        PortalContentRepository.UpsertResult result = repository.upsert(candidate, classifier.classify(candidate.title()), Instant.now());
        if (result == PortalContentRepository.UpsertResult.CREATED) created++;
        if (result == PortalContentRepository.UpsertResult.UPDATED) updated++;
      }
      repository.completeRun(runId, true, fetched, created, updated, null, Instant.now());
      log.info("Portal content synchronization completed: fetched={}, created={}, updated={}", fetched, created, updated);
      return new PortalSyncResult(fetched, created, updated, true, null);
    } catch (Exception exception) {
      String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
      repository.completeRun(runId, false, fetched, created, updated, error.substring(0, Math.min(error.length(), 1000)), Instant.now());
      log.warn("Portal content synchronization failed after {} fetched items", fetched, exception);
      return new PortalSyncResult(fetched, created, updated, false, error);
    }
  }

  private int boundedPageSize(int pageSize) {
    return Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
  }
}
