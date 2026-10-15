package com.aiexam.question.practice.attempt;

import com.aiexam.foundation.application.FoundationApplicationService;
import com.aiexam.question.practice.attempt.application.PracticePrincipal;
import com.aiexam.question.practice.attempt.domain.PracticeAnswerPayload;
import com.aiexam.question.practice.attempt.domain.PracticeQuestionSnapshot;
import com.aiexam.question.practice.attempt.domain.QuestionSettlementStatus;
import com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest;
import com.aiexam.question.practice.attempt.dto.SavePracticeAnswerRequest;
import com.aiexam.question.practice.attempt.dto.SettlePracticeQuestionRequest;
import com.aiexam.question.practice.attempt.dto.SubmitPracticeAttemptRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PracticeAttemptFixtures {
  private PracticeAttemptFixtures() {
  }

  static PracticePrincipal student() {
    return new PracticePrincipal(2L, "dev-student", Set.of());
  }

  static FoundationApplicationService permissiveFoundation() {
    return new FoundationApplicationService(null) {
      @Override
      public void validateQuestionScope(Long qualificationId, Long subjectId) {
      }

      @Override
      public void validatePaperScope(Long qualificationId, Long subjectId) {
      }

      @Override
      public void validateQuestionKnowledge(
          Long qualificationId,
          Long subjectId,
          String knowledgeType,
          Long knowledgeId
      ) {
      }
    };
  }

  static CreatePracticeAttemptRequest createRequest(UUID mutation, String feedbackMode) {
    return new CreatePracticeAttemptRequest(mutation, 1L, 11L, "PRACTICE", feedbackMode, 1,
        new CreatePracticeAttemptRequest.Source("KNOWLEDGE", "CHAPTER", List.of(101L), null, null));
  }

  static SavePracticeAnswerRequest answer(UUID mutation, long baseVersion, String optionKey) {
    return new SavePracticeAnswerRequest(mutation, baseVersion,
        new PracticeAnswerPayload(optionKey, null, null, null), false, 8);
  }

  static SettlePracticeQuestionRequest settle(UUID mutation, long baseVersion) {
    return new SettlePracticeQuestionRequest(mutation, baseVersion);
  }

  static SubmitPracticeAttemptRequest submit(UUID mutation, long baseVersion) {
    return new SubmitPracticeAttemptRequest(mutation, baseVersion);
  }

  static PracticeQuestionSnapshot singleChoiceQuestion() {
    return new PracticeQuestionSnapshot(1001L, 1, 3, "SINGLE_CHOICE", "进度管理单选题",
        List.of(new PracticeQuestionSnapshot.Stem(501L, 1, "以下哪项属于进度管理工具？")),
        List.of(new PracticeQuestionSnapshot.SubQuestion(9001L, "SINGLE_CHOICE", "选择最佳答案",
            List.of(
                new PracticeQuestionSnapshot.Option("A", "甘特图", 1),
                new PracticeQuestionSnapshot.Option("B", "鱼骨图", 2)),
            new PracticeAnswerPayload("A", null, null, null),
            "甘特图用于展示计划进度和实际进度。", new BigDecimal("2.00"))),
        List.of(new PracticeQuestionSnapshot.KnowledgeBinding("CHAPTER", 101L, "项目进度管理")),
        QuestionSettlementStatus.UNSETTLED, 0, BigDecimal.ZERO, 0, 0, 0);
  }

  static PracticeQuestionSnapshot essayQuestion() {
    return new PracticeQuestionSnapshot(1002L, 2, 5, "ESSAY", "论文练习题",
        List.of(new PracticeQuestionSnapshot.Stem(502L, 1, "请论述项目风险管理。")),
        List.of(new PracticeQuestionSnapshot.SubQuestion(9002L, "ESSAY_WRITING", "不少于800字",
            List.of(), new PracticeAnswerPayload(null, null, null, "参考论述"),
            "围绕风险识别、分析、应对和监控展开。", new BigDecimal("45.00"))),
        List.of(new PracticeQuestionSnapshot.KnowledgeBinding("TAG", 301L, "风险管理")),
        QuestionSettlementStatus.UNSETTLED, 0, BigDecimal.ZERO, 0, 0, 0);
  }

  static Map<String, Object> paperSource() {
    return Map.of("paperId", 8001L);
  }
}
