package com.aiexam.ai.port;

public interface ChatModelPort {
  ChatModelResult generate(String prompt, String evidence);
}
