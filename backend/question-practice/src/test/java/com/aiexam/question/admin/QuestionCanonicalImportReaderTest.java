package com.aiexam.question.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.question.admin.importing.QuestionCanonicalImportReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QuestionCanonicalImportReaderTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir
  Path root;

  @Test
  void readsFrozenQuestionImportJsonDocumentAndComputesServerSideHash() throws Exception {
    ObjectNode document = canonicalJson();
    document.put("sourceFileName", "2021年上午真题.docx");
    document.put("sourceFileSha256", "a".repeat(64));
    Path source = upload("questions.json", document.toString());

    var batch = reader().read(source.toString());

    assertThat(batch.sourceFileName()).isEqualTo("2021年上午真题.docx");
    assertThat(batch.sourceFileSha256()).isEqualTo("a".repeat(64));
    assertThat(batch.items()).singleElement().satisfies(item -> {
      assertThat(item.externalKey()).isEqualTo("JSON_SINGLE_001");
      assertThat(item.contentHash()).matches("[0-9a-f]{64}");
      assertThat(item.qualificationCode()).isEqualTo("SOFT_EXAM_ADVANCED");
      assertThat(item.subjectCode()).isEqualTo("COMPREHENSIVE_KNOWLEDGE");
      assertThat(item.content().payload().questionType()).isEqualTo("SINGLE_CHOICE");
      assertThat(item.source().sourceType()).isEqualTo("ORIGINAL");
      assertThat(item.source().copyrightStatus()).isEqualTo("ORIGINAL_DECLARED");
      assertThat(item.source().usageRestriction()).isEqualTo("NONE");
    });
  }

  @Test
  void keepsControlledCanonicalJsonLinesBackwardCompatible() throws Exception {
    Path source = upload("questions.jsonl", legacyJsonLine());

    var batch = reader().read(source.toString());

    assertThat(batch.items()).singleElement()
        .satisfies(item -> assertThat(item.externalKey()).isEqualTo("LEGACY_JSONL_001"));
  }

  @Test
  void acceptsGroupedSingleChoiceWithOrderedSubQuestions() throws Exception {
    ObjectNode line = (ObjectNode) JSON.readTree(legacyJsonLine());
    ArrayNode subQuestions = ((ObjectNode) line.path("question")).withArray("subQuestions");
    ObjectNode second = subQuestions.get(0).deepCopy();
    second.put("subQuestionNo", 2);
    second.put("sortOrder", 2);
    second.put("content", "第2空");
    subQuestions.add(second);
    Path source = upload("grouped-questions.jsonl", line.toString());

    var batch = reader().read(source.toString());

    assertThat(batch.items()).singleElement().satisfies(item ->
        assertThat(item.content().payload().subQuestions()).hasSize(2));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("negativeVectors")
  void rejectsFrozenJsonNegativeVectors(
      String name,
      Consumer<ObjectNode> mutation,
      String expectedCode
  ) throws Exception {
    ObjectNode document = canonicalJson();
    mutation.accept(document);
    Path source = upload("questions.json", document.toString());

    assertThatThrownBy(() -> reader().read(source.toString()))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.code()).isEqualTo(expectedCode));
  }

  private static Stream<Arguments> negativeVectors() {
    return Stream.of(
        Arguments.of("unknown-root-field", (Consumer<ObjectNode>) root -> root.put("unknown", true),
            "QUESTION_IMPORT_UNKNOWN_FIELD"),
        Arguments.of("unknown-nested-field", (Consumer<ObjectNode>) root ->
                ((ObjectNode) question(root).withArray("stems").get(0)).put("unexpected", true),
            "QUESTION_IMPORT_UNKNOWN_FIELD"),
        Arguments.of("single-answer-count", (Consumer<ObjectNode>) root -> {
          ArrayNode answers = sub(root).withArray("answers");
          answers.add(answers.get(0).deepCopy());
        }, "QUESTION_IMPORT_SINGLE_ANSWER_COUNT"),
        Arguments.of("multi-answer-min-two", (Consumer<ObjectNode>) root -> {
          question(root).put("questionType", "MULTI_CHOICE");
          sub(root).put("subQuestionType", "MULTI_CHOICE");
          ((ObjectNode) sub(root).withArray("scoringRules").get(0).path("ruleJson"))
              .put("mode", "SET_EQUAL");
        }, "QUESTION_IMPORT_MULTI_ANSWER_MIN_TWO"),
        Arguments.of("question-subtype-mismatch", (Consumer<ObjectNode>) root ->
                sub(root).put("subQuestionType", "JUDGE"),
            "QUESTION_IMPORT_TYPE_COMBINATION_INVALID"),
        Arguments.of("answer-format-mismatch", (Consumer<ObjectNode>) root ->
                sub(root).put("answerFormat", "BOOLEAN"),
            "QUESTION_IMPORT_ANSWER_FORMAT_MISMATCH"),
        Arguments.of("answer-kind-mismatch", (Consumer<ObjectNode>) root ->
                ((ObjectNode) sub(root).withArray("answers").get(0)).put("answerKind", "BOOLEAN"),
            "QUESTION_IMPORT_ANSWER_KIND_MISMATCH"),
        Arguments.of("choice-options-empty", (Consumer<ObjectNode>) root ->
                sub(root).putArray("options"),
            "QUESTION_IMPORT_OPTIONS_INVALID"),
        Arguments.of("scoring-mode-mismatch", (Consumer<ObjectNode>) root ->
                ((ObjectNode) sub(root).withArray("scoringRules").get(0).path("ruleJson"))
                    .put("mode", "MANUAL"),
            "QUESTION_IMPORT_SCORING_MODE_MISMATCH")
    );
  }

  private QuestionCanonicalImportReader reader() {
    return new QuestionCanonicalImportReader(root, JSON);
  }

  private Path upload(String name, String content) throws Exception {
    Path uploads = Files.createDirectories(root.resolve(".uploads"));
    Path source = uploads.resolve(UUID.randomUUID() + "--" + name);
    Files.writeString(source, content, StandardCharsets.UTF_8);
    return source;
  }

  private static ObjectNode canonicalJson() {
    try {
      return (ObjectNode) JSON.readTree("""
          {
            "schemaVersion":"question-import/1.0",
            "qualificationCode":"SOFT_EXAM_ADVANCED",
            "subjectCode":"COMPREHENSIVE_KNOWLEDGE",
            "questions":[{
              "externalKey":"JSON_SINGLE_001",
              "questionType":"SINGLE_CHOICE",
              "title":"JSON 单选题",
              "yearNo":2026,
              "difficulty":"MEDIUM",
              "source":{
                "sourceType":"ORIGINAL",
                "sourceRef":null,
                "syllabusBasis":"考试大纲",
                "copyrightStatus":"ORIGINAL_DECLARED",
                "usageRestriction":null
              },
              "stems":[{
                "stemKey":"S1","stemType":"MAIN_STEM","content":"题干",
                "resourceId":null,"sortOrder":1
              }],
              "subQuestions":[{
                "subQuestionNo":1,"subQuestionType":"SINGLE_CHOICE","content":"请选择",
                "score":2.00,"answerFormat":"OPTION_KEYS","gradingMode":"AUTO","sortOrder":1,
                "options":[
                  {"optionKey":"A","content":"正确项","sortOrder":1},
                  {"optionKey":"B","content":"干扰项","sortOrder":2}
                ],
                "answers":[{
                  "answerKind":"OPTION_KEYS","optionKeys":["A"],"booleanValue":null,
                  "textValue":null,"numberValue":null,"sortOrder":1
                }],
                "solutions":[{"solutionType":"ANALYSIS","content":"解析","sortOrder":1}],
                "scoringRules":[{
                  "ruleType":"AUTO",
                  "ruleJson":{"mode":"EXACT","tolerance":null,"caseSensitive":null},
                  "sortOrder":1
                }],
                "scoringPoints":[],
                "knowledgeBindings":[]
              }],
              "knowledgeBindings":[]
            }]
          }
          """);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static ObjectNode question(ObjectNode root) {
    return (ObjectNode) root.withArray("questions").get(0);
  }

  private static ObjectNode sub(ObjectNode root) {
    return (ObjectNode) question(root).withArray("subQuestions").get(0);
  }

  private String legacyJsonLine() {
    String document = """
        {"schemaVersion":"question-import/1.0","recordType":"QUESTION_PREFLIGHT",
         "importStatus":"PREFLIGHT","rightsStatus":"UNVERIFIED",
         "qualificationCode":"SOFT_EXAM_ADVANCED","subjectCode":"COMPREHENSIVE_KNOWLEDGE",
         "question":{"externalKey":"LEGACY_JSONL_001","contentHash":"%s",
           "questionType":"SINGLE_CHOICE","title":"Legacy","yearNo":2026,"difficulty":"MEDIUM",
           "source":{"sourceType":"SOURCE_DOCUMENT_IMPORT","sourceRef":"legacy",
             "copyrightStatus":"UNVERIFIED","usageRestriction":"internal publication prohibited",
             "syllabusBasis":"legacy"},
           "stems":[{"stemType":"MAIN_STEM","content":"题干","sortOrder":1,"stemKey":"S1"}],
           "subQuestions":[{"subQuestionNo":1,"subQuestionType":"SINGLE_CHOICE",
             "content":"请选择","score":1.00,"answerFormat":"OPTION_KEYS","gradingMode":"AUTO",
             "sortOrder":1,"options":[{"optionKey":"A","content":"A","sortOrder":1},
               {"optionKey":"B","content":"B","sortOrder":2}],
             "answers":[{"answerKind":"OPTION_KEYS","optionKeys":["A"]}],
             "solutions":[{"solutionType":"ANALYSIS","content":"解析","sortOrder":1}],
             "scoringRules":[{"ruleType":"AUTO","ruleJson":{"mode":"EXACT"},"sortOrder":1}],
             "scoringPoints":[],"knowledgeBindings":[]}],"knowledgeBindings":[]},
         "quality":{"importEligible":true,"validationErrors":[]},
         "sourceTrace":{"documentSha256":"%s","sourcePath":"legacy.jsonl",
           "locator":"line:1","questionNo":1},
         "paper":{"paperKind":"REAL_PAPER"}}
        """.formatted("a".repeat(64), "b".repeat(64));
    try {
      return JSON.readTree(document).toString();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
