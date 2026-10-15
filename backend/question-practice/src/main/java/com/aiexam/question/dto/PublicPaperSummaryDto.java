package com.aiexam.question.dto;

public record PublicPaperSummaryDto(
    Long paperId,
    Long qualificationId,
    Long subjectId,
    String paperName,
    String paperType,
    Integer yearNo,
    Integer durationMinutes,
    String status
) {
  public static PublicPaperSummaryDto from(PaperSummaryDto paper) {
    return new PublicPaperSummaryDto(
        paper.paperId(),
        paper.qualificationId(),
        paper.subjectId(),
        paper.paperName(),
        paper.paperType(),
        paper.yearNo(),
        paper.durationMinutes(),
        paper.status());
  }
}
