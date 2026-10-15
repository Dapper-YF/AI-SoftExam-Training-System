package com.aiexam.app.migration;

import com.aiexam.app.migration.v9.V9ErrorCode;
import com.aiexam.app.migration.v9.V9ManifestBundle;
import com.aiexam.app.migration.v9.V9MigrationException;
import com.aiexam.app.migration.v9.V9MigrationOrchestrator;
import java.util.Objects;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

public final class V9SoftExamTaxonomyRuleSeedMigration implements JavaMigration {
  private final V9ManifestBundle bundle;
  private final V9MigrationOrchestrator orchestrator;

  public V9SoftExamTaxonomyRuleSeedMigration() {
    this(V9ManifestBundle.load(), (connection, manifest) -> {
      throw new V9MigrationException(V9ErrorCode.V9_APPROVAL_MISSING,
          "V9 requires the migration-only authorized runner");
    });
  }

  public V9SoftExamTaxonomyRuleSeedMigration(V9ManifestBundle bundle,
      V9MigrationOrchestrator orchestrator) {
    this.bundle = Objects.requireNonNull(bundle, "bundle");
    this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
  }

  @Override
  public MigrationVersion getVersion() {
    return MigrationVersion.fromVersion("9");
  }

  @Override
  public String getDescription() {
    return "soft exam taxonomy rule seed";
  }

  @Override
  public Integer getChecksum() {
    return bundle.flywayChecksum();
  }

  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }

  @Override
  public void migrate(Context context) throws Exception {
    orchestrator.migrate(context.getConnection(), bundle);
  }
}
