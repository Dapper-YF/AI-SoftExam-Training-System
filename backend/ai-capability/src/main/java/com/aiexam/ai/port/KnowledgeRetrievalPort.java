package com.aiexam.ai.port;

import java.util.List;

public interface KnowledgeRetrievalPort {
  RetrievalDecision retrieve(String query, int topK);

  record Evidence(
      String fragmentKey,
      String knowledgeId,
      String conceptId,
      String contentKind,
      String sourcePages,
      String excerpt,
      Double score
  ) { }

  record RetrievalDecision(
      List<Evidence> bm25,
      List<Evidence> vector,
      List<Evidence> hybrid,
      boolean grounded,
      String reason
  ) { }
}
