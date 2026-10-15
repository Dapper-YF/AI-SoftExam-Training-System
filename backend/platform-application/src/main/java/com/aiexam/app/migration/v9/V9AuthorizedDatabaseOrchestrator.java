package com.aiexam.app.migration.v9;

import com.aiexam.app.migration.deploymentcontrol.AttemptState;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class V9AuthorizedDatabaseOrchestrator implements V9MigrationOrchestrator {
  private static final List<String> QUALIFICATION_COLUMNS = List.of(
      "domain_code", "official_name", "source_url", "verified_at",
      "verification_status", "catalog_version", "catalog_status");
  private static final List<String> RULE_COLUMNS = List.of(
      "period_code", "effective_from", "effective_to", "published_at", "version");

  private final V9ExecutionFence fence;
  private final Supplier<Connection> readOnlyConnectionSupplier;

  public V9AuthorizedDatabaseOrchestrator(V9ExecutionFence fence,
      Supplier<Connection> readOnlyConnectionSupplier) {
    this.fence = Objects.requireNonNull(fence, "fence");
    this.readOnlyConnectionSupplier = Objects.requireNonNull(
        readOnlyConnectionSupplier, "readOnlyConnectionSupplier");
  }

  @Override
  public void migrate(Connection connection, V9ManifestBundle bundle) throws Exception {
    executeExpand(connection, bundle, "01_QUALIFICATION_EXPAND", "exam_qualification",
        Map.of(
            "domain_code", "varchar(32):YES",
            "official_name", "varchar(96):YES",
            "source_url", "varchar(512):YES",
            "verified_at", "datetime(3):YES",
            "verification_status", "varchar(32):YES",
            "catalog_version", "varchar(32):YES",
            "catalog_status", "varchar(24):YES"),
        "db/migration-payload/v9/01_qualification_expand.sql");
    executeExpand(connection, bundle, "02_SUBJECT_EXPAND", "exam_subject",
        Map.of("official_name", "varchar(96):YES"),
        "db/migration-payload/v9/02_subject_expand.sql");
    executeExpand(connection, bundle, "03_RULE_EXPAND", "exam_rule",
        Map.of(
            "period_code", "varchar(32):YES",
            "effective_from", "datetime(3):YES",
            "effective_to", "datetime(3):YES",
            "published_at", "datetime(3):YES",
            "version", "bigint:YES"),
        "db/migration-payload/v9/03_rule_expand.sql");
    fence.transition(AttemptState.FLYWAY_STARTED, AttemptState.EXPAND_COMPLETED,
        schemaEvidence(connection));

    fence.verifyBoundary("DML_STARTED", AttemptState.EXPAND_COMPLETED);
    try {
      fence.transition(AttemptState.EXPAND_COMPLETED, AttemptState.DML_STARTED,
          schemaEvidence(connection));
    } catch (RuntimeException failure) {
      throw new V9MigrationException(V9ErrorCode.V9_RESTORE_REQUIRED,
          "DML_STARTED was not durably confirmed; restore classification is mandatory", failure);
    }
    fence.verifyBoundary("D_TRANSACTION_BEGIN", AttemptState.DML_STARTED);
    executeDataTransaction(connection, bundle);
    String dataEvidence = readOnlyDataEvidence(bundle);
    fence.transition(AttemptState.DML_STARTED, AttemptState.DML_COMPLETED, dataEvidence);
    fence.verifyBoundary("HARDENING_STARTED", AttemptState.DML_COMPLETED);
    fence.transition(AttemptState.DML_COMPLETED, AttemptState.HARDENING_STARTED, dataEvidence);

    executeHarden(connection, bundle, "04_QUALIFICATION_HARDEN",
        new HardenTarget("exam_qualification",
            Map.of(
                "ck_exam_qualification_domain", "domain_code = 'SOFT_EXAM'",
                "ck_exam_qualification_level", "level_code IN ('PRIMARY','INTERMEDIATE','ADVANCED')",
                "ck_exam_qualification_verification", "(verification_status = 'OFFICIAL_VERIFIED' AND source_url IS NOT NULL AND verified_at IS NOT NULL) OR verification_status IN ('THIRD_PARTY_CANDIDATE','VERIFICATION_EXPIRED')",
                "ck_exam_qualification_catalog_status", "catalog_status IN ('ACTIVE','RETIRED')",
                "ck_exam_qualification_operational_status", "status IN ('DRAFT','ENABLED','DISABLED') AND (catalog_status <> 'RETIRED' OR status <> 'ENABLED')"),
            Map.of(
                "idx_exam_qualification_tree", "N:domain_code,level_code,status,sort_order,id",
                "idx_exam_qualification_verification", "N:verification_status,verified_at"),
            List.of("domain_code", "official_name", "verification_status", "catalog_version",
                "catalog_status"), Set.of()),
        "db/migration-payload/v9/04_qualification_harden.sql");
    executeHarden(connection, bundle, "05_SUBJECT_HARDEN",
        new HardenTarget("exam_subject",
            Map.of(
                "ck_exam_subject_type", "subject_type IN ('COMPREHENSIVE','CASE','ESSAY','APPLICATION')",
                "ck_exam_subject_status", "status IN ('DRAFT','ENABLED','DISABLED')"),
            Map.of("idx_exam_subject_tree", "N:qualification_id,status,sort_order,id"),
            List.of("official_name"), Set.of("idx_exam_subject_qualification")),
        "db/migration-payload/v9/05_subject_harden.sql");
    executeHarden(connection, bundle, "06_RULE_HARDEN",
        new HardenTarget("exam_rule",
            Map.of(
                "ck_exam_rule_subject_scope", "subject_id IS NULL",
                "ck_exam_rule_status", "status IN ('DRAFT','PUBLISHED','RETIRED')",
                "ck_exam_rule_effective_range", "effective_from < effective_to"),
            Map.of(
                "idx_exam_rule_active", "N:qualification_id,period_code,status,effective_from,effective_to",
                "uk_exam_rule_period_version", "U:qualification_id,period_code,rule_code,rule_version"),
            List.of("period_code", "effective_from", "effective_to", "version"),
            Set.of("uk_exam_rule_version", "idx_exam_rule_subject")),
        "db/migration-payload/v9/06_rule_harden.sql");
    fence.transition(AttemptState.HARDENING_STARTED, AttemptState.COMPLETED,
        schemaEvidence(connection));
  }

  private void executeExpand(Connection connection, V9ManifestBundle bundle, String step,
      String table, Map<String, String> columns, String resource) throws SQLException {
    fence.verifyBoundary(step, AttemptState.FLYWAY_STARTED);
    int existing = countColumns(connection, table, List.copyOf(columns.keySet()));
    if (existing == columns.size()) {
      verifyExpandColumns(connection, table, columns);
      return;
    }
    if (existing != 0) {
      throw drift(step + " is partially applied");
    }
    executePayload(connection, bundle, resource);
    if (countColumns(connection, table, List.copyOf(columns.keySet())) != columns.size()) {
      throw drift(step + " did not reach its target definition");
    }
    verifyExpandColumns(connection, table, columns);
  }

  private static void verifyExpandColumns(Connection connection, String table,
      Map<String, String> expected) throws SQLException {
    for (Map.Entry<String, String> column : expected.entrySet()) {
      String actual = columnSignature(connection, table, column.getKey());
      if (!column.getValue().equals(actual)) {
        throw drift("expand column definition drift: " + table + "." + column.getKey());
      }
    }
  }

  private static String columnSignature(Connection connection, String table, String column)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT data_type,character_maximum_length,datetime_precision,is_nullable
        FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
        """)) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        String type = result.getString(1);
        if ("varchar".equals(type)) {
          type += "(" + result.getLong(2) + ")";
        } else if ("datetime".equals(type)) {
          type += "(" + result.getInt(3) + ")";
        }
        return type + ":" + result.getString(4);
      }
    }
  }

  private void executeHarden(Connection connection, V9ManifestBundle bundle, String step,
      HardenTarget target, String resource) throws SQLException {
    fence.verifyBoundary(step, AttemptState.HARDENING_STARTED);
    int existing = hardenObjectCount(connection, target);
    int expected = target.checks().size() + target.indexes().size();
    if (existing == expected) {
      verifyHardenTarget(connection, target);
      return;
    }
    if (existing != 0) {
      throw drift(step + " is partially applied");
    }
    executePayload(connection, bundle, resource);
    verifyHardenTarget(connection, target);
  }

  private static int hardenObjectCount(Connection connection, HardenTarget target)
      throws SQLException {
    int count = 0;
    for (String check : target.checks().keySet()) {
      if (constraintExists(connection, check)) {
        count++;
      }
    }
    for (String index : target.indexes().keySet()) {
      if (indexExists(connection, target.table(), index)) {
        count++;
      }
    }
    return count;
  }

  private static void verifyHardenTarget(Connection connection, HardenTarget target)
      throws SQLException {
    for (Map.Entry<String, String> check : target.checks().entrySet()) {
      String actual = checkClause(connection, check.getKey());
      if (actual == null || !normalizeDefinition(actual).equals(
          normalizeDefinition(check.getValue()))) {
        throw drift("CHECK definition drift: " + check.getKey());
      }
    }
    for (Map.Entry<String, String> index : target.indexes().entrySet()) {
      if (!index.getValue().equals(indexSignature(connection, target.table(), index.getKey()))) {
        throw drift("index definition drift: " + index.getKey());
      }
    }
    for (String column : target.notNullColumns()) {
      if (queryLong(connection, """
          SELECT COUNT(*) FROM information_schema.columns
          WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
            AND is_nullable='NO'
          """, target.table(), column) != 1) {
        throw drift("NOT NULL definition drift: " + target.table() + "." + column);
      }
    }
    for (String absent : target.absentIndexes()) {
      if (indexExists(connection, target.table(), absent)) {
        throw drift("obsolete index still exists: " + absent);
      }
    }
  }

  private static String checkClause(Connection connection, String constraint)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT cc.check_clause
        FROM information_schema.check_constraints cc
        JOIN information_schema.table_constraints tc
          ON tc.constraint_schema=cc.constraint_schema
         AND tc.constraint_name=cc.constraint_name
        WHERE tc.constraint_schema=DATABASE() AND tc.constraint_name=?
        """)) {
      statement.setString(1, constraint);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getString(1) : null;
      }
    }
  }

  private static String indexSignature(Connection connection, String table, String index)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
        GROUP BY non_unique
        """)) {
      statement.setString(1, table);
      statement.setString(2, index);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        return (result.getInt(1) == 0 ? "U:" : "N:") + result.getString(2);
      }
    }
  }

  private static boolean indexExists(Connection connection, String table, String index)
      throws SQLException {
    return queryLong(connection, """
        SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
        """, table, index) > 0;
  }

  private static String normalizeDefinition(String value) {
    return value.replace("`", "").replace("\\", "")
        .replaceAll("(?i)_[a-z0-9]+(?=')", "")
        .replaceAll("[\\s()]", "")
        .toLowerCase();
  }

  private void executeDataTransaction(Connection connection, V9ManifestBundle bundle)
      throws Exception {
    boolean originalAutoCommit = connection.getAutoCommit();
    boolean committed = false;
    try {
      connection.setAutoCommit(false);
      fence.verifyBoundary("D-001:LEGACY_RULES", AttemptState.DML_STARTED);
      V9LegacyRuleSupport.migrate(connection, bundle);
      fence.verifyBoundary("D-001:ENUMS", AttemptState.DML_STARTED);
      executePayload(connection, bundle, "db/migration-payload/v9/d_data.sql");
      fence.verifyBoundary("D-001:QUALIFICATIONS", AttemptState.DML_STARTED);
      seedQualifications(connection, bundle.manifest().path("qualificationSeeds"));
      fence.verifyBoundary("D-001:SUBJECTS", AttemptState.DML_STARTED);
      seedSubjects(connection, bundle.manifest().path("subjectSeeds"));
      fence.verifyBoundary("D_TRANSACTION_COMMIT", AttemptState.DML_STARTED);
      connection.commit();
      committed = true;
    } catch (Throwable failure) {
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
        throw unknown("D transaction rollback failed", failure);
      }
      if (failure instanceof V9MigrationException migrationFailure) {
        throw migrationFailure;
      }
      throw unknown("D transaction state is unknown", failure);
    } finally {
      try {
        connection.setAutoCommit(originalAutoCommit);
      } catch (SQLException restoreFailure) {
        throw unknown("D transaction autoCommit restore failed", restoreFailure);
      }
    }
    if (!committed) {
      throw unknown("D transaction commit was not confirmed", null);
    }
  }

  private static void seedQualifications(Connection connection, JsonNode seeds)
      throws SQLException {
    String update = """
        UPDATE exam_qualification SET domain_code=?, official_name=?, level_code=?,
          source_url=?, verified_at=?, verification_status=?, catalog_version=?, catalog_status=?,
          updated_at=NOW(3) WHERE qualification_code=? AND deleted=0
        """;
    String insert = """
        INSERT INTO exam_qualification
          (id,qualification_code,qualification_name,official_name,domain_code,level_code,
           source_url,verified_at,verification_status,catalog_version,catalog_status,
           status,sort_order,created_at,updated_at,deleted)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(3),NOW(3),0)
        """;
    for (JsonNode seed : seeds) {
      int updated;
      try (PreparedStatement statement = connection.prepareStatement(update)) {
        statement.setString(1, seed.path("domainCode").asText());
        statement.setString(2, seed.path("officialName").asText());
        statement.setString(3, seed.path("levelCode").asText());
        statement.setString(4, seed.path("sourceUrl").asText());
        statement.setTimestamp(5, Timestamp.from(OffsetDateTime.parse(
            seed.path("verifiedAt").asText()).toInstant()));
        statement.setString(6, seed.path("verificationStatus").asText());
        statement.setString(7, seed.path("catalogVersion").asText());
        statement.setString(8, seed.path("catalogStatus").asText());
        statement.setString(9, seed.path("qualificationCode").asText());
        updated = statement.executeUpdate();
      }
      if (updated == 0) {
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
          statement.setLong(1, seed.path("seedOrder").asLong());
          statement.setString(2, seed.path("qualificationCode").asText());
          statement.setString(3, seed.path("displayName").asText());
          statement.setString(4, seed.path("officialName").asText());
          statement.setString(5, seed.path("domainCode").asText());
          statement.setString(6, seed.path("levelCode").asText());
          statement.setString(7, seed.path("sourceUrl").asText());
          statement.setTimestamp(8, Timestamp.from(OffsetDateTime.parse(
              seed.path("verifiedAt").asText()).toInstant()));
          statement.setString(9, seed.path("verificationStatus").asText());
          statement.setString(10, seed.path("catalogVersion").asText());
          statement.setString(11, seed.path("catalogStatus").asText());
          statement.setString(12, seed.path("operationalStatus").asText());
          statement.setInt(13, seed.path("sortOrder").asInt());
          statement.executeUpdate();
        }
      }
    }
  }

  private static void seedSubjects(Connection connection, JsonNode seeds) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          UPDATE exam_subject SET subject_code='COMPREHENSIVE_KNOWLEDGE'
          WHERE qualification_id=1 AND subject_code='COMPREHENSIVE' AND deleted=0
          """);
    }
    String qualificationId =
        "SELECT id FROM exam_qualification WHERE qualification_code=? AND deleted=0";
    String update = """
        UPDATE exam_subject SET official_name=?, subject_type=?, updated_at=NOW(3)
        WHERE qualification_id=? AND subject_code=? AND deleted=0
        """;
    String insert = """
        INSERT INTO exam_subject
          (id,qualification_id,subject_code,subject_name,official_name,subject_type,sort_order,
           status,created_at,updated_at,deleted)
        VALUES (?,?,?,?,?,?,?,?,NOW(3),NOW(3),0)
        """;
    for (JsonNode seed : seeds) {
      long qualification;
      try (PreparedStatement statement = connection.prepareStatement(qualificationId)) {
        statement.setString(1, seed.path("qualificationCode").asText());
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) {
            throw drift("subject seed qualification does not exist");
          }
          qualification = result.getLong(1);
        }
      }
      int updated;
      try (PreparedStatement statement = connection.prepareStatement(update)) {
        statement.setString(1, seed.path("officialName").asText());
        statement.setString(2, seed.path("subjectType").asText());
        statement.setLong(3, qualification);
        statement.setString(4, seed.path("subjectCode").asText());
        updated = statement.executeUpdate();
      }
      if (updated == 0) {
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
          statement.setLong(1, seed.path("seedOrder").asLong());
          statement.setLong(2, qualification);
          statement.setString(3, seed.path("subjectCode").asText());
          statement.setString(4, seed.path("displayName").asText());
          statement.setString(5, seed.path("officialName").asText());
          statement.setString(6, seed.path("subjectType").asText());
          statement.setInt(7, seed.path("sortOrder").asInt());
          statement.setString(8, seed.path("status").asText());
          statement.executeUpdate();
        }
      }
    }
  }

  private String readOnlyDataEvidence(V9ManifestBundle bundle) throws Exception {
    try (Connection connection = readOnlyConnectionSupplier.get()) {
      connection.setReadOnly(true);
      if (queryLong(connection, "SELECT COUNT(*) FROM exam_qualification WHERE deleted=0") != 20
          || queryLong(connection, "SELECT COUNT(*) FROM exam_subject WHERE deleted=0") != 45) {
        throw new V9MigrationException(V9ErrorCode.V9_DML_FINGERPRINT_MISMATCH,
            "canonical seed count fingerprint mismatch");
      }
      V9DatabaseFingerprints.Fingerprints fingerprints =
          V9DatabaseFingerprints.calculate(connection);
      JsonNode manifest = bundle.manifest();
      if (!manifest.path("expectedSeedFingerprint").asText().equals(
          fingerprints.seedFingerprint())
          || !manifest.path("expectedBusinessFingerprint").asText().equals(
              fingerprints.businessFingerprint())) {
        throw new V9MigrationException(V9ErrorCode.V9_DML_FINGERPRINT_MISMATCH,
            "canonical database fingerprints do not match the approved manifest");
      }
      return JcsCanonicalizer.sha256Hex((fingerprints.seedFingerprint() + ":"
          + fingerprints.businessFingerprint()).getBytes(StandardCharsets.UTF_8));
    }
  }

  private static String schemaEvidence(Connection connection) throws SQLException {
    return JcsCanonicalizer.sha256Hex((connection.getCatalog() + ":" + countColumns(connection,
        "exam_qualification", QUALIFICATION_COLUMNS) + ":" + countColumns(connection,
        "exam_rule", RULE_COLUMNS)).getBytes(StandardCharsets.UTF_8));
  }

  private static void executePayload(Connection connection, V9ManifestBundle bundle,
      String resource) throws SQLException {
    String sql = new String(bundle.resourceBytes(resource), StandardCharsets.UTF_8);
    for (String statementSql : sql.split(";\\s*(?:\\n|$)")) {
      if (!statementSql.isBlank()) {
        try (Statement statement = connection.createStatement()) {
          statement.execute(statementSql);
        }
      }
    }
  }

  private static int countColumns(Connection connection, String table, List<String> columns)
      throws SQLException {
    String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
    String sql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
        + "AND table_name=? AND column_name IN (" + placeholders + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, table);
      for (int index = 0; index < columns.size(); index++) {
        statement.setString(index + 2, columns.get(index));
      }
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private static boolean constraintExists(Connection connection, String name)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT COUNT(*) FROM information_schema.table_constraints
        WHERE constraint_schema=DATABASE() AND constraint_name=?
        """)) {
      statement.setString(1, name);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1) == 1;
      }
    }
  }

  private static long queryLong(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private static long queryLong(Connection connection, String sql, String first, String second)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, first);
      statement.setString(2, second);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private record HardenTarget(String table, Map<String, String> checks,
                              Map<String, String> indexes, List<String> notNullColumns,
                              Set<String> absentIndexes) {
  }

  private static V9MigrationException drift(String message) {
    return new V9MigrationException(V9ErrorCode.V9_DDL_STATE_DRIFT, message);
  }

  private static V9MigrationException unknown(String message, Throwable cause) {
    return new V9MigrationException(V9ErrorCode.V9_TRANSACTION_STATE_UNKNOWN, message, cause);
  }
}
