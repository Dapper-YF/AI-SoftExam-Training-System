package com.aiexam.essay.repository;

import com.aiexam.essay.domain.EssayDraft;
import java.util.Optional;

public interface EssayDraftRepository {
  Optional<EssayDraft> findById(Long draftId);
}
