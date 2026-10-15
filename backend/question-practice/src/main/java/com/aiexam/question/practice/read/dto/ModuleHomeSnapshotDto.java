package com.aiexam.question.practice.read.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ModuleHomeSnapshotDto(
    List<RecentTaskDto> recentTasks,
    List<PlatformStatDto> stats,
    String sourceLabel,
    Long currentQualificationId,
    List<RecentPracticeDto> recentPractice,
    List<RecentScoreDto> recentScores,
    long mistakeCount,
    long favoriteCount,
    List<AvailableEntryDto> availableEntries
) {
  public record RecentTaskDto(
      String id,
      String title,
      String product,
      String description,
      Instant updatedAt,
      String routePath,
      String progress,
      String score
  ) {
  }

  public record PlatformStatDto(String id, String label, String value) {
  }

  public record RecentPracticeDto(
      Long attemptId,
      Long qualificationId,
      Long subjectId,
      String mode,
      String status,
      int questionCount,
      Instant startedAt,
      Instant submittedAt,
      Instant updatedAt
  ) {
  }

  public record RecentScoreDto(
      Long attemptId,
      Long qualificationId,
      Long subjectId,
      String status,
      BigDecimal totalScore,
      BigDecimal maxScore,
      Instant submittedAt
  ) {
  }

  public record AvailableEntryDto(String id, String label, String routePath) {
  }
}
