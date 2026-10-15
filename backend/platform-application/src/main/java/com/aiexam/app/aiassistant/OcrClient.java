package com.aiexam.app.aiassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Calls an OCR gateway. The gateway owns Alibaba Cloud RAM request signing. */
@Component
final class OcrClient {
  record Result(String text, Double confidence, String engine) { }
  private final AiAssistantProperties properties; private final ObjectMapper json;
  // The local OCR gateway is HTTP/1.1; h2c upgrade can drop the JSON request body.
  private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  OcrClient(AiAssistantProperties properties, ObjectMapper json) { this.properties = properties; this.json = json; }
  Result recognize(byte[] bytes, String contentType) {
    if (properties.ocrEndpoint() == null || properties.ocrEndpoint().isBlank()) return null;
    for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) try {
      String payload = json.writeValueAsString(Map.of("imageBase64", Base64.getEncoder().encodeToString(bytes), "contentType", contentType));
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.ocrEndpoint())).header("Content-Type", "application/json").timeout(Duration.ofSeconds(properties.requestTimeoutSeconds())).POST(HttpRequest.BodyPublishers.ofString(payload));
      if (properties.ocrToken() != null && !properties.ocrToken().isBlank()) builder.header("Authorization", "Bearer " + properties.ocrToken());
      HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); if (response.statusCode() / 100 != 2) return null;
      JsonNode root = json.readTree(response.body());
      JsonNode text = root.at("/text");
      if (text.isMissingNode() || text.asText().isBlank()) return null;
      JsonNode confidence = root.at("/confidence");
      return new Result(text.asText().trim(), confidence.isNumber() ? confidence.doubleValue() : null,
          root.at("/engine").asText(null));
    } catch (Exception ignored) { /* retry bounded gateway failures */ }
    return null;
  }
}
