package com.aiexam.essay.infra;

import com.aiexam.essay.domain.EssayDraft;
import com.aiexam.essay.repository.EssayDraftRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class EmptyEssayDraftRepository implements EssayDraftRepository {
  @Override
  public Optional<EssayDraft> findById(Long draftId) {
    return Optional.empty();
  }
}
