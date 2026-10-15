package com.aiexam.app.aiassistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aiexam.ai-assistant")
public record AiAssistantProperties(
    String dashscopeApiKey,
    String dashscopeModel,
    String answerProvider,
    String deepseekApiKey,
    String deepseekBaseUrl,
    String deepseekModel,
    String ocrEndpoint,
    String ocrToken,
    String embeddingEndpoint,
    String embeddingToken,
    String embeddingModel,
    String embeddingRevision,
    String qdrantEndpoint,
    String qdrantCollection,
    String uploadRoot,
    long maxImageBytes,
    int maxImagePixels,
    int requestTimeoutSeconds,
    int maxRetries,
    int maxRequestsPerMinute,
    double ragVectorMinScore,
    double ragVectorCardMinScore,
    double ragBm25MinScore,
    double ragMinScoreMargin,
    int ragCandidateTopK,
    int ragRrfK,
    double ragBm25Weight,
    double ragVectorWeight,
    int conversationRetentionDays,
    int attachmentRetentionHours
) {
  public AiAssistantProperties {
    dashscopeModel = dashscopeModel == null || dashscopeModel.isBlank() ? "qwen-plus" : dashscopeModel;
    answerProvider = answerProvider == null || answerProvider.isBlank() ? "deepseek" : answerProvider;
    deepseekBaseUrl = deepseekBaseUrl == null || deepseekBaseUrl.isBlank() ? "https://api.deepseek.com" : deepseekBaseUrl;
    deepseekModel = deepseekModel == null || deepseekModel.isBlank() ? "deepseek-v4-flash" : deepseekModel;
    embeddingModel = embeddingModel == null || embeddingModel.isBlank() ? "Qwen/Qwen3-Embedding-0.6B" : embeddingModel;
    qdrantCollection = qdrantCollection == null || qdrantCollection.isBlank() ? "knowledge_published" : qdrantCollection;
    uploadRoot = uploadRoot == null || uploadRoot.isBlank() ? "./var/ai-assistant" : uploadRoot;
    maxImageBytes = maxImageBytes <= 0 ? 10L * 1024 * 1024 : maxImageBytes;
    maxImagePixels = maxImagePixels <= 0 ? 24_000_000 : maxImagePixels;
    requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 30 : requestTimeoutSeconds;
    maxRetries = maxRetries < 0 ? 1 : Math.min(maxRetries, 3);
    maxRequestsPerMinute = maxRequestsPerMinute <= 0 ? 30 : maxRequestsPerMinute;
    ragVectorMinScore = ragVectorMinScore <= 0 ? 0.70d : ragVectorMinScore;
    ragVectorCardMinScore = ragVectorCardMinScore <= 0 ? 0.60d : ragVectorCardMinScore;
    ragBm25MinScore = ragBm25MinScore <= 0 ? 1.0d : ragBm25MinScore;
    ragMinScoreMargin = Math.max(0d, ragMinScoreMargin);
    ragCandidateTopK = ragCandidateTopK <= 0 ? 20 : Math.min(ragCandidateTopK, 100);
    ragRrfK = ragRrfK <= 0 ? 60 : ragRrfK;
    ragBm25Weight = ragBm25Weight <= 0 ? 1.0d : ragBm25Weight;
    ragVectorWeight = ragVectorWeight <= 0 ? 1.05d : ragVectorWeight;
    conversationRetentionDays = conversationRetentionDays <= 0 ? 7 : conversationRetentionDays;
    attachmentRetentionHours = attachmentRetentionHours <= 0 ? 24 : attachmentRetentionHours;
  }
}
