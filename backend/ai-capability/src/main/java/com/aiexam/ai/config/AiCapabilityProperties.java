package com.aiexam.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aiexam.ai-assistant")
public record AiCapabilityProperties(
    String answerProvider,
    String deepseekApiKey,
    String deepseekBaseUrl,
    String deepseekModel,
    String embeddingEndpoint,
    String embeddingToken,
    String embeddingModel,
    String qdrantEndpoint,
    String qdrantCollection,
    int requestTimeoutSeconds,
    int maxRetries,
    double ragVectorMinScore,
    double ragVectorCardMinScore,
    double ragBm25MinScore,
    double ragMinScoreMargin,
    int ragCandidateTopK,
    int ragRrfK,
    double ragBm25Weight,
    double ragVectorWeight
) {
  public AiCapabilityProperties {
    answerProvider = blank(answerProvider) ? "deepseek" : answerProvider;
    deepseekBaseUrl = blank(deepseekBaseUrl) ? "https://api.deepseek.com" : deepseekBaseUrl;
    deepseekModel = blank(deepseekModel) ? "deepseek-v4-flash" : deepseekModel;
    embeddingModel = blank(embeddingModel) ? "Qwen/Qwen3-Embedding-0.6B" : embeddingModel;
    qdrantCollection = blank(qdrantCollection) ? "knowledge_published" : qdrantCollection;
    requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 30 : requestTimeoutSeconds;
    maxRetries = maxRetries < 0 ? 1 : Math.min(maxRetries, 3);
    ragVectorMinScore = ragVectorMinScore <= 0 ? 0.70d : ragVectorMinScore;
    ragVectorCardMinScore = ragVectorCardMinScore <= 0 ? 0.60d : ragVectorCardMinScore;
    ragBm25MinScore = ragBm25MinScore <= 0 ? 1.0d : ragBm25MinScore;
    ragMinScoreMargin = Math.max(0d, ragMinScoreMargin);
    ragCandidateTopK = ragCandidateTopK <= 0 ? 20 : Math.min(ragCandidateTopK, 100);
    ragRrfK = ragRrfK <= 0 ? 60 : ragRrfK;
    ragBm25Weight = ragBm25Weight <= 0 ? 1.0d : ragBm25Weight;
    ragVectorWeight = ragVectorWeight <= 0 ? 1.05d : ragVectorWeight;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
