package com.aiexam.app.portal;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PortalContentScheduler {
  private final PortalContentService portalContentService;

  public PortalContentScheduler(PortalContentService portalContentService) {
    this.portalContentService = portalContentService;
  }

  @Scheduled(cron = "${aiexam.portal.crawler.cron}", zone = "${aiexam.portal.crawler.zone-id}")
  public void synchronizeDaily() {
    portalContentService.synchronize();
  }
}
