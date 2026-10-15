package com.aiexam.foundation.auth.infra;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile({"prod", "production", "staging"})
public class TencentCloudSmsProductionGuard {
  private final boolean enabled;
  private final String secretId;
  private final String secretKey;
  private final String sdkAppId;
  private final String signName;
  private final String templateId;

  public TencentCloudSmsProductionGuard(
      @Value("${aiexam.auth.challenge.tencent-sms.enabled:false}") boolean enabled,
      @Value("${aiexam.auth.challenge.tencent-sms.secret-id:}") String secretId,
      @Value("${aiexam.auth.challenge.tencent-sms.secret-key:}") String secretKey,
      @Value("${aiexam.auth.challenge.tencent-sms.sdk-app-id:}") String sdkAppId,
      @Value("${aiexam.auth.challenge.tencent-sms.sign-name:}") String signName,
      @Value("${aiexam.auth.challenge.tencent-sms.template-id:}") String templateId
  ) {
    this.enabled = enabled;
    this.secretId = secretId;
    this.secretKey = secretKey;
    this.sdkAppId = sdkAppId;
    this.signName = signName;
    this.templateId = templateId;
  }

  @PostConstruct
  public void rejectDisabledOrIncompleteProductionSmsConfiguration() {
    if (!enabled) {
      throw new IllegalStateException("TENCENT_SMS_DISABLED_IN_PRODUCTION");
    }
    List<String> missing = new ArrayList<>();
    requireText(secretId, "secret-id", missing);
    requireText(secretKey, "secret-key", missing);
    requireText(sdkAppId, "sdk-app-id", missing);
    requireText(signName, "sign-name", missing);
    requireText(templateId, "template-id", missing);
    if (!missing.isEmpty()) {
      throw new IllegalStateException("TENCENT_SMS_CONFIGURATION_INCOMPLETE:" + String.join(",", missing));
    }
  }

  private static void requireText(String value, String name, List<String> missing) {
    if (!StringUtils.hasText(value)) {
      missing.add(name);
    }
  }
}
