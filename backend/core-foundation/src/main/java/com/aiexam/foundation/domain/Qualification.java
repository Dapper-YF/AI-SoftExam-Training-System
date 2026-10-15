package com.aiexam.foundation.domain;

public record Qualification(
    Long id,
    String qualificationCode,
    String qualificationName,
    String levelCode,
    String status
) {
}
