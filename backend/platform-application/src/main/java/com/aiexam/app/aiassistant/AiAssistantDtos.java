package com.aiexam.app.aiassistant;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public final class AiAssistantDtos {
  private AiAssistantDtos() { }

  public record CreateSessionRequest(Long qualificationId, Long subjectId, Long questionId, String title) { }
  public record AskRequest(String content, Long attachmentId) { }
  public record FeedbackRequest(String feedbackCode, String reason) { }
  public record SessionDto(Long id, Long qualificationId, Long subjectId, Long questionId, String title, Instant updatedAt) { }
  public record MessageDto(
      Long id,
      String role,
      String content,
      String answerStatus,
      List<CitationDto> citations,
      Instant createdAt
  ) { }
  public record CitationDto(Long fragmentId, String documentTitle, String excerpt, String fragmentKey,
                            String contentKind, String sourcePages) {
    public CitationDto(Long fragmentId, String documentTitle, String excerpt) {
      this(fragmentId, documentTitle, excerpt, null, null, null);
    }
  }
  public record SessionDetailDto(SessionDto session, List<MessageDto> messages) { }
  public record AttachmentDto(Long id, String ocrStatus, String ocrText, String detectedQuestionType,
                               Double ocrConfidence, String ocrEngine) { }
  public record KnowledgeDocumentRequest(Long qualificationId, Long subjectId, String documentCode, String title,
                                         String sourceName, String sourceUrl, String copyrightBasis, String content,
                                         String knowledgeType, Long knowledgeId) { }
  public record KnowledgeDocumentDto(
      @JsonSerialize(using = ToStringSerializer.class) Long id,
      String documentCode,
      String title,
      String status,
      int fragmentCount,
      int indexedCount,
      int reviewedCount,
      int failedCount,
      String processingStatus,
      Instant updatedAt
  ) { }
  public record KnowledgeFragmentDto(
      @JsonSerialize(using = ToStringSerializer.class) Long id,
      int fragmentNo,
      String fragmentKey,
      String content,
      String status,
      String indexStatus,
      String qualityStatus,
      String contentKind,
      String sourcePages,
      String embeddingModel,
      String embeddingVersion,
      Integer embeddingDimension,
      String qdrantPointId,
      Instant embeddedAt
  ) { }
  public record RetrievalTestRequest(String query, String qualification, String subject, Integer topK) { }
  public record RetrievalResultDto(String fragmentKey, String knowledgeId, String conceptId, String contentKind,
                                   String sourcePages, String excerpt, Double score) { }
  public record RetrievalTestResponse(String query, List<RetrievalResultDto> bm25Results,
                                      List<RetrievalResultDto> vectorResults, List<RetrievalResultDto> hybridResults,
                                      String fusion, int topK) { }
}
