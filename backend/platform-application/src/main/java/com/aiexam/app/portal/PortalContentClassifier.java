package com.aiexam.app.portal;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PortalContentClassifier {
  private static final List<String> RESOURCE_KEYWORDS = List.of("考试大纲", "大纲", "教材", "考试用书", "样题", "考试文件", "模拟练习", "考试指南", "考试计划", "考试政策", "考试安排", "政策法规");
  private static final List<String> REGISTRATION_KEYWORDS = List.of("报名", "报考须知", "报名提示", "报名安排", "缴费", "资格审核", "准考证", "批次安排", "考试安排", "考试工作");

  public Classification classify(String title) {
    if (containsAny(title, RESOURCE_KEYWORDS)) {
      return new Classification(PortalContentCategory.OFFICIAL_RESOURCE, PortalContentType.RESOURCE, "官方资料");
    }
    if (containsAny(title, REGISTRATION_KEYWORDS)) {
      return new Classification(PortalContentCategory.REGISTRATION, PortalContentType.NEWS, "报名动态");
    }
    return new Classification(PortalContentCategory.POLICY, PortalContentType.NEWS, "政策资讯");
  }

  private boolean containsAny(String value, List<String> keywords) {
    return keywords.stream().anyMatch(value::contains);
  }

  public record Classification(PortalContentCategory category, PortalContentType contentType, String tag) {}
}
