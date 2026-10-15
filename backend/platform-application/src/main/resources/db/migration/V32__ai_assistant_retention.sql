-- AI 答疑数据保留策略：兼容已由开发环境手工创建索引的数据库。
SELECT COUNT(*) INTO @assistant_session_index_count
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ai_assistant_session'
  AND index_name = 'idx_ai_assistant_session_retention';
SET @assistant_session_index_sql = IF(
  @assistant_session_index_count = 0,
  'ALTER TABLE ai_assistant_session ADD KEY idx_ai_assistant_session_retention (deleted, status, updated_at)',
  'SELECT 1'
);
PREPARE assistant_session_index_statement FROM @assistant_session_index_sql;
EXECUTE assistant_session_index_statement;
DEALLOCATE PREPARE assistant_session_index_statement;

SELECT COUNT(*) INTO @assistant_attachment_index_count
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ai_assistant_attachment'
  AND index_name = 'idx_ai_assistant_attachment_retention';
SET @assistant_attachment_index_sql = IF(
  @assistant_attachment_index_count = 0,
  'ALTER TABLE ai_assistant_attachment ADD KEY idx_ai_assistant_attachment_retention (updated_at)',
  'SELECT 1'
);
PREPARE assistant_attachment_index_statement FROM @assistant_attachment_index_sql;
EXECUTE assistant_attachment_index_statement;
DEALLOCATE PREPARE assistant_attachment_index_statement;
