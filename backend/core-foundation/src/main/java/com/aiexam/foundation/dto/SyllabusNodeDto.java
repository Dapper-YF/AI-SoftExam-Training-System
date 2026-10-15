package com.aiexam.foundation.dto;

import java.util.List;

public record SyllabusNodeDto(
    Long id,
    Long qualificationId,
    Long subjectId,
    Long parentId,
    String syllabusVersion,
    String nodeCode,
    String nodeName,
    int levelNo,
    Integer sortOrder,
    String status,
    List<SyllabusNodeDto> children
) {
}
