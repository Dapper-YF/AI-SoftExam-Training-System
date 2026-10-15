package com.aiexam.app.migration.v9;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

public final class V9ManifestBundle {
  public static final String MANIFEST_RESOURCE =
      "db/migration-manifest/V9__soft_exam_taxonomy_rule_seed.manifest.json";
  private static final Set<String> ROOT_FIELDS = Set.of(
      "manifestSchemaVersion", "migrationVersion", "description",
      "orchestratorProtocolVersion", "steps", "dmlBatches", "targetDefinitions",
      "resources", "qualificationSeeds", "subjectSeeds", "legacyConverterVersion",
      "legacyConverterSha256", "legacyManifestVersion", "legacyManifestSha256",
      "expectedFinalSchemaFingerprint", "expectedSeedFingerprint",
      "expectedBusinessFingerprint");
  private static final Set<String> STEP_FIELDS = Set.of(
      "stepId", "phase", "operationType", "targetRefs", "resourceRefs");
  private static final Set<String> DML_BATCH_FIELDS = Set.of(
      "batchId", "sequence", "resourceRefs", "expectedAffectedRows",
      "expectedBatchFingerprint");
  private static final Set<String> TARGET_DEFINITION_FIELDS = Set.of(
      "objectType", "tableName", "objectName", "canonicalDefinition", "definitionSha256");
  private static final Set<String> RESOURCE_FIELDS = Set.of(
      "path", "phase", "operationType", "encoding", "lineEnding", "byteLength", "sha256");
  private static final Set<String> QUALIFICATION_SEED_FIELDS = Set.of(
      "seedOrder", "qualificationCode", "domainCode", "officialName", "displayName",
      "levelCode", "sortOrder", "sourceUrl", "verifiedAt", "verificationStatus",
      "catalogVersion", "catalogStatus", "operationalStatus");
  private static final Set<String> SUBJECT_SEED_FIELDS = Set.of(
      "seedOrder", "qualificationCode", "subjectCode", "officialName", "displayName",
      "subjectType", "sortOrder", "status");
  private static final List<String> EXPECTED_STEP_IDS = List.of(
      "01_QUALIFICATION_EXPAND", "02_SUBJECT_EXPAND", "03_RULE_EXPAND", "D_DATA",
      "04_QUALIFICATION_HARDEN", "05_SUBJECT_HARDEN", "06_RULE_HARDEN");
  private static final List<String> EXPECTED_STEP_PHASES = List.of("E", "E", "E", "D", "H", "H", "H");
  private static final List<String> EXPECTED_STEP_OPERATIONS = List.of(
      "DDL", "DDL", "DDL", "DML", "DDL", "DDL", "DDL");
  private static final ObjectMapper STRICT_MAPPER = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build());

  private final JsonNode manifest;
  private final String fullManifestSha256;
  private final int flywayChecksum;

  private V9ManifestBundle(JsonNode manifest) {
    validate(manifest);
    this.manifest = manifest;
    byte[] digest = JcsCanonicalizer.sha256(JcsCanonicalizer.canonicalize(manifest));
    this.fullManifestSha256 = java.util.HexFormat.of().formatHex(digest);
    this.flywayChecksum = ByteBuffer.wrap(digest).getInt();
    verifyResources(manifest.path("resources"));
  }

  public static V9ManifestBundle load() {
    ClassLoader loader = V9ManifestBundle.class.getClassLoader();
    try (InputStream input = loader.getResourceAsStream(MANIFEST_RESOURCE)) {
      if (input == null) {
        throw invalid("canonical V9 manifest is missing");
      }
      return parse(input.readAllBytes());
    } catch (IOException exception) {
      throw new V9MigrationException(V9ErrorCode.V9_MANIFEST_INVALID,
          "canonical V9 manifest cannot be read", exception);
    }
  }

  public static V9ManifestBundle parse(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || startsWithBom(bytes)) {
      throw invalid("canonical V9 manifest bytes are missing or contain a BOM");
    }
    try {
      StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes));
      return new V9ManifestBundle(STRICT_MAPPER.readTree(bytes));
    } catch (CharacterCodingException exception) {
      throw new V9MigrationException(V9ErrorCode.V9_MANIFEST_INVALID,
          "canonical V9 manifest is not valid UTF-8", exception);
    } catch (IOException exception) {
      throw new V9MigrationException(V9ErrorCode.V9_MANIFEST_INVALID,
          "canonical V9 manifest is not valid JSON", exception);
    }
  }

  public JsonNode manifest() {
    return manifest.deepCopy();
  }

  public String fullManifestSha256() {
    return fullManifestSha256;
  }

  public int flywayChecksum() {
    return flywayChecksum;
  }

  public byte[] resourceBytes(String path) {
    try (InputStream input = V9ManifestBundle.class.getClassLoader().getResourceAsStream(path)) {
      if (input == null) {
        throw invalid("manifest resource is missing");
      }
      return input.readAllBytes();
    } catch (IOException exception) {
      throw new V9MigrationException(V9ErrorCode.V9_MANIFEST_INVALID,
          "manifest resource cannot be read", exception);
    }
  }

  private void verifyResources(JsonNode resources) {
    for (JsonNode resource : resources) {
      String path = requiredText(resource, "path");
      byte[] bytes = resourceBytes(path);
      if (bytes.length == 0 || startsWithBom(bytes)
          || new String(bytes, StandardCharsets.UTF_8).contains("\r")) {
        throw invalid("payload must be non-empty UTF-8 without BOM and use LF only");
      }
      if (bytes.length != resource.path("byteLength").asInt()
          || !JcsCanonicalizer.sha256Hex(bytes).equals(requiredText(resource, "sha256"))) {
        throw invalid("payload length or SHA-256 does not match manifest");
      }
    }
  }

  private static void validate(JsonNode root) {
    if (!root.isObject()) {
      throw invalid("manifest root must be an object");
    }
    Set<String> fields = new HashSet<>();
    root.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(ROOT_FIELDS)) {
      throw invalid("manifest root fields do not match Draft 2020-12 contract");
    }
    if (root.path("manifestSchemaVersion").asInt(-1) != 1
        || !"9".equals(root.path("migrationVersion").asText())
        || !"soft exam taxonomy rule seed".equals(root.path("description").asText())
        || !"1".equals(root.path("orchestratorProtocolVersion").asText())) {
      throw invalid("manifest identity is invalid");
    }
    requireArraySize(root, "steps", 7);
    requireArraySize(root, "qualificationSeeds", 20);
    requireArraySize(root, "subjectSeeds", 45);
    requireNonEmptyArray(root, "dmlBatches");
    requireNonEmptyArray(root, "targetDefinitions");
    requireNonEmptyArray(root, "resources");
    validateSequence(root.path("qualificationSeeds"), "seedOrder", 20,
        List.of("qualificationCode"));
    validateSequence(root.path("subjectSeeds"), "seedOrder", 45,
        List.of("qualificationCode", "subjectCode"));
    validateSequence(root.path("dmlBatches"), "sequence", root.path("dmlBatches").size(),
        List.of("batchId"));
    for (String hashField : List.of("legacyConverterSha256", "legacyManifestSha256",
        "expectedFinalSchemaFingerprint", "expectedSeedFingerprint",
        "expectedBusinessFingerprint")) {
      if (!requiredText(root, hashField).matches("[0-9a-f]{64}")) {
        throw invalid("manifest SHA-256 field is invalid");
      }
    }
    validateSteps(root.path("steps"));
    validateDmlBatches(root.path("dmlBatches"));
    validateTargetDefinitions(root.path("targetDefinitions"));
    validateResources(root.path("resources"));
    validateQualificationSeeds(root.path("qualificationSeeds"));
    validateSubjectSeeds(root.path("subjectSeeds"), root.path("qualificationSeeds"));
    validateReferences(root);
    validateFingerprints(root);
  }

  private static void validateFingerprints(JsonNode root) {
    ObjectNode converter = STRICT_MAPPER.createObjectNode();
    converter.put("converterVersion", requiredText(root, "legacyConverterVersion"));
    converter.put("strategy", "MANIFEST_ONLY_V1");
    requireHash(root, "legacyConverterSha256", converter);

    if ("EMPTY-V1".equals(requiredText(root, "legacyManifestVersion"))) {
      ObjectNode legacyManifest = STRICT_MAPPER.createObjectNode();
      legacyManifest.put("manifestVersion", "EMPTY-V1");
      legacyManifest.set("mappings", STRICT_MAPPER.createArrayNode());
      requireHash(root, "legacyManifestSha256", legacyManifest);
    }

    requireHash(root, "expectedFinalSchemaFingerprint", root.path("targetDefinitions"));
    ObjectNode seeds = managedSeedProjection(root);
    requireHash(root, "expectedSeedFingerprint", seeds);
    for (JsonNode batch : root.path("dmlBatches")) {
      if (!canonicalHash(seeds).equals(requiredText(batch, "expectedBatchFingerprint"))) {
        throw invalid("DML batch fingerprint does not match canonical seeds");
      }
    }

    ObjectNode business = STRICT_MAPPER.createObjectNode();
    ArrayNode qualifications = business.putArray("qualifications");
    for (JsonNode seed : root.path("qualificationSeeds")) {
      ObjectNode item = qualifications.addObject();
      item.put("domainCode", requiredText(seed, "domainCode"));
      item.put("levelCode", requiredText(seed, "levelCode"));
      item.put("qualificationCode", requiredText(seed, "qualificationCode"));
    }
    ArrayNode subjects = business.putArray("subjects");
    for (JsonNode seed : root.path("subjectSeeds")) {
      ObjectNode item = subjects.addObject();
      item.put("qualificationCode", requiredText(seed, "qualificationCode"));
      item.put("subjectCode", requiredText(seed, "subjectCode"));
      item.put("subjectType", requiredText(seed, "subjectType"));
    }
    requireHash(root, "expectedBusinessFingerprint", business);
  }

  private static ObjectNode managedSeedProjection(JsonNode root) {
    ObjectNode seeds = STRICT_MAPPER.createObjectNode();
    ArrayNode qualifications = seeds.putArray("qualificationSeeds");
    for (JsonNode seed : root.path("qualificationSeeds")) {
      ObjectNode item = qualifications.addObject();
      item.put("seedOrder", requiredInteger(seed, "seedOrder", 1, 20));
      for (String field : List.of("qualificationCode", "domainCode", "officialName",
          "levelCode", "sourceUrl", "verifiedAt", "verificationStatus", "catalogVersion",
          "catalogStatus")) {
        item.put(field, requiredText(seed, field));
      }
    }
    ArrayNode subjects = seeds.putArray("subjectSeeds");
    for (JsonNode seed : root.path("subjectSeeds")) {
      ObjectNode item = subjects.addObject();
      item.put("seedOrder", requiredInteger(seed, "seedOrder", 1, 45));
      for (String field : List.of("qualificationCode", "subjectCode", "officialName",
          "subjectType")) {
        item.put(field, requiredText(seed, field));
      }
    }
    return seeds;
  }

  private static void requireHash(JsonNode root, String field, JsonNode value) {
    if (!canonicalHash(value).equals(requiredText(root, field))) {
      throw invalid("manifest " + field + " does not match canonical content");
    }
  }

  private static String canonicalHash(JsonNode value) {
    return JcsCanonicalizer.sha256Hex(JcsCanonicalizer.canonicalize(value));
  }

  private static void validateSteps(JsonNode steps) {
    for (int index = 0; index < steps.size(); index++) {
      JsonNode step = steps.get(index);
      requireExactFields(step, STEP_FIELDS, "step");
      if (!EXPECTED_STEP_IDS.get(index).equals(requiredText(step, "stepId"))
          || !EXPECTED_STEP_PHASES.get(index).equals(requiredEnum(step, "phase", Set.of("E", "D", "H")))
          || !EXPECTED_STEP_OPERATIONS.get(index).equals(
              requiredEnum(step, "operationType", Set.of("DDL", "DML")))) {
        throw invalid("manifest step order or identity is invalid");
      }
      validateSortedUniqueTextArray(step, "targetRefs", "^[A-Z_]+:[a-z0-9_]+:[a-zA-Z0-9_]+$");
      validateSortedUniqueTextArray(step, "resourceRefs", resourcePathPattern());
    }
  }

  private static void validateDmlBatches(JsonNode batches) {
    Set<String> ids = new HashSet<>();
    for (JsonNode batch : batches) {
      requireExactFields(batch, DML_BATCH_FIELDS, "DML batch");
      String batchId = requiredPattern(batch, "batchId", "^D-[0-9]{3}$");
      if (!ids.add(batchId)) {
        throw invalid("manifest contains a duplicate DML batch");
      }
      requiredInteger(batch, "sequence", 1, Integer.MAX_VALUE);
      requiredInteger(batch, "expectedAffectedRows", 0, Integer.MAX_VALUE);
      requiredPattern(batch, "expectedBatchFingerprint", "^[0-9a-f]{64}$");
      validateSortedUniqueTextArray(batch, "resourceRefs", resourcePathPattern());
    }
  }

  private static void validateTargetDefinitions(JsonNode definitions) {
    List<String> keys = new ArrayList<>();
    for (JsonNode definition : definitions) {
      requireExactFields(definition, TARGET_DEFINITION_FIELDS, "target definition");
      String objectType = requiredEnum(definition, "objectType",
          Set.of("COLUMN", "GENERATED_COLUMN", "INDEX", "UNIQUE", "CHECK"));
      String tableName = requiredPattern(definition, "tableName", "^[a-z][a-z0-9_]{0,63}$");
      String objectName = requiredPattern(definition, "objectName", "^[a-zA-Z][a-zA-Z0-9_]{0,127}$");
      String canonical = requiredText(definition, "canonicalDefinition");
      String expectedHash = requiredPattern(definition, "definitionSha256", "^[0-9a-f]{64}$");
      if (!JcsCanonicalizer.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8)).equals(expectedHash)) {
        throw invalid("target definition SHA-256 does not match canonicalDefinition");
      }
      keys.add(objectType + '\u0000' + tableName + '\u0000' + objectName);
    }
    requireStrictlySorted(keys, "target definitions");
  }

  private static void validateResources(JsonNode resources) {
    List<String> paths = new ArrayList<>();
    for (JsonNode resource : resources) {
      requireExactFields(resource, RESOURCE_FIELDS, "resource");
      paths.add(requiredPattern(resource, "path", resourcePathPattern()));
      requiredEnum(resource, "phase", Set.of("E", "D", "H"));
      requiredEnum(resource, "operationType", Set.of("DDL", "DML"));
      if (!"UTF-8".equals(requiredText(resource, "encoding"))
          || !"LF".equals(requiredText(resource, "lineEnding"))) {
        throw invalid("manifest resource encoding or line ending is invalid");
      }
      requiredInteger(resource, "byteLength", 1, Integer.MAX_VALUE);
      requiredPattern(resource, "sha256", "^[0-9a-f]{64}$");
    }
    requireStrictlySorted(paths, "resources");
  }

  private static void validateQualificationSeeds(JsonNode seeds) {
    for (JsonNode seed : seeds) {
      requireExactFields(seed, QUALIFICATION_SEED_FIELDS, "qualification seed");
      requiredInteger(seed, "seedOrder", 1, 20);
      requiredPattern(seed, "qualificationCode", codePattern());
      if (!"SOFT_EXAM".equals(requiredText(seed, "domainCode"))) {
        throw invalid("qualification domainCode is invalid");
      }
      requiredBoundedText(seed, "officialName", 96);
      requiredBoundedText(seed, "displayName", 96);
      requiredEnum(seed, "levelCode", Set.of("PRIMARY", "INTERMEDIATE", "ADVANCED"));
      requiredInteger(seed, "sortOrder", 0, Integer.MAX_VALUE);
      validateUri(requiredBoundedText(seed, "sourceUrl", 512));
      validateDateTime(requiredText(seed, "verifiedAt"));
      requiredEnum(seed, "verificationStatus",
          Set.of("THIRD_PARTY_CANDIDATE", "OFFICIAL_VERIFIED", "VERIFICATION_EXPIRED"));
      requiredBoundedText(seed, "catalogVersion", 32);
      requiredEnum(seed, "catalogStatus", Set.of("ACTIVE", "RETIRED"));
      requiredEnum(seed, "operationalStatus", Set.of("DRAFT", "ENABLED", "DISABLED"));
    }
  }

  private static void validateSubjectSeeds(JsonNode seeds, JsonNode qualificationSeeds) {
    Set<String> qualifications = new HashSet<>();
    qualificationSeeds.forEach(seed -> qualifications.add(requiredText(seed, "qualificationCode")));
    for (JsonNode seed : seeds) {
      requireExactFields(seed, SUBJECT_SEED_FIELDS, "subject seed");
      requiredInteger(seed, "seedOrder", 1, 45);
      String qualification = requiredPattern(seed, "qualificationCode", codePattern());
      if (!qualifications.contains(qualification)) {
        throw invalid("subject seed references an unknown qualification");
      }
      requiredPattern(seed, "subjectCode", codePattern());
      requiredBoundedText(seed, "officialName", 96);
      requiredBoundedText(seed, "displayName", 96);
      requiredEnum(seed, "subjectType", Set.of("COMPREHENSIVE", "CASE", "ESSAY", "APPLICATION"));
      requiredInteger(seed, "sortOrder", 0, Integer.MAX_VALUE);
      requiredEnum(seed, "status", Set.of("DRAFT", "ENABLED", "DISABLED"));
    }
  }

  private static void validateReferences(JsonNode root) {
    Set<String> targets = new HashSet<>();
    root.path("targetDefinitions").forEach(definition -> targets.add(
        requiredText(definition, "objectType") + ":" + requiredText(definition, "tableName")
            + ":" + requiredText(definition, "objectName")));
    Map<String, JsonNode> resources = new HashMap<>();
    root.path("resources").forEach(resource -> resources.put(requiredText(resource, "path"), resource));
    for (JsonNode step : root.path("steps")) {
      for (JsonNode ref : step.path("targetRefs")) {
        if (!targets.contains(ref.textValue())) {
          throw invalid("manifest step contains an unresolved target reference");
        }
      }
      for (JsonNode ref : step.path("resourceRefs")) {
        JsonNode resource = resources.get(ref.textValue());
        if (resource == null
            || !requiredText(step, "phase").equals(requiredText(resource, "phase"))
            || !requiredText(step, "operationType").equals(requiredText(resource, "operationType"))) {
          throw invalid("manifest step resource phase or operation type is inconsistent");
        }
      }
    }
    for (JsonNode batch : root.path("dmlBatches")) {
      for (JsonNode ref : batch.path("resourceRefs")) {
        JsonNode resource = resources.get(ref.textValue());
        if (resource == null || !"D".equals(requiredText(resource, "phase"))
            || !"DML".equals(requiredText(resource, "operationType"))) {
          throw invalid("manifest DML batch resource is inconsistent");
        }
      }
    }
  }

  private static void validateSequence(JsonNode array, String sequenceField, int expectedSize,
      List<String> uniqueFields) {
    Set<String> unique = new HashSet<>();
    for (int index = 0; index < expectedSize; index++) {
      JsonNode item = array.get(index);
      if (!item.isObject() || item.path(sequenceField).asInt(-1) != index + 1) {
        throw invalid("manifest sequence is not canonical");
      }
      StringBuilder key = new StringBuilder();
      for (String field : uniqueFields) {
        key.append('\u0000').append(requiredText(item, field));
      }
      if (!unique.add(key.toString())) {
        throw invalid("manifest contains a duplicate canonical key");
      }
    }
  }

  private static void requireArraySize(JsonNode root, String field, int expected) {
    JsonNode value = root.path(field);
    if (!value.isArray() || value.size() != expected) {
      throw invalid("manifest array size is invalid");
    }
  }

  private static void requireNonEmptyArray(JsonNode root, String field) {
    JsonNode value = root.path(field);
    if (!value.isArray() || value.isEmpty()) {
      throw invalid("manifest array must not be empty");
    }
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.textValue().isEmpty()) {
      throw invalid("manifest text field is missing");
    }
    return value.textValue();
  }

  private static String requiredBoundedText(JsonNode node, String field, int maxLength) {
    String value = requiredText(node, field);
    if (value.length() > maxLength) {
      throw invalid("manifest text field is too long");
    }
    return value;
  }

  private static String requiredPattern(JsonNode node, String field, String pattern) {
    String value = requiredText(node, field);
    if (!value.matches(pattern)) {
      throw invalid("manifest text field has an invalid format");
    }
    return value;
  }

  private static String requiredEnum(JsonNode node, String field, Set<String> values) {
    String value = requiredText(node, field);
    if (!values.contains(value)) {
      throw invalid("manifest enum field is invalid");
    }
    return value;
  }

  private static int requiredInteger(JsonNode node, String field, int minimum, int maximum) {
    JsonNode value = node.path(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw invalid("manifest integer field is invalid");
    }
    int integer = value.intValue();
    if (integer < minimum || integer > maximum) {
      throw invalid("manifest integer field is outside the allowed range");
    }
    return integer;
  }

  private static void requireExactFields(JsonNode node, Set<String> expected, String objectName) {
    if (!node.isObject()) {
      throw invalid("manifest " + objectName + " must be an object");
    }
    Set<String> fields = new HashSet<>();
    node.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(expected)) {
      throw invalid("manifest " + objectName + " fields are invalid");
    }
  }

  private static void validateSortedUniqueTextArray(JsonNode node, String field, String pattern) {
    JsonNode values = node.path(field);
    if (!values.isArray() || values.isEmpty()) {
      throw invalid("manifest reference array must not be empty");
    }
    List<String> texts = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isTextual() || !value.textValue().matches(pattern)) {
        throw invalid("manifest reference is invalid");
      }
      texts.add(value.textValue());
    }
    requireStrictlySorted(texts, field);
  }

  private static void requireStrictlySorted(List<String> values, String field) {
    for (int index = 1; index < values.size(); index++) {
      if (values.get(index - 1).compareTo(values.get(index)) >= 0) {
        throw invalid("manifest " + field + " must be strictly sorted and unique");
      }
    }
  }

  private static void validateUri(String value) {
    try {
      URI uri = new URI(value);
      if (!uri.isAbsolute()) {
        throw invalid("manifest sourceUrl must be absolute");
      }
    } catch (URISyntaxException exception) {
      throw invalid("manifest sourceUrl is invalid");
    }
  }

  private static void validateDateTime(String value) {
    try {
      OffsetDateTime.parse(value);
    } catch (DateTimeParseException exception) {
      throw invalid("manifest verifiedAt is invalid");
    }
  }

  private static String resourcePathPattern() {
    return "^db/migration-payload/v9/[a-z0-9][a-z0-9/_-]*\\.sql$";
  }

  private static String codePattern() {
    return "^[A-Z][A-Z0-9_]{0,63}$";
  }

  private static boolean startsWithBom(byte[] bytes) {
    return bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb
        && bytes[2] == (byte) 0xbf;
  }

  private static V9MigrationException invalid(String message) {
    return new V9MigrationException(V9ErrorCode.V9_MANIFEST_INVALID, message);
  }
}
