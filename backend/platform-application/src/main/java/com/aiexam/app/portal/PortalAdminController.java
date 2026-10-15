package com.aiexam.app.portal;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/portal")
public class PortalAdminController {
  private final PortalContentService portalContentService;

  public PortalAdminController(PortalContentService portalContentService) {
    this.portalContentService = portalContentService;
  }

  @PostMapping("/crawl")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<PortalSyncResult> crawl() {
    return ApiResponse.ok(portalContentService.synchronize());
  }

  @GetMapping("/contents")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<PageResponse<PortalContentAdminDto>> contents(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "category", required = false) String category,
      @RequestParam(name = "keyword", required = false) String keyword,
      @RequestParam(name = "pageNo", defaultValue = "1") long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") long pageSize) {
    PortalContentStatus parsedStatus = status == null || status.isBlank() ? null : PortalContentStatus.valueOf(status.trim().toUpperCase());
    PortalContentCategory parsedCategory = category == null || category.isBlank() ? null : PortalContentCategory.valueOf(category.trim().toUpperCase());
    return ApiResponse.ok(portalContentService.reviewQueue(parsedStatus, parsedCategory, keyword, pageNo, pageSize));
  }

  @GetMapping("/contents/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<PortalContentAdminDto> detail(@PathVariable(name = "id") @Positive long id) {
    return ApiResponse.ok(portalContentService.reviewDetail(id));
  }

  @PutMapping("/contents/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<PortalContentAdminDto> save(
      @PathVariable(name = "id") @Positive long id, @Valid @RequestBody PortalContentReviewRequest request) {
    return ApiResponse.ok(portalContentService.saveReview(id, request));
  }

  @PostMapping("/contents/{id}/publish")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<PortalContentAdminDto> publish(@PathVariable(name = "id") @Positive long id) {
    return ApiResponse.ok(portalContentService.publish(id));
  }

  @PostMapping("/contents/{id}/reject")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<PortalContentAdminDto> reject(@PathVariable(name = "id") @Positive long id) {
    return ApiResponse.ok(portalContentService.reject(id));
  }

  @PostMapping("/contents/{id}/offline")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<PortalContentAdminDto> offline(@PathVariable(name = "id") @Positive long id) {
    return ApiResponse.ok(portalContentService.offline(id));
  }

  @PostMapping("/contents/{id}/pin")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<PortalContentAdminDto> pin(@PathVariable(name = "id") @Positive long id) {
    return ApiResponse.ok(portalContentService.pin(id));
  }

  @PostMapping("/contents/{id}/unpin")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<PortalContentAdminDto> unpin(@PathVariable(name = "id") @Positive long id) {
    return ApiResponse.ok(portalContentService.unpin(id));
  }
}
