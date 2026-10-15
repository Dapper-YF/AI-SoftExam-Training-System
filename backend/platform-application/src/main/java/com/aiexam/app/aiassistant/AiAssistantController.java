package com.aiexam.app.aiassistant;

import com.aiexam.app.aiassistant.AiAssistantDtos.*;
import com.aiexam.ai.port.KnowledgeRetrievalPort;
import com.aiexam.ai.port.KnowledgeRetrievalPort.Evidence;
import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformAuthenticationException;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/ai-assistant")
public class AiAssistantController {
  private final AiAssistantService service;
  private final KnowledgeRetrievalPort retrieval;
  private final CurrentUserProvider currentUserProvider;
  public AiAssistantController(AiAssistantService service, KnowledgeRetrievalPort retrieval, CurrentUserProvider currentUserProvider) {
    this.service = service; this.retrieval = retrieval; this.currentUserProvider = currentUserProvider;
  }
  @PostMapping("/sessions") public ApiResponse<SessionDto> create(@RequestBody CreateSessionRequest request) {
    return ApiResponse.ok(service.createSession(userId(), request));
  }
  @PostMapping("/current-session") public ApiResponse<SessionDto> currentSession() {
    return ApiResponse.ok(service.currentSession(userId()));
  }
  @GetMapping("/sessions") public ApiResponse<List<SessionDto>> recentSessions(
      @RequestParam(value = "questionId", required = false) Long questionId) {
    return ApiResponse.ok(service.recentSessions(userId(), questionId));
  }
  @GetMapping("/sessions/{id}") public ApiResponse<SessionDetailDto> detail(@PathVariable("id") @Positive Long id) {
    return ApiResponse.ok(service.detail(userId(), id));
  }
  @PostMapping("/sessions/{id}/messages") public ApiResponse<MessageDto> ask(@PathVariable("id") @Positive Long id, @RequestBody AskRequest request) {
    return ApiResponse.ok(service.ask(userId(), id, request));
  }
  @PostMapping("/messages/{id}/feedback") public ApiResponse<Void> feedback(@PathVariable("id") @Positive Long id, @RequestBody FeedbackRequest request) {
    service.feedback(userId(), id, request); return ApiResponse.ok(null);
  }
  @PostMapping(value = "/sessions/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<AttachmentDto> upload(@PathVariable("id") @Positive Long id, @RequestPart("file") MultipartFile file) {
    return ApiResponse.ok(service.upload(userId(), id, file));
  }
  @PutMapping("/attachments/{id}/ocr") public ApiResponse<AttachmentDto> confirmOcr(@PathVariable("id") @Positive Long id, @RequestBody String text) {
    return ApiResponse.ok(service.confirmOcr(userId(), id, text));
  }
  @PostMapping("/knowledge/retrieval-test") public ApiResponse<RetrievalTestResponse> retrievalTest(@RequestBody RetrievalTestRequest request) {
    userId();
    if (request == null || request.query() == null || request.query().isBlank()) throw new IllegalArgumentException("query must not be blank");
    String query = request.query().trim();
    int topK = request.topK() == null ? 3 : Math.max(1, Math.min(request.topK(), 20));
    KnowledgeRetrievalPort.RetrievalDecision decision = retrieval.retrieve(query, topK);
    return ApiResponse.ok(new RetrievalTestResponse(
        query,
        results(decision.bm25()),
        results(decision.vector()),
        results(decision.hybrid()),
        "RRF",
        topK));
  }
  private List<RetrievalResultDto> results(List<Evidence> evidence) {
    return evidence.stream()
        .map(item -> new RetrievalResultDto(
            item.fragmentKey(),
            item.knowledgeId(),
            item.conceptId(),
            item.contentKind(),
            item.sourcePages(),
            item.excerpt(),
            item.score()))
        .toList();
  }
  private Long userId() { return currentUserProvider.currentPrincipal().orElseThrow(PlatformAuthenticationException::new).userId(); }
}
