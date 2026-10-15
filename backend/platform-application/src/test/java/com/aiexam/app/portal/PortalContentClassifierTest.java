package com.aiexam.app.portal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PortalContentClassifierTest {
  private final PortalContentClassifier classifier = new PortalContentClassifier();

  @Test
  void classifiesRegistrationAndOfficialResourcesBeforePolicy() {
    assertThat(classifier.classify("2026年上半年软考报名通知").category()).isEqualTo(PortalContentCategory.REGISTRATION);
    assertThat(classifier.classify("计算机资格考试大纲发布").contentType()).isEqualTo(PortalContentType.RESOURCE);
    assertThat(classifier.classify("关于公布成绩查询安排的通知").category()).isEqualTo(PortalContentCategory.POLICY);
  }
}
