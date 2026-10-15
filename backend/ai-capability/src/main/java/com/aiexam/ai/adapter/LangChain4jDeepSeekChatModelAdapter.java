package com.aiexam.ai.adapter;

import com.aiexam.ai.config.AiCapabilityProperties;
import com.aiexam.ai.port.ChatModelPort;
import com.aiexam.ai.port.ChatModelResult;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class LangChain4jDeepSeekChatModelAdapter implements ChatModelPort {
  private static final String SYSTEM_PROMPT = """
      你是信息系统项目管理师培训助手。
      请结合用户提供的项目背景与知识材料完成任务。
      不得编造知识来源或页码；材料不足时应明确给出保守建议。
      """;

  private final AiCapabilityProperties properties;

  public LangChain4jDeepSeekChatModelAdapter(AiCapabilityProperties properties) {
    this.properties = properties;
  }

  @Override
  public ChatModelResult generate(String prompt, String evidence) {
    long started = System.nanoTime();
    String provider = "deepseek";
    String modelName = properties.deepseekModel();
    if (!provider.equalsIgnoreCase(properties.answerProvider())) {
      return result(null, provider, modelName, "UNAVAILABLE", started, "ANSWER_PROVIDER_DISABLED");
    }
    if (properties.deepseekApiKey() == null || properties.deepseekApiKey().isBlank()) {
      return result(null, provider, modelName, "UNAVAILABLE", started, "DEEPSEEK_API_KEY_MISSING");
    }
    try {
      OpenAiChatModel model = OpenAiChatModel.builder()
          .baseUrl(withoutTrailingSlash(properties.deepseekBaseUrl()))
          .apiKey(properties.deepseekApiKey())
          .modelName(modelName)
          .temperature(0.2)
          .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
          .maxRetries(properties.maxRetries())
          .build();
      String context = evidence == null || evidence.isBlank()
          ? "本次没有可用的知识材料，请避免虚构引用。"
          : evidence;
      String content = model.chat(SYSTEM_PROMPT + "\n\n知识材料：\n" + context + "\n\n任务：\n" + prompt);
      return result(content, provider, modelName, "SUCCEEDED", started, null);
    } catch (RuntimeException exception) {
      return result(null, provider, modelName, "MODEL_ERROR", started, "DEEPSEEK_REQUEST_FAILED");
    }
  }

  private ChatModelResult result(
      String content,
      String provider,
      String model,
      String status,
      long started,
      String errorCode
  ) {
    return new ChatModelResult(
        content,
        provider,
        model,
        status,
        (System.nanoTime() - started) / 1_000_000,
        errorCode);
  }

  private static String withoutTrailingSlash(String value) {
    return value.replaceAll("/+$", "");
  }
}
