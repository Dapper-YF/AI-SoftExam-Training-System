package com.aiexam.question.admin.application;

import com.aiexam.common.error.BusinessException;
import com.aiexam.question.admin.dto.QuestionImportRequest;
import com.aiexam.question.admin.dto.QuestionImportTokenConfirmRequest;
import com.aiexam.question.admin.dto.QuestionImportUploadResultDto;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class QuestionImportUploadService {
  static final String SCHEMA_VERSION = "question-import/1.0";
  static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
  private static final Pattern SAFE_FILE_NAME = Pattern.compile("[\\p{L}\\p{N} ._()-]{1,180}");
  private static final Pattern TOKEN = Pattern.compile(
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final List<String> EXTENSIONS = List.of(".xlsx", ".json", ".jsonl");
  private static final String GENERIC_BINARY_MIME = "application/octet-stream";
  private static final Map<String, Set<String>> MIME_TYPES = Map.of(
      ".xlsx", Set.of(
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          GENERIC_BINARY_MIME),
      ".json", Set.of("application/json", "text/json", GENERIC_BINARY_MIME),
      ".jsonl", Set.of("application/x-ndjson", "application/ndjson", "application/jsonl",
          "application/json", "text/plain", GENERIC_BINARY_MIME));

  private final QuestionImportApplicationService importService;
  private final QuestionAdminCurrentUser currentUser;
  private final Path allowedRoot;

  @Autowired
  public QuestionImportUploadService(
      QuestionImportApplicationService importService,
      QuestionAdminCurrentUser currentUser,
      @Value("${aiexam.question-import.allowed-root:}") String allowedRoot
  ) {
    this(importService, currentUser,
        allowedRoot == null || allowedRoot.isBlank() ? null : Path.of(allowedRoot));
  }

  public QuestionImportUploadService(
      QuestionImportApplicationService importService,
      QuestionAdminCurrentUser currentUser,
      Path allowedRoot
  ) {
    this.importService = importService;
    this.currentUser = currentUser;
    this.allowedRoot = allowedRoot;
  }

  public QuestionImportUploadResultDto preflight(
      String schemaVersion,
      UUID clientMutationId,
      MultipartFile file
  ) {
    currentUser.require("question:import");
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw invalid("QUESTION_IMPORT_SCHEMA_VERSION_INVALID",
          "schemaVersion must be question-import/1.0");
    }
    if (clientMutationId == null) {
      throw invalid("QUESTION_IMPORT_MUTATION_ID_REQUIRED", "clientMutationId is required");
    }
    StoredUpload stored = store(file);
    var result = importService.preflight(new QuestionImportRequest(stored.path().toString(), clientMutationId));
    String status = result.eligibleCount() > 0 ? "READY_TO_CONFIRM" : "PREFLIGHT_FAILED";
    return QuestionImportUploadResultDto.from(stored.token(), status, result);
  }

  public QuestionImportUploadResultDto confirm(
      String rawToken,
      QuestionImportTokenConfirmRequest request
  ) {
    currentUser.require("question:import");
    if (request == null || request.clientMutationId() == null) {
      throw invalid("QUESTION_IMPORT_MUTATION_ID_REQUIRED", "clientMutationId is required");
    }
    String token = token(rawToken);
    Path source = locate(token);
    var result = importService.confirm(new QuestionImportRequest(source.toString(), request.clientMutationId()));
    return QuestionImportUploadResultDto.from(token, "COMPLETED", result);
  }

  private StoredUpload store(MultipartFile file) {
    if (file == null || file.isEmpty() || file.getSize() <= 0) {
      throw invalid("QUESTION_IMPORT_FILE_EMPTY", "Import file must not be empty");
    }
    if (file.getSize() > MAX_FILE_BYTES) {
      throw invalid("QUESTION_IMPORT_FILE_SIZE_INVALID", "Import file must be at most 10 MiB");
    }
    String originalName = file.getOriginalFilename();
    if (originalName == null || !SAFE_FILE_NAME.matcher(originalName).matches()
        || originalName.contains("/") || originalName.contains("\\")) {
      throw invalid("QUESTION_IMPORT_FILE_NAME_INVALID", "Import file name is unsafe");
    }
    String extension = extension(originalName);
    validateMime(file.getContentType(), extension);
    Path uploads = uploadDirectory();
    String token = UUID.randomUUID().toString();
    Path target = uploads.resolve(token + "--" + originalName).normalize();
    if (!target.startsWith(uploads)) {
      throw invalid("QUESTION_IMPORT_PATH_FORBIDDEN", "Upload path is outside the controlled directory");
    }
    boolean complete = false;
    try (InputStream input = file.getInputStream();
         OutputStream output = Files.newOutputStream(target, createNewOptions())) {
      byte[] buffer = new byte[8192];
      long written = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        written += read;
        if (written > MAX_FILE_BYTES) {
          throw invalid("QUESTION_IMPORT_FILE_SIZE_INVALID", "Import file must be at most 10 MiB");
        }
        output.write(buffer, 0, read);
      }
      if (written == 0) {
        throw invalid("QUESTION_IMPORT_FILE_EMPTY", "Import file must not be empty");
      }
      validateSignature(target, extension);
      complete = true;
      return new StoredUpload(token, target);
    } catch (FileAlreadyExistsException exception) {
      throw invalid("QUESTION_IMPORT_TOKEN_COLLISION", "Could not allocate an upload token");
    } catch (IOException exception) {
      throw invalid("QUESTION_IMPORT_UPLOAD_FAILED", "Could not persist the import upload");
    } finally {
      if (!complete) {
        try {
          Files.deleteIfExists(target);
        } catch (IOException ignored) {
          // A failed partial upload is never returned as a usable token.
        }
      }
    }
  }

  private Path locate(String token) {
    Path uploads = uploadDirectory();
    List<Path> matches;
    try (var paths = Files.list(uploads)) {
      String prefix = token + "--";
      matches = paths
          .filter(path -> path.normalize().startsWith(uploads))
          .filter(path -> path.getFileName().toString().startsWith(prefix))
          .filter(path -> safeStoredName(path.getFileName().toString().substring(prefix.length())))
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> !Files.isSymbolicLink(path))
          .toList();
    } catch (IOException exception) {
      throw new BusinessException("QUESTION_IMPORT_UPLOAD_NOT_FOUND",
          "Import upload source is no longer available", HttpStatus.NOT_FOUND);
    }
    if (matches.size() != 1) {
      throw new BusinessException("QUESTION_IMPORT_UPLOAD_NOT_FOUND",
          "Import upload token does not identify exactly one source file", HttpStatus.NOT_FOUND);
    }
    try {
      Path real = matches.get(0).toRealPath();
      Path realUploads = uploads.toRealPath();
      if (!real.startsWith(realUploads)) {
        throw invalid("QUESTION_IMPORT_PATH_FORBIDDEN", "Upload token resolves outside the controlled directory");
      }
      return real;
    } catch (IOException exception) {
      throw new BusinessException("QUESTION_IMPORT_UPLOAD_NOT_FOUND",
          "Import upload source is no longer available", HttpStatus.NOT_FOUND);
    }
  }

  private boolean safeStoredName(String originalName) {
    if (!SAFE_FILE_NAME.matcher(originalName).matches()
        || originalName.contains("/") || originalName.contains("\\")) {
      return false;
    }
    String lower = originalName.toLowerCase(Locale.ROOT);
    return EXTENSIONS.stream().anyMatch(lower::endsWith);
  }

  private Path uploadDirectory() {
    if (allowedRoot == null) {
      throw invalid("QUESTION_IMPORT_ROOT_NOT_CONFIGURED",
          "Question import allowed root is not configured");
    }
    try {
      Path root = allowedRoot.toRealPath();
      if (!Files.isDirectory(root)) {
        throw invalid("QUESTION_IMPORT_ROOT_NOT_CONFIGURED",
            "Question import allowed root is not a directory");
      }
      Path uploads = root.resolve(".uploads").normalize();
      if (!uploads.startsWith(root)) {
        throw invalid("QUESTION_IMPORT_PATH_FORBIDDEN", "Controlled upload directory is invalid");
      }
      try {
        Files.createDirectory(uploads);
      } catch (FileAlreadyExistsException ignored) {
        // The directory is validated below for every operation.
      }
      if (Files.isSymbolicLink(uploads) || !Files.isDirectory(uploads, LinkOption.NOFOLLOW_LINKS)) {
        throw invalid("QUESTION_IMPORT_PATH_FORBIDDEN",
            "Controlled upload directory must be a real directory");
      }
      Path realUploads = uploads.toRealPath();
      if (!realUploads.startsWith(root)) {
        throw invalid("QUESTION_IMPORT_PATH_FORBIDDEN",
            "Controlled upload directory resolves outside the allowed root");
      }
      return realUploads;
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException exception) {
      throw invalid("QUESTION_IMPORT_ROOT_NOT_CONFIGURED",
          "Question import allowed root is missing or inaccessible");
    }
  }

  private String extension(String originalName) {
    String lower = originalName.toLowerCase(Locale.ROOT);
    return EXTENSIONS.stream()
        .filter(lower::endsWith)
        .findFirst()
        .orElseThrow(() -> invalid("QUESTION_IMPORT_FILE_TYPE_INVALID",
            "Only .xlsx, question-import/1.0 .json and canonical .jsonl imports are allowed"));
  }

  private void validateMime(String rawMime, String extension) {
    String mime = rawMime == null ? "" : rawMime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (mime.isEmpty() || !MIME_TYPES.getOrDefault(extension, Set.of()).contains(mime)) {
      throw invalid("QUESTION_IMPORT_FILE_MIME_INVALID",
          "Import file MIME type does not match its extension");
    }
  }

  private void validateSignature(Path path, String extension) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    if (".xlsx".equals(extension)) {
      if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K'
          || bytes[2] != 3 || bytes[3] != 4) {
        throw invalid("QUESTION_IMPORT_FILE_SIGNATURE_INVALID",
            "XLSX upload is not an OOXML ZIP package");
      }
      return;
    }

    final String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException exception) {
      throw invalid("QUESTION_IMPORT_FILE_SIGNATURE_INVALID",
          "JSON import must be valid UTF-8 text");
    }
    int offset = !text.isEmpty() && text.charAt(0) == '\uFEFF' ? 1 : 0;
    while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) {
      offset++;
    }
    if (offset >= text.length() || text.charAt(offset) != '{') {
      throw invalid("QUESTION_IMPORT_FILE_SIGNATURE_INVALID",
          "JSON import must start with a JSON object");
    }
  }

  private String token(String rawToken) {
    String normalized = rawToken == null ? "" : rawToken.toLowerCase(Locale.ROOT);
    if (!TOKEN.matcher(normalized).matches()) {
      throw invalid("QUESTION_IMPORT_TOKEN_INVALID", "Import token must be a canonical UUID");
    }
    return UUID.fromString(normalized).toString();
  }

  private OpenOption[] createNewOptions() {
    return new OpenOption[] {StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};
  }

  private BusinessException invalid(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }

  private record StoredUpload(String token, Path path) {
  }
}
