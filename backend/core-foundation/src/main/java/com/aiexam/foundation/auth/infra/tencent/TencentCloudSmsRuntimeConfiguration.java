package com.aiexam.foundation.auth.infra.tencent;

public record TencentCloudSmsRuntimeConfiguration(
    String secretId,
    String secretKey,
    String sdkAppId,
    String signName,
    String templateId,
    String region,
    String endpoint
) {
}
