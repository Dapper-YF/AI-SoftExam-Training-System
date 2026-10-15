package com.aiexam.ai.repository;

import com.aiexam.ai.port.AiTaskRef;

public record SubmitReservation(
    AiTaskRef task,
    boolean newlyCreated
) {
}
