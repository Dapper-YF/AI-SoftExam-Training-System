package com.aiexam.question.practice.read.repository;

import com.aiexam.common.api.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PracticeReadRepository {
  PageResponse<MistakeReadModel> findMistakes(
      Long userId,
      Long qualificationId,
      Long subjectId,
      String status,
      long pageNo,
      long pageSize
  );

  Optional<MistakeReadModel> findSettledMistake(Long userId, Long questionId);

  ModuleHomeReadModel moduleHome(Long userId);

  PracticeCatalogReadModel catalog(Long qualificationId);

  record MistakeReadModel(
      Long questionId,
      Long qualificationId,
      Long subjectId,
      String questionType,
      String questionTitle,
      Long lastAttemptId,
      int mistakeCount,
      Instant lastWrongAt,
      String status
  ) {
  }

  record AttemptReadModel(
      Long attemptId,
      Long qualificationId,
      Long subjectId,
      String mode,
      String status,
      int questionCount,
      Instant startedAt,
      Instant submittedAt,
      Instant updatedAt,
      BigDecimal totalScore,
      BigDecimal maxScore
  ) {
  }

  record ModuleHomeReadModel(
      Long currentQualificationId,
      List<AttemptReadModel> recentPractice,
      List<AttemptReadModel> recentScores,
      long attemptCount,
      long mistakeCount,
      long favoriteCount
  ) {
  }

  record CatalogQuestionReadModel(
      Long subjectId,
      String subjectName,
      String subjectType,
      String questionType,
      long questionCount
  ) {
  }

  record PracticeCatalogReadModel(
      List<CatalogQuestionReadModel> questions,
      long paperCount,
      List<Integer> paperYears
  ) {
  }
}
