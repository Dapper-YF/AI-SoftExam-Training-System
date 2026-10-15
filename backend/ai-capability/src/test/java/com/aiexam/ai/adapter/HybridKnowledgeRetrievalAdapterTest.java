package com.aiexam.ai.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HybridKnowledgeRetrievalAdapterTest {
  @Test
  void acceptsStrongBm25EvidenceWhenVectorChannelIsPresentButWeak() {
    assertThat(HybridKnowledgeRetrievalAdapter.isGrounded(false, true)).isTrue();
  }

  @Test
  void doesNotLetOneCardPermanentlyDisplaceTextbookSources() {
    assertThat(HybridKnowledgeRetrievalAdapter.contentBoost("SOURCE"))
        .isGreaterThan(HybridKnowledgeRetrievalAdapter.contentBoost("CARD"));
  }
}
