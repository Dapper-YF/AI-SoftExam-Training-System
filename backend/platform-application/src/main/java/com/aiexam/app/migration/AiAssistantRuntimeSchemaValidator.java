package com.aiexam.app.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Fails closed when the application code and the AI assistant migration history diverge. */
public final class AiAssistantRuntimeSchemaValidator {
  public void validate(Connection connection) throws SQLException {
    boolean v23Applied = migrationApplied(connection, "23");
    boolean v24Applied = migrationApplied(connection, "24");
    boolean sessionTableExists = tableExists(connection, "ai_assistant_session");
    if (!v23Applied && sessionTableExists) {
      throw new IllegalStateException("AI assistant tables exist without Flyway V23 history");
    }
    if (!v23Applied || !v24Applied) {
      throw new IllegalStateException("Flyway V23 and V24 must be applied before enabling AI assistant");
    }
    requireColumn(connection, "ai_assistant_message", "answer_mode");
    requireColumn(connection, "ai_assistant_message", "cache_hit");
    requireColumn(connection, "knowledge_fragment", "index_status");
    if (!tableExists(connection, "knowledge_document_version") || !tableExists(connection, "ai_assistant_call_log")) {
      throw new IllegalStateException("AI assistant V24 audit tables are not ready");
    }
  }

  private boolean migrationApplied(Connection connection, String version) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT COUNT(*) FROM flyway_schema_history WHERE version=? AND success=1
        """)) {
      statement.setString(1, version);
      try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1) == 1; }
    }
  }

  private boolean tableExists(Connection connection, String table) throws SQLException {
    return count(connection, """
        SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?
        """, table) == 1;
  }

  private void requireColumn(Connection connection, String table, String column) throws SQLException {
    if (count(connection, """
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
        """, table, column) != 1) {
      throw new IllegalStateException("AI assistant schema column is missing: " + table + "." + column);
    }
  }

  private int count(Connection connection, String sql, String... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) statement.setString(index + 1, parameters[index]);
      try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1); }
    }
  }
}
