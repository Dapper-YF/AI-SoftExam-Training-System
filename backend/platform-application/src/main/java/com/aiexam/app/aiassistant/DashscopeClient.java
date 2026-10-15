package com.aiexam.app.aiassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Optional provider: lack of a key deliberately falls back to cited local evidence. */
@Component
final class DashscopeClient {
  private final AiAssistantProperties properties; private final ObjectMapper json; private final HttpClient client=HttpClient.newHttpClient();
  DashscopeClient(AiAssistantProperties properties,ObjectMapper json){this.properties=properties;this.json=json;}
  String answer(String question,String evidence){
    if(properties.dashscopeApiKey()==null||properties.dashscopeApiKey().isBlank())return null;
    for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) try {
      String prompt = evidence == null || evidence.isBlank()
          ? "你是软考备考助手。请直接使用通用知识回答用户问题，给出清晰、准确、易懂的解释；涉及计算时列出步骤。不要提及知识库、检索、资料来源或回答依据。\n问题：" + question
          : "你是软考备考助手。请结合给定内容回答，给出清晰、准确、易懂的解释；涉及计算时列出步骤。不要提及知识库、检索或内部资料来源。\n给定内容：\n" + evidence + "\n问题：" + question;
      String body=json.writeValueAsString(Map.of("model",properties.dashscopeModel(),"input",Map.of("messages",java.util.List.of(Map.of("role","user","content",prompt)))));
      HttpRequest request=HttpRequest.newBuilder(URI.create("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation"))
          .header("Authorization","Bearer "+properties.dashscopeApiKey()).header("Content-Type","application/json").timeout(Duration.ofSeconds(properties.requestTimeoutSeconds())).POST(HttpRequest.BodyPublishers.ofString(body)).build();
      HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString()); if(response.statusCode()/100!=2)return null;
      JsonNode root=json.readTree(response.body()); JsonNode text=root.at("/output/text"); return text.isMissingNode()?null:text.asText();
    } catch(Exception ignored) { /* retry bounded provider failures */ }
    return null;
  }
}
