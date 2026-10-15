package com.aiexam.question.admin.application;

import com.aiexam.common.error.BusinessException;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.ScoringPoint;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.VersionContent;
import com.aiexam.question.dto.request.QuestionCreateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** One fail-closed semantic validator shared by manual, JSONL and XLSX writes. */
public final class QuestionPayloadValidator {
  private static final Set<String> QUESTION_TYPES = Set.of(
      "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CASE", "ESSAY");
  private static final Set<String> SUB_TYPES = Set.of(
      "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CALCULATION", "SHORT_ANSWER",
      "ESSAY_WRITING", "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH",
      "REQUIREMENT_SPEC", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION");
  private static final Set<String> OBJECTIVE_TYPES = Set.of(
      "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE");
  private static final Set<String> SOLUTION_TYPES = Set.of(
      "STANDARD", "REFERENCE_ANSWER", "ANALYSIS", "AI_ENHANCED");

  private final ObjectMapper objectMapper;

  public QuestionPayloadValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper.copy().findAndRegisterModules();
  }

  public VersionContent validate(QuestionCreateRequest payload) {
    if (payload == null || payload.qualificationId() == null || payload.qualificationId() <= 0
        || payload.subjectId() == null || payload.subjectId() <= 0
        || !QUESTION_TYPES.contains(upper(payload.questionType()))) {
      throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
          "Question identity and a supported questionType are required");
    }
    if (payload.yearNo() != null && (payload.yearNo() < 2000 || payload.yearNo() > 2100)) {
      throw invalid("QUESTION_IMPORT_FIELD_INVALID", "yearNo must be between 2000 and 2100");
    }
    if (payload.difficulty() != null
        && !Set.of("EASY", "MEDIUM", "HARD").contains(upper(payload.difficulty()))) {
      throw invalid("QUESTION_IMPORT_FIELD_INVALID", "difficulty is not supported");
    }

    List<QuestionCreateRequest.StemRequest> stems = safe(payload.stems());
    List<QuestionCreateRequest.SubQuestionRequest> subQuestions = safe(payload.subQuestions());
    if (stems.isEmpty() || subQuestions.isEmpty()) {
      throw invalid("QUESTION_IMPORT_STEM_REQUIRED",
          "At least one stem and one sub-question are required");
    }
    requireContiguous(stems.stream().map(QuestionCreateRequest.StemRequest::sortOrder).toList(),
        "stem sortOrder");
    for (QuestionCreateRequest.StemRequest stem : stems) {
      if (blank(stem.stemType()) || blank(stem.content())) {
        throw invalid("QUESTION_IMPORT_STEM_REQUIRED", "Every stem requires type and content");
      }
    }

    String questionType = upper(payload.questionType());
    if ("CASE".equals(questionType)) {
      if (subQuestions.size() < 2 || subQuestions.size() > 4
          || stems.stream().noneMatch(stem -> "CASE_MATERIAL".equals(upper(stem.stemType())))) {
        throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
            "CASE requires CASE_MATERIAL and two to four sub-questions");
      }
    } else if ("SINGLE_CHOICE".equals(questionType)) {
      if (subQuestions.size() > 10) {
        throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
            "SINGLE_CHOICE requires one to ten sub-questions");
      }
    } else if (subQuestions.size() != 1) {
      throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
          "MULTI_CHOICE, JUDGE and ESSAY require exactly one sub-question");
    }
    if ("ESSAY".equals(questionType)
        && stems.stream().noneMatch(stem -> "ESSAY_REQUIREMENT".equals(upper(stem.stemType())))) {
      throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
          "ESSAY requires an ESSAY_REQUIREMENT stem");
    }

    requireContiguous(subQuestions.stream()
        .map(QuestionCreateRequest.SubQuestionRequest::subQuestionNo).toList(),
        "subQuestionNo");
    requireContiguous(subQuestions.stream()
        .map(QuestionCreateRequest.SubQuestionRequest::sortOrder).toList(),
        "sub-question sortOrder");

    Map<Integer, List<ScoringPoint>> scoringPoints = new LinkedHashMap<>();
    for (QuestionCreateRequest.SubQuestionRequest sub : subQuestions) {
      validateSubQuestion(questionType, sub);
      List<ScoringPoint> points = safe(sub.scoringPoints()).stream()
          .map(point -> new ScoringPoint(point.pointCode(), point.pointText(), point.score(),
              point.sortOrder()))
          .toList();
      scoringPoints.put(sub.subQuestionNo(), points);
    }
    validateKnowledgeBindings(payload.knowledgeBindings());
    return new VersionContent(payload, scoringPoints);
  }

  private void validateSubQuestion(String questionType,
      QuestionCreateRequest.SubQuestionRequest sub) {
    String subType = upper(sub.subQuestionType());
    if (!SUB_TYPES.contains(subType) || blank(sub.content()) || sub.score() == null
        || sub.score().scale() > 2 || sub.score().compareTo(BigDecimal.ZERO) <= 0) {
      throw invalid("QUESTION_IMPORT_FIELD_INVALID",
          "Every sub-question requires valid type, content and a positive two-decimal score");
    }
    if (!typeMatches(questionType, subType)) {
      throw invalid("QUESTION_IMPORT_TYPE_COMBINATION_INVALID",
          "questionType and subQuestionType do not match");
    }

    String expectedFormat = switch (subType) {
      case "SINGLE_CHOICE", "MULTI_CHOICE" -> "OPTION_KEYS";
      case "JUDGE" -> "BOOLEAN";
      case "CALCULATION" -> "NUMBER";
      case "SHORT_ANSWER", "ESSAY_WRITING" -> "TEXT";
      case "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC",
          "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION" -> "JSON";
      default -> throw new IllegalStateException("Unsupported sub-question type");
    };
    if (!expectedFormat.equals(upper(sub.answerFormat()))) {
      throw invalid("QUESTION_IMPORT_ANSWER_FORMAT_MISMATCH",
          "answerFormat does not match subQuestionType");
    }

    List<QuestionCreateRequest.OptionRequest> options = safe(sub.options());
    Set<String> optionKeys = new HashSet<>();
    if (Set.of("SINGLE_CHOICE", "MULTI_CHOICE").contains(subType)) {
      if (options.size() < 2) {
        throw invalid("QUESTION_IMPORT_OPTIONS_INVALID", "Choice questions require at least two options");
      }
      requireContiguous(options.stream().map(QuestionCreateRequest.OptionRequest::sortOrder).toList(),
          "option sortOrder");
      for (QuestionCreateRequest.OptionRequest option : options) {
        String key = upper(option.optionKey());
        if (key == null || !key.matches("[A-Z]") || !optionKeys.add(key)
            || blank(option.optionContent())) {
          throw invalid("QUESTION_IMPORT_OPTIONS_INVALID",
              "Option keys must be unique ASCII uppercase letters with non-empty content");
        }
      }
    } else if (!options.isEmpty()) {
      throw invalid("QUESTION_IMPORT_OPTIONS_INVALID", "Only choice questions may contain options");
    }

    List<QuestionCreateRequest.AnswerKeyRequest> answers = safe(sub.answerKeys());
    if (answers.size() != 1) {
      throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH",
          "Every sub-question requires exactly one answer payload");
    }
    QuestionCreateRequest.AnswerKeyRequest answer = answers.get(0);
    if (!expectedFormat.equals(upper(answer.answerFormat()))) {
      throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH",
          "answerKind does not match answerFormat");
    }
    validateAnswer(subType, answer, optionKeys);

    boolean objective = OBJECTIVE_TYPES.contains(subType);
    String gradingMode = upper(sub.gradingMode());
    Set<String> allowedGradingModes = switch (subType) {
      case "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE" -> Set.of("AUTO");
      case "CALCULATION" -> Set.of("AUTO", "MANUAL");
      case "SHORT_ANSWER", "ESSAY_WRITING" -> Set.of("MANUAL", "AI_ASSISTED");
      case "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC",
          "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION" -> Set.of("MANUAL");
      default -> Set.of();
    };
    if (!allowedGradingModes.contains(gradingMode)) {
      throw invalid("QUESTION_IMPORT_SCORING_MODE_MISMATCH",
          "gradingMode does not match the sub-question type");
    }
    List<QuestionCreateRequest.ScoringRuleRequest> rules = safe(sub.scoringRules());
    if (rules.size() != 1 || !gradingMode.equals(upper(rules.get(0).ruleType()))) {
      throw invalid("QUESTION_IMPORT_SCORING_MODE_MISMATCH",
          "Exactly one scoring rule matching gradingMode is required");
    }
    JsonNode ruleJson = parseJsonObject(rules.get(0).ruleJson(),
        "QUESTION_IMPORT_SCORING_MODE_MISMATCH", "ruleJson must be a JSON object");
    String ruleMode = upper(ruleJson.path("mode").asText(null));
    String expectedRuleMode = switch (subType) {
      case "SINGLE_CHOICE", "JUDGE" -> "EXACT";
      case "MULTI_CHOICE" -> "SET_EQUAL";
      case "CALCULATION" -> "AUTO".equals(gradingMode) ? "NUMERIC_TOLERANCE" : "MANUAL";
      case "SHORT_ANSWER", "ESSAY_WRITING" ->
          "MANUAL".equals(gradingMode) ? "MANUAL" : "KEYWORD";
      case "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC",
          "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION" -> "MANUAL";
      default -> null;
    };
    if (!expectedRuleMode.equals(ruleMode)) {
      throw invalid("QUESTION_IMPORT_SCORING_MODE_MISMATCH",
          "ruleJson.mode does not match gradingMode");
    }
    if ("NUMERIC_TOLERANCE".equals(ruleMode)
        && (!ruleJson.path("tolerance").isNumber()
        || ruleJson.path("tolerance").decimalValue().compareTo(BigDecimal.ZERO) < 0)) {
      throw invalid("QUESTION_IMPORT_SCORING_MODE_MISMATCH",
          "NUMERIC_TOLERANCE requires a non-negative tolerance");
    }

    for (QuestionCreateRequest.SolutionRequest solution : safe(sub.solutions())) {
      if (!SOLUTION_TYPES.contains(upper(solution.solutionType())) || blank(solution.content())) {
        throw invalid("QUESTION_IMPORT_FIELD_INVALID", "Solution type/content is invalid");
      }
    }

    List<QuestionCreateRequest.ScoringPointRequest> points = safe(sub.scoringPoints());
    if (objective && !points.isEmpty()) {
      throw invalid("QUESTION_IMPORT_SCORE_CLOSURE_INVALID",
          "Objective questions cannot contain subjective scoring points");
    }
    if (!objective) {
      if (points.isEmpty()) {
        throw invalid("QUESTION_IMPORT_SCORE_CLOSURE_INVALID",
            "Subjective questions require scoring points");
      }
      requireContiguous(points.stream().map(QuestionCreateRequest.ScoringPointRequest::sortOrder).toList(),
          "scoring-point sortOrder");
      Set<String> codes = new HashSet<>();
      BigDecimal total = BigDecimal.ZERO;
      for (QuestionCreateRequest.ScoringPointRequest point : points) {
        if (blank(point.pointCode()) || !codes.add(upper(point.pointCode()))
            || blank(point.pointText()) || point.score() == null || point.score().scale() > 2
            || point.score().compareTo(BigDecimal.ZERO) <= 0) {
          throw invalid("QUESTION_IMPORT_SCORE_CLOSURE_INVALID", "Scoring point is invalid");
        }
        total = total.add(point.score());
      }
      if (total.compareTo(sub.score()) != 0) {
        throw invalid("QUESTION_IMPORT_SCORE_CLOSURE_INVALID",
            "Scoring-point scores must equal the sub-question score");
      }
    }
    validateKnowledgeBindings(sub.knowledgeBindings());
  }

  private void validateAnswer(String subType, QuestionCreateRequest.AnswerKeyRequest answer,
      Set<String> optionKeys) {
    String value = answer.answerValue();
    switch (subType) {
      case "SINGLE_CHOICE", "MULTI_CHOICE" -> {
        List<String> selected = optionAnswerKeys(answer);
        if (("SINGLE_CHOICE".equals(subType) && selected.size() != 1)
            || ("MULTI_CHOICE".equals(subType) && selected.size() < 2)
            || selected.stream().map(this::upper).anyMatch(key -> !optionKeys.contains(key))
            || new HashSet<>(selected.stream().map(this::upper).toList()).size() != selected.size()) {
          throw invalid("QUESTION_IMPORT_OPTION_REFERENCE_INVALID",
              "Answer optionKeys must be unique options from the same sub-question");
        }
      }
      case "JUDGE" -> {
        if (value == null || !("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
          throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH", "JUDGE requires a boolean answer");
        }
      }
      case "CALCULATION" -> {
        try {
          new BigDecimal(value);
        } catch (RuntimeException exception) {
          throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH",
              "CALCULATION requires a numeric answer");
        }
      }
      case "SHORT_ANSWER", "ESSAY_WRITING" -> {
        if (blank(value)) {
          throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH", "Text answer cannot be blank");
        }
      }
      case "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC",
          "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION" -> {
        JsonNode node = parseJsonObject(answer.answerJson(), "QUESTION_IMPORT_ANSWER_KIND_MISMATCH",
            "Structured answer requires a JSON object");
        if (!node.path("schemaVersion").isTextual() || node.path("schemaVersion").asText().isBlank()) {
          throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH", "Structured answer requires schemaVersion");
        }
      }
      default -> throw new IllegalStateException("Unsupported sub-question type");
    }
  }

  private List<String> optionAnswerKeys(QuestionCreateRequest.AnswerKeyRequest answer) {
    if (!blank(answer.answerJson())) {
      try {
        JsonNode node = objectMapper.readTree(answer.answerJson());
        if (!node.isArray() || node.isEmpty()) {
          throw new IllegalArgumentException();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode key : node) {
          if (!key.isTextual() || blank(key.asText())) {
            throw new IllegalArgumentException();
          }
          result.add(upper(key.asText()));
        }
        return result;
      } catch (Exception exception) {
        throw invalid("QUESTION_IMPORT_ANSWER_KIND_MISMATCH",
            "optionKeys must be a non-empty JSON string array");
      }
    }
    if (blank(answer.answerValue())) {
      return List.of();
    }
    return java.util.Arrays.stream(answer.answerValue().split(","))
        .map(this::upper).filter(value -> !blank(value)).toList();
  }

  private boolean typeMatches(String questionType, String subType) {
    return switch (questionType) {
      case "SINGLE_CHOICE" -> "SINGLE_CHOICE".equals(subType);
      case "MULTI_CHOICE" -> "MULTI_CHOICE".equals(subType);
      case "JUDGE" -> "JUDGE".equals(subType);
      case "ESSAY" -> "ESSAY_WRITING".equals(subType);
      case "CASE" -> Set.of("SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CALCULATION",
          "SHORT_ANSWER", "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH",
          "REQUIREMENT_SPEC", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION").contains(subType);
      default -> false;
    };
  }

  private void validateKnowledgeBindings(List<QuestionCreateRequest.KnowledgeBindingRequest> bindings) {
    for (QuestionCreateRequest.KnowledgeBindingRequest binding : safe(bindings)) {
      if (binding == null || blank(binding.knowledgeType()) || binding.knowledgeId() == null
          || binding.knowledgeId() <= 0) {
        throw invalid("QUESTION_IMPORT_KNOWLEDGE_MAPPING_REQUIRED",
            "Knowledge bindings must resolve to positive platform IDs");
      }
    }
  }

  private JsonNode parseJsonObject(String value, String code, String message) {
    try {
      JsonNode node = objectMapper.readTree(value);
      if (!node.isObject()) {
        throw new IllegalArgumentException();
      }
      return node;
    } catch (Exception exception) {
      throw invalid(code, message);
    }
  }

  private void requireContiguous(List<Integer> values, String field) {
    if (values.isEmpty() || values.stream().anyMatch(value -> value == null || value <= 0)) {
      throw invalid("QUESTION_IMPORT_SORT_ORDER_INVALID", field + " must start at 1");
    }
    List<Integer> sorted = values.stream().sorted().toList();
    for (int index = 0; index < sorted.size(); index++) {
      if (sorted.get(index) != index + 1) {
        throw invalid("QUESTION_IMPORT_SORT_ORDER_INVALID",
            field + " must be unique and contiguous from 1");
      }
    }
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private String upper(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private BusinessException invalid(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }
}
