package com.aiexam.question.admin.importing;

import com.aiexam.common.error.BusinessException;
import com.aiexam.question.admin.dto.QuestionWorkbookValidationDto;
import com.aiexam.question.admin.importing.QuestionCanonicalImportReader.CanonicalBatch;
import com.aiexam.question.admin.importing.QuestionCanonicalImportReader.CanonicalItem;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.PendingKnowledgeBinding;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.ScoringPoint;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.VersionContent;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.VersionSource;
import com.aiexam.question.dto.request.QuestionCreateRequest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;

/** Strict POI reader for the frozen question-import/1.0 nine-sheet workbook. */
public final class QuestionWorkbookImportReader {
  private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
  private static final long MAX_EXPANDED_BYTES = 50L * 1024 * 1024;
  private static final double MAX_COMPRESSION_RATIO = 100.0;
  private static final int MAX_ZIP_ENTRIES = 10_000;
  private static final int MAX_QUESTIONS = 200;
  private static final String SCHEMA_VERSION = "question-import/1.0";
  private static final LinkedHashMap<String, List<ColumnSpec>> CONTRACT = contract();
  private static final Map<String, List<String>> BUSINESS_KEYS = businessKeys();
  private static final Set<String> COMPOSITE_CHILDREN = Set.of(
      "Options", "Answers", "Solutions", "ScoringRules", "ScoringPoints");
  private static final Set<String> OBJECTIVE = Set.of("SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE");

  private final Path allowedRoot;
  private final ObjectMapper objectMapper;
  private final ObjectMapper canonicalMapper;

  public QuestionWorkbookImportReader(Path allowedRoot, ObjectMapper objectMapper) {
    this.allowedRoot = allowedRoot;
    this.objectMapper = objectMapper.copy().findAndRegisterModules()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    this.canonicalMapper = this.objectMapper.copy()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  public WorkbookReadResult read(String inputPath) {
    Path path = resolve(inputPath);
    inspectZip(path);
    try {
      byte[] workbookBytes = Files.readAllBytes(path);
      String workbookSha = sha256(workbookBytes);
      Map<String, List<DataRow>> rows;
      try (InputStream input = Files.newInputStream(path); XSSFWorkbook workbook = new XSSFWorkbook(input)) {
        rows = readWorkbook(workbook);
      }
      validateCrossSheet(rows);
      List<CanonicalItem> items = toCanonicalItems(path, workbookSha, rows);
      CanonicalBatch batch = new CanonicalBatch(path, workbookSha, items, items.size(),
          null, null, null, null, null, null, null, null, null);
      QuestionWorkbookValidationDto validation = new QuestionWorkbookValidationDto(true,
          CONTRACT.size(), CONTRACT.values().stream().mapToInt(List::size).sum(),
          List.copyOf(CONTRACT.keySet()));
      return new WorkbookReadResult(batch, validation);
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw invalid("QUESTION_IMPORT_XLSX_INVALID",
          "Cannot read XLSX safely: " + safeMessage(exception));
    }
  }

  private Map<String, List<DataRow>> readWorkbook(XSSFWorkbook workbook) {
    List<String> actualNames = new ArrayList<>();
    for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
      actualNames.add(workbook.getSheetName(index));
    }
    List<String> expectedNames = List.copyOf(CONTRACT.keySet());
    List<String> missing = expectedNames.stream().filter(name -> !actualNames.contains(name)).toList();
    if (!missing.isEmpty()) {
      throw invalid("QUESTION_IMPORT_MISSING_SHEET", "Missing required sheet(s): " + missing);
    }
    if (!actualNames.equals(expectedNames)) {
      throw invalid("QUESTION_IMPORT_UNKNOWN_FIELD",
          "Workbook sheet names/order must exactly equal " + expectedNames);
    }
    if (!SCHEMA_VERSION.equals(stringCell(workbook.getSheet("Questions"), 0, 0))) {
      throw invalid("QUESTION_IMPORT_SCHEMA_INVALID",
          "Questions!A1 must equal " + SCHEMA_VERSION);
    }
    if (!workbook.getExternalLinksTable().isEmpty()) {
      throw invalid("QUESTION_IMPORT_FILE_UNSAFE", "External workbook links are forbidden");
    }

    Map<String, List<DataRow>> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<ColumnSpec>> contract : CONTRACT.entrySet()) {
      Sheet sheet = workbook.getSheet(contract.getKey());
      assertHeader(sheet, contract.getValue());
      List<DataRow> dataRows = new ArrayList<>();
      for (int rowIndex = 2; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (row == null || rowEmpty(row)) {
          continue;
        }
        rejectTrailingData(sheet.getSheetName(), row, contract.getValue().size());
        Map<String, Object> values = new LinkedHashMap<>();
        for (int columnIndex = 0; columnIndex < contract.getValue().size(); columnIndex++) {
          ColumnSpec spec = contract.getValue().get(columnIndex);
          values.put(spec.name(), readCell(sheet.getSheetName(), row, columnIndex, spec));
        }
        dataRows.add(new DataRow(sheet.getSheetName(), rowIndex + 1, values));
      }
      result.put(contract.getKey(), List.copyOf(dataRows));
    }
    if (result.get("Questions").isEmpty() || result.get("Questions").size() > MAX_QUESTIONS) {
      throw invalid("QUESTION_IMPORT_CAPACITY_INVALID",
          "Questions must contain between 1 and " + MAX_QUESTIONS + " rows");
    }
    return result;
  }

  private void validateCrossSheet(Map<String, List<DataRow>> rows) {
    validateBusinessKeys(rows);
    Set<String> questions = rows.get("Questions").stream()
        .map(row -> row.text("externalKey")).collect(Collectors.toCollection(LinkedHashSet::new));
    for (Map.Entry<String, List<DataRow>> sheet : rows.entrySet()) {
      if ("Questions".equals(sheet.getKey())) {
        continue;
      }
      for (DataRow row : sheet.getValue()) {
        if (!questions.contains(row.text("externalKey"))) {
          throw rowError("QUESTION_IMPORT_ORPHAN_FOREIGN_KEY", row, "externalKey",
              "Child externalKey does not reference Questions");
        }
      }
    }

    Set<String> subKeys = rows.get("SubQuestions").stream()
        .map(row -> composite(row.text("externalKey"), row.integer("subQuestionNo")))
        .collect(Collectors.toSet());
    for (String sheet : COMPOSITE_CHILDREN) {
      for (DataRow row : rows.get(sheet)) {
        requireSubQuestion(subKeys, row);
      }
    }
    for (DataRow row : rows.get("KnowledgeBindings")) {
      if (row.integer("subQuestionNo") != null) {
        requireSubQuestion(subKeys, row);
      }
      BigDecimal weight = row.decimal("weight");
      if (weight.compareTo(BigDecimal.ZERO) <= 0 || weight.compareTo(BigDecimal.ONE) > 0) {
        throw rowError("QUESTION_IMPORT_FIELD_INVALID", row, "weight",
            "Knowledge weight must be greater than 0 and no greater than 1");
      }
    }

    validateSortOrders(rows);
    validateAnswers(rows);
    validateQuestionMatrix(rows);
    validateScope(rows.get("Questions"));
  }

  private void validateBusinessKeys(Map<String, List<DataRow>> rows) {
    for (Map.Entry<String, List<String>> key : BUSINESS_KEYS.entrySet()) {
      Set<String> seen = new HashSet<>();
      for (DataRow row : rows.get(key.getKey())) {
        String value = key.getValue().stream().map(field -> String.valueOf(row.value(field)))
            .collect(Collectors.joining("\u001f"));
        if (!seen.add(value)) {
          throw rowError("QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY", row,
              String.join(",", key.getValue()), "Duplicate workbook business key");
        }
      }
    }
  }

  private void validateSortOrders(Map<String, List<DataRow>> rows) {
    validateSort(rows.get("Stems"), row -> row.text("externalKey"));
    validateSort(rows.get("SubQuestions"), row -> row.text("externalKey"));
    for (String sheet : List.of("Options", "Answers", "Solutions", "ScoringRules", "ScoringPoints")) {
      validateSort(rows.get(sheet), row -> composite(row.text("externalKey"), row.integer("subQuestionNo")));
    }
    validateSort(rows.get("KnowledgeBindings"), row ->
        composite(row.text("externalKey"), row.integer("subQuestionNo")));
  }

  private void validateSort(List<DataRow> rows, Function<DataRow, String> parent) {
    Map<String, List<DataRow>> groups = rows.stream().collect(Collectors.groupingBy(parent,
        LinkedHashMap::new, Collectors.toList()));
    for (List<DataRow> group : groups.values()) {
      List<Integer> actual = group.stream().map(row -> row.integer("sortOrder")).sorted().toList();
      for (int index = 0; index < actual.size(); index++) {
        if (actual.get(index) != index + 1) {
          throw rowError("QUESTION_IMPORT_SORT_ORDER_INVALID", group.get(0), "sortOrder",
              "sortOrder must be unique and contiguous from 1 inside its parent");
        }
      }
    }
  }

  private void validateAnswers(Map<String, List<DataRow>> rows) {
    Map<String, Set<String>> options = new HashMap<>();
    for (DataRow row : rows.get("Options")) {
      options.computeIfAbsent(composite(row.text("externalKey"), row.integer("subQuestionNo")),
          ignored -> new HashSet<>()).add(row.text("optionKey"));
    }
    for (DataRow row : rows.get("Answers")) {
      String kind = row.text("answerKind");
      List<String> payloads = List.of("optionKeys", "booleanValue", "textValue", "numberValue", "structuredValue");
      long present = payloads.stream().filter(field -> row.value(field) != null).count();
      String expected = switch (kind) {
        case "OPTION_KEYS" -> "optionKeys";
        case "BOOLEAN" -> "booleanValue";
        case "TEXT" -> "textValue";
        case "NUMBER" -> "numberValue";
        case "JSON" -> "structuredValue";
        default -> null;
      };
      if (present != 1 || expected == null || row.value(expected) == null) {
        throw rowError("QUESTION_IMPORT_ANSWER_KIND_MISMATCH", row, kind,
            "answerKind must select exactly one non-null payload column");
      }
      if ("OPTION_KEYS".equals(kind)) {
        JsonNode keys = row.json("optionKeys");
        if (keys.isEmpty()) {
          throw rowError("QUESTION_IMPORT_OPTION_REFERENCE_INVALID", row, "optionKeys",
              "optionKeys cannot be empty");
        }
        Set<String> selected = new HashSet<>();
        Set<String> available = options.getOrDefault(
            composite(row.text("externalKey"), row.integer("subQuestionNo")), Set.of());
        for (JsonNode keyNode : keys) {
          String key = keyNode.isTextual() ? upper(keyNode.asText()) : null;
          if (key == null || !selected.add(key) || !available.contains(key)) {
            throw rowError("QUESTION_IMPORT_OPTION_REFERENCE_INVALID", row, "optionKeys",
                "Every answer option must be unique and belong to the same sub-question");
          }
        }
      }
    }
  }

  private void validateQuestionMatrix(Map<String, List<DataRow>> rows) {
    Map<String, List<DataRow>> stems = group(rows.get("Stems"), "externalKey");
    Map<String, List<DataRow>> subs = group(rows.get("SubQuestions"), "externalKey");
    Map<String, List<DataRow>> options = groupComposite(rows.get("Options"));
    Map<String, List<DataRow>> answers = groupComposite(rows.get("Answers"));
    Map<String, List<DataRow>> rules = groupComposite(rows.get("ScoringRules"));
    Map<String, List<DataRow>> points = groupComposite(rows.get("ScoringPoints"));

    for (DataRow question : rows.get("Questions")) {
      String externalKey = question.text("externalKey");
      String questionType = question.text("questionType");
      List<DataRow> questionStems = stems.getOrDefault(externalKey, List.of());
      List<DataRow> questionSubs = subs.getOrDefault(externalKey, List.of());
      if (questionStems.isEmpty() || questionSubs.isEmpty()) {
        throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", question, "questionType",
            "Every question requires stems and sub-questions");
      }
      if (questionStems.stream().anyMatch(stem -> stem.value("resourceId") != null)) {
        throw rowError("QUESTION_IMPORT_RESOURCE_MAPPING_REQUIRED", question, "resourceId",
            "resourceId cannot be accepted until the resource service mapping is available");
      }
      if ("CASE".equals(questionType)) {
        if (questionSubs.size() < 2 || questionSubs.size() > 4
            || questionStems.stream().noneMatch(stem -> "CASE_MATERIAL".equals(stem.text("stemType")))) {
          throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", question, "questionType",
              "CASE requires CASE_MATERIAL and two to four sub-questions");
        }
      } else if ("SINGLE_CHOICE".equals(questionType)) {
        if (questionSubs.size() > 10) {
          throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", question, "questionType",
              "SINGLE_CHOICE requires one to ten sub-questions");
        }
      } else if (questionSubs.size() != 1) {
        throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", question, "questionType",
            "MULTI_CHOICE, JUDGE and ESSAY require exactly one sub-question");
      }
      if ("ESSAY".equals(questionType)
          && questionStems.stream().noneMatch(stem -> "ESSAY_REQUIREMENT".equals(stem.text("stemType")))) {
        throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", question, "questionType",
            "ESSAY requires an ESSAY_REQUIREMENT stem");
      }

      for (DataRow sub : questionSubs) {
        String subKey = composite(externalKey, sub.integer("subQuestionNo"));
        String subType = sub.text("subQuestionType");
        if (!typeMatches(questionType, subType)) {
          throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", sub, "subQuestionType",
              "questionType and subQuestionType do not match");
        }
        String expectedFormat = expectedAnswerFormat(subType);
        if (!expectedFormat.equals(sub.text("answerFormat"))) {
          throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", sub, "answerFormat",
              "answerFormat does not match subQuestionType");
        }
        List<DataRow> subOptions = options.getOrDefault(subKey, List.of());
        List<DataRow> subAnswers = answers.getOrDefault(subKey, List.of());
        List<DataRow> subRules = rules.getOrDefault(subKey, List.of());
        if (subAnswers.size() != 1 || !expectedFormat.equals(subAnswers.get(0).text("answerKind"))) {
          throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", sub, "answerFormat",
              "Exactly one matching answer row is required");
        }
        if (Set.of("SINGLE_CHOICE", "MULTI_CHOICE").contains(subType)) {
          if (subOptions.size() < 2) {
            throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", sub, "subQuestionType",
                "Choice questions require at least two options");
          }
          int selected = subAnswers.get(0).json("optionKeys").size();
          if (("SINGLE_CHOICE".equals(subType) && selected != 1)
              || ("MULTI_CHOICE".equals(subType) && selected < 2)) {
            throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", subAnswers.get(0),
                "optionKeys", "Choice answer cardinality is invalid");
          }
        } else if (!subOptions.isEmpty()) {
          throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", sub, "subQuestionType",
              "Only choice questions may contain options");
        }

        boolean objective = OBJECTIVE.contains(subType);
        String grading = sub.text("gradingMode");
        if ((objective && !"AUTO".equals(grading))
            || (!objective && !Set.of("MANUAL", "AI_ASSISTED").contains(grading))
            || subRules.size() != 1 || !grading.equals(subRules.get(0).text("ruleType"))) {
          throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", sub, "gradingMode",
              "gradingMode and scoring rule do not match the sub-question type");
        }
        String ruleMode = upper(subRules.get(0).json("ruleJson").path("mode").asText(null));
        if ((objective && !"EXACT".equals(ruleMode)) || (!objective && !grading.equals(ruleMode))) {
          throw rowError("QUESTION_IMPORT_TYPE_COMBINATION_INVALID", subRules.get(0), "ruleJson",
              "ruleJson.mode does not match gradingMode");
        }
        List<DataRow> subPoints = points.getOrDefault(subKey, List.of());
        if (objective && !subPoints.isEmpty()) {
          throw rowError("QUESTION_IMPORT_SCORE_CLOSURE_INVALID", sub, "score",
              "Objective sub-questions cannot contain scoring points");
        }
        if (!objective) {
          BigDecimal total = subPoints.stream().map(point -> point.decimal("score"))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
          if (subPoints.isEmpty() || total.compareTo(sub.decimal("score")) != 0) {
            throw rowError("QUESTION_IMPORT_SCORE_CLOSURE_INVALID", sub, "score",
                "Subjective scoring-point values must close exactly to the sub-question score");
          }
        }
      }
    }
  }

  private void validateScope(List<DataRow> questions) {
    Set<String> scopes = questions.stream()
        .map(row -> composite(row.text("qualificationCode"), row.text("subjectCode")))
        .collect(Collectors.toSet());
    if (scopes.size() != 1) {
      throw rowError("QUESTION_IMPORT_SCOPE_MISMATCH", questions.get(0), "qualificationCode,subjectCode",
          "Every Questions row in one workbook must share one qualification and subject scope");
    }
  }

  private List<CanonicalItem> toCanonicalItems(Path path, String workbookSha,
      Map<String, List<DataRow>> rows) {
    Map<String, List<DataRow>> stems = group(rows.get("Stems"), "externalKey");
    Map<String, List<DataRow>> subs = group(rows.get("SubQuestions"), "externalKey");
    Map<String, List<DataRow>> options = groupComposite(rows.get("Options"));
    Map<String, List<DataRow>> answers = groupComposite(rows.get("Answers"));
    Map<String, List<DataRow>> solutions = groupComposite(rows.get("Solutions"));
    Map<String, List<DataRow>> rules = groupComposite(rows.get("ScoringRules"));
    Map<String, List<DataRow>> points = groupComposite(rows.get("ScoringPoints"));
    Map<String, List<DataRow>> bindings = group(rows.get("KnowledgeBindings"), "externalKey");
    Set<String> hashes = new HashSet<>();
    List<CanonicalItem> result = new ArrayList<>();

    for (DataRow question : rows.get("Questions")) {
      String key = question.text("externalKey");
      List<QuestionCreateRequest.StemRequest> stemRequests = stems.get(key).stream()
          .sorted(java.util.Comparator.comparing(row -> row.integer("sortOrder")))
          .map(row -> new QuestionCreateRequest.StemRequest(row.text("stemType"), row.text("content"),
              null, row.integer("sortOrder"), row.text("stemKey"), row.longValue("resourceId")))
          .toList();
      List<PendingKnowledgeBinding> pending = bindings.getOrDefault(key, List.of()).stream()
          .sorted(java.util.Comparator.comparing(row -> row.integer("sortOrder")))
          .map(row -> new PendingKnowledgeBinding(row.integer("subQuestionNo"),
              row.text("knowledgeType"), row.text("knowledgeCode"), row.decimal("weight"),
              row.integer("sortOrder")))
          .toList();
      Map<Integer, List<ScoringPoint>> scoringPointMap = new LinkedHashMap<>();
      List<QuestionCreateRequest.SubQuestionRequest> subRequests = new ArrayList<>();
      for (DataRow sub : subs.get(key).stream()
          .sorted(java.util.Comparator.comparing(row -> row.integer("sortOrder"))).toList()) {
        int number = sub.integer("subQuestionNo");
        String composite = composite(key, number);
        List<QuestionCreateRequest.OptionRequest> optionRequests = options.getOrDefault(composite, List.of())
            .stream().sorted(java.util.Comparator.comparing(row -> row.integer("sortOrder")))
            .map(row -> new QuestionCreateRequest.OptionRequest(row.text("optionKey"), row.text("content"),
                row.integer("sortOrder"))).toList();
        List<QuestionCreateRequest.AnswerKeyRequest> answerRequests = answers.get(composite).stream()
            .sorted(java.util.Comparator.comparing(row -> row.integer("sortOrder")))
            .map(this::answerRequest).toList();
        List<QuestionCreateRequest.SolutionRequest> solutionRequests = solutions
            .getOrDefault(composite, List.of()).stream()
            .sorted(java.util.Comparator.comparing(row -> row.integer("sortOrder")))
            .map(row -> new QuestionCreateRequest.SolutionRequest(row.text("solutionType"),
                row.text("content"), row.integer("sortOrder"))).toList();
        List<QuestionCreateRequest.ScoringRuleRequest> ruleRequests = rules.get(composite).stream()
            .sorted(java.util.Comparator.comparing(row -> row.integer("sortOrder")))
            .map(row -> new QuestionCreateRequest.ScoringRuleRequest(row.text("ruleType"),
                json(row.json("ruleJson")), row.integer("sortOrder"))).toList();
        List<QuestionCreateRequest.ScoringPointRequest> pointRequests = points
            .getOrDefault(composite, List.of()).stream()
            .sorted(java.util.Comparator.comparing(row -> row.integer("sortOrder")))
            .map(row -> new QuestionCreateRequest.ScoringPointRequest(row.text("pointCode"),
                row.text("pointText"), row.decimal("score"), row.integer("sortOrder"))).toList();
        scoringPointMap.put(number, pointRequests.stream().map(point -> new ScoringPoint(
            point.pointCode(), point.pointText(), point.score(), point.sortOrder())).toList());
        subRequests.add(new QuestionCreateRequest.SubQuestionRequest(number, sub.text("subQuestionType"),
            sub.text("content"), sub.decimal("score"), sub.text("answerFormat"),
            sub.text("gradingMode"), sub.integer("sortOrder"), null, optionRequests, answerRequests,
            solutionRequests, ruleRequests, pointRequests, List.of()));
      }
      QuestionCreateRequest payload = new QuestionCreateRequest(1L, 1L,
          question.text("questionType"), question.text("title"), question.integer("yearNo"),
          question.text("difficulty"), "XLSX_IMPORT", question.text("sourceRef"), stemRequests,
          List.copyOf(subRequests), List.of());
      VersionContent content = new VersionContent(payload, scoringPointMap, pending);
      Map<String, Object> canonical = new LinkedHashMap<>();
      canonical.put("schemaVersion", SCHEMA_VERSION);
      canonical.put("qualificationCode", question.text("qualificationCode"));
      canonical.put("subjectCode", question.text("subjectCode"));
      canonical.put("question", payload);
      canonical.put("pendingKnowledgeBindings", pending);
      String canonicalJson = jsonCanonical(canonical);
      String contentHash = sha256(canonicalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (!hashes.add(contentHash)) {
        throw rowError("QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY", question, "externalKey",
            "Two workbook questions normalize to the same content hash");
      }
      VersionSource source = new VersionSource(key, "XLSX_IMPORT", question.text("sourceRef"),
          path.toString(), workbookSha, "Questions/" + question.rowNumber() + "/externalKey",
          null, paperKind(question.text("sourceRef")), question.text("syllabusBasis"),
          "UNVERIFIED", "INTERNAL_ONLY", contentHash, contentHash);
      result.add(new CanonicalItem(question.rowNumber(), key, contentHash, true, null,
          canonicalJson, question.text("qualificationCode"), question.text("subjectCode"),
          content, source));
    }
    return List.copyOf(result);
  }

  private QuestionCreateRequest.AnswerKeyRequest answerRequest(DataRow row) {
    String kind = row.text("answerKind");
    return switch (kind) {
      case "OPTION_KEYS" -> {
        List<String> keys = new ArrayList<>();
        row.json("optionKeys").forEach(node -> keys.add(upper(node.asText())));
        yield new QuestionCreateRequest.AnswerKeyRequest(kind, String.join(",", keys), json(keys));
      }
      case "BOOLEAN" -> new QuestionCreateRequest.AnswerKeyRequest(kind,
          row.bool("booleanValue").toString(), row.bool("booleanValue").toString());
      case "TEXT" -> new QuestionCreateRequest.AnswerKeyRequest(kind, row.text("textValue"), null);
      case "NUMBER" -> new QuestionCreateRequest.AnswerKeyRequest(kind,
          row.decimal("numberValue").toPlainString(), row.decimal("numberValue").toPlainString());
      case "JSON" -> new QuestionCreateRequest.AnswerKeyRequest(kind, null, json(row.json("structuredValue")));
      default -> throw new IllegalStateException("Unsupported answer kind");
    };
  }

  private Object readCell(String sheetName, Row row, int columnIndex, ColumnSpec spec) {
    Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    if (cell == null || cell.getCellType() == CellType.BLANK) {
      if (spec.nullable()) {
        return null;
      }
      throw cellError("QUESTION_IMPORT_FIELD_INVALID", sheetName, row.getRowNum() + 1,
          spec.name(), "Required cell is blank");
    }
    if (cell.getCellType() == CellType.FORMULA) {
      throw cellError("QUESTION_IMPORT_FILE_UNSAFE", sheetName, row.getRowNum() + 1,
          spec.name(), "Formulas are forbidden");
    }
    return switch (spec.kind()) {
      case STRING -> requireString(cell, sheetName, row, spec, null, false);
      case ASCII_UPPER -> requireString(cell, sheetName, row, spec, Set.of(), true);
      case ENUM -> requireString(cell, sheetName, row, spec, spec.allowed(), false);
      case INTEGER -> integerCell(cell, sheetName, row, spec, false);
      case LONG -> integerCell(cell, sheetName, row, spec, true);
      case DECIMAL -> decimalCell(cell, sheetName, row, spec);
      case BOOLEAN -> booleanCell(cell, sheetName, row, spec);
      case JSON_ARRAY -> jsonCell(cell, sheetName, row, spec, true);
      case JSON_OBJECT -> jsonCell(cell, sheetName, row, spec, false);
    };
  }

  private String requireString(Cell cell, String sheet, Row row, ColumnSpec spec,
      Set<String> allowed, boolean asciiUpper) {
    if (cell.getCellType() != CellType.STRING) {
      throw typeError(sheet, row, spec, "text");
    }
    String raw = cell.getStringCellValue();
    if (raw == null || raw.isEmpty()) {
      if (spec.nullable()) {
        return null;
      }
      throw typeError(sheet, row, spec, "non-empty text");
    }
    if (raw.trim().isEmpty()) {
      throw cellError("QUESTION_IMPORT_FIELD_INVALID", sheet, row.getRowNum() + 1, spec.name(),
          "Whitespace-only values are forbidden");
    }
    String value = raw.trim();
    if (asciiUpper && !value.matches("[A-Z]")) {
      throw cellError("QUESTION_IMPORT_FIELD_INVALID", sheet, row.getRowNum() + 1, spec.name(),
          "Value must be one ASCII uppercase letter");
    }
    if (allowed != null && !allowed.isEmpty() && !allowed.contains(value)) {
      throw cellError("QUESTION_IMPORT_FIELD_INVALID", sheet, row.getRowNum() + 1, spec.name(),
          "Unsupported enum value");
    }
    return value;
  }

  private Object integerCell(Cell cell, String sheet, Row row, ColumnSpec spec, boolean asLong) {
    BigDecimal decimal = numeric(cell, sheet, row, spec);
    try {
      if (asLong) {
        return decimal.longValueExact();
      }
      return decimal.intValueExact();
    } catch (ArithmeticException exception) {
      throw typeError(sheet, row, spec, "integer");
    }
  }

  private BigDecimal decimalCell(Cell cell, String sheet, Row row, ColumnSpec spec) {
    BigDecimal value = numeric(cell, sheet, row, spec);
    if (value.scale() > spec.scale()) {
      throw cellError("QUESTION_IMPORT_FIELD_INVALID", sheet, row.getRowNum() + 1, spec.name(),
          "Decimal scale exceeds " + spec.scale());
    }
    return value;
  }

  private BigDecimal numeric(Cell cell, String sheet, Row row, ColumnSpec spec) {
    if (cell.getCellType() != CellType.NUMERIC || DateUtil.isCellDateFormatted(cell)) {
      throw typeError(sheet, row, spec, "number");
    }
    return new BigDecimal(NumberToTextConverter.toText(cell.getNumericCellValue())).stripTrailingZeros();
  }

  private Boolean booleanCell(Cell cell, String sheet, Row row, ColumnSpec spec) {
    if (cell.getCellType() != CellType.BOOLEAN) {
      throw typeError(sheet, row, spec, "boolean");
    }
    return cell.getBooleanCellValue();
  }

  private JsonNode jsonCell(Cell cell, String sheet, Row row, ColumnSpec spec, boolean array) {
    String text = requireString(cell, sheet, row, spec, null, false);
    try {
      JsonNode node = objectMapper.readTree(text);
      if ((array && !node.isArray()) || (!array && !node.isObject())) {
        throw new IllegalArgumentException();
      }
      return node;
    } catch (Exception exception) {
      throw cellError("QUESTION_IMPORT_FIELD_INVALID", sheet, row.getRowNum() + 1, spec.name(),
          array ? "Cell must contain a strict JSON array" : "Cell must contain a strict JSON object");
    }
  }

  private void assertHeader(Sheet sheet, List<ColumnSpec> columns) {
    Row header = sheet.getRow(1);
    if (header == null) {
      throw invalid("QUESTION_IMPORT_UNKNOWN_FIELD", "Missing header row in " + sheet.getSheetName());
    }
    rejectTrailingData(sheet.getSheetName(), header, columns.size());
    for (int index = 0; index < columns.size(); index++) {
      Cell cell = header.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
      String value = cell != null && cell.getCellType() == CellType.STRING
          ? cell.getStringCellValue() : null;
      if (!columns.get(index).name().equals(value)) {
        throw cellError("QUESTION_IMPORT_UNKNOWN_FIELD", sheet.getSheetName(), 2,
            columns.get(index).name(), "Header names/order must exactly match the frozen contract");
      }
    }
  }

  private void rejectTrailingData(String sheet, Row row, int columnCount) {
    for (int index = columnCount; index < Math.max(row.getLastCellNum(), columnCount); index++) {
      Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
      if (cell != null && cell.getCellType() != CellType.BLANK) {
        throw cellError("QUESTION_IMPORT_UNKNOWN_FIELD", sheet, row.getRowNum() + 1,
            "column-" + (index + 1), "Unexpected column contains data");
      }
    }
  }

  private boolean rowEmpty(Row row) {
    for (Cell cell : row) {
      if (cell.getCellType() == CellType.FORMULA) {
        return false;
      }
      if (cell.getCellType() != CellType.BLANK) {
        if (cell.getCellType() != CellType.STRING || !cell.getStringCellValue().isEmpty()) {
          return false;
        }
      }
    }
    return true;
  }

  private String stringCell(Sheet sheet, int rowIndex, int columnIndex) {
    Row row = sheet == null ? null : sheet.getRow(rowIndex);
    Cell cell = row == null ? null : row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    return cell != null && cell.getCellType() == CellType.STRING ? cell.getStringCellValue() : null;
  }

  private Path resolve(String inputPath) {
    if (allowedRoot == null || inputPath == null || inputPath.isBlank()) {
      throw invalid("QUESTION_IMPORT_ROOT_NOT_CONFIGURED",
          "A non-empty allowed import root and XLSX path are required");
    }
    try {
      Path root = allowedRoot.toRealPath();
      Path candidate = Path.of(inputPath);
      if (!candidate.isAbsolute()) {
        candidate = root.resolve(candidate);
      }
      Path real = candidate.normalize().toRealPath();
      long size = Files.size(real);
      if (!real.startsWith(root) || !Files.isRegularFile(real)
          || !real.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
        throw invalid("QUESTION_IMPORT_PATH_FORBIDDEN", "XLSX must resolve inside the configured root");
      }
      if (size <= 0 || size > MAX_FILE_BYTES) {
        throw invalid("QUESTION_IMPORT_FILE_SIZE_INVALID", "XLSX must be non-empty and at most 10 MiB");
      }
      return real;
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException | java.nio.file.InvalidPathException exception) {
      throw invalid("QUESTION_IMPORT_PATH_FORBIDDEN", "XLSX path is invalid or forbidden");
    }
  }

  private void inspectZip(Path path) {
    try (ZipFile zip = new ZipFile(path.toFile())) {
      long expanded = 0;
      int entries = 0;
      Set<String> names = new HashSet<>();
      var enumeration = zip.entries();
      while (enumeration.hasMoreElements()) {
        ZipEntry entry = enumeration.nextElement();
        entries++;
        String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!names.add(name) || name.startsWith("/") || name.contains("../")) {
          throw invalid("QUESTION_IMPORT_FILE_UNSAFE", "Duplicate or unsafe ZIP entry is forbidden");
        }
        if (entries > MAX_ZIP_ENTRIES || entry.getSize() < 0 || entry.getCompressedSize() < 0) {
          throw invalid("QUESTION_IMPORT_FILE_UNSAFE", "XLSX ZIP metadata is unsafe");
        }
        expanded = Math.addExact(expanded, entry.getSize());
        if (expanded > MAX_EXPANDED_BYTES) {
          throw invalid("QUESTION_IMPORT_FILE_UNSAFE", "XLSX expanded size exceeds 50 MiB");
        }
        if (entry.getSize() > 0 && entry.getCompressedSize() == 0
            || entry.getCompressedSize() > 0
            && (double) entry.getSize() / entry.getCompressedSize() > MAX_COMPRESSION_RATIO) {
          throw invalid("QUESTION_IMPORT_FILE_UNSAFE", "XLSX ZIP compression ratio is unsafe");
        }
        if (name.contains("vbaproject") || name.endsWith(".bin")
            || name.startsWith("xl/externallinks/") || name.startsWith("xl/embeddings/")
            || name.startsWith("customui/")) {
          throw invalid("QUESTION_IMPORT_FILE_UNSAFE",
              "Macros, binaries, external links and embedded objects are forbidden");
        }
      }
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException | ArithmeticException exception) {
      throw invalid("QUESTION_IMPORT_FILE_UNSAFE", "XLSX is not a safe ZIP package");
    }
  }

  private void requireSubQuestion(Set<String> subKeys, DataRow row) {
    if (!subKeys.contains(composite(row.text("externalKey"), row.integer("subQuestionNo")))) {
      throw rowError("QUESTION_IMPORT_ORPHAN_SUBQUESTION", row, "subQuestionNo",
          "Composite child key does not reference SubQuestions");
    }
  }

  private Map<String, List<DataRow>> group(List<DataRow> rows, String field) {
    return rows.stream().collect(Collectors.groupingBy(row -> row.text(field), LinkedHashMap::new,
        Collectors.toList()));
  }

  private Map<String, List<DataRow>> groupComposite(List<DataRow> rows) {
    return rows.stream().collect(Collectors.groupingBy(
        row -> composite(row.text("externalKey"), row.integer("subQuestionNo")),
        LinkedHashMap::new, Collectors.toList()));
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

  private String expectedAnswerFormat(String subType) {
    return switch (subType) {
      case "SINGLE_CHOICE", "MULTI_CHOICE" -> "OPTION_KEYS";
      case "JUDGE" -> "BOOLEAN";
      case "CALCULATION" -> "NUMBER";
      case "SHORT_ANSWER", "ESSAY_WRITING" -> "TEXT";
      case "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION" -> "JSON";
      default -> "";
    };
  }

  private String paperKind(String sourceRef) {
    String normalized = upper(sourceRef);
    if (normalized == null) {
      return null;
    }
    if (normalized.contains("MOCK_PAPER")) {
      return "MOCK_PAPER";
    }
    return normalized.contains("REAL_PAPER") ? "REAL_PAPER" : null;
  }

  private String composite(Object left, Object right) {
    return String.valueOf(left) + "\u001f" + String.valueOf(right);
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize workbook value", exception);
    }
  }

  private String jsonCanonical(Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot canonicalize workbook question", exception);
    }
  }

  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private String upper(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private String safeMessage(Throwable throwable) {
    return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
  }

  private BusinessException typeError(String sheet, Row row, ColumnSpec spec, String expected) {
    return cellError("QUESTION_IMPORT_FIELD_INVALID", sheet, row.getRowNum() + 1, spec.name(),
        "Cell must be " + expected);
  }

  private BusinessException rowError(String code, DataRow row, String field, String message) {
    return cellError(code, row.sheet(), row.rowNumber(), field, message);
  }

  private BusinessException cellError(String code, String sheet, int row, String field,
      String message) {
    return invalid(code, message + " [" + sheet + "/" + row + "/" + field + "]");
  }

  private BusinessException invalid(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }

  private static LinkedHashMap<String, List<ColumnSpec>> contract() {
    LinkedHashMap<String, List<ColumnSpec>> result = new LinkedHashMap<>();
    result.put("Questions", List.of(
        string("externalKey"), string("qualificationCode"), string("subjectCode"),
        enumeration("questionType", "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CASE", "ESSAY", "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION"),
        nullableString("title"), nullableInteger("yearNo"),
        enumeration("difficulty", "EASY", "MEDIUM", "HARD"),
        enumeration("sourceType", "ORIGINAL", "AUTHORIZED_IMPORT"), nullableString("sourceRef"),
        string("syllabusBasis"), enumeration("copyrightStatus", "ORIGINAL_DECLARED", "AUTHORIZED"),
        nullableString("usageRestriction")));
    result.put("Stems", List.of(string("externalKey"), string("stemKey"),
        enumeration("stemType", "MAIN_STEM", "CASE_MATERIAL", "ATTACHMENT", "ESSAY_REQUIREMENT"),
        nullableString("content"), nullableLong("resourceId"), integer("sortOrder")));
    result.put("SubQuestions", List.of(string("externalKey"), integer("subQuestionNo"),
        enumeration("subQuestionType", "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CALCULATION",
            "SHORT_ANSWER", "ESSAY_WRITING", "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION"), string("content"), decimal("score", 2),
        enumeration("answerFormat", "OPTION_KEYS", "BOOLEAN", "TEXT", "NUMBER", "JSON"),
        enumeration("gradingMode", "AUTO", "MANUAL", "AI_ASSISTED"), integer("sortOrder")));
    result.put("Options", List.of(string("externalKey"), integer("subQuestionNo"),
        new ColumnSpec("optionKey", Kind.ASCII_UPPER, false, 0, Set.of()), string("content"),
        integer("sortOrder")));
    result.put("Answers", List.of(string("externalKey"), integer("subQuestionNo"),
        enumeration("answerKind", "OPTION_KEYS", "BOOLEAN", "TEXT", "NUMBER", "JSON"),
        nullableJson("optionKeys", Kind.JSON_ARRAY), nullableBoolean("booleanValue"),
        nullableString("textValue"), nullableDecimal("numberValue", 2), integer("sortOrder")));
    result.put("Solutions", List.of(string("externalKey"), integer("subQuestionNo"),
        enumeration("solutionType", "REFERENCE_ANSWER", "ANALYSIS"), string("content"),
        integer("sortOrder")));
    result.put("ScoringRules", List.of(string("externalKey"), integer("subQuestionNo"),
        enumeration("ruleType", "AUTO", "MANUAL", "AI_ASSISTED"),
        new ColumnSpec("ruleJson", Kind.JSON_OBJECT, false, 0, Set.of()), integer("sortOrder")));
    result.put("ScoringPoints", List.of(string("externalKey"), integer("subQuestionNo"),
        string("pointCode"), string("pointText"), decimal("score", 2), integer("sortOrder")));
    result.put("KnowledgeBindings", List.of(string("externalKey"), nullableInteger("subQuestionNo"),
        enumeration("knowledgeType", "SYLLABUS_NODE", "CHAPTER_NODE", "TAG"),
        string("knowledgeCode"), decimal("weight", 3), integer("sortOrder")));
    return result;
  }

  private static Map<String, List<String>> businessKeys() {
    Map<String, List<String>> result = new LinkedHashMap<>();
    result.put("Questions", List.of("externalKey"));
    result.put("Stems", List.of("externalKey", "stemKey"));
    result.put("SubQuestions", List.of("externalKey", "subQuestionNo"));
    result.put("Options", List.of("externalKey", "subQuestionNo", "optionKey"));
    result.put("Answers", List.of("externalKey", "subQuestionNo", "sortOrder"));
    result.put("Solutions", List.of("externalKey", "subQuestionNo", "sortOrder"));
    result.put("ScoringRules", List.of("externalKey", "subQuestionNo", "sortOrder"));
    result.put("ScoringPoints", List.of("externalKey", "subQuestionNo", "pointCode"));
    result.put("KnowledgeBindings",
        List.of("externalKey", "subQuestionNo", "knowledgeType", "knowledgeCode"));
    return Map.copyOf(result);
  }

  private static ColumnSpec string(String name) {
    return new ColumnSpec(name, Kind.STRING, false, 0, Set.of());
  }

  private static ColumnSpec nullableString(String name) {
    return new ColumnSpec(name, Kind.STRING, true, 0, Set.of());
  }

  private static ColumnSpec integer(String name) {
    return new ColumnSpec(name, Kind.INTEGER, false, 0, Set.of());
  }

  private static ColumnSpec nullableInteger(String name) {
    return new ColumnSpec(name, Kind.INTEGER, true, 0, Set.of());
  }

  private static ColumnSpec nullableLong(String name) {
    return new ColumnSpec(name, Kind.LONG, true, 0, Set.of());
  }

  private static ColumnSpec decimal(String name, int scale) {
    return new ColumnSpec(name, Kind.DECIMAL, false, scale, Set.of());
  }

  private static ColumnSpec nullableDecimal(String name, int scale) {
    return new ColumnSpec(name, Kind.DECIMAL, true, scale, Set.of());
  }

  private static ColumnSpec nullableBoolean(String name) {
    return new ColumnSpec(name, Kind.BOOLEAN, true, 0, Set.of());
  }

  private static ColumnSpec nullableJson(String name, Kind kind) {
    return new ColumnSpec(name, kind, true, 0, Set.of());
  }

  private static ColumnSpec enumeration(String name, String... values) {
    return new ColumnSpec(name, Kind.ENUM, false, 0, Set.copyOf(Arrays.asList(values)));
  }

  public record WorkbookReadResult(CanonicalBatch batch, QuestionWorkbookValidationDto validation) {
  }

  private record ColumnSpec(String name, Kind kind, boolean nullable, int scale,
                            Set<String> allowed) {
  }

  private enum Kind {
    STRING, ASCII_UPPER, ENUM, INTEGER, LONG, DECIMAL, BOOLEAN, JSON_ARRAY, JSON_OBJECT
  }

  private record DataRow(String sheet, int rowNumber, Map<String, Object> values) {
    Object value(String field) {
      return values.get(field);
    }

    String text(String field) {
      return (String) values.get(field);
    }

    Integer integer(String field) {
      return (Integer) values.get(field);
    }

    Long longValue(String field) {
      return (Long) values.get(field);
    }

    BigDecimal decimal(String field) {
      return (BigDecimal) values.get(field);
    }

    Boolean bool(String field) {
      return (Boolean) values.get(field);
    }

    JsonNode json(String field) {
      return (JsonNode) values.get(field);
    }
  }
}
