package com.aiexam.question.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.question.application.PaperApplicationService;
import com.aiexam.question.dto.PublicPaperPageDto;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/public/papers")
public class PublicPaperController {
  private final PaperApplicationService paperApplicationService;

  public PublicPaperController(PaperApplicationService paperApplicationService) {
    this.paperApplicationService = paperApplicationService;
  }

  @GetMapping
  public ApiResponse<PublicPaperPageDto> list(
      @RequestParam(name = "qualificationId", required = false) @Positive Long qualificationId,
      @RequestParam(name = "subjectId", required = false) @Positive Long subjectId,
      @RequestParam(name = "paperType", required = false) String paperType,
      @RequestParam(name = "yearNo", required = false) Integer yearNo,
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(PublicPaperPageDto.from(paperApplicationService.listPublishedPapers(
        qualificationId,
        subjectId,
        paperType,
        yearNo,
        pageNo,
        pageSize)));
  }
}
