package com.aiexam.question.admin.importing;

import com.aiexam.common.error.BusinessException;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.ScoringPoint;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.PendingKnowledgeBinding;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.VersionContent;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.VersionSource;
import com.aiexam.question.dto.request.QuestionCreateRequest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

public class QuestionCanonicalImportReader {
  static final String CANONICAL_FILE = "canonical-questions.jsonl";
  private static final long MAX_CANONICAL_BYTES = 256L * 1024 * 1024;
  private static final long MAX_JSON_DOCUMENT_BYTES = 10L * 1024 * 1024;
  private static final int MAX_LINES = 100_000;
  private static final int MAX_JSON_QUESTIONS = 200;
  private static final Set<String> QUESTION_TYPES = Set.of(
      "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CASE", "ESSAY", "CODE_GAP", "FILL_BLANK",
      "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION");
  private static final Set<String> OBJECTIVE_TYPES = Set.of(
      "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE");
  private static final Set<String> ROOT_FIELDS = Set.of(
      "schemaVersion", "qualificationCode", "subjectCode", "questions");
  private static final Set<String> OPTIONAL_ROOT_FIELDS = Set.of(
      "paperKind", "paperName", "yearNo", "durationMinutes", "totalScore",
      "sourceFileName", "sourceFileSha256");
  private static final Set<String> QUESTION_FIELDS = Set.of(
      "externalKey", "questionType", "title", "yearNo", "difficulty", "source", "stems",
      "subQuestions", "knowledgeBindings");
  private static final Set<String> SOURCE_FIELDS = Set.of(
      "sourceType", "sourceRef", "syllabusBasis", "copyrightStatus", "usageRestriction");
  private static final Set<String> STEM_FIELDS = Set.of(
      "stemKey", "stemType", "content", "attachmentUrl", "resourceId", "sortOrder");
  private static final Set<String> OPTIONAL_STEM_FIELDS = Set.of("attachmentUrl");
  private static final Set<String> SUB_QUESTION_FIELDS = Set.of(
      "subQuestionNo", "subQuestionType", "content", "score", "answerFormat", "gradingMode",
      "sortOrder", "options", "answers", "solutions", "scoringRules", "scoringPoints",
      "knowledgeBindings");
  private static final Set<String> OPTION_FIELDS = Set.of("optionKey", "content", "sortOrder");
  private static final Set<String> ANSWER_FIELDS = Set.of(
      "answerKind", "optionKeys", "booleanValue", "textValue", "numberValue", "structuredValue", "sortOrder");
  private static final Set<String> SOLUTION_FIELDS = Set.of("solutionType", "content", "sortOrder");
  private static final Set<String> SCORING_RULE_FIELDS = Set.of("ruleType", "ruleJson", "sortOrder");
  private static final Set<String> SCORING_RULE_PAYLOAD_FIELDS = Set.of(
      "mode", "tolerance", "caseSensitive");
  private static final Set<String> SCORING_POINT_FIELDS = Set.of(
      "pointCode", "pointText", "score", "sortOrder");
  private static final Set<String> KNOWLEDGE_BINDING_FIELDS = Set.of(
      "knowledgeType", "knowledgeCode", "weight", "sortOrder");

  private final Path allowedRoot;
  private final ObjectMapper objectMapper;
  private final ObjectMapper canonicalMapper;

  public QuestionCanonicalImportReader(Path allowedRoot, ObjectMapper objectMapper) {
    this.allowedRoot = allowedRoot;
    this.objectMapper = objectMapper.copy().findAndRegisterModules()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    this.canonicalMapper = this.objectMapper.copy()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  public CanonicalBatch read(String inputPath) {
    Path canonical = resolveCanonical(inputPath);
    try {
      long size = Files.size(canonical);
      boolean jsonDocument = canonical.getFileName().toString().toLowerCase(Locale.ROOT)
          .endsWith(".json");
      long maximum = jsonDocument ? MAX_JSON_DOCUMENT_BYTES : MAX_CANONICAL_BYTES;
      if (size <= 0 || size > maximum) {
        throw invalid("QUESTION_IMPORT_FILE_SIZE_INVALID",
            jsonDocument
                ? "question-import/1.0 JSON must be non-empty and no larger than 10 MiB"
                : "Canonical JSONL must be non-empty and no larger than 256 MiB");
      }
      byte[] bytes = Files.readAllBytes(canonical);
      String sha256 = sha256(bytes);
      if (jsonDocument) {
        return readJsonDocument(canonical, bytes, sha256);
      }
      List<CanonicalItem> items = new ArrayList<>();
      Set<String> eligibleKeys = new HashSet<>();
      Set<String> eligibleHashes = new HashSet<>();
      try (BufferedReader reader = Files.newBufferedReader(canonical, StandardCharsets.UTF_8)) {
        String line;
        int lineNo = 0;
        while ((line = reader.readLine()) != null) {
          lineNo++;
          if (lineNo > MAX_LINES) {
            throw invalid("QUESTION_IMPORT_TOO_MANY_ROWS", "Canonical JSONL exceeds 100000 rows");
          }
          if (line.isBlank()) {
            throw invalid("QUESTION_IMPORT_EMPTY_LINE", "Canonical JSONL contains an empty row at " + lineNo);
          }
          CanonicalItem item = parse(lineNo, line);
          if (item.eligible()) {
            if (!eligibleKeys.add(item.externalKey()) || !eligibleHashes.add(item.contentHash())) {
              throw invalid("QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY",
                  "Eligible canonical rows duplicate externalKey/contentHash at line " + lineNo);
            }
          }
          items.add(item);
        }
      }
      int eligible = (int) items.stream().filter(CanonicalItem::eligible).count();
      return new CanonicalBatch(canonical, sha256, items, eligible, null, null, null, null,
          null, null, null, null, null);
    } catch (IOException exception) {
      throw invalid("QUESTION_IMPORT_FILE_READ_FAILED", "Cannot read canonical JSONL: " + exception.getMessage());
    }
  }

  public Path resolveWithinRoot(String inputPath) {
    return resolveCanonical(inputPath);
  }

  private Path resolveCanonical(String inputPath) {
    if (allowedRoot == null || inputPath == null || inputPath.isBlank()) {
      throw invalid("QUESTION_IMPORT_ROOT_NOT_CONFIGURED",
          "A non-empty allowed import root and input path are required");
    }
    try {
      // Canonicalize both sides with the same link policy. JUnit's Windows temp root can
      // itself be a junction; comparing a non-followed root with a followed child falsely
      // rejects a file that is actually contained by that root.
      Path root = allowedRoot.toRealPath();
      Path candidate = Path.of(inputPath);
      if (!candidate.isAbsolute()) {
        candidate = root.resolve(candidate);
      }
      candidate = candidate.normalize();
      if (Files.isDirectory(candidate)) {
        candidate = candidate.resolve(CANONICAL_FILE);
      }
      Path real = candidate.toRealPath();
      if (!real.startsWith(root) || !Files.isRegularFile(real)
          || !isCanonicalSource(root, real)) {
        throw invalid("QUESTION_IMPORT_PATH_FORBIDDEN",
            "Input must resolve to canonical-questions.jsonl or a controlled JSONL upload");
      }
      return real;
    } catch (IOException | java.nio.file.InvalidPathException exception) {
      throw invalid("QUESTION_IMPORT_PATH_FORBIDDEN",
          "Input path is missing, invalid, or outside the configured import root: "
              + exception.getMessage());
    }
  }

  private boolean isCanonicalSource(Path root, Path real) throws IOException {
    if (CANONICAL_FILE.equals(real.getFileName().toString())) {
      return true;
    }
    String fileName = real.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    if (!fileName.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        + "--[\\p{L}\\p{N} ._()-]{1,180}\\.(json|jsonl)")) {
      return false;
    }
    Path uploads = root.resolve(".uploads").normalize();
    return Files.isDirectory(uploads) && real.getParent().equals(uploads.toRealPath());
  }

  private CanonicalBatch readJsonDocument(Path path, byte[] bytes, String fileSha256) {
    final JsonNode root;
    try {
      root = objectMapper.readTree(bytes);
    } catch (IOException exception) {
      throw invalid("QUESTION_IMPORT_JSON_INVALID", "question-import/1.0 JSON is invalid");
    }
    requireExactObject(root, ROOT_FIELDS, OPTIONAL_ROOT_FIELDS, "");
    requireText(root, "schemaVersion", "question-import/1.0", 1);
    String qualificationCode = requiredCode(root, "qualificationCode", "", 32);
    String subjectCode = requiredCode(root, "subjectCode", "", 32);
    String paperKind = nullableText(root, "paperKind");
    if (paperKind != null) {
      paperKind = paperKind.toUpperCase(Locale.ROOT);
      if (!Set.of("REAL_PAPER", "MOCK_PAPER").contains(paperKind)) {
        throw invalid("QUESTION_IMPORT_PAPER_KIND_INVALID", "paperKind must be REAL_PAPER or MOCK_PAPER");
      }
    }
    JsonNode questions = root.path("questions");
    if (!questions.isArray() || questions.isEmpty() || questions.size() > MAX_JSON_QUESTIONS) {
      throw invalid("QUESTION_IMPORT_SCHEMA_INVALID",
          "/questions must contain between 1 and " + MAX_JSON_QUESTIONS + " questions");
    }

    Set<String> externalKeys = new HashSet<>();
    Set<String> contentHashes = new HashSet<>();
    List<CanonicalItem> items = new ArrayList<>();
    for (int index = 0; index < questions.size(); index++) {
      JsonNode question = questions.get(index);
      String pointer = "/questions/" + index;
      validateFrozenQuestion(question, pointer);
      String externalKey = requiredText(question, "externalKey", index + 1);
      if (!externalKeys.add(externalKey)) {
        throw invalid("QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY",
            "Duplicate externalKey at " + pointer + "/externalKey");
      }

      ParsedContent parsed = parseQuestion(question, index + 1);
      Map<String, Object> canonical = new LinkedHashMap<>();
      canonical.put("schemaVersion", "question-import/1.0");
      canonical.put("qualificationCode", qualificationCode);
      canonical.put("subjectCode", subjectCode);
      canonical.put("question", canonicalMapper.convertValue(question, Object.class));
      String canonicalJson = canonicalJson(canonical);
      String contentHash = sha256(canonicalJson.getBytes(StandardCharsets.UTF_8));
      if (!contentHashes.add(contentHash)) {
        throw invalid("QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY",
            "Two JSON questions normalize to the same content hash at " + pointer);
      }

      JsonNode source = question.path("source");
      String sourceType = requiredText(source, "sourceType", index + 1);
      String sourceRef = nullableText(source, "sourceRef");
      String usageRestriction = nullableText(source, "usageRestriction");
      VersionSource versionSource = new VersionSource(externalKey, sourceType, sourceRef,
          path.toString(), fileSha256, pointer, index + 1, paperKind,
          requiredText(source, "syllabusBasis", index + 1),
          requiredText(source, "copyrightStatus", index + 1),
          usageRestriction == null ? "NONE" : usageRestriction, contentHash, contentHash);
      items.add(new CanonicalItem(index + 1, externalKey, contentHash, true, null, canonicalJson,
          qualificationCode, subjectCode, parsed.content(), versionSource));
    }
    String sourceFileName = nullableText(root, "sourceFileName");
    String sourceFileSha256 = nullableText(root, "sourceFileSha256");
    if (sourceFileName != null
        && (sourceFileName.length() > 255 || sourceFileName.contains("/")
            || sourceFileName.contains("\\") || sourceFileName.contains(".."))) {
      throw invalid("QUESTION_IMPORT_SOURCE_FILE_NAME_INVALID",
          "sourceFileName must be a safe file name");
    }
    if (sourceFileSha256 != null && !sourceFileSha256.matches("[0-9a-fA-F]{64}")) {
      throw invalid("QUESTION_IMPORT_SOURCE_FILE_HASH_INVALID",
          "sourceFileSha256 must be a 64 character hexadecimal SHA-256");
    }
    return new CanonicalBatch(path, fileSha256, items, items.size(), qualificationCode, subjectCode,
        paperKind, nullableText(root, "paperName"), nullableInt(root, "yearNo"),
        nullableInt(root, "durationMinutes"), root.path("totalScore").isNumber()
            ? root.path("totalScore").decimalValue() : null,
        sourceFileName, sourceFileSha256 == null ? null : sourceFileSha256.toLowerCase(Locale.ROOT));
  }

  private void validateFrozenQuestion(JsonNode question, String pointer) {
    requireExactObject(question, QUESTION_FIELDS, pointer);
    String externalKey = requiredText(question, "externalKey", 1);
    if (!externalKey.matches("[A-Z0-9][A-Z0-9_-]{0,63}")) {
      throw schema(pointer + "/externalKey", "externalKey is invalid");
    }
    String questionType = requiredText(question, "questionType", 1).toUpperCase(Locale.ROOT);
    if (!QUESTION_TYPES.contains(questionType)) {
      throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
          "Unsupported questionType at " + pointer + "/questionType");
    }
    if (!question.path("title").isNull() && !question.path("title").isTextual()) {
      throw schema(pointer + "/title", "title must be text or null");
    }
    if (!question.path("yearNo").isNull()
        && (!question.path("yearNo").isIntegralNumber()
        || question.path("yearNo").asInt() < 2000 || question.path("yearNo").asInt() > 2100)) {
      throw schema(pointer + "/yearNo", "yearNo must be null or between 2000 and 2100");
    }
    if (!Set.of("EASY", "MEDIUM", "HARD").contains(requiredText(question, "difficulty", 1))) {
      throw schema(pointer + "/difficulty", "difficulty is invalid");
    }

    validateFrozenSource(question.path("source"), pointer + "/source");
    JsonNode stems = requiredNonEmptyArray(question, "stems", pointer);
    requireContiguousSort(stems, pointer + "/stems");
    for (int index = 0; index < stems.size(); index++) {
      JsonNode stem = stems.get(index);
      String stemPointer = pointer + "/stems/" + index;
      requireExactObject(stem, STEM_FIELDS, OPTIONAL_STEM_FIELDS, stemPointer);
      requiredText(stem, "stemKey", 1);
      if (!Set.of("MAIN_STEM", "CASE_MATERIAL", "ATTACHMENT", "ESSAY_REQUIREMENT")
          .contains(requiredText(stem, "stemType", 1))) {
        throw schema(stemPointer + "/stemType", "stemType is invalid");
      }
      boolean hasContent = stem.path("content").isTextual() && !stem.path("content").asText().isBlank();
      boolean hasResource = stem.path("resourceId").isIntegralNumber()
          && stem.path("resourceId").asLong() > 0;
      if (!hasContent && !hasResource) {
        throw schema(stemPointer, "stem requires content or resourceId");
      }
      JsonNode attachmentUrl = stem.path("attachmentUrl");
      if (!attachmentUrl.isMissingNode() && !attachmentUrl.isNull()
          && (!attachmentUrl.isTextual() || !attachmentUrl.asText().matches(
          "/api/v1/public/question-assets/[0-9A-Fa-f]{16}\\.(?i:png|jpe?g|gif|webp)"))) {
        throw schema(stemPointer + "/attachmentUrl", "attachmentUrl is invalid");
      }
    }

    JsonNode subQuestions = requiredNonEmptyArray(question, "subQuestions", pointer);
    if (!validSubQuestionCount(questionType, subQuestions.size())) {
      throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
          "Question/sub-question cardinality is invalid at " + pointer + "/subQuestions");
    }
    if ("CASE".equals(questionType) && !containsStemType(stems, "CASE_MATERIAL")) {
      throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
          "CASE requires CASE_MATERIAL at " + pointer + "/stems");
    }
    if ("ESSAY".equals(questionType) && !containsStemType(stems, "ESSAY_REQUIREMENT")) {
      throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
          "ESSAY requires ESSAY_REQUIREMENT at " + pointer + "/stems");
    }
    requireContiguousSort(subQuestions, pointer + "/subQuestions");
    Set<Integer> numbers = new HashSet<>();
    for (int index = 0; index < subQuestions.size(); index++) {
      JsonNode sub = subQuestions.get(index);
      int number = sub.path("subQuestionNo").asInt(0);
      if (number <= 0 || !numbers.add(number)) {
        throw invalid("QUESTION_IMPORT_SORT_ORDER_INVALID",
            "subQuestionNo must be unique and positive at " + pointer + "/subQuestions/" + index);
      }
      validateFrozenSubQuestion(questionType, sub, pointer + "/subQuestions/" + index);
    }
    for (int number = 1; number <= numbers.size(); number++) {
      if (!numbers.contains(number)) {
        throw invalid("QUESTION_IMPORT_SORT_ORDER_INVALID",
            "subQuestionNo must be contiguous from 1 at " + pointer + "/subQuestions");
      }
    }
    validateKnowledgeBindings(question.path("knowledgeBindings"), pointer + "/knowledgeBindings");
  }

  private void validateFrozenSource(JsonNode source, String pointer) {
    requireExactObject(source, SOURCE_FIELDS, pointer);
    String sourceType = requiredText(source, "sourceType", 1);
    String copyright = requiredText(source, "copyrightStatus", 1);
    requiredText(source, "syllabusBasis", 1);
    if ("ORIGINAL".equals(sourceType)) {
      if (!source.path("sourceRef").isNull() || !"ORIGINAL_DECLARED".equals(copyright)) {
        throw schema(pointer, "ORIGINAL source requires null sourceRef and ORIGINAL_DECLARED rights");
      }
    } else if ("AUTHORIZED_IMPORT".equals(sourceType)) {
      if (!source.path("sourceRef").isTextual() || source.path("sourceRef").asText().isBlank()
          || !"AUTHORIZED".equals(copyright)) {
        throw schema(pointer, "AUTHORIZED_IMPORT requires sourceRef and AUTHORIZED rights");
      }
    } else {
      throw schema(pointer + "/sourceType", "sourceType is invalid");
    }
    if (!source.path("usageRestriction").isNull()
        && !source.path("usageRestriction").isTextual()) {
      throw schema(pointer + "/usageRestriction", "usageRestriction must be text or null");
    }
  }

  private void validateFrozenSubQuestion(String questionType, JsonNode sub, String pointer) {
    requireExactObject(sub, SUB_QUESTION_FIELDS, pointer);
    String subType = requiredText(sub, "subQuestionType", 1).toUpperCase(Locale.ROOT);
    if (!typeMatches(questionType, subType)) {
      throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
          "questionType and subQuestionType do not match at " + pointer);
    }
    String expectedFormat = switch (subType) {
      case "SINGLE_CHOICE", "MULTI_CHOICE" -> "OPTION_KEYS";
      case "JUDGE" -> "BOOLEAN";
      case "CALCULATION" -> "NUMBER";
      case "SHORT_ANSWER", "ESSAY_WRITING" -> "TEXT";
      case "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION" -> "JSON";
      default -> "";
    };
    if (!expectedFormat.equals(requiredText(sub, "answerFormat", 1))) {
      throw invalid("QUESTION_IMPORT_ANSWER_FORMAT_MISMATCH",
          "answerFormat does not match subQuestionType at " + pointer + "/answerFormat");
    }
    BigDecimal score = positiveDecimal(sub.path("score"), pointer + "/score");
    JsonNode options = requiredArray(sub, "options", 1);
    JsonNode answers = requiredArray(sub, "answers", 1);
    JsonNode solutions = requiredArray(sub, "solutions", 1);
    JsonNode rules = requiredArray(sub, "scoringRules", 1);
    JsonNode points = requiredArray(sub, "scoringPoints", 1);
    boolean choice = Set.of("SINGLE_CHOICE", "MULTI_CHOICE").contains(subType);
    if ((choice && options.size() < 2) || (!choice && !options.isEmpty())) {
      throw invalid("QUESTION_IMPORT_OPTIONS_INVALID", "options are invalid at " + pointer + "/options");
    }

    Set<String> optionKeys = new HashSet<>();
    requireContiguousSort(options, pointer + "/options");
    for (int index = 0; index < options.size(); index++) {
      JsonNode option = options.get(index);
      requireExactObject(option, OPTION_FIELDS, pointer + "/options/" + index);
      String key = requiredText(option, "optionKey", 1);
      if (!key.matches("[A-Z]") || !optionKeys.add(key)) {
        throw invalid("QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY",
            "optionKey must be a unique uppercase letter at " + pointer + "/options/" + index);
      }
      requiredText(option, "content", 1);
    }

    String countCode = "SINGLE_CHOICE".equals(subType)
        ? "QUESTION_IMPORT_SINGLE_ANSWER_COUNT" : "QUESTION_IMPORT_ANSWER_KIND_MISMATCH";
    if (answers.size() != 1) {
      throw invalid(countCode, "Exactly one answer is required at " + pointer + "/answers");
    }
    JsonNode answer = answers.get(0);
    requireExactObject(answer, ANSWER_FIELDS, Set.of("structuredValue"), pointer + "/answers/0");
    String answerKind = requiredText(answer, "answerKind", 1);
    if (!expectedFormat.equals(answerKind)) {
      throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH",
          "answerKind does not match answerFormat at " + pointer + "/answers/0");
    }
    validateAnswerPayload(subType, answer, optionKeys, pointer + "/answers/0");

    if (solutions.isEmpty()) {
      throw schema(pointer + "/solutions", "at least one solution is required");
    }
    requireContiguousSort(solutions, pointer + "/solutions");
    for (int index = 0; index < solutions.size(); index++) {
      JsonNode solution = solutions.get(index);
      requireExactObject(solution, SOLUTION_FIELDS, pointer + "/solutions/" + index);
      if (!Set.of("REFERENCE_ANSWER", "ANALYSIS").contains(requiredText(solution, "solutionType", 1))) {
        throw schema(pointer + "/solutions/" + index + "/solutionType", "solutionType is invalid");
      }
      requiredText(solution, "content", 1);
    }

    validateScoringRule(subType, sub, rules, pointer + "/scoringRules");
    boolean objective = OBJECTIVE_TYPES.contains(subType);
    if (objective && !points.isEmpty()) {
      throw invalid("QUESTION_IMPORT_SCORE_CLOSURE_INVALID",
          "Objective questions cannot contain scoring points at " + pointer + "/scoringPoints");
    }
    if (!objective && points.isEmpty()) {
      String code = "CASE".equals(questionType)
          ? "QUESTION_IMPORT_CASE_SCORING_POINTS_REQUIRED"
          : "QUESTION_IMPORT_SCORE_CLOSURE_INVALID";
      throw invalid(code, "Subjective question requires scoring points at " + pointer);
    }
    requireContiguousSort(points, pointer + "/scoringPoints");
    BigDecimal pointTotal = BigDecimal.ZERO;
    Set<String> pointCodes = new HashSet<>();
    for (int index = 0; index < points.size(); index++) {
      JsonNode point = points.get(index);
      requireExactObject(point, SCORING_POINT_FIELDS, pointer + "/scoringPoints/" + index);
      String pointCode = requiredText(point, "pointCode", 1);
      if (!pointCode.matches("[A-Z][A-Z0-9_]{0,31}") || !pointCodes.add(pointCode)) {
        throw invalid("QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY",
            "pointCode is invalid or duplicated at " + pointer + "/scoringPoints/" + index);
      }
      requiredText(point, "pointText", 1);
      pointTotal = pointTotal.add(positiveDecimal(point.path("score"),
          pointer + "/scoringPoints/" + index + "/score"));
    }
    if (!objective && pointTotal.compareTo(score) != 0) {
      throw invalid("QUESTION_IMPORT_SCORE_CLOSURE_INVALID",
          "Scoring points do not close to sub-question score at " + pointer);
    }
    validateKnowledgeBindings(sub.path("knowledgeBindings"), pointer + "/knowledgeBindings");
  }

  private void validateAnswerPayload(String subType, JsonNode answer, Set<String> optionKeys,
      String pointer) {
    String kind = answer.path("answerKind").asText();
    boolean optionPayload = answer.path("optionKeys").isArray();
    boolean booleanPayload = answer.path("booleanValue").isBoolean();
    boolean textPayload = answer.path("textValue").isTextual()
        && !answer.path("textValue").asText().isBlank();
    boolean numberPayload = answer.path("numberValue").isNumber();
    boolean structuredPayload = answer.path("structuredValue").isObject();
    int present = (optionPayload ? 1 : 0) + (booleanPayload ? 1 : 0)
        + (textPayload ? 1 : 0) + (numberPayload ? 1 : 0) + (structuredPayload ? 1 : 0);
    boolean matching = switch (kind) {
      case "OPTION_KEYS" -> optionPayload;
      case "BOOLEAN" -> booleanPayload;
      case "TEXT" -> textPayload;
      case "NUMBER" -> numberPayload;
      case "JSON" -> structuredPayload && answer.path("structuredValue").path("schemaVersion").isTextual();
      default -> false;
    };
    if (present != 1 || !matching) {
      throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH",
          "answerKind must select exactly one payload at " + pointer);
    }
    if (optionPayload) {
      Set<String> selected = new HashSet<>();
      for (JsonNode key : answer.path("optionKeys")) {
        if (!key.isTextual() || !selected.add(key.asText()) || !optionKeys.contains(key.asText())) {
          throw invalid("QUESTION_IMPORT_OPTION_REFERENCE_INVALID",
              "Answer option must be unique and belong to the same sub-question at " + pointer);
        }
      }
      if ("SINGLE_CHOICE".equals(subType) && selected.size() != 1) {
        throw invalid("QUESTION_IMPORT_SINGLE_ANSWER_COUNT",
            "SINGLE_CHOICE requires exactly one answer option at " + pointer);
      }
      if ("MULTI_CHOICE".equals(subType) && selected.size() < 2) {
        throw invalid("QUESTION_IMPORT_MULTI_ANSWER_MIN_TWO",
            "MULTI_CHOICE requires at least two answer options at " + pointer);
      }
    }
  }

  private void validateScoringRule(String subType, JsonNode sub, JsonNode rules, String pointer) {
    String gradingMode = requiredText(sub, "gradingMode", 1);
    if (rules.size() != 1) {
      throw invalid("QUESTION_IMPORT_SCORING_MODE_MISMATCH",
          "Exactly one scoring rule is required at " + pointer);
    }
    JsonNode rule = rules.get(0);
    requireExactObject(rule, SCORING_RULE_FIELDS, pointer + "/0");
    JsonNode ruleJson = rule.path("ruleJson");
    requireExactObject(ruleJson, SCORING_RULE_PAYLOAD_FIELDS, pointer + "/0/ruleJson");
    String ruleType = requiredText(rule, "ruleType", 1);
    String mode = requiredText(ruleJson, "mode", 1);
    String expectedMode = switch (subType) {
      case "SINGLE_CHOICE", "JUDGE" -> "EXACT";
      case "MULTI_CHOICE" -> "SET_EQUAL";
      case "CALCULATION" -> "AUTO".equals(gradingMode) ? "NUMERIC_TOLERANCE" : "MANUAL";
      case "SHORT_ANSWER", "ESSAY_WRITING" ->
          "MANUAL".equals(gradingMode) ? "MANUAL" : "KEYWORD";
      default -> "";
    };
    Set<String> allowedGrading = switch (subType) {
      case "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE" -> Set.of("AUTO");
      case "CALCULATION" -> Set.of("AUTO", "MANUAL");
      case "SHORT_ANSWER", "ESSAY_WRITING" -> Set.of("MANUAL", "AI_ASSISTED");
      default -> Set.of();
    };
    if (!allowedGrading.contains(gradingMode) || !gradingMode.equals(ruleType)
        || !expectedMode.equals(mode)) {
      throw invalid("QUESTION_IMPORT_SCORING_MODE_MISMATCH",
          "gradingMode, ruleType and ruleJson.mode do not match at " + pointer);
    }
    if ("NUMERIC_TOLERANCE".equals(mode)
        && (!ruleJson.path("tolerance").isNumber()
        || ruleJson.path("tolerance").decimalValue().compareTo(BigDecimal.ZERO) < 0)) {
      throw invalid("QUESTION_IMPORT_SCORING_MODE_MISMATCH",
          "NUMERIC_TOLERANCE requires a non-negative tolerance at " + pointer);
    }
    if (!ruleJson.path("caseSensitive").isNull()
        && !ruleJson.path("caseSensitive").isBoolean()) {
      throw schema(pointer + "/0/ruleJson/caseSensitive", "caseSensitive must be boolean or null");
    }
  }

  private void validateKnowledgeBindings(JsonNode bindings, String pointer) {
    if (!bindings.isArray()) {
      throw schema(pointer, "knowledgeBindings must be an array");
    }
    requireContiguousSort(bindings, pointer);
    Set<String> keys = new HashSet<>();
    for (int index = 0; index < bindings.size(); index++) {
      JsonNode binding = bindings.get(index);
      requireExactObject(binding, KNOWLEDGE_BINDING_FIELDS, pointer + "/" + index);
      String type = requiredText(binding, "knowledgeType", 1);
      String code = requiredText(binding, "knowledgeCode", 1);
      if (!Set.of("SYLLABUS_NODE", "CHAPTER_NODE", "TAG").contains(type)) {
        throw schema(pointer + "/" + index + "/knowledgeType", "knowledgeType is invalid");
      }
      if (!keys.add(type + "\u001f" + code)) {
        throw invalid("QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY",
            "Duplicate knowledge binding at " + pointer + "/" + index);
      }
      JsonNode weight = binding.path("weight");
      if (!weight.isNumber() || weight.decimalValue().compareTo(BigDecimal.ZERO) < 0
          || weight.decimalValue().compareTo(BigDecimal.ONE) > 0) {
        throw schema(pointer + "/" + index + "/weight", "weight must be between 0 and 1");
      }
    }
  }

  private void requireExactObject(JsonNode node, Set<String> fields, String pointer) {
    requireExactObject(node, fields, Set.of(), pointer);
  }

  private void requireExactObject(JsonNode node, Set<String> fields, Set<String> optionalFields,
      String pointer) {
    if (node == null || !node.isObject()) {
      throw schema(pointer, "value must be an object");
    }
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    Set<String> unknown = new HashSet<>(actual);
    unknown.removeAll(fields);
    unknown.removeAll(optionalFields);
    if (!unknown.isEmpty()) {
      throw invalid("QUESTION_IMPORT_UNKNOWN_FIELD",
          "Unknown field(s) " + unknown + " at " + (pointer.isEmpty() ? "/" : pointer));
    }
    Set<String> missing = new HashSet<>(fields);
    missing.removeAll(actual);
    missing.removeAll(optionalFields);
    if (!missing.isEmpty()) {
      throw schema(pointer, "Missing required field(s) " + missing);
    }
  }

  private JsonNode requiredNonEmptyArray(JsonNode parent, String field, String pointer) {
    JsonNode value = parent.path(field);
    if (!value.isArray() || value.isEmpty()) {
      throw schema(pointer + "/" + field, field + " must be a non-empty array");
    }
    return value;
  }

  private void requireContiguousSort(JsonNode array, String pointer) {
    if (!array.isArray() || array.isEmpty()) {
      return;
    }
    int[] values = new int[array.size()];
    for (int index = 0; index < array.size(); index++) {
      JsonNode value = array.get(index).path("sortOrder");
      if (!value.isIntegralNumber() || value.asInt() <= 0) {
        throw invalid("QUESTION_IMPORT_SORT_ORDER_INVALID",
            "sortOrder must be positive at " + pointer + "/" + index);
      }
      values[index] = value.asInt();
    }
    Arrays.sort(values);
    for (int index = 0; index < values.length; index++) {
      if (values[index] != index + 1) {
        throw invalid("QUESTION_IMPORT_SORT_ORDER_INVALID",
            "sortOrder must be unique and contiguous from 1 at " + pointer);
      }
    }
  }

  private BigDecimal positiveDecimal(JsonNode value, String pointer) {
    if (!value.isNumber() || value.decimalValue().compareTo(BigDecimal.ZERO) <= 0
        || value.decimalValue().scale() > 2) {
      throw schema(pointer, "score must be a positive number with at most two decimals");
    }
    return value.decimalValue();
  }

  private boolean containsStemType(JsonNode stems, String type) {
    for (JsonNode stem : stems) {
      if (type.equals(stem.path("stemType").asText())) {
        return true;
      }
    }
    return false;
  }

  private boolean typeMatches(String questionType, String subType) {
    return switch (questionType) {
      case "SINGLE_CHOICE" -> "SINGLE_CHOICE".equals(subType);
      case "MULTI_CHOICE" -> "MULTI_CHOICE".equals(subType);
      case "JUDGE" -> "JUDGE".equals(subType);
      case "ESSAY" -> "ESSAY_WRITING".equals(subType);
      case "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION" -> questionType.equals(subType);
      case "CASE" -> Set.of("SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CALCULATION",
          "SHORT_ANSWER").contains(subType);
      default -> false;
    };
  }

  private String requiredCode(JsonNode parent, String field, String pointer, int maximumLength) {
    String value = requiredText(parent, field, 1);
    if (value.length() > maximumLength || !value.matches("[A-Z][A-Z0-9_]{1,31}")) {
      throw schema(pointer + "/" + field, field + " is invalid");
    }
    return value;
  }

  private String canonicalJson(Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot canonicalize question-import/1.0 JSON", exception);
    }
  }

  private BusinessException schema(String pointer, String message) {
    return invalid("QUESTION_IMPORT_SCHEMA_INVALID",
        message + " [" + (pointer == null || pointer.isEmpty() ? "/" : pointer) + "]");
  }

  private CanonicalItem parse(int lineNo, String canonicalJson) {
    final JsonNode root;
    try {
      root = objectMapper.readTree(canonicalJson);
    } catch (JsonProcessingException exception) {
      throw invalid("QUESTION_IMPORT_JSON_INVALID", "Invalid JSON at line " + lineNo);
    }
    requireText(root, "schemaVersion", "question-import/1.0", lineNo);
    requireText(root, "recordType", "QUESTION_PREFLIGHT", lineNo);
    requireText(root, "importStatus", "PREFLIGHT", lineNo);
    requireText(root, "rightsStatus", "UNVERIFIED", lineNo);
    JsonNode question = requiredObject(root, "question", lineNo);
    String externalKey = requiredText(question, "externalKey", lineNo);
    String contentHash = requiredText(question, "contentHash", lineNo).toLowerCase(Locale.ROOT);
    if (!contentHash.matches("[0-9a-f]{64}")) {
      throw invalid("QUESTION_IMPORT_CONTENT_HASH_INVALID", "Invalid contentHash at line " + lineNo);
    }
    JsonNode quality = requiredObject(root, "quality", lineNo);
    boolean eligible = quality.path("importEligible").asBoolean(false);
    if (!eligible) {
      return new CanonicalItem(lineNo, externalKey, contentHash, false,
          firstValidationError(quality), canonicalJson, null, null, null, null);
    }
    if (!quality.path("validationErrors").isArray() || !quality.path("validationErrors").isEmpty()) {
      throw invalid("QUESTION_IMPORT_ELIGIBILITY_INCONSISTENT",
          "Eligible row still contains validationErrors at line " + lineNo);
    }
    String qualificationCode = requiredText(root, "qualificationCode", lineNo);
    String subjectCode = requiredText(root, "subjectCode", lineNo);
    if ("UNKNOWN".equalsIgnoreCase(qualificationCode) || "UNKNOWN".equalsIgnoreCase(subjectCode)) {
      throw invalid("QUESTION_IMPORT_SCOPE_UNKNOWN", "Eligible row has UNKNOWN scope at line " + lineNo);
    }
    JsonNode source = requiredObject(question, "source", lineNo);
    requireText(source, "sourceType", "SOURCE_DOCUMENT_IMPORT", lineNo);
    requireText(source, "copyrightStatus", "UNVERIFIED", lineNo);
    String restriction = requiredText(source, "usageRestriction", lineNo).toLowerCase(Locale.ROOT);
    if (!restriction.contains("internal") || !restriction.contains("publication prohibited")) {
      throw invalid("QUESTION_IMPORT_RIGHTS_INVALID",
          "Eligible source must be internal-only and publication-prohibited at line " + lineNo);
    }
    ParsedContent parsed = parseQuestion(question, lineNo);
    JsonNode trace = requiredObject(root, "sourceTrace", lineNo);
    JsonNode paper = requiredObject(root, "paper", lineNo);
    String sourceSha = requiredText(trace, "documentSha256", lineNo).toLowerCase(Locale.ROOT);
    if (!sourceSha.matches("[0-9a-f]{64}")) {
      throw invalid("QUESTION_IMPORT_SOURCE_SHA_INVALID", "Invalid source SHA at line " + lineNo);
    }
    String paperKind = requiredText(paper, "paperKind", lineNo).toUpperCase(Locale.ROOT);
    if (!Set.of("REAL_PAPER", "MOCK_PAPER").contains(paperKind)) {
      throw invalid("QUESTION_IMPORT_PAPER_KIND_INVALID", "Unsupported paperKind at line " + lineNo);
    }
    VersionSource versionSource = new VersionSource(externalKey, "SOURCE_DOCUMENT_IMPORT",
        requiredText(source, "sourceRef", lineNo), requiredText(trace, "sourcePath", lineNo),
        sourceSha, requiredText(trace, "locator", lineNo), nullableInt(trace, "questionNo"),
        paperKind, requiredText(source, "syllabusBasis", lineNo), "UNVERIFIED", "INTERNAL_ONLY",
        contentHash, contentHash);
    return new CanonicalItem(lineNo, externalKey, contentHash, true, null, canonicalJson,
        qualificationCode, subjectCode, parsed.content(), versionSource);
  }

  private ParsedContent parseQuestion(JsonNode question, int lineNo) {
    String questionType = requiredText(question, "questionType", lineNo).toUpperCase(Locale.ROOT);
    if (!QUESTION_TYPES.contains(questionType)) {
      throw invalid("QUESTION_IMPORT_TYPE_INVALID", "Unsupported questionType at line " + lineNo);
    }
    List<QuestionCreateRequest.StemRequest> stems = new ArrayList<>();
    JsonNode stemNodes = requiredArray(question, "stems", lineNo);
    for (JsonNode stem : stemNodes) {
      stems.add(new QuestionCreateRequest.StemRequest(requiredText(stem, "stemType", lineNo),
          nullableText(stem, "content"), nullableText(stem, "attachmentUrl"), stem.path("sortOrder").asInt(),
          requiredText(stem, "stemKey", lineNo),
          stem.path("resourceId").isIntegralNumber() ? stem.path("resourceId").asLong() : null));
    }
    if (stems.isEmpty()) {
      throw invalid("QUESTION_IMPORT_STEM_REQUIRED", "Eligible row has no stem at line " + lineNo);
    }
    JsonNode subNodes = requiredArray(question, "subQuestions", lineNo);
    if (!validSubQuestionCount(questionType, subNodes.size())) {
      throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
          "Question/sub-question cardinality is invalid at line " + lineNo);
    }
    List<QuestionCreateRequest.SubQuestionRequest> subQuestions = new ArrayList<>();
    Map<Integer, List<ScoringPoint>> scoringPoints = new LinkedHashMap<>();
    List<PendingKnowledgeBinding> pendingKnowledgeBindings = new ArrayList<>();
    for (JsonNode sub : subNodes) {
      int number = sub.path("subQuestionNo").asInt(0);
      String subType = requiredText(sub, "subQuestionType", lineNo).toUpperCase(Locale.ROOT);
      if (number <= 0) {
        throw invalid("QUESTION_IMPORT_SUBQUESTION_INVALID", "subQuestionNo is invalid at line " + lineNo);
      }
      BigDecimal score = sub.path("score").isNull() || sub.path("score").isMissingNode()
          ? null : sub.path("score").decimalValue();
      if (score == null && OBJECTIVE_TYPES.contains(subType)) {
        score = BigDecimal.ONE.setScale(2);
      }
      if (score == null || score.compareTo(BigDecimal.ZERO) <= 0) {
        throw invalid("QUESTION_IMPORT_SCORE_INVALID", "Positive score is required at line " + lineNo);
      }
      List<QuestionCreateRequest.OptionRequest> options = new ArrayList<>();
      for (JsonNode option : requiredArray(sub, "options", lineNo)) {
        options.add(new QuestionCreateRequest.OptionRequest(requiredText(option, "optionKey", lineNo),
            requiredText(option, "content", lineNo), option.path("sortOrder").asInt()));
      }
      List<QuestionCreateRequest.AnswerKeyRequest> answers = parseAnswers(sub, lineNo);
      List<QuestionCreateRequest.SolutionRequest> solutions = new ArrayList<>();
      for (JsonNode solution : requiredArray(sub, "solutions", lineNo)) {
        solutions.add(new QuestionCreateRequest.SolutionRequest(
            requiredText(solution, "solutionType", lineNo), requiredText(solution, "content", lineNo),
            solution.path("sortOrder").asInt()));
      }
      List<QuestionCreateRequest.ScoringRuleRequest> rules = new ArrayList<>();
      for (JsonNode rule : requiredArray(sub, "scoringRules", lineNo)) {
        rules.add(new QuestionCreateRequest.ScoringRuleRequest(requiredText(rule, "ruleType", lineNo),
            json(rule.path("ruleJson")), rule.path("sortOrder").asInt()));
      }
      List<ScoringPoint> points = new ArrayList<>();
      BigDecimal pointTotal = BigDecimal.ZERO;
      for (JsonNode point : requiredArray(sub, "scoringPoints", lineNo)) {
        BigDecimal pointScore = point.path("score").decimalValue();
        if (pointScore.compareTo(BigDecimal.ZERO) <= 0) {
          throw invalid("QUESTION_IMPORT_SCORE_CLOSURE_INVALID",
              "Scoring point must be positive at line " + lineNo);
        }
        points.add(new ScoringPoint(requiredText(point, "pointCode", lineNo),
            requiredText(point, "pointText", lineNo), pointScore, point.path("sortOrder").asInt()));
        pointTotal = pointTotal.add(pointScore);
      }
      if (!points.isEmpty() && pointTotal.compareTo(score) != 0) {
        throw invalid("QUESTION_IMPORT_SCORE_CLOSURE_INVALID",
            "Scoring points do not close to sub-question score at line " + lineNo);
      }
      if ("CASE".equals(questionType) && !OBJECTIVE_TYPES.contains(subType) && points.isEmpty()) {
        throw invalid("QUESTION_IMPORT_CASE_SCORING_POINTS_REQUIRED",
            "Subjective CASE sub-question requires scoring points at line " + lineNo);
      }
      pendingKnowledgeBindings.addAll(parsePendingKnowledgeBindings(
          sub.path("knowledgeBindings"), number, lineNo));
      scoringPoints.put(number, points);
      subQuestions.add(new QuestionCreateRequest.SubQuestionRequest(number, subType,
          requiredText(sub, "content", lineNo), score,
          requiredText(sub, "answerFormat", lineNo), requiredText(sub, "gradingMode", lineNo),
          sub.path("sortOrder").asInt(), null, options, answers, solutions, rules,
          points.stream().map(point -> new QuestionCreateRequest.ScoringPointRequest(
              point.pointCode(), point.pointText(), point.score(), point.sortOrder())).toList(),
          List.of()));
    }
    pendingKnowledgeBindings.addAll(parsePendingKnowledgeBindings(
        question.path("knowledgeBindings"), null, lineNo));
    JsonNode source = requiredObject(question, "source", lineNo);
    String sourceType = nullableText(source, "sourceType");
    QuestionCreateRequest payload = new QuestionCreateRequest(1L, 1L, questionType,
        nullableText(question, "title"), nullableInt(question, "yearNo"),
        nullableText(question, "difficulty"),
        sourceType == null ? "SOURCE_DOCUMENT_IMPORT" : sourceType,
        nullableText(source, "sourceRef"),
        stems, subQuestions, List.of());
    return new ParsedContent(new VersionContent(payload, scoringPoints, pendingKnowledgeBindings));
  }

  private List<QuestionCreateRequest.AnswerKeyRequest> parseAnswers(JsonNode sub, int lineNo) {
    JsonNode answerNodes = requiredArray(sub, "answers", lineNo);
    if (answerNodes.isEmpty()) {
      throw invalid("QUESTION_IMPORT_ANSWER_MISSING", "Eligible row has no answer at line " + lineNo);
    }
    List<QuestionCreateRequest.AnswerKeyRequest> answers = new ArrayList<>();
    for (JsonNode answer : answerNodes) {
      String kind = requiredText(answer, "answerKind", lineNo).toUpperCase(Locale.ROOT);
      String value = null;
      String answerJson = null;
      switch (kind) {
        case "OPTION_KEYS" -> {
          JsonNode keys = requiredArray(answer, "optionKeys", lineNo);
          if (keys.isEmpty()) {
            throw invalid("QUESTION_IMPORT_ANSWER_MISSING", "optionKeys is empty at line " + lineNo);
          }
          List<String> values = new ArrayList<>();
          keys.forEach(key -> values.add(key.asText().toUpperCase(Locale.ROOT)));
          value = String.join(",", values);
          answerJson = json(values);
        }
        case "BOOLEAN" -> {
          if (!answer.path("booleanValue").isBoolean()) {
            throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH", "booleanValue is required at line " + lineNo);
          }
          value = Boolean.toString(answer.path("booleanValue").asBoolean());
          answerJson = value;
        }
        case "TEXT" -> value = requiredText(answer, "textValue", lineNo);
        case "NUMBER" -> {
          if (!answer.path("numberValue").isNumber()) {
            throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH", "numberValue is required at line " + lineNo);
          }
          value = answer.path("numberValue").decimalValue().toPlainString();
          answerJson = value;
        }
        case "JSON" -> {
          JsonNode structured = answer.path("structuredValue");
          if (!structured.isObject() || !structured.path("schemaVersion").isTextual()) {
            throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH", "structuredValue with schemaVersion is required at line " + lineNo);
          }
          answerJson = json(structured);
        }
        default -> throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH",
            "Unsupported answerKind at line " + lineNo);
      }
      answers.add(new QuestionCreateRequest.AnswerKeyRequest(kind, value, answerJson));
    }
    return answers;
  }

  private List<PendingKnowledgeBinding> parsePendingKnowledgeBindings(
      JsonNode bindings, Integer subQuestionNo, int lineNo) {
    if (bindings == null || bindings.isMissingNode() || bindings.isNull()) {
      return List.of();
    }
    if (!bindings.isArray()) {
      throw invalid("QUESTION_IMPORT_SCHEMA_INVALID",
          "knowledgeBindings must be an array at line " + lineNo);
    }
    List<PendingKnowledgeBinding> result = new ArrayList<>();
    for (JsonNode binding : bindings) {
      BigDecimal weight = binding.path("weight").isNumber()
          ? binding.path("weight").decimalValue() : BigDecimal.ONE;
      result.add(new PendingKnowledgeBinding(subQuestionNo,
          requiredText(binding, "knowledgeType", lineNo),
          requiredText(binding, "knowledgeCode", lineNo), weight,
          binding.path("sortOrder").asInt()));
    }
    return List.copyOf(result);
  }

  private boolean validSubQuestionCount(String questionType, int count) {
    return switch (questionType) {
      case "CASE" -> count >= 2 && count <= 4;
      case "SINGLE_CHOICE" -> count >= 1 && count <= 10;
      default -> count == 1;
    };
  }

  private String firstValidationError(JsonNode quality) {
    JsonNode errors = quality.path("validationErrors");
    return errors.isArray() && !errors.isEmpty() ? errors.get(0).path("code").asText("INELIGIBLE")
        : "INELIGIBLE";
  }

  private JsonNode requiredObject(JsonNode parent, String field, int lineNo) {
    JsonNode value = parent.path(field);
    if (!value.isObject()) {
      throw invalid("QUESTION_IMPORT_SCHEMA_INVALID", field + " must be an object at line " + lineNo);
    }
    return value;
  }

  private JsonNode requiredArray(JsonNode parent, String field, int lineNo) {
    JsonNode value = parent.path(field);
    if (!value.isArray()) {
      throw invalid("QUESTION_IMPORT_SCHEMA_INVALID", field + " must be an array at line " + lineNo);
    }
    return value;
  }

  private String requiredText(JsonNode parent, String field, int lineNo) {
    String value = nullableText(parent, field);
    if (value == null || value.isBlank()) {
      throw invalid("QUESTION_IMPORT_SCHEMA_INVALID", field + " is required at line " + lineNo);
    }
    return value;
  }

  private void requireText(JsonNode parent, String field, String expected, int lineNo) {
    if (!expected.equals(requiredText(parent, field, lineNo))) {
      throw invalid("QUESTION_IMPORT_SCHEMA_INVALID",
          field + " must equal " + expected + " at line " + lineNo);
    }
  }

  private String nullableText(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }

  private Integer nullableInt(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asInt();
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize canonical import value", exception);
    }
  }

  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private BusinessException invalid(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }

  public record CanonicalBatch(Path canonicalPath, String sha256, List<CanonicalItem> items,
                               int eligibleCount, String qualificationCode, String subjectCode,
                               String paperKind, String paperName, Integer yearNo,
                               Integer durationMinutes, BigDecimal totalScore,
                               String sourceFileName, String sourceFileSha256) {
    public CanonicalBatch {
      items = List.copyOf(items);
    }
  }

  public record CanonicalItem(int lineNo, String externalKey, String contentHash,
                              boolean eligible, String errorCode, String canonicalJson,
                              String qualificationCode, String subjectCode,
                              VersionContent content, VersionSource source) {
  }

  private record ParsedContent(VersionContent content) {
  }
}
