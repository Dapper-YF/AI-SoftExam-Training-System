package com.aiexam.app.migration.v9;

import java.sql.Connection;
import java.sql.SQLException;

public final class V9LegacyManifestPreflight {
  private V9LegacyManifestPreflight() {
  }

  public static void validate(Connection connection, V9ManifestBundle bundle) {
    try {
      V9LegacyRuleSupport.validate(connection, bundle);
    } catch (SQLException exception) {
      throw new V9MigrationException(V9ErrorCode.V9_MANIFEST_INVALID,
          "legacy manifest preflight could not be completed", exception);
    }
  }
}
