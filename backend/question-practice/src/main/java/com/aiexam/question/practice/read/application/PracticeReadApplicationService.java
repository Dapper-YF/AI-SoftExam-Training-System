package com.aiexam.question.practice.read.application;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.ResourceNotFoundException;
import com.aiexam.question.practice.attempt.application.PracticeAttemptApplicationService;
import com.aiexam.question.practice.attempt.application.PracticeCurrentUserProvider;
import com.aiexam.question.practice.attempt.domain.QuestionSettlementStatus;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptDetailDto;
import com.aiexam.question.practice.read.dto.ModuleHomeSnapshotDto;
import com.aiexam.question.practice.read.dto.PracticeCatalogDto;
import com.aiexam.question.practice.read.dto.PracticeMistakeDetailDto;
import com.aiexam.question.practice.read.dto.PracticeMistakeDto;
import com.aiexam.question.practice.read.dto.PublicPracticeCatalogDto;
import com.aiexam.question.practice.read.repository.PracticeReadRepository;
import com.aiexam.question.practice.read.repository.PracticeReadRepository.AttemptReadModel;
import com.aiexam.question.practice.read.repository.PracticeReadRepository.MistakeReadModel;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PracticeReadApplicationService {
  private static final List<ModuleHomeSnapshotDto.AvailableEntryDto> AVAILABLE_ENTRIES = List.of(
      new ModuleHomeSnapshotDto.AvailableEntryDto("practice-start", "刷题练习", "/app/practice/start"),
      new ModuleHomeSnapshotDto.AvailableEntryDto("practice-review", "错题回顾", "/app/practice/review"),
      new ModuleHomeSnapshotDto.AvailableEntryDto("practice-reports", "练习报告", "/app/practice/reports"),
      new ModuleHomeSnapshotDto.AvailableEntryDto("practice-resources", "题库资源", "/app/practice/resources"));

  private final PracticeReadRepository repository;
  private final PracticeCurrentUserProvider currentUserProvider;
  private final PracticeAttemptApplicationService attemptService;

  public PracticeReadApplicationService(
      PracticeReadRepository repository,
      PracticeCurrentUserProvider currentUserProvider,
      PracticeAttemptApplicationService attemptService
  ) {
    this.repository = repository;
    this.currentUserProvider = currentUserProvider;
    this.attemptService = attemptService;
  }

  public PageResponse<PracticeMistakeDto> mistakes(
      Long qualificationId,
      Long subjectId,
      String status,
      long pageNo,
      long pageSize
  ) {
    Long userId = currentUserProvider.requireCurrentUser().userId();
    PageResponse<MistakeReadModel> page = repository.findMistakes(
        userId, qualificationId, subjectId, normalizeStatus(status), pageNo(pageNo), pageSize(pageSize));
    return new PageResponse<>(page.items().stream().map(this::mistake).toList(),
        page.pageNo(), page.pageSize(), page.total(), page.hasNext());
  }

  public PracticeMistakeDetailDto mistake(Long questionId) {
    Long userId = currentUserProvider.requireCurrentUser().userId();
    MistakeReadModel mistake = repository.findSettledMistake(userId, questionId)
        .orElseThrow(() -> new ResourceNotFoundException("错题不存在或尚未结算"));
    PracticeAttemptDetailDto attempt = attemptService.get(mistake.lastAttemptId());
    PracticeAttemptDetailDto.QuestionDto question = attempt.questions().stream()
        .filter(candidate -> candidate.questionId().equals(questionId))
        .filter(candidate -> candidate.settlementStatus() == QuestionSettlementStatus.SETTLED)
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("错题作答快照不存在"));
    return new PracticeMistakeDetailDto(mistake(mistake), question);
  }

  public ModuleHomeSnapshotDto moduleHome() {
    Long userId = currentUserProvider.requireCurrentUser().userId();
    var snapshot = repository.moduleHome(userId);
    List<ModuleHomeSnapshotDto.RecentPracticeDto> recentPractice = snapshot.recentPractice().stream()
        .map(this::recentPractice)
        .toList();
    List<ModuleHomeSnapshotDto.RecentScoreDto> recentScores = snapshot.recentScores().stream()
        .map(this::recentScore)
        .toList();
    List<ModuleHomeSnapshotDto.RecentTaskDto> recentTasks = snapshot.recentPractice().stream()
        .map(this::recentTask)
        .toList();
    List<ModuleHomeSnapshotDto.PlatformStatDto> stats = List.of(
        new ModuleHomeSnapshotDto.PlatformStatDto("practice-count", "练习次数",
            Long.toString(snapshot.attemptCount())),
        new ModuleHomeSnapshotDto.PlatformStatDto("mistake-count", "待复习错题",
            Long.toString(snapshot.mistakeCount())),
        new ModuleHomeSnapshotDto.PlatformStatDto("favorite-count", "收藏题目",
            Long.toString(snapshot.favoriteCount())));
    return new ModuleHomeSnapshotDto(recentTasks, stats, "实时练习数据",
        snapshot.currentQualificationId(), recentPractice, recentScores,
        snapshot.mistakeCount(), snapshot.favoriteCount(), AVAILABLE_ENTRIES);
  }

  public PracticeCatalogDto catalog(Long qualificationId) {
    var snapshot = repository.catalog(qualificationId);
    Map<Long, List<PracticeReadRepository.CatalogQuestionReadModel>> bySubject = new LinkedHashMap<>();
    snapshot.questions().forEach(item ->
        bySubject.computeIfAbsent(item.subjectId(), ignored -> new java.util.ArrayList<>()).add(item));
    List<PracticeCatalogDto.SubjectDto> subjects = bySubject.values().stream()
        .map(rows -> {
          var first = rows.get(0);
          List<PracticeCatalogDto.QuestionTypeDto> questionTypes = rows.stream()
              .map(row -> new PracticeCatalogDto.QuestionTypeDto(row.questionType(), row.questionCount()))
              .toList();
          long questionCount = rows.stream().mapToLong(PracticeReadRepository.CatalogQuestionReadModel::questionCount)
              .sum();
          return new PracticeCatalogDto.SubjectDto(first.subjectId(), first.subjectName(), first.subjectType(),
              questionCount, questionTypes);
        })
        .toList();
    long questionCount = subjects.stream().mapToLong(PracticeCatalogDto.SubjectDto::questionCount).sum();
    return new PracticeCatalogDto(qualificationId, questionCount, snapshot.paperCount(), snapshot.paperYears(),
        subjects);
  }

  public PublicPracticeCatalogDto publicCatalog(Long qualificationId) {
    PracticeCatalogDto catalog = catalog(qualificationId);
    List<PublicPracticeCatalogDto.SubjectDto> subjects = catalog.subjects().stream()
        .map(subject -> {
          boolean available = subject.questionCount() > 0;
          return new PublicPracticeCatalogDto.SubjectDto(subject.subjectId(), subject.subjectName(),
              subject.subjectType(), available, available ? null : "NO_PUBLISHED_QUESTION");
        })
        .toList();
    boolean paperAvailable = catalog.paperCount() > 0;
    boolean available = subjects.stream().anyMatch(PublicPracticeCatalogDto.SubjectDto::available)
        || paperAvailable;
    return new PublicPracticeCatalogDto(qualificationId, available,
        available ? null : "NO_PUBLISHED_QUESTION",
        subjects,
        new PublicPracticeCatalogDto.PaperCatalogDto(paperAvailable,
            paperAvailable ? null : "PAPER_ASSEMBLING"));
  }

  private PracticeMistakeDto mistake(MistakeReadModel item) {
    String title = item.questionTitle() == null || item.questionTitle().isBlank()
        ? "题目 #" + item.questionId()
        : item.questionTitle();
    return new PracticeMistakeDto(item.questionId(), item.qualificationId(), item.subjectId(),
        item.questionType(), title, item.lastAttemptId(), item.mistakeCount(), item.lastWrongAt(), item.status());
  }

  private ModuleHomeSnapshotDto.RecentPracticeDto recentPractice(AttemptReadModel item) {
    return new ModuleHomeSnapshotDto.RecentPracticeDto(item.attemptId(), item.qualificationId(), item.subjectId(),
        item.mode(), item.status(), item.questionCount(), item.startedAt(), item.submittedAt(), item.updatedAt());
  }

  private ModuleHomeSnapshotDto.RecentScoreDto recentScore(AttemptReadModel item) {
    return new ModuleHomeSnapshotDto.RecentScoreDto(item.attemptId(), item.qualificationId(), item.subjectId(),
        item.status(), item.totalScore(), item.maxScore(), item.submittedAt());
  }

  private ModuleHomeSnapshotDto.RecentTaskDto recentTask(AttemptReadModel item) {
    String score = item.totalScore() == null ? null : decimal(item.totalScore()) + " / " + decimal(item.maxScore());
    return new ModuleHomeSnapshotDto.RecentTaskDto(
        item.attemptId().toString(),
        "练习 #" + item.attemptId(),
        "practice",
        item.status(),
        item.updatedAt(),
        "/app/practice/attempts/" + item.attemptId(),
        item.questionCount() + " 题",
        score);
  }

  private String decimal(BigDecimal value) {
    return value == null ? "-" : value.stripTrailingZeros().toPlainString();
  }

  private String normalizeStatus(String value) {
    return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private long pageNo(long value) {
    return Math.max(1, value);
  }

  private long pageSize(long value) {
    return value < 1 ? 20 : Math.min(value, 100);
  }
}
