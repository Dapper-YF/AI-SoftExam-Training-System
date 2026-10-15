package com.aiexam.app.migration.v9;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class V9LegacyRuleSupport {
  static final String CONVERTER_ID = "SOFT_EXAM_LEGACY_RULE";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private V9LegacyRuleSupport() {
  }

  static void validate(Connection connection, V9ManifestBundle bundle) throws SQLException {
    loadValidated(connection, bundle);
  }

  static void migrate(Connection connection, V9ManifestBundle bundle) throws SQLException {
    List<Mapping> mappings = loadValidated(connection, bundle);
    if (mappings.isEmpty()) {
      return;
    }
    Map<String, List<Mapping>> groups = new LinkedHashMap<>();
    for (Mapping mapping : mappings) {
      groups.computeIfAbsent(mapping.mergeGroup(), ignored -> new ArrayList<>()).add(mapping);
    }
    for (List<Mapping> group : groups.values()) {
      group.sort(Comparator.comparingInt(Mapping::mergeOrder));
      Mapping winner = group.get(0);
      try (PreparedStatement statement = connection.prepareStatement("""
          UPDATE exam_rule SET qualification_id=?, subject_id=NULL, rule_code=?, rule_version=?,
            rule_json=CAST(? AS JSON), effective_at=NULL, period_code=?, effective_from=?,
            effective_to=?, published_at=NULL, version=0, updated_at=NOW(3)
          WHERE id=? AND deleted=0
          """)) {
        statement.setLong(1, winner.targetQualificationId());
        statement.setString(2, winner.targetRuleCode());
        statement.setString(3, winner.targetRuleVersion());
        statement.setString(4, winner.targetRuleJson().toString());
        statement.setString(5, winner.targetPeriodCode());
        statement.setTimestamp(6, Timestamp.valueOf(winner.targetEffectiveFrom()));
        statement.setTimestamp(7, Timestamp.valueOf(winner.targetEffectiveTo()));
        statement.setLong(8, winner.sourceRuleId());
        requireSingleUpdate(statement.executeUpdate(), "legacy rule winner changed after preflight");
      }
      for (int index = 1; index < group.size(); index++) {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE exam_rule SET deleted=1, updated_at=NOW(3) WHERE id=? AND deleted=0
            """)) {
          statement.setLong(1, group.get(index).sourceRuleId());
          requireSingleUpdate(statement.executeUpdate(), "legacy merge member changed after preflight");
        }
      }
    }
  }

  static String computeSourceSnapshotSha256(Connection connection) throws SQLException {
    return JcsCanonicalizer.sha256Hex(JcsCanonicalizer.canonicalize(sourceSnapshot(
        loadSources(connection))));
  }

  static String computeManifestSha256(Connection connection) throws SQLException {
    Header header = loadHeader(connection);
    List<Mapping> mappings = loadMappings(connection);
    return JcsCanonicalizer.sha256Hex(JcsCanonicalizer.canonicalize(
        manifestProjection(header, mappings)));
  }

  private static List<Mapping> loadValidated(Connection connection, V9ManifestBundle bundle)
      throws SQLException {
    List<SourceRule> sources = loadSources(connection);
    String version = bundle.manifest().path("legacyManifestVersion").asText();
    if (sources.isEmpty()) {
      if (!"EMPTY-V1".equals(version)) {
        throw invalid("non-empty legacy manifest cannot target an empty rule baseline");
      }
      return List.of();
    }
    if ("EMPTY-V1".equals(version)) {
      throw invalid("legacy exam_rule rows require a bound reviewed manifest");
    }
    requireManifestTables(connection);
    Header header = loadHeader(connection);
    if (!version.equals(header.manifestVersion())
        || !"9".equals(header.targetSchemaVersion())
        || header.reviewerArch().equals(header.reviewerData())) {
      throw invalid("legacy manifest header is not a distinct two-person V9 approval");
    }
    if (!bundle.manifest().path("legacyManifestSha256").asText()
        .equals(header.manifestSha256())) {
      throw invalid("legacy manifest header does not match the canonical bundle");
    }
    String snapshot = JcsCanonicalizer.sha256Hex(JcsCanonicalizer.canonicalize(
        sourceSnapshot(sources)));
    if (!snapshot.equals(header.sourceSnapshotHash())) {
      throw invalid("legacy source snapshot hash drifted");
    }
    List<Mapping> mappings = loadMappings(connection);
    if (mappings.size() != sources.size()) {
      throw invalid("legacy manifest mapping coverage is incomplete");
    }
    String computedManifest = JcsCanonicalizer.sha256Hex(JcsCanonicalizer.canonicalize(
        manifestProjection(header, mappings)));
    if (!computedManifest.equals(header.manifestSha256())) {
      throw invalid("reviewed legacy manifest content hash drifted");
    }
    Map<Long, SourceRule> sourceById = new HashMap<>();
    for (SourceRule source : sources) {
      sourceById.put(source.id(), source);
    }
    Set<Long> mapped = new HashSet<>();
    Map<String, Mapping> groupIdentity = new HashMap<>();
    Map<String, Set<Integer>> groupOrders = new HashMap<>();
    for (Mapping mapping : mappings) {
      SourceRule source = sourceById.get(mapping.sourceRuleId());
      if (source == null || !mapped.add(mapping.sourceRuleId())) {
        throw invalid("legacy manifest contains an orphan or duplicate source mapping");
      }
      validateMapping(bundle, connection, source, mapping);
      Mapping identity = groupIdentity.putIfAbsent(mapping.mergeGroup(), mapping);
      if (identity != null && !identity.sameTarget(mapping)) {
        throw invalid("legacy merge group has conflicting target identities");
      }
      if (!groupOrders.computeIfAbsent(mapping.mergeGroup(), ignored -> new HashSet<>())
          .add(mapping.mergeOrder())) {
        throw invalid("legacy merge order is duplicated");
      }
    }
    for (Map.Entry<String, Set<Integer>> entry : groupOrders.entrySet()) {
      int maximum = entry.getValue().stream().mapToInt(Integer::intValue).max().orElse(0);
      if (maximum != entry.getValue().size() || !entry.getValue().contains(1)) {
        throw invalid("legacy merge order must be contiguous from one");
      }
    }
    validateTargetConflicts(groupIdentity.values());
    return mappings;
  }

  private static void validateMapping(V9ManifestBundle bundle, Connection connection,
      SourceRule source, Mapping mapping) throws SQLException {
    if (!source.ruleCode().equals(mapping.sourceRuleCode())
        || source.qualificationId() != mapping.sourceQualificationId()
        || !java.util.Objects.equals(source.subjectId(), mapping.sourceSubjectId())) {
      throw invalid("legacy source anchor does not match the reviewed manifest");
    }
    if (!source.fingerprint().equals(mapping.contentFingerprint())) {
      throw invalid("legacy source content fingerprint drifted");
    }
    if (!java.util.Objects.equals(source.effectiveAt(), mapping.expectedEffectiveAt())) {
      throw invalid("legacy effective_at differs from the reviewed manifest");
    }
    if (source.effectiveAt() != null
        && !source.effectiveAt().equals(mapping.targetEffectiveFrom())) {
      throw invalid("legacy effective_at must equal the explicit target effectiveFrom");
    }
    if (!"0.9".equals(mapping.sourceSchemaVersion())) {
      throw invalid("legacy rule schema is unsupported");
    }
    if (!CONVERTER_ID.equals(mapping.converterId())
        || !bundle.manifest().path("legacyConverterVersion").asText()
        .equals(mapping.converterVersion())) {
      throw invalid("legacy converter identity does not match the canonical bundle");
    }
    if (!"EXAM_PERIOD_RULE".equals(mapping.targetRuleCode())
        || !mapping.targetEffectiveFrom().isBefore(mapping.targetEffectiveTo())) {
      throw invalid("legacy target rule identity or effective range is invalid");
    }
    JsonNode converted = convert(source.ruleJson(), mapping.sourceSchemaVersion());
    if (!JcsCanonicalizer.sha256Hex(JcsCanonicalizer.canonicalize(converted)).equals(
        JcsCanonicalizer.sha256Hex(JcsCanonicalizer.canonicalize(mapping.convertedRuleJson())))
        || !mapping.convertedRuleJson().equals(mapping.targetRuleJson())) {
      throw invalid("legacy deterministic conversion differs from targetRuleJson");
    }
    validateTargetRuleJson(connection, mapping);
  }

  private static JsonNode convert(JsonNode source, String schemaVersion) {
    if (!"0.9".equals(schemaVersion) || !source.isObject()
        || !"0.9".equals(source.path("schemaVersion").asText())
        || !source.has("targetRule") || !source.path("targetRule").isObject()) {
      throw invalid("legacy converter input is unsupported");
    }
    Set<String> fields = new HashSet<>();
    source.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(Set.of("schemaVersion", "targetRule"))) {
      throw invalid("legacy converter input contains unknown fields");
    }
    return source.path("targetRule");
  }

  private static void validateTargetRuleJson(Connection connection, Mapping mapping)
      throws SQLException {
    JsonNode rule = mapping.targetRuleJson();
    Set<String> required = Set.of("schemaVersion", "ruleVersion", "qualificationCode",
        "examCycle", "periodCode", "effectiveFrom", "effectiveTo", "enabled",
        "totalDurationMinutes", "deadlinePolicy", "subjects", "verification");
    Set<String> allowed = new HashSet<>(required);
    allowed.add("sharedForceSubmitMinutes");
    Set<String> actual = new HashSet<>();
    rule.fieldNames().forEachRemaining(actual::add);
    if (!actual.containsAll(required) || !allowed.containsAll(actual)
        || !"1.0".equals(rule.path("schemaVersion").asText())
        || !mapping.targetRuleVersion().equals(rule.path("ruleVersion").asText())
        || !mapping.targetPeriodCode().equals(rule.path("periodCode").asText())
        || !rule.path("subjects").isArray() || rule.path("subjects").isEmpty()
        || !rule.path("verification").isObject()) {
      throw invalid("targetRuleJson does not satisfy the frozen 1.0 root contract");
    }
    String qualificationCode;
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT qualification_code FROM exam_qualification WHERE id=? AND deleted=0
        """)) {
      statement.setLong(1, mapping.targetQualificationId());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw invalid("target qualification does not exist");
        }
        qualificationCode = result.getString(1);
      }
    }
    if (!qualificationCode.equals(rule.path("qualificationCode").asText())) {
      throw invalid("targetRuleJson qualificationCode does not match its relation anchor");
    }
  }

  private static void validateTargetConflicts(Iterable<Mapping> targets) {
    List<Mapping> values = new ArrayList<>();
    targets.forEach(values::add);
    for (int left = 0; left < values.size(); left++) {
      for (int right = left + 1; right < values.size(); right++) {
        Mapping one = values.get(left);
        Mapping two = values.get(right);
        if (one.targetQualificationId() == two.targetQualificationId()
            && one.targetPeriodCode().equals(two.targetPeriodCode())
            && one.targetRuleCode().equals(two.targetRuleCode())
            && one.targetRuleVersion().equals(two.targetRuleVersion())) {
          throw invalid("legacy target unique identity is duplicated");
        }
        if (one.targetQualificationId() == two.targetQualificationId()
            && one.targetPeriodCode().equals(two.targetPeriodCode())
            && one.targetRuleCode().equals(two.targetRuleCode())
            && one.targetEffectiveFrom().isBefore(two.targetEffectiveTo())
            && two.targetEffectiveFrom().isBefore(one.targetEffectiveTo())) {
          throw invalid("legacy target effective ranges overlap");
        }
      }
    }
  }

  private static List<SourceRule> loadSources(Connection connection) throws SQLException {
    List<SourceRule> values = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT id,qualification_id,subject_id,rule_code,rule_version,status,effective_at,
               CAST(rule_json AS CHAR CHARACTER SET utf8mb4)
        FROM exam_rule WHERE deleted=0 ORDER BY id
        """); ResultSet result = statement.executeQuery()) {
      while (result.next()) {
        JsonNode json = parseJson(result.getString(8), "legacy rule_json is invalid");
        Instant effectiveAt = result.getTimestamp(7) == null ? null
            : result.getTimestamp(7).toLocalDateTime().toInstant(ZoneOffset.UTC);
        SourceRule source = new SourceRule(result.getLong(1), result.getLong(2),
            nullableLong(result, 3), result.getString(4), result.getString(5),
            result.getString(6), effectiveAt, json, null);
        values.add(source.withFingerprint(sourceFingerprint(source)));
      }
    }
    return values;
  }

  private static Header loadHeader(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT manifest_version,source_snapshot_hash,target_schema_version,generated_at,
               reviewer_arch,reviewer_data,manifest_sha256
        FROM soft_exam_v9_legacy_manifest_header
        """); ResultSet result = statement.executeQuery()) {
      if (!result.next()) {
        throw invalid("legacy manifest header is missing");
      }
      Header header = new Header(result.getString(1), result.getString(2), result.getString(3),
          result.getTimestamp(4).toInstant(), result.getString(5), result.getString(6),
          result.getString(7));
      if (result.next()) {
        throw invalid("legacy manifest header must contain exactly one row");
      }
      return header;
    }
  }

  private static List<Mapping> loadMappings(Connection connection) throws SQLException {
    List<Mapping> values = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT source_rule_id,source_rule_code,source_qualification_id,source_subject_id,
               source_schema_version,content_fingerprint,expected_effective_at,
               target_qualification_id,target_period_code,target_effective_from,
               target_effective_to,target_rule_code,target_rule_version,merge_group,merge_order,
               CAST(target_rule_json AS CHAR CHARACTER SET utf8mb4),
               CAST(converted_rule_json AS CHAR CHARACTER SET utf8mb4),converter_id,
               converter_version
        FROM soft_exam_v9_legacy_manifest_mapping ORDER BY source_rule_id
        """); ResultSet result = statement.executeQuery()) {
      while (result.next()) {
        values.add(new Mapping(result.getLong(1), result.getString(2), result.getLong(3),
            nullableLong(result, 4), result.getString(5), result.getString(6),
            nullableInstant(result, 7), result.getLong(8), result.getString(9),
            result.getTimestamp(10).toLocalDateTime(), result.getTimestamp(11).toLocalDateTime(),
            result.getString(12), result.getString(13), result.getString(14), result.getInt(15),
            parseJson(result.getString(16), "targetRuleJson is invalid"),
            parseJson(result.getString(17), "converted rule JSON is invalid"),
            result.getString(18), result.getString(19)));
      }
    }
    return values;
  }

  private static ObjectNode sourceSnapshot(List<SourceRule> sources) {
    ObjectNode root = MAPPER.createObjectNode();
    ArrayNode rows = root.putArray("rules");
    for (SourceRule source : sources) {
      rows.add(sourceProjection(source));
    }
    return root;
  }

  private static ObjectNode manifestProjection(Header header, List<Mapping> mappings) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("manifestVersion", header.manifestVersion());
    root.put("sourceSnapshotHash", header.sourceSnapshotHash());
    root.put("targetSchemaVersion", header.targetSchemaVersion());
    root.put("generatedAt", header.generatedAt().toString());
    ArrayNode reviewers = root.putArray("reviewedBy");
    reviewers.add(header.reviewerArch());
    reviewers.add(header.reviewerData());
    ArrayNode values = root.putArray("mappings");
    for (Mapping mapping : mappings) {
      ObjectNode item = values.addObject();
      item.put("legacyRuleId", mapping.sourceRuleId());
      item.put("legacyRuleCode", mapping.sourceRuleCode());
      item.put("qualificationId", mapping.sourceQualificationId());
      putNullable(item, "subjectId", mapping.sourceSubjectId());
      item.put("legacySchemaVersion", mapping.sourceSchemaVersion());
      item.put("contentFingerprint", mapping.contentFingerprint());
      putNullable(item, "effectiveAtExpected", mapping.expectedEffectiveAt());
      item.put("targetQualificationId", mapping.targetQualificationId());
      item.put("targetPeriodCode", mapping.targetPeriodCode());
      item.put("targetEffectiveFrom", mapping.targetEffectiveFrom().toString());
      item.put("targetEffectiveTo", mapping.targetEffectiveTo().toString());
      item.put("targetRuleCode", mapping.targetRuleCode());
      item.put("targetRuleVersion", mapping.targetRuleVersion());
      item.put("mergeGroup", mapping.mergeGroup());
      item.put("mergeOrder", mapping.mergeOrder());
      item.set("targetRuleJson", mapping.targetRuleJson());
      item.put("converterId", mapping.converterId());
      item.put("converterVersion", mapping.converterVersion());
    }
    return root;
  }

  private static ObjectNode sourceProjection(SourceRule source) {
    ObjectNode value = MAPPER.createObjectNode();
    value.put("id", source.id());
    value.put("qualificationId", source.qualificationId());
    putNullable(value, "subjectId", source.subjectId());
    value.put("ruleCode", source.ruleCode());
    value.put("ruleVersion", source.ruleVersion());
    value.put("status", source.status());
    putNullable(value, "effectiveAt", source.effectiveAt());
    value.set("ruleJson", source.ruleJson());
    return value;
  }

  private static String sourceFingerprint(SourceRule source) {
    return JcsCanonicalizer.sha256Hex(JcsCanonicalizer.canonicalize(sourceProjection(source)));
  }

  private static void requireManifestTables(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
          AND table_name IN ('soft_exam_v9_legacy_manifest_header',
                             'soft_exam_v9_legacy_manifest_mapping')
        """); ResultSet result = statement.executeQuery()) {
      result.next();
      if (result.getInt(1) != 2) {
        throw invalid("reviewed legacy manifest input tables are missing");
      }
    }
  }

  private static JsonNode parseJson(String json, String message) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception exception) {
      throw new V9MigrationException(V9ErrorCode.V9_MANIFEST_INVALID, message, exception);
    }
  }

  private static Long nullableLong(ResultSet result, int index) throws SQLException {
    long value = result.getLong(index);
    return result.wasNull() ? null : value;
  }

  private static Instant nullableInstant(ResultSet result, int index) throws SQLException {
    Timestamp value = result.getTimestamp(index);
    return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
  }

  private static void putNullable(ObjectNode node, String name, Object value) {
    if (value == null) {
      node.putNull(name);
    } else if (value instanceof Number number) {
      node.put(name, number.longValue());
    } else {
      node.put(name, value.toString());
    }
  }

  private static void requireSingleUpdate(int count, String message) {
    if (count != 1) {
      throw invalid(message);
    }
  }

  private static V9MigrationException invalid(String message) {
    return new V9MigrationException(V9ErrorCode.V9_MANIFEST_INVALID, message);
  }

  private record Header(String manifestVersion, String sourceSnapshotHash,
                        String targetSchemaVersion, Instant generatedAt,
                        String reviewerArch, String reviewerData, String manifestSha256) {
  }

  private record SourceRule(long id, long qualificationId, Long subjectId, String ruleCode,
                            String ruleVersion, String status, Instant effectiveAt,
                            JsonNode ruleJson, String fingerprint) {
    SourceRule withFingerprint(String value) {
      return new SourceRule(id, qualificationId, subjectId, ruleCode, ruleVersion, status,
          effectiveAt, ruleJson, value);
    }
  }

  private record Mapping(long sourceRuleId, String sourceRuleCode,
                         long sourceQualificationId, Long sourceSubjectId,
                         String sourceSchemaVersion, String contentFingerprint,
                         Instant expectedEffectiveAt, long targetQualificationId,
                         String targetPeriodCode, LocalDateTime targetEffectiveFrom,
                         LocalDateTime targetEffectiveTo, String targetRuleCode,
                         String targetRuleVersion, String mergeGroup, int mergeOrder,
                         JsonNode targetRuleJson, JsonNode convertedRuleJson,
                         String converterId, String converterVersion) {
    boolean sameTarget(Mapping other) {
      return targetQualificationId == other.targetQualificationId
          && targetPeriodCode.equals(other.targetPeriodCode)
          && targetEffectiveFrom.equals(other.targetEffectiveFrom)
          && targetEffectiveTo.equals(other.targetEffectiveTo)
          && targetRuleCode.equals(other.targetRuleCode)
          && targetRuleVersion.equals(other.targetRuleVersion)
          && targetRuleJson.equals(other.targetRuleJson);
    }
  }
}
