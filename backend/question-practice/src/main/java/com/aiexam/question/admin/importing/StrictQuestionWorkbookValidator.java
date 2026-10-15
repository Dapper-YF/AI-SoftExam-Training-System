package com.aiexam.question.admin.importing;

import com.aiexam.question.admin.dto.QuestionWorkbookValidationDto;
import com.aiexam.question.admin.importing.QuestionCanonicalImportReader.CanonicalBatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;

/** Backwards-compatible facade for the strict POI workbook reader. */
public class StrictQuestionWorkbookValidator {
  private final QuestionWorkbookImportReader reader;

  public StrictQuestionWorkbookValidator(Path allowedRoot) {
    this(allowedRoot, new ObjectMapper().findAndRegisterModules());
  }

  public StrictQuestionWorkbookValidator(Path allowedRoot, ObjectMapper objectMapper) {
    this.reader = new QuestionWorkbookImportReader(allowedRoot, objectMapper);
  }

  public QuestionWorkbookValidationDto validate(String inputPath) {
    return reader.read(inputPath).validation();
  }

  public CanonicalBatch readBatch(String inputPath) {
    return reader.read(inputPath).batch();
  }
}
