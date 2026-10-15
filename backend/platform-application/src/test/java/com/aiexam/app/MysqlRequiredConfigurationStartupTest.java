package com.aiexam.app;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MysqlRequiredConfigurationStartupTest {
  private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
  private static final List<String> REQUIRED_VARIABLES = List.of(
      "MYSQL_URL", "MYSQL_USERNAME", "MYSQL_PASSWORD");
  private static final String SAFE_TEST_URL =
      "jdbc:mysql://127.0.0.1:1/config_guard_test?connectTimeout=100";
  private static final String TEST_VALUE = "config-guard-value";

  @Test
  void failsBeforeDatabaseInitializationWhenMysqlUrlIsMissing() throws Exception {
    assertMissingVariable("MYSQL_URL", Map.of(
        "MYSQL_USERNAME", TEST_VALUE,
        "MYSQL_PASSWORD", TEST_VALUE));
  }

  @Test
  void failsBeforeDatabaseInitializationWhenMysqlUsernameIsMissing() throws Exception {
    assertMissingVariable("MYSQL_USERNAME", Map.of(
        "MYSQL_URL", SAFE_TEST_URL,
        "MYSQL_PASSWORD", TEST_VALUE));
  }

  @Test
  void failsBeforeDatabaseInitializationWhenMysqlPasswordIsMissing() throws Exception {
    assertMissingVariable("MYSQL_PASSWORD", Map.of(
        "MYSQL_URL", SAFE_TEST_URL,
        "MYSQL_USERNAME", TEST_VALUE));
  }

  private void assertMissingVariable(String missingVariable, Map<String, String> provided)
      throws Exception {
    ProcessResult result = startApplication(missingVariable, provided);

    assertThat(result.finished()).as("child application must terminate").isTrue();
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output())
        .contains("Required mysql profile environment variable is missing: " + missingVariable)
        .doesNotContain(
            "HikariPool",
            "FlywayExecutor",
            "DbMigrate",
            "jdbc:h2:",
            "jdbc:mysql://127.0.0.1:33307/ai_exam_platform");
  }

  private ProcessResult startApplication(String missingVariable, Map<String, String> provided)
      throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command()).redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    REQUIRED_VARIABLES.forEach(variable -> removeIgnoreCase(environment, variable));
    environment.putAll(provided);
    removeIgnoreCase(environment, missingVariable);

    Process process = builder.start();
    CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readOutput(process));
    boolean finished = process.waitFor(STARTUP_TIMEOUT.toSeconds(), SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(5, SECONDS);
    }
    return new ProcessResult(finished, process.exitValue(), output.get(5, SECONDS));
  }

  private List<String> command() {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-Dspring.profiles.active=mysql");
    command.add("-cp");
    command.add(mainApplicationClasspath());
    command.add(AiExamPlatformApplication.class.getName());
    command.add("--spring.main.web-application-type=none");
    command.add("--spring.main.banner-mode=off");
    return command;
  }

  private String mainApplicationClasspath() {
    return Pattern.compile(Pattern.quote(File.pathSeparator))
        .splitAsStream(System.getProperty("java.class.path"))
        .filter(entry -> !entry.replace('\\', '/').contains("/target/test-classes"))
        .collect(Collectors.joining(File.pathSeparator));
  }

  private String javaExecutable() {
    String executable = System.getProperty("os.name").toLowerCase().contains("win")
        ? "java.exe"
        : "java";
    return Path.of(System.getProperty("java.home"), "bin", executable).toString();
  }

  private void removeIgnoreCase(Map<String, String> environment, String key) {
    environment.keySet().removeIf(candidate -> candidate.equalsIgnoreCase(key));
  }

  private String readOutput(Process process) {
    try {
      return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read child application output", exception);
    }
  }

  private record ProcessResult(boolean finished, int exitCode, String output) {}
}
