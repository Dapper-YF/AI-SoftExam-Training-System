package com.aiexam.app.learning;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record LearningChapterKnowledgePointRequest(@NotNull List<Long> knowledgePointIds) {}
