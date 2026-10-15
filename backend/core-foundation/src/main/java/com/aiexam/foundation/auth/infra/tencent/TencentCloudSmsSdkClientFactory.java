package com.aiexam.foundation.auth.infra.tencent;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.sms.v20210111.SmsClient;
import com.tencentcloudapi.sms.v20210111.models.SendSmsRequest;
import com.tencentcloudapi.sms.v20210111.models.SendSmsResponse;
import com.tencentcloudapi.sms.v20210111.models.SendStatus;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TencentCloudSmsSdkClientFactory implements TencentCloudSmsClientFactory {
  @Override
  public TencentCloudSmsClient create(TencentCloudSmsRuntimeConfiguration configuration) {
    return new SdkTencentCloudSmsClient(configuration);
  }

  private static final class SdkTencentCloudSmsClient implements TencentCloudSmsClient {
    private final SmsClient client;

    private SdkTencentCloudSmsClient(TencentCloudSmsRuntimeConfiguration configuration) {
      try {
        Credential credential = new Credential(configuration.secretId(), configuration.secretKey());
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint(configuration.endpoint());
        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        this.client = new SmsClient(credential, configuration.region(), clientProfile);
      } catch (RuntimeException exception) {
        throw new IllegalStateException("TENCENT_SMS_CLIENT_INIT_FAILED", exception);
      }
    }

    @Override
    public TencentCloudSmsSendResult send(TencentCloudSmsSendCommand command) {
      try {
        SendSmsRequest request = new SendSmsRequest();
        request.setSmsSdkAppId(command.smsSdkAppId());
        request.setSignName(command.signName());
        request.setTemplateId(command.templateId());
        request.setPhoneNumberSet(command.phoneNumberSet().toArray(String[]::new));
        request.setTemplateParamSet(command.templateParamSet().toArray(String[]::new));
        SendSmsResponse response = client.SendSms(request);
        SendStatus[] sendStatusSet = response == null ? null : response.getSendStatusSet();
        List<TencentCloudSmsSendStatus> statuses = sendStatusSet == null
            ? List.of()
            : java.util.Arrays.stream(sendStatusSet)
                .map(status -> new TencentCloudSmsSendStatus(
                    status.getCode(),
                    status.getMessage(),
                    status.getPhoneNumber()))
                .toList();
        return new TencentCloudSmsSendResult(response == null ? null : response.getRequestId(), statuses);
      } catch (TencentCloudSDKException exception) {
        throw new IllegalStateException("TENCENT_SMS_SDK_ERROR", exception);
      }
    }
  }
}
