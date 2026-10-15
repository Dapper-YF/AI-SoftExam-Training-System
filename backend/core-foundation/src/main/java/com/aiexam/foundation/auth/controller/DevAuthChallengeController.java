package com.aiexam.foundation.auth.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.foundation.auth.repository.DevChallengeCodeLookupPort;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"dev", "test", "local-dev"})
@ConditionalOnBean(DevChallengeCodeLookupPort.class)
@ConditionalOnProperty(prefix = "aiexam.auth.challenge.dev-delivery", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/auth/dev")
public class DevAuthChallengeController {
  private final DevChallengeCodeLookupPort codeLookupPort;

  public DevAuthChallengeController(DevChallengeCodeLookupPort codeLookupPort) {
    this.codeLookupPort = codeLookupPort;
  }

  @GetMapping("/challenges/{challengeId}/verification-code")
  public ApiResponse<Map<String, String>> verificationCode(
      @PathVariable("challengeId") UUID challengeId) {
    String code = codeLookupPort.findCode(challengeId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND,
            "验证码不存在",
            HttpStatus.NOT_FOUND));
    return ApiResponse.ok(Map.of("verificationCode", code));
  }
}
