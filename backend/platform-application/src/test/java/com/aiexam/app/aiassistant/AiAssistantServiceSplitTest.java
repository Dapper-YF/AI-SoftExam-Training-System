package com.aiexam.app.aiassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AiAssistantServiceSplitTest {
  @Test
  void routesComparisonQuestionsToConceptScenario() {
    assertThat(AiAssistantService.scenario("请比较范围管理和需求管理的差异", null))
        .isEqualTo("CONCEPT_COMPARISON");
  }

  @Test
  void routesCalculationQuestionsWithoutTheWordCalculationToStepByStepScenario() {
    assertThat(AiAssistantService.scenario("求 192.168.1.0/24 划分 14 个子网时的掩码", null))
        .isEqualTo("CALCULATION_STEP_BY_STEP");
  }

  @Test
  void routesOrdinaryQuestionsToQuestionOrGeneralScenario() {
    assertThat(AiAssistantService.scenario("什么是项目范围", null)).isEqualTo("GENERAL_QA");
    assertThat(AiAssistantService.scenario("为什么这道题选 A", 10L)).isEqualTo("QUESTION_EXPLANATION");
  }

  @Test
  void generalPromptRejectsUnrelatedAnswerTemplates() {
    String prompt = AiAssistantService.promptFor(
        "GENERAL_QA",
        "信息系统项目管理师考试中，十大知识领域是什么？");

    assertThat(prompt)
        .contains("只能使用与问题直接相关的证据")
        .contains("不得套用既有答案模板")
        .contains("知识库未覆盖");
  }

  @Test
  void preservesHeadingContextAndUsesParagraphBoundaries() {
    List<String> fragments = AiAssistantService.split("""
        # 项目范围管理

        ## 核心定义

        项目范围是项目需要完成的全部工作。

        ## 易错点

        确认范围关注验收，控制范围关注变更。
        """);

    assertThat(fragments).containsExactly(
        "项目范围管理 > 核心定义\n\n项目范围是项目需要完成的全部工作。",
        "项目范围管理 > 易错点\n\n确认范围关注验收，控制范围关注变更。");
  }

  @Test
  void splitsLongTextAtSentenceBoundariesBeforeHardLimit() {
    String text = "第一句内容。" + "第二句内容。".repeat(300);

    List<String> fragments = AiAssistantService.split(text);

    assertThat(fragments).hasSizeGreaterThan(1);
    assertThat(fragments).allSatisfy(fragment -> assertThat(fragment.length()).isLessThanOrEqualTo(1200));
    assertThat(fragments).allMatch(fragment -> fragment.endsWith("。"));
  }

  @Test
  void ignoresBlankInput() {
    assertThat(AiAssistantService.split(" \n\r\n ")).isEmpty();
  }

  @Test
  void cleansImportedTextBeforeChunking() {
    String cleaned = AiAssistantService.cleanKnowledgeContent(
        "\uFEFF第一章  范围管理\r\n\r\n\r\n正文\t内容\u00A0说明  \r\n");

    assertThat(cleaned).isEqualTo("第一章 范围管理\n\n正文 内容 说明");
  }

  @Test
  void derivesKnowledgeProcessingStatusFromFragmentProjection() {
    assertThat(KnowledgeIndexingService.processingStatus(3, 3, 0)).isEqualTo("INDEXED");
    assertThat(KnowledgeIndexingService.processingStatus(3, 1, 0)).isEqualTo("PARTIAL");
    assertThat(KnowledgeIndexingService.processingStatus(3, 0, 1)).isEqualTo("FAILED");
    assertThat(KnowledgeIndexingService.processingStatus(3, 0, 0)).isEqualTo("READY");
  }
}
