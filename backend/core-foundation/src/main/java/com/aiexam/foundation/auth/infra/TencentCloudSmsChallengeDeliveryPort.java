package com.aiexam.foundation.auth.infra;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.foundation.auth.dto.AuthProviderCapabilityDto;
import com.aiexam.foundation.auth.repository.ChallengeDeliveryPort;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient.TencentCloudSmsSendCommand;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient.TencentCloudSmsSendResult;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient.TencentCloudSmsSendStatus;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClientFactory;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsRuntimeConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile({"prod", "production", "staging"})
@ConditionalOnProperty(prefix = "aiexam.auth.challenge.tencent-sms", name = "enabled", havingValue = "true")
public class TencentCloudSmsChallengeDeliveryPort implements ChallengeDeliveryPort {
  private static final Logger log = LoggerFactory.getLogger(TencentCloudSmsChallengeDeliveryPort.class);
  private static final String ENDPOINT = "sms.tencentcloudapi.com";
  private static final String TEMPLATE_MINUTES = "5";
  private static final String SAFE_SMS_UNAVAILABLE_MESSAGE = "SMS service is temporarily unavailable, please try again later.";
  private static final String SMS_PROVIDER_CODE = "TENCENT_CLOUD_SMS";

  private final TencentCloudSmsClient client;
  private final String smsSdkAppId;
  private final String signName;
  private final String templateId;
  private final String region;

  public TencentCloudSmsChallengeDeliveryPort(
      TencentCloudSmsClientFactory clientFactory,
      @Value("${aiexam.auth.challenge.tencent-sms.secret-id:}") String secretId,
      @Value("${aiexam.auth.challenge.tencent-sms.secret-key:}") String secretKey,
      @Value("${aiexam.auth.challenge.tencent-sms.sdk-app-id:}") String smsSdkAppId,
      @Value("${aiexam.auth.challenge.tencent-sms.sign-name:}") String signName,
      @Value("${aiexam.auth.challenge.tencent-sms.template-id:}") String templateId,
      @Value("${aiexam.auth.challenge.tencent-sms.region:ap-guangzhou}") String region
  ) {
    List<String> missing = new ArrayList<>();
    requireValue(secretId, "secret-id", missing);
    requireValue(secretKey, "secret-key", missing);
    requireValue(smsSdkAppId, "sdk-app-id", missing);
    requireValue(signName, "sign-name", missing);
    requireValue(templateId, "template-id", missing);
    if (!missing.isEmpty()) {
      throw new IllegalStateException("TENCENT_SMS_CONFIGURATION_INCOMPLETE:" + String.join(",", missing));
    }
    this.smsSdkAppId = smsSdkAppId.trim();
    this.signName = signName.trim();
    this.templateId = templateId.trim();
    this.region = StringUtils.hasText(region) ? region.trim() : "ap-guangzhou";
    TencentCloudSmsRuntimeConfiguration configuration = new TencentCloudSmsRuntimeConfiguration(
        secretId.trim(),
        secretKey.trim(),
        this.smsSdkAppId,
        this.signName,
        this.templateId,
        this.region,
        ENDPOINT);
    this.client = clientFactory.create(configuration);
  }

  @Override
  public AuthProviderCapabilityDto mobileCapability() {
    return AuthProviderCapabilityDto.available(SMS_PROVIDER_CODE, "Tencent Cloud SMS challenge delivery enabled");
  }

  @Override
  public AuthProviderCapabilityDto emailCapability() {
    return AuthProviderCapabilityDto.disabled("EMAIL", "Email challenge delivery not enabled");
  }

  @Override
  public AuthProviderCapabilityDto wechatCapability() {
    return AuthProviderCapabilityDto.disabled("WECHAT", "WeChat challenge delivery not enabled");
  }

  @Override
  public void deliverMobileCode(String purpose, UUID challengeId, String normalizedMobile, String code) {
    TencentCloudSmsSendResult result = send(purpose, challengeId, normalizedMobile, code);
    TencentCloudSmsSendStatus status = result.sendStatusSet().get(0);
    if (!"Ok".equals(status.code()) || !normalizedMobile.equals(status.phoneNumber())) {
      throw unavailable(purpose, challengeId, result.requestId(), "SEND_STATUS_NOT_OK");
    }
    log.info("Tencent SMS delivered, purpose={}, challengeId={}, requestId={}, status={}",
        purpose, challengeId, result.requestId(), status.code());
  }

  @Override
  public void deliverEmailCode(String purpose, UUID challengeId, String normalizedEmail, String code) {
    throw new UnsupportedOperationException("Tencent Cloud SMS challenge delivery does not handle email");
  }

  private TencentCloudSmsSendResult send(String purpose, UUID challengeId, String normalizedMobile, String code) {
    TencentCloudSmsSendResult result;
    try {
      result = client.send(new TencentCloudSmsSendCommand(
          smsSdkAppId,
          signName,
          templateId,
          List.of(normalizedMobile),
          List.of(code, TEMPLATE_MINUTES)));
    } catch (RuntimeException exception) {
      log.warn("Tencent SMS delivery failed, purpose={}, challengeId={}, requestId={}, errorType={}",
          purpose,
          challengeId,
          null,
          exception.getClass().getSimpleName());
      throw unavailable(purpose, challengeId, null, exception.getClass().getSimpleName());
    }
    if (result == null || !StringUtils.hasText(result.requestId()) || result.sendStatusSet() == null
        || result.sendStatusSet().size() != 1) {
      throw unavailable(purpose, challengeId, result == null ? null : result.requestId(), "EMPTY_OR_MULTI_TARGET_RESPONSE");
    }
    return result;
  }

  private BusinessException unavailable(String purpose, UUID challengeId, String requestId, String errorType) {
    log.warn("Tencent SMS unavailable, purpose={}, challengeId={}, requestId={}, errorType={}",
        purpose, challengeId, requestId, errorType);
    return new BusinessException(
        ErrorCode.AUTH_SMS_UNAVAILABLE,
        SAFE_SMS_UNAVAILABLE_MESSAGE,
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  private static void requireValue(String value, String fieldName, List<String> missing) {
    if (!StringUtils.hasText(value)) {
      missing.add(fieldName);
    }
  }
}
