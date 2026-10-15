package com.aiexam.question.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.question.admin.application.QuestionAdminCurrentUser;
import com.aiexam.question.admin.application.QuestionImportApplicationService;
import com.aiexam.question.admin.application.QuestionImportUploadService;
import com.aiexam.question.admin.dto.QuestionImportRequest;
import com.aiexam.question.admin.dto.QuestionImportResultDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MultipartFile;

class QuestionImportUploadServiceTest {
  @TempDir
  Path root;

  @Test
  void acceptsJsonAndJsonLinesWithMatchingMimeAndObjectSignature() {
    QuestionImportUploadService service = service();

    var json = service.preflight("question-import/1.0", UUID.randomUUID(),
        file("questions.json", "application/json", "  {\"schemaVersion\":\"question-import/1.0\"}"));
    var jsonl = service.preflight("question-import/1.0", UUID.randomUUID(),
        file("questions.jsonl", "application/x-ndjson", "{\"schemaVersion\":\"question-import/1.0\"}"));

    assertThat(root.resolve(".uploads").resolve(json.token() + "--questions.json")).isRegularFile();
    assertThat(root.resolve(".uploads").resolve(jsonl.token() + "--questions.jsonl")).isRegularFile();
  }

  @Test
  void rejectsExplicitMimeMismatchBeforePersistingUpload() {
    assertCode("QUESTION_IMPORT_FILE_MIME_INVALID", () -> service().preflight(
        "question-import/1.0", UUID.randomUUID(),
        file("questions.json", "text/html", "{}")));

    assertThat(root.resolve(".uploads")).doesNotExist();
  }

  @Test
  void rejectsMissingMimeAndBinaryJsonSignature() {
    assertCode("QUESTION_IMPORT_FILE_MIME_INVALID", () -> service().preflight(
        "question-import/1.0", UUID.randomUUID(), file("questions.json", null, "{}")));
    assertCode("QUESTION_IMPORT_FILE_SIGNATURE_INVALID", () -> service().preflight(
        "question-import/1.0", UUID.randomUUID(),
        file("questions.json", "application/json", "<html>not json</html>")));

    assertThat(root.resolve(".uploads")).isDirectory();
    assertThat(root.resolve(".uploads")).isEmptyDirectory();
  }

  @Test
  void rejectsXlsxMimeAndSignatureMismatches() {
    assertCode("QUESTION_IMPORT_FILE_MIME_INVALID", () -> service().preflight(
        "question-import/1.0", UUID.randomUUID(),
        file("questions.xlsx", "application/json", "PK\u0003\u0004")));
    assertCode("QUESTION_IMPORT_FILE_SIGNATURE_INVALID", () -> service().preflight(
        "question-import/1.0", UUID.randomUUID(),
        file("questions.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "not a zip")));
  }

  private QuestionImportUploadService service() {
    QuestionAdminCurrentUser currentUser = new QuestionAdminCurrentUser(() -> Optional.of(
        new PlatformPrincipal(7L, "importer", "session", List.of("ADMIN"),
            List.of("question:import"), 1)));
    return new QuestionImportUploadService(new StubImportService(root), currentUser, root);
  }

  private TestMultipartFile file(String name, String mime, String content) {
    return new TestMultipartFile(name, mime, content.getBytes(StandardCharsets.UTF_8));
  }

  private void assertCode(String code, ThrowingCall call) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
        exception -> assertThat(exception.code()).isEqualTo(code));
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void run();
  }

  private static final class StubImportService extends QuestionImportApplicationService {
    private StubImportService(Path root) {
      super(null, null, new ObjectMapper(), root);
    }

    @Override
    public QuestionImportResultDto preflight(QuestionImportRequest request) {
      return new QuestionImportResultDto(null, null, "a".repeat(64), 1, 1, 0, 0, 0, 0,
          false, "UNVERIFIED", "INTERNAL_ONLY", List.of());
    }
  }

  private record TestMultipartFile(String originalFilename, String contentType, byte[] content)
      implements MultipartFile {
    @Override
    public String getName() {
      return "file";
    }

    @Override
    public String getOriginalFilename() {
      return originalFilename;
    }

    @Override
    public String getContentType() {
      return contentType;
    }

    @Override
    public boolean isEmpty() {
      return content.length == 0;
    }

    @Override
    public long getSize() {
      return content.length;
    }

    @Override
    public byte[] getBytes() {
      return content.clone();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File destination) throws IOException {
      Files.copy(getInputStream(), destination.toPath());
    }
  }
}
