package com.aiexam.app.aiassistant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期清理答疑短期数据，避免会话、OCR 文本和图片无限累积。 */
@Component
public class AiAssistantRetentionScheduler {
  private static final Logger log = LoggerFactory.getLogger(AiAssistantRetentionScheduler.class);
  private final JdbcTemplate jdbc;
  private final AiAssistantProperties properties;

  public AiAssistantRetentionScheduler(JdbcTemplate jdbc, AiAssistantProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Shanghai")
  public void cleanExpiredData() {
    Timestamp attachmentCutoff = Timestamp.from(Instant.now()
        .minusSeconds(properties.attachmentRetentionHours() * 3600L));
    redactExpiredOcrText(attachmentCutoff);
    List<String> attachmentPaths = jdbc.query(
        "SELECT storage_key FROM ai_assistant_attachment WHERE updated_at < ?",
        (rs, row) -> rs.getString(1), attachmentCutoff);
    int attachmentCount = jdbc.update("DELETE FROM ai_assistant_attachment WHERE updated_at < ?", attachmentCutoff);
    attachmentPaths.forEach(this::deleteFile);

    Timestamp sessionCutoff = Timestamp.from(Instant.now()
        .minusSeconds(properties.conversationRetentionDays() * 86400L));
    List<Long> sessionIds = jdbc.query(
        "SELECT id FROM ai_assistant_session WHERE updated_at < ? AND deleted=0",
        (rs, row) -> rs.getLong(1), sessionCutoff);
    int messageCount = 0;
    for (Long sessionId : sessionIds) {
      List<Long> messageIds = jdbc.query(
          "SELECT id FROM ai_assistant_message WHERE session_id=?", (rs, row) -> rs.getLong(1), sessionId);
      for (Long messageId : messageIds) {
        jdbc.update("DELETE FROM ai_assistant_citation WHERE assistant_message_id=?", messageId);
        jdbc.update("DELETE FROM ai_assistant_feedback WHERE message_id=?", messageId);
        jdbc.update("DELETE FROM ai_assistant_call_log WHERE assistant_message_id=?", messageId);
      }
      messageCount += jdbc.update("DELETE FROM ai_assistant_message WHERE session_id=?", sessionId);
      jdbc.update("DELETE FROM ai_assistant_attachment WHERE session_id=?", sessionId);
      jdbc.update("DELETE FROM ai_assistant_session WHERE id=?", sessionId);
    }
    if (attachmentCount > 0 || messageCount > 0 || !sessionIds.isEmpty()) {
      log.info("AI assistant retention cleanup completed: attachments={}, messages={}, sessions={}",
          attachmentCount, messageCount, sessionIds.size());
    }
  }

  private void redactExpiredOcrText(Timestamp cutoff) {
    List<OcrMessage> messages = jdbc.query("""
        SELECT id,content FROM ai_assistant_message
        WHERE role_code='USER' AND created_at < ? AND content LIKE '%题图识别内容：%'
        """, (rs, row) -> new OcrMessage(rs.getLong(1), rs.getString(2)), cutoff);
    for (OcrMessage message : messages) {
      int marker = message.content().indexOf("题图识别内容：");
      if (marker >= 0) jdbc.update("UPDATE ai_assistant_message SET content=? WHERE id=?",
          message.content().substring(0, marker).trim(), message.id());
    }
  }

  private void deleteFile(String storageKey) {
    if (storageKey == null || storageKey.isBlank()) return;
    try {
      Files.deleteIfExists(Path.of(storageKey));
    } catch (Exception exception) {
      log.warn("Could not delete expired AI assistant attachment: {}", storageKey, exception);
    }
  }

  private record OcrMessage(Long id, String content) { }
}
