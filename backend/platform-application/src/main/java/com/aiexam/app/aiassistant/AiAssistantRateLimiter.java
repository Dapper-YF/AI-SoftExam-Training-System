package com.aiexam.app.aiassistant;

import com.aiexam.common.error.BusinessException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
final class AiAssistantRateLimiter {
  private final AiAssistantProperties properties;
  private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

  AiAssistantRateLimiter(AiAssistantProperties properties) { this.properties = properties; }

  void acquire(long userId) {
    String key = userId + ":" + Instant.now().truncatedTo(ChronoUnit.MINUTES);
    int current = counts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    if (current > properties.maxRequestsPerMinute()) {
      throw new BusinessException("AI_ASSISTANT_RATE_LIMITED", "请求过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
    }
  }
}
