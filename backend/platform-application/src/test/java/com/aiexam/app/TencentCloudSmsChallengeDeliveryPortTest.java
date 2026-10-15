package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.foundation.auth.infra.TencentCloudSmsChallengeDeliveryPort;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient.TencentCloudSmsSendCommand;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient.TencentCloudSmsSendResult;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient.TencentCloudSmsSendStatus;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClientFactory;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsRuntimeConfiguration;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TencentCloudSmsChallengeDeliveryPortTest {
  @Test
  void deliverMobileCodeMapsTencentRequestAndAcceptsOnlyOkStatus() {
    CapturingFactory factory = new CapturingFactory(new FakeClient(new TencentCloudSmsSendResult(
        "request-123",
        List.of(new TencentCloudSmsSendStatus("Ok", "send success", "+8613812345678")))));
    TencentCloudSmsChallengeDeliveryPort port = new TencentCloudSmsChallengeDeliveryPort(
        factory,
        "secret-id",
        "secret-key",
        "1400006666",
        "TencentCloud",
        "1110",
        "ap-guangzhou");

    ListAppender<ILoggingEvent> appender = attachAppender();
    port.deliverMobileCode("REGISTER", UUID.fromString("018fa7d9-0000-7000-8000-000000000201"), "+8613812345678", "654321");

    assertThat(factory.configuration.get()).isNotNull();
    assertThat(factory.configuration.get().endpoint()).isEqualTo("sms.tencentcloudapi.com");
    assertThat(factory.configuration.get().region()).isEqualTo("ap-guangzhou");
    assertThat(factory.configuration.get().secretId()).isEqualTo("secret-id");
    assertThat(factory.configuration.get().secretKey()).isEqualTo("secret-key");
    assertThat(factory.configuration.get().sdkAppId()).isEqualTo("1400006666");
    assertThat(factory.configuration.get().signName()).isEqualTo("TencentCloud");
    assertThat(factory.configuration.get().templateId()).isEqualTo("1110");

    assertThat(factory.client.lastCommand).isNotNull();
    assertThat(factory.client.lastCommand.phoneNumberSet()).containsExactly("+8613812345678");
    assertThat(factory.client.lastCommand.templateParamSet()).containsExactly("654321", "5");
    assertThat(factory.client.lastCommand.smsSdkAppId()).isEqualTo("1400006666");
    assertThat(factory.client.lastCommand.signName()).isEqualTo("TencentCloud");
    assertThat(factory.client.lastCommand.templateId()).isEqualTo("1110");

    assertThat(appender.list).anySatisfy(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.INFO);
      assertThat(event.getFormattedMessage()).contains("Tencent SMS delivered");
      assertThat(event.getFormattedMessage()).contains("request-123");
      assertThat(event.getFormattedMessage()).doesNotContain("654321", "+8613812345678", "secret-id", "secret-key");
    });
    detachAppender(appender);
  }

  @Test
  void deliverMobileCodeFailsClosedWhenSendStatusIsNotOk() {
    CapturingFactory factory = new CapturingFactory(new FakeClient(new TencentCloudSmsSendResult(
        "request-456",
        List.of(new TencentCloudSmsSendStatus("FailedOperation.TemplateParamSetNotMatchApprovedTemplate",
            "request content does not match the template content",
            "+8613812345678")))));
    TencentCloudSmsChallengeDeliveryPort port = new TencentCloudSmsChallengeDeliveryPort(
        factory,
        "secret-id",
        "secret-key",
        "1400006666",
        "TencentCloud",
        "1110",
        "ap-guangzhou");

    assertThatThrownBy(() -> port.deliverMobileCode(
        "REGISTER",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000202"),
        "+8613812345678",
        "654321"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_SMS_UNAVAILABLE));
  }

  @Test
  void missingTencentConfigurationFailsFastBeforeClientCreation() {
    CapturingFactory factory = new CapturingFactory(new FakeClient(new TencentCloudSmsSendResult(
        "request-789",
        List.of(new TencentCloudSmsSendStatus("Ok", "send success", "+8613812345678")))));

    assertThatThrownBy(() -> new TencentCloudSmsChallengeDeliveryPort(
        factory,
        "",
        "secret-key",
        "1400006666",
        "TencentCloud",
        "1110",
        "ap-guangzhou"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TENCENT_SMS_CONFIGURATION_INCOMPLETE")
        .hasMessageContaining("secret-id");
  }

  private ListAppender<ILoggingEvent> attachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(TencentCloudSmsChallengeDeliveryPort.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private void detachAppender(ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(TencentCloudSmsChallengeDeliveryPort.class);
    logger.detachAppender(appender);
  }

  private static final class CapturingFactory implements TencentCloudSmsClientFactory {
    private final AtomicReference<TencentCloudSmsRuntimeConfiguration> configuration = new AtomicReference<>();
    private final FakeClient client;

    private CapturingFactory(FakeClient client) {
      this.client = client;
    }

    @Override
    public TencentCloudSmsClient create(TencentCloudSmsRuntimeConfiguration configuration) {
      this.configuration.set(configuration);
      return client;
    }
  }

  private static final class FakeClient implements TencentCloudSmsClient {
    private final TencentCloudSmsSendResult result;
    private TencentCloudSmsSendCommand lastCommand;

    private FakeClient(TencentCloudSmsSendResult result) {
      this.result = result;
    }

    @Override
    public TencentCloudSmsSendResult send(TencentCloudSmsSendCommand command) {
      this.lastCommand = command;
      return result;
    }
  }
}
