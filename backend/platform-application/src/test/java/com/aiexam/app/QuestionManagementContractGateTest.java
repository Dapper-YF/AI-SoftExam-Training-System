package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class QuestionManagementContractGateTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> QUESTION_TYPES = Set.of(
      "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CASE", "ESSAY");
  private static final Set<String> VERSION_STATES = Set.of(
      "DRAFT", "IN_REVIEW", "PUBLISHED", "RETIRED");
  private static final Set<String> PERMISSIONS = Set.of(
      "AUTHOR", "REVIEWER", "PUBLISHER", "QUESTION_ADMIN");
  private static final Set<String> EXCEL_SHEETS = Set.of(
      "Questions", "Stems", "SubQuestions", "Options", "Answers",
      "Solutions", "ScoringRules", "ScoringPoints", "KnowledgeBindings");
  private static final Map<String, List<String>> EXCEL_COLUMNS = Map.ofEntries(
      Map.entry("Questions", List.of("externalKey", "qualificationCode", "subjectCode",
          "questionType", "title", "yearNo", "difficulty", "sourceType", "sourceRef",
          "syllabusBasis", "copyrightStatus", "usageRestriction")),
      Map.entry("Stems", List.of("externalKey", "stemKey", "stemType", "content",
          "resourceId", "sortOrder")),
      Map.entry("SubQuestions", List.of("externalKey", "subQuestionNo", "subQuestionType",
          "content", "score", "answerFormat", "gradingMode", "sortOrder")),
      Map.entry("Options", List.of("externalKey", "subQuestionNo", "optionKey", "content",
          "sortOrder")),
      Map.entry("Answers", List.of("externalKey", "subQuestionNo", "answerKind", "optionKeys",
          "booleanValue", "textValue", "numberValue", "sortOrder")),
      Map.entry("Solutions", List.of("externalKey", "subQuestionNo", "solutionType", "content",
          "sortOrder")),
      Map.entry("ScoringRules", List.of("externalKey", "subQuestionNo", "ruleType", "ruleJson",
          "sortOrder")),
      Map.entry("ScoringPoints", List.of("externalKey", "subQuestionNo", "pointCode", "pointText",
          "score", "sortOrder")),
      Map.entry("KnowledgeBindings", List.of("externalKey", "subQuestionNo", "knowledgeType",
          "knowledgeCode", "weight", "sortOrder")));
  private static final Map<String, List<String>> EXCEL_BUSINESS_KEYS = Map.ofEntries(
      Map.entry("Questions", List.of("externalKey")),
      Map.entry("Stems", List.of("externalKey", "stemKey")),
      Map.entry("SubQuestions", List.of("externalKey", "subQuestionNo")),
      Map.entry("Options", List.of("externalKey", "subQuestionNo", "optionKey")),
      Map.entry("Answers", List.of("externalKey", "subQuestionNo", "sortOrder")),
      Map.entry("Solutions", List.of("externalKey", "subQuestionNo", "sortOrder")),
      Map.entry("ScoringRules", List.of("externalKey", "subQuestionNo", "sortOrder")),
      Map.entry("ScoringPoints", List.of("externalKey", "subQuestionNo", "pointCode")),
      Map.entry("KnowledgeBindings", List.of(
          "externalKey", "subQuestionNo", "knowledgeType", "knowledgeCode")));
  private static final Map<String, List<String>> EXCEL_FOREIGN_KEYS = Map.ofEntries(
      Map.entry("Questions", List.of()),
      Map.entry("Stems", List.of("externalKey->Questions.externalKey")),
      Map.entry("SubQuestions", List.of("externalKey->Questions.externalKey")),
      Map.entry("Options", List.of(
          "externalKey+subQuestionNo->SubQuestions.externalKey+subQuestionNo")),
      Map.entry("Answers", List.of(
          "externalKey+subQuestionNo->SubQuestions.externalKey+subQuestionNo")),
      Map.entry("Solutions", List.of(
          "externalKey+subQuestionNo->SubQuestions.externalKey+subQuestionNo")),
      Map.entry("ScoringRules", List.of(
          "externalKey+subQuestionNo->SubQuestions.externalKey+subQuestionNo")),
      Map.entry("ScoringPoints", List.of(
          "externalKey+subQuestionNo->SubQuestions.externalKey+subQuestionNo")),
      Map.entry("KnowledgeBindings", List.of("externalKey->Questions.externalKey",
          "externalKey+subQuestionNo->SubQuestions.externalKey+subQuestionNo when subQuestionNo is non-null")));
  private static final Set<String> EXCEL_CONSTRAINT_IDS = Set.of(
      "CHILD_QUESTION_FK", "COMPOUND_SUBQUESTION_FK", "BUSINESS_KEY_UNIQUE",
      "SORT_ORDER_CONTIGUOUS", "OPTION_KEY_UNIQUE", "ANSWER_PAYLOAD_DISCRIMINATOR",
      "ANSWER_OPTION_REFERENCE", "QUESTION_SUBQUESTION_TYPE_AND_SCORING_MODE",
      "CASE_STRUCTURE_AND_SCORE_CLOSURE", "SCOPE_UNIFORM");

  private static final Path FREEZE =
      docs("架构设计/p0-question-admin-import-case-practice-contract-freeze-2026-07-14.md");
  private static final List<Path> CONTRACTS = List.of(
      docs("需求分析/requirements-analysis-2026-07-08.md"),
      docs("架构设计/architecture-database-blueprint-2026-07-09.md"),
      docs("架构设计/frontend-backend-concurrency-baseline-2026-07-09.md"),
      docs("数据库设计/p0-logical-data-model-ddl-2026-07-09.md"),
      docs("接口设计/p0-api-acceptance-contract-2026-07-09.md"),
      FREEZE);

  @Test
  void allAuthorityDocumentsFreezeTheSameEnumsAndPublishOrder() throws IOException {
    for (Path contract : CONTRACTS) {
      assertThat(contract).as("authority document").exists();
      String content = read(contract);
      assertThat(content).as(contract.toString())
          .contains("QUESTION_TYPES = SINGLE_CHOICE,MULTI_CHOICE,JUDGE,CASE,ESSAY")
          .contains("QUESTION_VERSION_STATES = DRAFT,IN_REVIEW,PUBLISHED,RETIRED")
          .contains("QUESTION_PERMISSIONS = AUTHOR,REVIEWER,PUBLISHER,QUESTION_ADMIN")
          .contains("QUESTION_PUBLISH_WRITE_ORDER = RETIRE_CURRENT_PUBLISHED,PUBLISH_NEW_VERSION,UPDATE_QUESTION_POINTERS")
          .contains("QUESTION_SELF_REVIEW_RULE = reviewerId!=submittedBy")
          .contains("事务失败整体回滚");
      QUESTION_TYPES.forEach(type -> assertThat(content).contains(type));
      VERSION_STATES.forEach(state -> assertThat(content).contains(state));
      PERMISSIONS.forEach(permission -> assertThat(content).contains(permission));
    }

    JsonNode transaction = jsonBlock(FREEZE, "QUESTION_PUBLISH_TRANSACTION");
    assertThat(texts(transaction.path("lockOrder"))).containsExactly(
        "Q_QUESTION", "QUESTION_VERSION_IDS_ASC");
    assertThat(texts(transaction.path("writeOrder"))).containsExactly(
        "RETIRE_CURRENT_PUBLISHED", "PUBLISH_NEW_VERSION", "UPDATE_QUESTION_POINTERS");
    assertThat(transaction.path("rollbackOnFailure").asBoolean()).isTrue();
    assertThat(transaction.path("selfReview").path("denyWhen").asText())
        .isEqualTo("reviewerId==submittedBy");
    assertThat(texts(transaction.path("selfReview").path("appliesToRoles")))
        .containsExactlyInAnyOrderElementsOf(PERMISSIONS);
  }

  @Test
  void allAuthorityDocumentsFreezeImportAndReferenceBoundaries() throws IOException {
    for (Path contract : CONTRACTS) {
      String content = read(contract);
      assertThat(content).as(contract.toString())
          .contains("QUESTION_IMPORT_SCHEMA = question-import/1.0")
          .contains("QUESTION_MUTATION_STATES = PROCESSING,COMPLETED")
          .contains("CASE_REFERENCE_VISIBILITY = AFTER_ATTEMPT_SUBMISSION")
          .contains("CASE_SELF_ASSESSMENT_LABEL = SELF_ASSESSED");
    }
  }

  @Test
  void importSchemaIsDraft202012RecursivelyClosedAndFullyRequired() throws IOException {
    JsonNode schema = jsonBlock(FREEZE, "QUESTION_IMPORT_SCHEMA");
    assertThat(schema.path("$schema").asText())
        .isEqualTo("https://json-schema.org/draft/2020-12/schema");
    assertThat(schema.path("$id").asText()).isEqualTo("question-import/1.0");
    assertThat(schema.path("$defs").fieldNames()).toIterable()
        .contains("question", "source", "stem", "subQuestion", "option", "answer",
            "solution", "scoringRule", "scoringPoint", "knowledgeBinding");
    assertClosedObjects(schema, "#");
    assertThat(schema.toString()).contains("oneOf", "if", "then", "$ref", "x-ordering");
    assertThat(schema.toString()).doesNotContain("\"nullable\"");
  }

  @Test
  void excelTemplateDefinesNineSheetsAndEveryColumnContract() throws IOException {
    JsonNode template = jsonBlock(FREEZE, "QUESTION_IMPORT_EXCEL_TEMPLATE");
    assertThat(template.path("schemaVersion").asText()).isEqualTo("question-import/1.0");
    assertThat(template.path("macrosAllowed").asBoolean()).isFalse();
    assertThat(template.path("formulasAllowed").asBoolean()).isFalse();
    assertThat(template.path("externalLinksAllowed").asBoolean()).isFalse();

    Set<String> names = new LinkedHashSet<>();
    for (JsonNode sheet : template.path("sheets")) {
      names.add(sheet.path("name").asText());
      assertThat(sheet.path("columns").isArray()).isTrue();
      assertThat(sheet.path("columns").size()).isGreaterThan(0);
      Set<String> columnNames = new HashSet<>();
      for (JsonNode column : sheet.path("columns")) {
        assertThat(column.path("name").asText()).isNotBlank();
        assertThat(column.path("type").asText()).isNotBlank();
        assertThat(column.has("required")).isTrue();
        assertThat(column.has("nullable")).isTrue();
        assertThat(columnNames.add(column.path("name").asText()))
            .as("duplicate column in " + sheet.path("name").asText()).isTrue();
      }
      String sheetName = sheet.path("name").asText();
      assertThat(new ArrayList<>(columnNames)).containsExactlyInAnyOrderElementsOf(
          EXCEL_COLUMNS.get(sheetName));
      assertThat(texts(sheet.path("businessKey"))).containsExactlyElementsOf(
          EXCEL_BUSINESS_KEYS.get(sheetName));
      assertThat(texts(sheet.path("foreignKeys"))).containsExactlyElementsOf(
          EXCEL_FOREIGN_KEYS.get(sheetName));
    }
    assertThat(names).containsExactlyInAnyOrderElementsOf(EXCEL_SHEETS);
    assertThat(template.path("canonicalModel").asText()).isEqualTo("QUESTION_IMPORT_SCHEMA");
    assertThat(template.path("crossSheetConstraints").size()).isEqualTo(10);
    Set<String> constraintIds = new HashSet<>();
    template.path("crossSheetConstraints")
        .forEach(constraint -> constraintIds.add(constraint.path("id").asText()));
    assertThat(constraintIds).containsExactlyInAnyOrderElementsOf(EXCEL_CONSTRAINT_IDS);
  }

  @Test
  void documentedPositiveAndNegativeVectorsAreExecutable() throws IOException {
    JsonNode vectors = jsonBlock(FREEZE, "QUESTION_IMPORT_VECTORS");
    JsonNode schema = jsonBlock(FREEZE, "QUESTION_IMPORT_SCHEMA");
    ObjectNode positive = (ObjectNode) vectors.path("jsonPositive").deepCopy();
    assertStandardDraft202012(schema, positive, true, "json-positive");
    assertThat(validateCanonicalImport(positive)).isEqualTo("OK");

    Set<String> jsonCases = new LinkedHashSet<>();
    for (JsonNode testCase : vectors.path("jsonNegativeMutations")) {
      ObjectNode mutated = positive.deepCopy();
      applyJsonMutation(mutated, testCase.path("mutation"));
      assertThat(testCase.path("draft202012Valid").asBoolean()).as(testCase.path("id").asText())
          .isFalse();
      assertStandardDraft202012(schema, mutated, false, testCase.path("id").asText());
      assertThat(validateCanonicalImport(mutated)).as(testCase.path("id").asText())
          .isEqualTo(testCase.path("expectedCode").asText());
      jsonCases.add(testCase.path("id").asText());
    }
    assertThat(jsonCases).contains(
        "single-answer-count", "multi-answer-min-two", "case-scoring-points",
        "unknown-root-field", "single-question-subtype-judge", "single-answer-format-boolean",
        "single-answer-kind-boolean", "single-options-empty", "single-grading-mode-manual");

    ObjectNode workbook = (ObjectNode) vectors.path("excelPositive").deepCopy();
    JsonNode template = jsonBlock(FREEZE, "QUESTION_IMPORT_EXCEL_TEMPLATE");
    assertThat(validateWorkbook(workbook, template)).isEqualTo("OK");
    Set<String> excelCases = new LinkedHashSet<>();
    for (JsonNode testCase : vectors.path("excelNegativeMutations")) {
      ObjectNode mutated = workbook.deepCopy();
      applyExcelMutation(mutated, testCase.path("mutation"));
      assertThat(validateWorkbook(mutated, template)).as(testCase.path("id").asText())
          .isEqualTo(testCase.path("expectedCode").asText());
      excelCases.add(testCase.path("id").asText());
    }
    assertThat(excelCases).contains(
        "missing-sheet", "orphan-foreign-key", "duplicate-business-key",
        "compound-subquestion-orphan", "wrong-option-reference",
        "cross-sheet-question-type-mismatch", "duplicate-compound-business-key",
        "sort-order-gap", "scoring-point-score-mismatch");

    JsonNode essayVisibility = StreamSupport.stream(
            vectors.path("visibilityCases").spliterator(), false)
        .filter(node -> "essay-answer-before-submit".equals(node.path("id").asText()))
        .findFirst().orElseThrow();
    assertThat(essayVisibility.path("attemptStatus").asText()).isEqualTo("IN_PROGRESS");
    assertThat(texts(essayVisibility.path("forbiddenResponseFields")))
        .contains("standardAnswer", "solution", "scoringPoints");
    assertThat(essayVisibility.path("expectedVisibility").asText()).isEqualTo("HIDDEN");
  }

  private static void assertClosedObjects(JsonNode node, String path) {
    if (node.isObject()) {
      if (isObjectSchema(node)) {
        assertThat(node.path("additionalProperties").isBoolean() && !node.path("additionalProperties").asBoolean())
            .as(path + " additionalProperties=false").isTrue();
        JsonNode properties = node.path("properties");
        assertThat(properties.isObject()).as(path + " properties").isTrue();
        Set<String> propertyNames = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(propertyNames::add);
        assertThat(texts(node.path("required"))).as(path + " required")
            .containsExactlyInAnyOrderElementsOf(propertyNames);
      }
      Iterator<String> fields = node.fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        assertClosedObjects(node.path(field), path + "/" + field);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        assertClosedObjects(node.get(i), path + "/" + i);
      }
    }
  }

  private static boolean isObjectSchema(JsonNode node) {
    JsonNode type = node.path("type");
    if (type.isTextual()) {
      return "object".equals(type.asText());
    }
    return type.isArray() && texts(type).contains("object");
  }

  private static String validateCanonicalImport(ObjectNode root) {
    if (!fieldNames(root).equals(Set.of("schemaVersion", "qualificationCode", "subjectCode", "questions"))) {
      return "QUESTION_IMPORT_UNKNOWN_FIELD";
    }
    if (!"question-import/1.0".equals(root.path("schemaVersion").asText())
        || root.path("qualificationCode").asText().isBlank()
        || root.path("subjectCode").asText().isBlank()
        || !root.path("questions").isArray()
        || root.path("questions").isEmpty()) {
      return "QUESTION_IMPORT_SCHEMA_INVALID";
    }
    Set<String> externalKeys = new HashSet<>();
    for (JsonNode question : root.path("questions")) {
      if (!externalKeys.add(question.path("externalKey").asText())) {
        return "QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY";
      }
      String questionType = question.path("questionType").asText();
      for (JsonNode subQuestion : question.path("subQuestions")) {
        String type = subQuestion.path("subQuestionType").asText();
        if (!allowedSubQuestionTypes(questionType).contains(type)) {
          return "QUESTION_IMPORT_TYPE_COMBINATION_INVALID";
        }
        String expectedFormat = expectedAnswerFormat(type);
        if (!expectedFormat.equals(subQuestion.path("answerFormat").asText())) {
          return "QUESTION_IMPORT_ANSWER_FORMAT_MISMATCH";
        }
        JsonNode answers = subQuestion.path("answers");
        if ("SINGLE_CHOICE".equals(type)
            && (answers.size() != 1 || answers.get(0).path("optionKeys").size() != 1)) {
          return "QUESTION_IMPORT_SINGLE_ANSWER_COUNT";
        }
        if ("MULTI_CHOICE".equals(type)
            && (answers.size() != 1 || answers.get(0).path("optionKeys").size() < 2)) {
          return "QUESTION_IMPORT_MULTI_ANSWER_MIN_TWO";
        }
        String expectedKind = expectedAnswerKind(type);
        if (answers.size() != 1 || !expectedKind.equals(answers.get(0).path("answerKind").asText())) {
          return "QUESTION_IMPORT_ANSWER_KIND_MISMATCH";
        }
        boolean choice = Set.of("SINGLE_CHOICE", "MULTI_CHOICE").contains(type);
        if ((choice && subQuestion.path("options").size() < 2)
            || (!choice && !subQuestion.path("options").isEmpty())) {
          return "QUESTION_IMPORT_OPTIONS_INVALID";
        }
        String gradingMode = subQuestion.path("gradingMode").asText();
        JsonNode scoringRule = subQuestion.path("scoringRules").isEmpty()
            ? JSON.nullNode() : subQuestion.path("scoringRules").get(0);
        if (!allowedGradingModes(type).contains(gradingMode)
            || subQuestion.path("scoringRules").size() != 1
            || !gradingMode.equals(scoringRule.path("ruleType").asText())
            || !allowedScoringRuleModes(type, gradingMode)
                .contains(scoringRule.path("ruleJson").path("mode").asText())) {
          return "QUESTION_IMPORT_SCORING_MODE_MISMATCH";
        }
        if ("CASE".equals(questionType)
            && Set.of("SHORT_ANSWER", "CALCULATION").contains(type)
            && subQuestion.path("scoringPoints").isEmpty()) {
          return "QUESTION_IMPORT_CASE_SCORING_POINTS_REQUIRED";
        }
      }
    }
    return "OK";
  }

  private static Set<String> allowedSubQuestionTypes(String questionType) {
    return switch (questionType) {
      case "SINGLE_CHOICE" -> Set.of("SINGLE_CHOICE");
      case "MULTI_CHOICE" -> Set.of("MULTI_CHOICE");
      case "JUDGE" -> Set.of("JUDGE");
      case "CASE" -> Set.of(
          "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CALCULATION", "SHORT_ANSWER");
      case "ESSAY" -> Set.of("ESSAY_WRITING");
      default -> Set.of();
    };
  }

  private static String expectedAnswerFormat(String subQuestionType) {
    return switch (subQuestionType) {
      case "SINGLE_CHOICE", "MULTI_CHOICE" -> "OPTION_KEYS";
      case "JUDGE" -> "BOOLEAN";
      case "CALCULATION" -> "NUMBER";
      case "SHORT_ANSWER", "ESSAY_WRITING" -> "TEXT";
      default -> "";
    };
  }

  private static String expectedAnswerKind(String subQuestionType) {
    return expectedAnswerFormat(subQuestionType);
  }

  private static Set<String> allowedGradingModes(String subQuestionType) {
    return switch (subQuestionType) {
      case "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE" -> Set.of("AUTO");
      case "CALCULATION" -> Set.of("AUTO", "MANUAL");
      case "SHORT_ANSWER", "ESSAY_WRITING" -> Set.of("MANUAL", "AI_ASSISTED");
      default -> Set.of();
    };
  }

  private static Set<String> allowedScoringRuleModes(String subQuestionType, String gradingMode) {
    if ("MANUAL".equals(gradingMode)) {
      return Set.of("MANUAL");
    }
    if ("AI_ASSISTED".equals(gradingMode)) {
      return Set.of("KEYWORD");
    }
    return switch (subQuestionType) {
      case "SINGLE_CHOICE", "JUDGE" -> Set.of("EXACT");
      case "MULTI_CHOICE" -> Set.of("SET_EQUAL");
      case "CALCULATION" -> Set.of("NUMERIC_TOLERANCE");
      default -> Set.of();
    };
  }

  private static String validateWorkbook(ObjectNode workbook, JsonNode template) {
    if (!fieldNames(workbook).equals(EXCEL_SHEETS)) {
      return "QUESTION_IMPORT_MISSING_SHEET";
    }
    for (JsonNode sheetContract : template.path("sheets")) {
      String sheetName = sheetContract.path("name").asText();
      Set<String> expectedColumns = new LinkedHashSet<>();
      sheetContract.path("columns").forEach(column -> expectedColumns.add(column.path("name").asText()));
      for (JsonNode row : workbook.path(sheetName)) {
        if (!fieldNames(row).equals(expectedColumns)) {
          return "QUESTION_IMPORT_UNKNOWN_FIELD";
        }
        for (JsonNode column : sheetContract.path("columns")) {
          JsonNode value = row.path(column.path("name").asText());
          if (value.isNull() && !column.path("nullable").asBoolean()) {
            return "QUESTION_IMPORT_SCHEMA_INVALID";
          }
          if (!value.isNull() && !matchesExcelType(value, column.path("type").asText())) {
            return "QUESTION_IMPORT_SCHEMA_INVALID";
          }
        }
      }
    }
    for (JsonNode sheetContract : template.path("sheets")) {
      String sheetName = sheetContract.path("name").asText();
      Set<String> keys = new HashSet<>();
      for (JsonNode row : workbook.path(sheetName)) {
        if (!keys.add(compoundKey(row, texts(sheetContract.path("businessKey"))))) {
          return "QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY";
        }
      }
    }

    Map<String, String> questionTypes = new HashMap<>();
    Set<String> scopes = new HashSet<>();
    for (JsonNode row : workbook.path("Questions")) {
      questionTypes.put(row.path("externalKey").asText(), row.path("questionType").asText());
      scopes.add(row.path("qualificationCode").asText() + "|" + row.path("subjectCode").asText());
    }
    if (scopes.size() > 1) {
      return "QUESTION_IMPORT_SCOPE_MISMATCH";
    }
    for (String sheet : EXCEL_SHEETS) {
      if ("Questions".equals(sheet)) {
        continue;
      }
      for (JsonNode row : workbook.path(sheet)) {
        if (!questionTypes.containsKey(row.path("externalKey").asText())) {
          return "QUESTION_IMPORT_ORPHAN_FOREIGN_KEY";
        }
      }
    }

    Map<String, JsonNode> subQuestions = new HashMap<>();
    for (JsonNode row : workbook.path("SubQuestions")) {
      subQuestions.put(subQuestionKey(row), row);
    }
    for (String sheet : Set.of(
        "Options", "Answers", "Solutions", "ScoringRules", "ScoringPoints", "KnowledgeBindings")) {
      for (JsonNode row : workbook.path(sheet)) {
        if (!row.path("subQuestionNo").isNull() && !subQuestions.containsKey(subQuestionKey(row))) {
          return "QUESTION_IMPORT_ORPHAN_SUBQUESTION";
        }
      }
    }

    if (!allSortOrdersContiguous(workbook)) {
      return "QUESTION_IMPORT_SORT_ORDER_INVALID";
    }

    Map<String, Set<String>> options = new HashMap<>();
    for (JsonNode row : workbook.path("Options")) {
      options.computeIfAbsent(subQuestionKey(row), ignored -> new HashSet<>())
          .add(row.path("optionKey").asText());
    }
    Map<String, JsonNode> answers = uniqueRowsBySubQuestion(workbook.path("Answers"));
    Map<String, JsonNode> scoringRules = uniqueRowsBySubQuestion(workbook.path("ScoringRules"));
    for (Map.Entry<String, JsonNode> entry : subQuestions.entrySet()) {
      JsonNode subQuestion = entry.getValue();
      String questionType = questionTypes.get(subQuestion.path("externalKey").asText());
      String subType = subQuestion.path("subQuestionType").asText();
      if (!allowedSubQuestionTypes(questionType).contains(subType)
          || !expectedAnswerFormat(subType).equals(subQuestion.path("answerFormat").asText())) {
        return "QUESTION_IMPORT_TYPE_COMBINATION_INVALID";
      }
      JsonNode answer = answers.get(entry.getKey());
      if (answer == null || !expectedAnswerKind(subType).equals(answer.path("answerKind").asText())
          || !answerPayloadMatchesKind(answer)) {
        return "QUESTION_IMPORT_ANSWER_KIND_MISMATCH";
      }
      String gradingMode = subQuestion.path("gradingMode").asText();
      JsonNode scoringRule = scoringRules.get(entry.getKey());
      if (!allowedGradingModes(subType).contains(gradingMode)
          || scoringRule == null || !gradingMode.equals(scoringRule.path("ruleType").asText())
          || !allowedScoringRuleModes(subType, gradingMode)
              .contains(scoringRule.path("ruleJson").path("mode").asText())) {
        return "QUESTION_IMPORT_SCORING_MODE_MISMATCH";
      }
      Set<String> optionKeys = options.getOrDefault(entry.getKey(), Set.of());
      boolean choice = Set.of("SINGLE_CHOICE", "MULTI_CHOICE").contains(subType);
      if ((choice && optionKeys.size() < 2) || (!choice && !optionKeys.isEmpty())) {
        return "QUESTION_IMPORT_OPTIONS_INVALID";
      }
      if (choice) {
        for (JsonNode optionKey : answer.path("optionKeys")) {
          if (!optionKeys.contains(optionKey.asText())) {
            return "QUESTION_IMPORT_OPTION_REFERENCE_INVALID";
          }
        }
      }
    }

    Map<String, BigDecimal> scoringPointTotals = new HashMap<>();
    for (JsonNode row : workbook.path("ScoringPoints")) {
      scoringPointTotals.merge(subQuestionKey(row), row.path("score").decimalValue(), BigDecimal::add);
    }
    for (Map.Entry<String, JsonNode> entry : subQuestions.entrySet()) {
      String type = entry.getValue().path("subQuestionType").asText();
      if (Set.of("CALCULATION", "SHORT_ANSWER", "ESSAY_WRITING").contains(type)) {
        BigDecimal total = scoringPointTotals.get(entry.getKey());
        if (total == null || total.compareTo(entry.getValue().path("score").decimalValue()) != 0) {
          return "QUESTION_IMPORT_SCORE_CLOSURE_INVALID";
        }
      }
    }
    Map<String, Integer> subQuestionCounts = new HashMap<>();
    subQuestions.values().forEach(row -> subQuestionCounts.merge(
        row.path("externalKey").asText(), 1, Integer::sum));
    for (Map.Entry<String, String> question : questionTypes.entrySet()) {
      int count = subQuestionCounts.getOrDefault(question.getKey(), 0);
      if (("CASE".equals(question.getValue()) && (count < 2 || count > 4))
          || (!"CASE".equals(question.getValue()) && count != 1)) {
        return "QUESTION_IMPORT_TYPE_COMBINATION_INVALID";
      }
    }
    return "OK";
  }

  private static boolean answerPayloadMatchesKind(JsonNode answer) {
    String kind = answer.path("answerKind").asText();
    boolean optionKeys = !answer.path("optionKeys").isNull();
    boolean booleanValue = !answer.path("booleanValue").isNull();
    boolean textValue = !answer.path("textValue").isNull();
    boolean numberValue = !answer.path("numberValue").isNull();
    int populated = (optionKeys ? 1 : 0) + (booleanValue ? 1 : 0)
        + (textValue ? 1 : 0) + (numberValue ? 1 : 0);
    return populated == 1 && switch (kind) {
      case "OPTION_KEYS" -> optionKeys;
      case "BOOLEAN" -> booleanValue;
      case "TEXT" -> textValue;
      case "NUMBER" -> numberValue;
      default -> false;
    };
  }

  private static Map<String, JsonNode> uniqueRowsBySubQuestion(JsonNode rows) {
    Map<String, JsonNode> result = new HashMap<>();
    rows.forEach(row -> result.put(subQuestionKey(row), row));
    return result;
  }

  private static String compoundKey(JsonNode row, List<String> columns) {
    List<String> values = new ArrayList<>();
    for (String column : columns) {
      values.add(row.path(column).isNull() ? "<NULL>" : row.path(column).asText());
    }
    return String.join("|", values);
  }

  private static String subQuestionKey(JsonNode row) {
    return row.path("externalKey").asText() + "|" + row.path("subQuestionNo").asInt();
  }

  private static boolean allSortOrdersContiguous(ObjectNode workbook) {
    for (String sheet : Set.of(
        "Stems", "SubQuestions", "Options", "Answers", "Solutions", "ScoringRules",
        "ScoringPoints", "KnowledgeBindings")) {
      Map<String, Set<Integer>> byParent = new HashMap<>();
      for (JsonNode row : workbook.path(sheet)) {
        String parent = switch (sheet) {
          case "Stems", "SubQuestions" -> row.path("externalKey").asText();
          default -> subQuestionKey(row);
        };
        if (!byParent.computeIfAbsent(parent, ignored -> new HashSet<>())
            .add(row.path("sortOrder").asInt())) {
          return false;
        }
      }
      for (Set<Integer> orders : byParent.values()) {
        for (int expected = 1; expected <= orders.size(); expected++) {
          if (!orders.contains(expected)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static boolean matchesExcelType(JsonNode value, String type) {
    if (type.startsWith("ENUM:")) {
      return value.isTextual() && Set.of(type.substring(5).split("\\|", -1)).contains(value.asText());
    }
    return switch (type) {
      case "STRING", "ASCII_UPPER" -> value.isTextual();
      case "INTEGER", "LONG" -> value.isIntegralNumber();
      case "DECIMAL_8_2", "DECIMAL_4_3" -> value.isNumber();
      case "BOOLEAN" -> value.isBoolean();
      case "JSON_ARRAY" -> value.isArray();
      case "JSON_OBJECT" -> value.isObject();
      default -> false;
    };
  }

  private static void applyJsonMutation(ObjectNode root, JsonNode mutation) {
    String operation = mutation.path("operation").asText();
    String pointer = mutation.path("pointer").asText();
    if ("ADD_FIELD".equals(operation)) {
      root.set(mutation.path("field").asText(), mutation.path("value"));
      return;
    }
    JsonNode target = root.at(pointer);
    if ("APPEND".equals(operation)) {
      ((ArrayNode) target).add(mutation.path("value"));
    } else if ("REPLACE".equals(operation)) {
      int slash = pointer.lastIndexOf('/');
      JsonNode parent = root.at(pointer.substring(0, slash));
      ((ObjectNode) parent).set(pointer.substring(slash + 1), mutation.path("value"));
    } else {
      throw new IllegalArgumentException("Unsupported mutation " + operation);
    }
  }

  private static void applyExcelMutation(ObjectNode workbook, JsonNode mutation) {
    String operation = mutation.path("operation").asText();
    String sheet = mutation.path("sheet").asText();
    if ("REMOVE_SHEET".equals(operation)) {
      workbook.remove(sheet);
    } else if ("APPEND_ROW".equals(operation)) {
      ((ArrayNode) workbook.path(sheet)).add(mutation.path("row"));
    } else if ("DUPLICATE_ROW".equals(operation)) {
      ArrayNode rows = (ArrayNode) workbook.path(sheet);
      rows.add(rows.get(mutation.path("rowIndex").asInt()).deepCopy());
    } else if ("SET_EXTERNAL_KEY".equals(operation)) {
      ((ObjectNode) workbook.path(sheet).get(mutation.path("rowIndex").asInt()))
          .put("externalKey", mutation.path("externalKey").asText());
    } else if ("SET_FIELD".equals(operation)) {
      ((ObjectNode) workbook.path(sheet).get(mutation.path("rowIndex").asInt()))
          .set(mutation.path("field").asText(), mutation.path("value"));
    } else {
      throw new IllegalArgumentException("Unsupported Excel mutation " + operation);
    }
  }

  private static void assertStandardDraft202012(
      JsonNode schema, JsonNode instance, boolean expectedValid, String caseId) throws IOException {
    String script = """
        import json, sys
        from jsonschema import Draft202012Validator
        payload = json.load(sys.stdin)
        Draft202012Validator.check_schema(payload['schema'])
        valid = not list(Draft202012Validator(payload['schema']).iter_errors(payload['instance']))
        if valid != payload['expectedValid']:
            print('expectedValid=%s actualValid=%s' % (payload['expectedValid'], valid))
            sys.exit(2)
        """;
    Process process = new ProcessBuilder("python", "-c", script)
        .redirectErrorStream(true)
        .start();
    ObjectNode input = JSON.createObjectNode();
    input.set("schema", schema);
    input.set("instance", instance);
    input.put("expectedValid", expectedValid);
    process.getOutputStream().write(JSON.writeValueAsBytes(input));
    process.getOutputStream().close();
    boolean finished;
    try {
      finished = process.waitFor(20, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Draft 2020-12 validator interrupted", interrupted);
    }
    assertThat(finished).as(caseId + " Draft 2020-12 timeout").isTrue();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.exitValue()).as(caseId + " Draft 2020-12 output: " + output).isZero();
  }

  private static JsonNode jsonBlock(Path path, String name) throws IOException {
    String content = read(path);
    String start = "<!-- " + name + "_JSON_BEGIN -->";
    String end = "<!-- " + name + "_JSON_END -->";
    int startIndex = content.indexOf(start);
    int endIndex = content.indexOf(end);
    assertThat(startIndex).as(name + " start marker").isGreaterThanOrEqualTo(0);
    assertThat(endIndex).as(name + " end marker").isGreaterThan(startIndex);
    String block = content.substring(startIndex + start.length(), endIndex)
        .replaceFirst("(?s)^\\s*```json\\s*", "")
        .replaceFirst("(?s)\\s*```\\s*$", "")
        .trim();
    return JSON.readTree(block);
  }

  private static Set<String> fieldNames(JsonNode node) {
    Set<String> result = new LinkedHashSet<>();
    node.fieldNames().forEachRemaining(result::add);
    return result;
  }

  private static List<String> texts(JsonNode array) {
    List<String> result = new ArrayList<>();
    array.forEach(node -> result.add(node.asText()));
    return result;
  }

  private static String read(Path path) throws IOException {
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  private static Path docs(String relative) {
    return Path.of(System.getProperty("user.dir"))
        .resolve("../../docs")
        .resolve(relative)
        .normalize();
  }
}
