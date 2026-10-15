package com.aiexam.ai.application;

import com.aiexam.ai.dto.AiTaskStatusDto;
import com.aiexam.ai.repository.AiTaskRepository;
import com.aiexam.common.error.ResourceNotFoundException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class AiTaskApplicationService {
  private static final AtomicLong ID_SEQUENCE = new AtomicLong();
  private static final String QUESTION_EXPLANATION_BIZ_TYPE = "QUESTION_EXPLANATION";
  private static final String QUESTION_EXPLANATION_SCENARIO = "QUESTION_AI_EXPLANATION";

  private final AiTaskRepository aiTaskRepository;

  public AiTaskApplicationService(AiTaskRepository aiTaskRepository) {
    this.aiTaskRepository = aiTaskRepository;
  }

  public AiTaskStatusDto getTaskStatus(Long taskId) {
    return aiTaskRepository.findStatus(taskId)
        .orElseThrow(() -> new ResourceNotFoundException("AI任务不存在"));
  }

  public AiTaskStatusDto createQuestionExplanationTask(Long questionId) {
    return aiTaskRepository.findLatestByBiz(
            QUESTION_EXPLANATION_BIZ_TYPE,
            questionId,
            QUESTION_EXPLANATION_SCENARIO)
        .orElseGet(() -> aiTaskRepository.createTask(
            nextId(),
            null,
            QUESTION_EXPLANATION_BIZ_TYPE,
            questionId,
            QUESTION_EXPLANATION_SCENARIO,
            QUESTION_EXPLANATION_BIZ_TYPE + ":" + questionId,
            null));
  }

  private Long nextId() {
    return System.currentTimeMillis() * 100_000 + Math.floorMod(ID_SEQUENCE.getAndIncrement(), 100_000);
  }
}
