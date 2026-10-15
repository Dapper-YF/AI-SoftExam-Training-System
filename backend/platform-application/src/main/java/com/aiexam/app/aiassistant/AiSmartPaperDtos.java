package com.aiexam.app.aiassistant;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AiSmartPaperDtos {
  private AiSmartPaperDtos() { }

  public record CreateRequest(Long qualificationId, Long subjectId, Integer questionCount, LocalDate targetExamDate) { }
  public record PlanStage(String stage, LocalDate startDate, LocalDate endDate, String focus) { }
  public record SmartPaperDto(Long id, String paperName, Long qualificationId, Long subjectId, List<Long> questionIds,
                              List<String> weakPoints, List<PlanStage> plan, Instant createdAt) { }
}
