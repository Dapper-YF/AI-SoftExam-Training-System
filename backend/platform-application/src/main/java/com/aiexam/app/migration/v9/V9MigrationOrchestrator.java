package com.aiexam.app.migration.v9;

import java.sql.Connection;

@FunctionalInterface
public interface V9MigrationOrchestrator {
  void migrate(Connection connection, V9ManifestBundle bundle) throws Exception;
}
