package com.aiexam.foundation.auth.infra.tencent;

public interface TencentCloudSmsClientFactory {
  TencentCloudSmsClient create(TencentCloudSmsRuntimeConfiguration configuration);
}
