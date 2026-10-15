package com.aiexam.question.admin.dto;

import java.util.List;

public record QuestionDocumentPreviewDto(
    String uploadToken,
    String fileName,
    String format,
    int pageCount,
    String fileSha256,
    String extractedText,
    List<CandidateQuestion> questions,
    List<CandidateAsset> assets,
    List<CandidateAsset> unassignedAssets,
    Diagnostics diagnostics,
    List<String> warnings
) {
  public record CandidateQuestion(
      int questionNo,
      String questionType,
      String content,
      List<String> options,
      String answer,
      String solution,
      java.math.BigDecimal score,
      List<String> imageFileNames,
      List<String> warnings
  ) {}

  public record CandidateAsset(
      String assetToken,
      String sourceRelationId,
      int sourceOrder,
      String fileName,
      String previewUrl,
      String mediaType,
      long byteSize,
      Integer width,
      Integer height,
      Integer assignedQuestionNo
  ) {}

  public record Diagnostics(
      int detectedQuestionCount,
      int imageCount,
      int answerCount,
      List<Integer> missingQuestionNos,
      List<Integer> duplicateQuestionNos,
      List<Integer> questionsWithoutOptions,
      List<Integer> questionsWithoutAnswers,
      int unassignedImageCount,
      boolean readyForReview
  ) {}
}
