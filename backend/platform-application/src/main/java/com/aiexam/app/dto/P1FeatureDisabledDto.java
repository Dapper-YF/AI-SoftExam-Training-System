package com.aiexam.app.dto;

public record P1FeatureDisabledDto(
    String featureCode,
    String featureName,
    boolean enabled,
    String status,
    boolean architectConfirmationRequired
) {
  public static P1FeatureDisabledDto of(String featureCode, String featureName) {
    return new P1FeatureDisabledDto(
        featureCode,
        featureName,
        false,
        "功能未启用，等待 P1 正式接口契约与 DDL 确认",
        true);
  }
}
