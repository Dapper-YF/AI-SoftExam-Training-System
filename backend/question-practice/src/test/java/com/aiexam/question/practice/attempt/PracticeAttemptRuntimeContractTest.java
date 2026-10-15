package com.aiexam.question.practice.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import com.aiexam.question.practice.attempt.controller.PracticeAttemptController;
import com.aiexam.question.practice.attempt.infra.JdbcPracticeAttemptRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

class PracticeAttemptRuntimeContractTest {
  @Test
  void exposesFrozenAttemptHttpRoutes() {
    Set<String> routes = Arrays.stream(PracticeAttemptController.class.getDeclaredMethods())
        .flatMap(method -> Arrays.stream(route(method)))
        .collect(Collectors.toSet());

    assertThat(routes).contains(
        "POST:",
        "GET:/{attemptId}",
        "PUT:/{attemptId}/answers/{subQuestionId}",
        "POST:/{attemptId}/questions/{questionId}/settle",
        "POST:/{attemptId}/submit",
        "GET:/{attemptId}/questions/{questionId}/explanation",
        "GET:");
  }

  @Test
  void productionRepositoryIsTheJdbcAdapter() {
    assertThat(JdbcPracticeAttemptRepository.class.getAnnotation(org.springframework.stereotype.Repository.class))
        .isNotNull();
  }

  private String[] route(Method method) {
    if (method.isAnnotationPresent(GetMapping.class)) {
      return routeValues(method.getAnnotation(GetMapping.class).value())
          .map(value -> "GET:" + value)
          .toArray(String[]::new);
    }
    if (method.isAnnotationPresent(PostMapping.class)) {
      return routeValues(method.getAnnotation(PostMapping.class).value())
          .map(value -> "POST:" + value)
          .toArray(String[]::new);
    }
    if (method.isAnnotationPresent(PutMapping.class)) {
      return routeValues(method.getAnnotation(PutMapping.class).value())
          .map(value -> "PUT:" + value)
          .toArray(String[]::new);
    }
    return new String[0];
  }

  private java.util.stream.Stream<String> routeValues(String[] values) {
    return values.length == 0 ? java.util.stream.Stream.of("") : Arrays.stream(values);
  }
}
