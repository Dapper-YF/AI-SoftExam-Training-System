package com.aiexam.foundation.profile.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.foundation.profile.application.UserProfileApplicationService;
import com.aiexam.foundation.profile.dto.UpdateUserProfileRequest;
import com.aiexam.foundation.profile.dto.UserProfileDto;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/profile")
@PreAuthorize("isAuthenticated()")
public class UserProfileController {
  private final UserProfileApplicationService service;

  public UserProfileController(UserProfileApplicationService service) {
    this.service = service;
  }

  @GetMapping("/me")
  public ApiResponse<UserProfileDto> me() {
    return ApiResponse.ok(service.me());
  }

  @PutMapping("/me")
  public ApiResponse<UserProfileDto> updateMe(@Valid @RequestBody UpdateUserProfileRequest request) {
    return ApiResponse.ok(service.updateMe(request));
  }
}
