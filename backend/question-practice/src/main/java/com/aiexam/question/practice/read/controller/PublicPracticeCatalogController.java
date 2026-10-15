package com.aiexam.question.practice.read.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.question.practice.read.application.PracticeReadApplicationService;
import com.aiexam.question.practice.read.dto.PublicPracticeCatalogDto;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/public/practice/catalog")
public class PublicPracticeCatalogController {
  private final PracticeReadApplicationService service;

  public PublicPracticeCatalogController(PracticeReadApplicationService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<PublicPracticeCatalogDto> catalog(
      @RequestParam("qualificationId") @Positive Long qualificationId
  ) {
    return ApiResponse.ok(service.publicCatalog(qualificationId));
  }
}
