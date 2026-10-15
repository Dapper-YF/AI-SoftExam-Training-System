package com.aiexam.foundation.profile.application;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ResourceNotFoundException;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.foundation.profile.dto.UpdateUserProfileRequest;
import com.aiexam.foundation.profile.dto.UserProfileDto;
import com.aiexam.foundation.application.FoundationApplicationService;
import com.aiexam.foundation.profile.ExamRegionCatalog;
import com.aiexam.foundation.profile.repository.UserProfileRepository;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileApplicationService {
  private final CurrentUserProvider currentUserProvider;
  private final UserProfileRepository repository;
  private final FoundationApplicationService foundationApplicationService;

  @Autowired
  public UserProfileApplicationService(
      CurrentUserProvider currentUserProvider,
      UserProfileRepository repository, FoundationApplicationService foundationApplicationService
  ) {
    this.currentUserProvider = currentUserProvider;
    this.repository = repository;
    this.foundationApplicationService = foundationApplicationService;
  }

  public UserProfileApplicationService(CurrentUserProvider currentUserProvider, UserProfileRepository repository) {
    this(currentUserProvider, repository, null);
  }

  public UserProfileDto me() {
    Long userId = currentUserProvider.requireUserId();
    return repository.findByUserId(userId)
        .orElseThrow(() -> new ResourceNotFoundException("用户不存在或已停用"));
  }

  @Transactional
  public UserProfileDto updateMe(UpdateUserProfileRequest request) {
    Long userId = currentUserProvider.requireUserId();
    String displayName = normalizeDisplayName(request.displayName());
    Long qualificationId = request.preparationQualificationId();
    String regionCode = request.examRegionCode() == null ? null : request.examRegionCode().trim().toUpperCase(Locale.ROOT);
    if ((qualificationId == null) != (regionCode == null || regionCode.isBlank())) {
      throw new BusinessException("VALIDATION_ERROR", "备考资格和考区必须同时选择", HttpStatus.BAD_REQUEST);
    }
    String regionName = null;
    if (qualificationId != null) {
      if (foundationApplicationService == null) {
        throw new BusinessException("VALIDATION_ERROR", "资格服务暂不可用", HttpStatus.SERVICE_UNAVAILABLE);
      }
      foundationApplicationService.validatePaperScope(qualificationId, null);
      try { regionName = ExamRegionCatalog.requireName(regionCode); }
      catch (IllegalArgumentException ex) { throw new BusinessException("VALIDATION_ERROR", "考区无效", HttpStatus.BAD_REQUEST); }
    }
    boolean updated = repository.updatePreparation(userId, displayName, qualificationId, regionCode, regionName, request.version());
    if (!updated) {
      repository.findByUserId(userId)
          .orElseThrow(() -> new ResourceNotFoundException("用户不存在或已停用"));
      throw new BusinessException("PROFILE_CONFLICT", "个人资料已变化，请刷新后重试", HttpStatus.CONFLICT);
    }
    return me();
  }

  private String normalizeDisplayName(String displayName) {
    String normalized = displayName == null ? "" : displayName.trim();
    if (normalized.isBlank() || normalized.length() > 64) {
      throw new BusinessException("VALIDATION_ERROR", "展示名格式不正确", HttpStatus.BAD_REQUEST);
    }
    if (normalized.toUpperCase(Locale.ROOT).contains("ROLE_")) {
      throw new BusinessException("VALIDATION_ERROR", "展示名格式不正确", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }
}
