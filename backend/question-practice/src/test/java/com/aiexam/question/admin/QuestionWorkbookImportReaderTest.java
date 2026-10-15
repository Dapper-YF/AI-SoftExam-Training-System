package com.aiexam.question.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.question.admin.importing.StrictQuestionWorkbookValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestionWorkbookImportReaderTest {
  private static final Map<String, List<String>> HEADERS = headers();

  @TempDir
  Path importRoot;

  @Test
  void parsesFrozenNineSheetWorkbookIntoCanonicalCandidates() throws Exception {
    Path workbook = workbook("valid.xlsx", ignored -> { });
    var reader = reader();

    var validation = reader.validate(workbook.toString());
    var batch = reader.readBatch(workbook.toString());

    assertThat(validation.compatible()).isTrue();
    assertThat(validation.sheetCount()).isEqualTo(9);
    assertThat(validation.columnCount()).isEqualTo(61);
    assertThat(batch.items()).hasSize(2);
    assertThat(batch.items()).extracting(item -> item.content().payload().questionType())
        .containsExactly("SINGLE_CHOICE", "CASE");
    assertThat(batch.items()).allSatisfy(item -> {
      assertThat(item.source().sourceFile()).isEqualTo(workbook.toRealPath().toString());
      assertThat(item.source().sourceSha256()).hasSize(64);
      assertThat(item.source().copyrightStatus()).isEqualTo("UNVERIFIED");
      assertThat(item.source().usageRestriction()).isEqualTo("INTERNAL_ONLY");
    });
    assertThat(batch.items().get(1).content().scoringPoints()).hasSize(2);
  }

  @Test
  void enforcesChildQuestionForeignKey() throws Exception {
    assertFailure("child-fk.xlsx", workbook -> cell(workbook, "Options", 2, 0).setCellValue("Q_MISSING"),
        "QUESTION_IMPORT_ORPHAN_FOREIGN_KEY");
  }

  @Test
  void enforcesCompoundSubQuestionForeignKey() throws Exception {
    assertFailure("compound-fk.xlsx", workbook -> cell(workbook, "Options", 2, 1).setCellValue(99),
        "QUESTION_IMPORT_ORPHAN_SUBQUESTION");
  }

  @Test
  void enforcesBusinessKeyUniqueness() throws Exception {
    assertFailure("duplicate-question.xlsx", workbook -> copyRow(workbook.getSheet("Questions"), 2, 4),
        "QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY");
  }

  @Test
  void enforcesContiguousSortOrder() throws Exception {
    assertFailure("sort-gap.xlsx", workbook -> cell(workbook, "Options", 3, 4).setCellValue(3),
        "QUESTION_IMPORT_SORT_ORDER_INVALID");
  }

  @Test
  void enforcesOptionKeyUniqueness() throws Exception {
    assertFailure("duplicate-option.xlsx", workbook -> cell(workbook, "Options", 3, 2).setCellValue("A"),
        "QUESTION_IMPORT_DUPLICATE_BUSINESS_KEY");
  }

  @Test
  void enforcesAnswerPayloadDiscriminator() throws Exception {
    assertFailure("answer-discriminator.xlsx",
        workbook -> cell(workbook, "Answers", 2, 4).setCellValue(true),
        "QUESTION_IMPORT_ANSWER_KIND_MISMATCH");
  }

  @Test
  void enforcesAnswerOptionReference() throws Exception {
    assertFailure("option-reference.xlsx",
        workbook -> cell(workbook, "Answers", 2, 3).setCellValue("[\"Z\"]"),
        "QUESTION_IMPORT_OPTION_REFERENCE_INVALID");
  }

  @Test
  void enforcesQuestionSubQuestionTypeAndScoringMatrix() throws Exception {
    assertFailure("type-matrix.xlsx",
        workbook -> cell(workbook, "SubQuestions", 2, 2).setCellValue("JUDGE"),
        "QUESTION_IMPORT_TYPE_COMBINATION_INVALID");
  }

  @Test
  void enforcesCaseScoringPointClosure() throws Exception {
    assertFailure("score-closure.xlsx",
        workbook -> cell(workbook, "ScoringPoints", 2, 4).setCellValue(4),
        "QUESTION_IMPORT_SCORE_CLOSURE_INVALID");
  }

  @Test
  void enforcesUniformQualificationAndSubjectScope() throws Exception {
    assertFailure("scope.xlsx",
        workbook -> cell(workbook, "Questions", 3, 1).setCellValue("OTHER_QUAL"),
        "QUESTION_IMPORT_SCOPE_MISMATCH");
  }

  @Test
  void rejectsMissingSheetAndFormula() throws Exception {
    assertFailure("missing.xlsx", workbook -> workbook.removeSheetAt(workbook.getSheetIndex("Answers")),
        "QUESTION_IMPORT_MISSING_SHEET");
    assertFailure("formula.xlsx", workbook -> cell(workbook, "Questions", 2, 4).setCellFormula("1+1"),
        "QUESTION_IMPORT_FILE_UNSAFE");
  }

  private void assertFailure(String name, Consumer<XSSFWorkbook> mutation, String code)
      throws Exception {
    Path path = workbook(name, mutation);
    assertThatThrownBy(() -> reader().readBatch(path.toString()))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.code()).isEqualTo(code));
  }

  private StrictQuestionWorkbookValidator reader() {
    return new StrictQuestionWorkbookValidator(importRoot,
        new ObjectMapper().findAndRegisterModules());
  }

  private Path workbook(String name, Consumer<XSSFWorkbook> mutation) throws IOException {
    Path path = importRoot.resolve(name);
    writeValidWorkbook(path, mutation);
    return path;
  }

  static void writeValidWorkbook(Path path, Consumer<XSSFWorkbook> mutation) throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      for (Map.Entry<String, List<String>> entry : HEADERS.entrySet()) {
        Sheet sheet = workbook.createSheet(entry.getKey());
        if ("Questions".equals(entry.getKey())) {
          sheet.createRow(0).createCell(0).setCellValue("question-import/1.0");
        }
        put(sheet.createRow(1), entry.getValue().toArray());
      }
      populate(workbook);
      mutation.accept(workbook);
      try (OutputStream output = Files.newOutputStream(path)) {
        workbook.write(output);
      }
    }
  }

  private static void populate(XSSFWorkbook workbook) {
    append(workbook, "Questions", "Q_SINGLE_001", "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE",
        "SINGLE_CHOICE", "Choose one", null, "EASY", "ORIGINAL", "REAL_PAPER:2026:1",
        "SYLLABUS-2026", "ORIGINAL_DECLARED", null);
    append(workbook, "Questions", "Q_CASE_001", "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE",
        "CASE", "Case", 2026, "MEDIUM", "ORIGINAL", "MOCK_PAPER:2026:1",
        "SYLLABUS-2026", "ORIGINAL_DECLARED", null);

    append(workbook, "Stems", "Q_SINGLE_001", "S1", "MAIN_STEM", "Choose one.", null, 1);
    append(workbook, "Stems", "Q_CASE_001", "M1", "CASE_MATERIAL", "Shared case.", null, 1);

    append(workbook, "SubQuestions", "Q_SINGLE_001", 1, "SINGLE_CHOICE", "Choose one.",
        2.00, "OPTION_KEYS", "AUTO", 1);
    append(workbook, "SubQuestions", "Q_CASE_001", 1, "SHORT_ANSWER", "State one point.",
        5.00, "TEXT", "MANUAL", 1);
    append(workbook, "SubQuestions", "Q_CASE_001", 2, "CALCULATION", "Calculate.",
        5.00, "NUMBER", "MANUAL", 2);

    append(workbook, "Options", "Q_SINGLE_001", 1, "A", "Answer A", 1);
    append(workbook, "Options", "Q_SINGLE_001", 1, "B", "Answer B", 2);

    append(workbook, "Answers", "Q_SINGLE_001", 1, "OPTION_KEYS", "[\"A\"]", null,
        null, null, 1);
    append(workbook, "Answers", "Q_CASE_001", 1, "TEXT", null, null,
        "Reference point.", null, 1);
    append(workbook, "Answers", "Q_CASE_001", 2, "NUMBER", null, null,
        null, 10.00, 1);

    append(workbook, "Solutions", "Q_SINGLE_001", 1, "ANALYSIS", "A is correct.", 1);
    append(workbook, "Solutions", "Q_CASE_001", 1, "REFERENCE_ANSWER", "Reference point.", 1);
    append(workbook, "Solutions", "Q_CASE_001", 2, "REFERENCE_ANSWER", "10.00", 1);

    append(workbook, "ScoringRules", "Q_SINGLE_001", 1, "AUTO",
        "{\"mode\":\"EXACT\"}", 1);
    append(workbook, "ScoringRules", "Q_CASE_001", 1, "MANUAL",
        "{\"mode\":\"MANUAL\"}", 1);
    append(workbook, "ScoringRules", "Q_CASE_001", 2, "MANUAL",
        "{\"mode\":\"MANUAL\"}", 1);

    append(workbook, "ScoringPoints", "Q_CASE_001", 1, "P1", "Point one", 5.00, 1);
    append(workbook, "ScoringPoints", "Q_CASE_001", 2, "P1", "Correct result", 5.00, 1);
  }

  private static void append(XSSFWorkbook workbook, String sheetName, Object... values) {
    Sheet sheet = workbook.getSheet(sheetName);
    put(sheet.createRow(sheet.getLastRowNum() + 1), values);
  }

  private static void put(Row row, Object... values) {
    for (int index = 0; index < values.length; index++) {
      Object value = values[index];
      if (value == null) {
        continue;
      }
      Cell cell = row.createCell(index);
      if (value instanceof Number number) {
        cell.setCellValue(number.doubleValue());
      } else if (value instanceof Boolean bool) {
        cell.setCellValue(bool);
      } else {
        cell.setCellValue(value.toString());
      }
    }
  }

  private Cell cell(XSSFWorkbook workbook, String sheet, int row, int column) {
    Row target = workbook.getSheet(sheet).getRow(row);
    Cell cell = target.getCell(column);
    return cell == null ? target.createCell(column) : cell;
  }

  private void copyRow(Sheet sheet, int sourceIndex, int targetIndex) {
    Row source = sheet.getRow(sourceIndex);
    Row target = sheet.createRow(targetIndex);
    for (Cell sourceCell : source) {
      Cell targetCell = target.createCell(sourceCell.getColumnIndex(), sourceCell.getCellType());
      switch (sourceCell.getCellType()) {
        case STRING -> targetCell.setCellValue(sourceCell.getStringCellValue());
        case NUMERIC -> targetCell.setCellValue(sourceCell.getNumericCellValue());
        case BOOLEAN -> targetCell.setCellValue(sourceCell.getBooleanCellValue());
        default -> { }
      }
    }
  }

  private static Map<String, List<String>> headers() {
    Map<String, List<String>> result = new LinkedHashMap<>();
    result.put("Questions", List.of("externalKey", "qualificationCode", "subjectCode",
        "questionType", "title", "yearNo", "difficulty", "sourceType", "sourceRef",
        "syllabusBasis", "copyrightStatus", "usageRestriction"));
    result.put("Stems", List.of("externalKey", "stemKey", "stemType", "content",
        "resourceId", "sortOrder"));
    result.put("SubQuestions", List.of("externalKey", "subQuestionNo", "subQuestionType",
        "content", "score", "answerFormat", "gradingMode", "sortOrder"));
    result.put("Options", List.of("externalKey", "subQuestionNo", "optionKey", "content",
        "sortOrder"));
    result.put("Answers", List.of("externalKey", "subQuestionNo", "answerKind", "optionKeys",
        "booleanValue", "textValue", "numberValue", "sortOrder"));
    result.put("Solutions", List.of("externalKey", "subQuestionNo", "solutionType", "content",
        "sortOrder"));
    result.put("ScoringRules", List.of("externalKey", "subQuestionNo", "ruleType", "ruleJson",
        "sortOrder"));
    result.put("ScoringPoints", List.of("externalKey", "subQuestionNo", "pointCode", "pointText",
        "score", "sortOrder"));
    result.put("KnowledgeBindings", List.of("externalKey", "subQuestionNo", "knowledgeType",
        "knowledgeCode", "weight", "sortOrder"));
    return result;
  }
}
