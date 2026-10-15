package com.aiexam.app.portal;

import com.aiexam.common.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/public/portal")
public class PortalContentController {
  private final PortalContentService portalContentService;

  public PortalContentController(PortalContentService portalContentService) {
    this.portalContentService = portalContentService;
  }

  @GetMapping("/news")
  public ApiResponse<List<PortalContentDto>> news(
      @RequestParam(name = "category", defaultValue = "POLICY") PortalContentCategory category,
      @RequestParam(name = "pageSize", defaultValue = "6") @Min(1) @Max(12) int pageSize) {
    return ApiResponse.ok(portalContentService.news(category, pageSize));
  }

  @GetMapping("/resources")
  public ApiResponse<List<PortalContentDto>> resources(
      @RequestParam(name = "pageSize", defaultValue = "6") @Min(1) @Max(12) int pageSize) {
    return ApiResponse.ok(portalContentService.resources(pageSize));
  }
}
