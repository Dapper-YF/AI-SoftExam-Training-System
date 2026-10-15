package com.aiexam.question.practice.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import com.aiexam.question.practice.application.PracticeFavoriteNoteApplicationService;
import com.aiexam.question.practice.dto.PracticeFavoriteDeleteDto;
import com.aiexam.question.practice.dto.PracticeFavoriteDto;
import com.aiexam.question.practice.dto.PracticeNoteDto;
import com.aiexam.question.practice.dto.request.PracticeFavoriteCreateRequest;
import com.aiexam.question.practice.dto.request.PracticeNoteCreateRequest;
import com.aiexam.question.practice.dto.request.PracticeNoteUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/practice")
public class PracticeFavoriteNoteController {
  private final PracticeFavoriteNoteApplicationService service;

  public PracticeFavoriteNoteController(PracticeFavoriteNoteApplicationService service) {
    this.service = service;
  }

  @GetMapping("/favorites")
  public ApiResponse<PageResponse<PracticeFavoriteDto>> favorites(
      Authentication authentication,
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    Long userId = PracticeCurrentUser.requireUserId(authentication);
    return ApiResponse.ok(service.listFavorites(userId, pageNo, pageSize));
  }

  @PostMapping("/favorites")
  public ResponseEntity<ApiResponse<PracticeFavoriteDto>> createFavorite(
      Authentication authentication,
      @Valid @RequestBody PracticeFavoriteCreateRequest request
  ) {
    Long userId = PracticeCurrentUser.requireUserId(authentication);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(service.createFavorite(userId, request)));
  }

  @DeleteMapping("/favorites/{questionId}")
  public ApiResponse<PracticeFavoriteDeleteDto> deleteFavorite(
      Authentication authentication,
      @PathVariable("questionId") @Positive Long questionId
  ) {
    Long userId = PracticeCurrentUser.requireUserId(authentication);
    return ApiResponse.ok(service.deleteFavorite(userId, questionId));
  }

  @PutMapping("/favorites/{questionId}/group")
  public ApiResponse<PracticeFavoriteDto> updateFavoriteGroup(
      Authentication authentication,
      @PathVariable("questionId") @Positive Long questionId,
      @RequestBody PracticeFavoriteCreateRequest request
  ) {
    Long userId = PracticeCurrentUser.requireUserId(authentication);
    return ApiResponse.ok(service.updateFavoriteGroup(userId, questionId, request.favoriteGroup()));
  }

  @GetMapping("/notes")
  public ApiResponse<PageResponse<PracticeNoteDto>> notes(
      Authentication authentication,
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    Long userId = PracticeCurrentUser.requireUserId(authentication);
    return ApiResponse.ok(service.listNotes(userId, pageNo, pageSize));
  }

  @PostMapping("/notes")
  public ResponseEntity<ApiResponse<PracticeNoteDto>> createNote(
      Authentication authentication,
      @Valid @RequestBody PracticeNoteCreateRequest request
  ) {
    Long userId = PracticeCurrentUser.requireUserId(authentication);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(service.createNote(userId, request)));
  }

  @PutMapping("/notes/{id}")
  public ApiResponse<PracticeNoteDto> updateNote(
      Authentication authentication,
      @PathVariable("id") @Positive Long id,
      @Valid @RequestBody PracticeNoteUpdateRequest request
  ) {
    Long userId = PracticeCurrentUser.requireUserId(authentication);
    return ApiResponse.ok(service.updateNote(userId, id, request));
  }
}
