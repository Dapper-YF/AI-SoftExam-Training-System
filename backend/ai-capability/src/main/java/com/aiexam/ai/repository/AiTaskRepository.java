package com.aiexam.ai.repository;

import com.aiexam.ai.dto.AiTaskStatusDto;
import java.util.Optional;

public interface AiTaskRepository {
  Optional<AiTaskStatusDto> findStatus(Long taskId);

  Optional<AiTaskStatusDto> findLatestByBiz(String bizType, Long bizId, String scenario);

  AiTaskStatusDto createTask(
      Long taskId,
      Long userId,
      String bizType,
      Long bizId,
      String scenario,
      String requestHash,
      String inputSnapshotJson);
}
