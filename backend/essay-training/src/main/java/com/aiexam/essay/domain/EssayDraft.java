package com.aiexam.essay.domain;

public record EssayDraft(Long id, Long userId, Long qualificationId, String status) {
}
