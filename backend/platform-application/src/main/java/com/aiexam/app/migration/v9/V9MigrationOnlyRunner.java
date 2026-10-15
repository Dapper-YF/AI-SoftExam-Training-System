package com.aiexam.app.migration.v9;

import com.aiexam.app.migration.V9SoftExamTaxonomyRuleSeedMigration;
import org.flywaydb.core.Flyway;

public final class V9MigrationOnlyRunner {
  private V9MigrationOnlyRunner() {
  }

  public static Flyway build(String jdbcUrl, String username, String password,
      V9MigrationOrchestrator orchestrator) {
    return build(jdbcUrl, username, password, V9ManifestBundle.load(), orchestrator);
  }

  public static Flyway build(String jdbcUrl, String username, String password,
      V9ManifestBundle bundle, V9MigrationOrchestrator orchestrator) {
    return Flyway.configure()
        .dataSource(jdbcUrl, username, password)
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .javaMigrations(new V9SoftExamTaxonomyRuleSeedMigration(bundle, orchestrator))
        .load();
  }
}
