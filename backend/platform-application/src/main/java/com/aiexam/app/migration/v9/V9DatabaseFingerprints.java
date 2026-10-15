package com.aiexam.app.migration.v9;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class V9DatabaseFingerprints {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DateTimeFormatter VERIFIED_AT = DateTimeFormatter.ofPattern(
      "yyyy-MM-dd'T'HH:mm:ssXXX");

  private V9DatabaseFingerprints() {
  }

  public static Fingerprints calculate(Connection connection) throws SQLException {
    ObjectNode managed = MAPPER.createObjectNode();
    ObjectNode business = MAPPER.createObjectNode();
    ArrayNode managedQualifications = managed.putArray("qualificationSeeds");
    ArrayNode businessQualifications = business.putArray("qualifications");
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT qualification_code,domain_code,official_name,level_code,source_url,verified_at,
               verification_status,catalog_version,catalog_status
        FROM exam_qualification WHERE deleted=0 ORDER BY id
        """); ResultSet result = statement.executeQuery()) {
      int seedOrder = 0;
      while (result.next()) {
        seedOrder++;
        ObjectNode item = managedQualifications.addObject();
        item.put("seedOrder", seedOrder);
        item.put("qualificationCode", result.getString(1));
        item.put("domainCode", result.getString(2));
        item.put("officialName", result.getString(3));
        item.put("levelCode", result.getString(4));
        item.put("sourceUrl", result.getString(5));
        item.put("verifiedAt", VERIFIED_AT.format(result.getTimestamp(6).toInstant()
            .atOffset(ZoneOffset.ofHours(8))));
        item.put("verificationStatus", result.getString(7));
        item.put("catalogVersion", result.getString(8));
        item.put("catalogStatus", result.getString(9));

        ObjectNode identity = businessQualifications.addObject();
        identity.put("domainCode", result.getString(2));
        identity.put("levelCode", result.getString(4));
        identity.put("qualificationCode", result.getString(1));
      }
    }
    ArrayNode managedSubjects = managed.putArray("subjectSeeds");
    ArrayNode businessSubjects = business.putArray("subjects");
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT q.qualification_code,s.subject_code,s.official_name,s.subject_type
        FROM exam_subject s
        JOIN exam_qualification q ON q.id=s.qualification_id AND q.deleted=0
        WHERE s.deleted=0 ORDER BY s.id
        """); ResultSet result = statement.executeQuery()) {
      int seedOrder = 0;
      while (result.next()) {
        seedOrder++;
        ObjectNode item = managedSubjects.addObject();
        item.put("seedOrder", seedOrder);
        item.put("qualificationCode", result.getString(1));
        item.put("subjectCode", result.getString(2));
        item.put("officialName", result.getString(3));
        item.put("subjectType", result.getString(4));

        ObjectNode identity = businessSubjects.addObject();
        identity.put("qualificationCode", result.getString(1));
        identity.put("subjectCode", result.getString(2));
        identity.put("subjectType", result.getString(4));
      }
    }
    return new Fingerprints(hash(managed), hash(business));
  }

  private static String hash(ObjectNode node) {
    return JcsCanonicalizer.sha256Hex(JcsCanonicalizer.canonicalize(node));
  }

  public record Fingerprints(String seedFingerprint, String businessFingerprint) {
  }
}
