package com.aiexam.question.admin.dto;

import java.time.Instant;

public record QuestionAuditEventDto(
    long eventId,
    long questionId,
    Long questionVersionId,
    long eventSequence,
    String eventType,
    long actorUserId,
    String clientMutationId,
    String eventPayloadJson,
    Instant createdAt
) {
}
