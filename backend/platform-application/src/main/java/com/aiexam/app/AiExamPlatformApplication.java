package com.aiexam.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.aiexam.app.portal.PortalCrawlerProperties;
import com.aiexam.app.aiassistant.AiAssistantProperties;
import com.aiexam.ai.config.AiCapabilityProperties;

@SpringBootApplication(
    scanBasePackages = "com.aiexam",
    exclude = UserDetailsServiceAutoConfiguration.class
)
@EnableScheduling
@EnableConfigurationProperties({
    PortalCrawlerProperties.class,
    AiAssistantProperties.class,
    AiCapabilityProperties.class
})
public class AiExamPlatformApplication {
  public static void main(String[] args) {
    SpringApplication.run(AiExamPlatformApplication.class, args);
  }
}
