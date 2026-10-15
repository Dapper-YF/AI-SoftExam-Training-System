package com.aiexam.foundation.auth.infra.tencent;

import java.util.List;

public interface TencentCloudSmsClient {
  TencentCloudSmsSendResult send(TencentCloudSmsSendCommand command);

  record TencentCloudSmsSendCommand(
      String smsSdkAppId,
      String signName,
      String templateId,
      List<String> phoneNumberSet,
      List<String> templateParamSet
  ) {
  }

  record TencentCloudSmsSendResult(
      String requestId,
      List<TencentCloudSmsSendStatus> sendStatusSet
  ) {
  }

  record TencentCloudSmsSendStatus(
      String code,
      String message,
      String phoneNumber
  ) {
  }
}
