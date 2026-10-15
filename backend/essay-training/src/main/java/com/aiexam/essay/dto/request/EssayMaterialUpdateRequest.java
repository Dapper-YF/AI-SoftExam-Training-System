package com.aiexam.essay.dto.request;

public record EssayMaterialUpdateRequest(
    Long backgroundId,
    Long categoryId,
    String materialType,
    String title,
    String content,
    String status,
    String tags
) {
}
