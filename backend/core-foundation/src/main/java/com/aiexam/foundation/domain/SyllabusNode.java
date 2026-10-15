package com.aiexam.foundation.domain;

public record SyllabusNode(
    Long id,
    Long subjectId,
    Long parentId,
    String nodeCode,
    String nodeName,
    int levelNo
) {
}
