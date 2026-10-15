package com.aiexam.question.practice.attempt.infra;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PracticeAttemptRuntimeConfiguration {
  @Bean
  Clock practiceAttemptClock() {
    return Clock.systemUTC();
  }
}
