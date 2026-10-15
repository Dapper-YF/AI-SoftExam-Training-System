package com.aiexam.app.migration;

import com.aiexam.app.migration.v9.V9ErrorCode;
import com.aiexam.app.migration.v9.V9MigrationException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class V9RuntimeSchemaValidator {
  private static final int EXPECTED_FLYWAY_CHECKSUM = 1699861289;
  private static final int MINIMUM_QUALIFICATION_SEEDS = 20;
  private static final int MINIMUM_SUBJECT_SEEDS = 45;
  private static final List<CheckDefinition> REQUIRED_CHECKS = List.of(
      new CheckDefinition("exam_qualification", "ck_exam_qualification_domain",
          "domain_code = 'SOFT_EXAM'"),
      new CheckDefinition("exam_qualification", "ck_exam_qualification_level",
          "level_code IN ('PRIMARY','INTERMEDIATE','ADVANCED')"),
      new CheckDefinition("exam_qualification", "ck_exam_qualification_verification",
          "(verification_status = 'OFFICIAL_VERIFIED' AND source_url IS NOT NULL "
              + "AND verified_at IS NOT NULL) OR verification_status IN "
              + "('THIRD_PARTY_CANDIDATE','VERIFICATION_EXPIRED')"),
      new CheckDefinition("exam_qualification", "ck_exam_qualification_catalog_status",
          "catalog_status IN ('ACTIVE','RETIRED')"),
      new CheckDefinition("exam_qualification", "ck_exam_qualification_operational_status",
          "status IN ('DRAFT','ENABLED','DISABLED') AND "
              + "(catalog_status <> 'RETIRED' OR status <> 'ENABLED')"),
      new CheckDefinition("exam_subject", "ck_exam_subject_type",
          "subject_type IN ('COMPREHENSIVE','CASE','ESSAY','APPLICATION')"),
      new CheckDefinition("exam_subject", "ck_exam_subject_status",
          "status IN ('DRAFT','ENABLED','DISABLED')"),
      new CheckDefinition("exam_rule", "ck_exam_rule_subject_scope", "subject_id IS NULL"),
      new CheckDefinition("exam_rule", "ck_exam_rule_status",
          "status IN ('DRAFT','PUBLISHED','RETIRED')"),
      new CheckDefinition("exam_rule", "ck_exam_rule_effective_range",
          "effective_from < effective_to"));
  private static final List<IndexDefinition> REQUIRED_INDEXES = List.of(
      new IndexDefinition("exam_qualification", "idx_exam_qualification_tree", 1, "BTREE",
          List.of("domain_code", "level_code", "status", "sort_order", "id")),
      new IndexDefinition("exam_qualification", "idx_exam_qualification_verification", 1,
          "BTREE", List.of("verification_status", "verified_at")),
      new IndexDefinition("exam_subject", "idx_exam_subject_tree", 1, "BTREE",
          List.of("qualification_id", "status", "sort_order", "id")),
      new IndexDefinition("exam_rule", "uk_exam_rule_period_version", 0, "BTREE",
          List.of("qualification_id", "period_code", "rule_code", "rule_version")),
      new IndexDefinition("exam_rule", "idx_exam_rule_active", 1, "BTREE",
          List.of("qualification_id", "period_code", "status", "effective_from",
              "effective_to")));
  private static final List<ObsoleteIndex> OBSOLETE_INDEXES = List.of(
      new ObsoleteIndex("exam_subject", "idx_exam_subject_qualification"),
      new ObsoleteIndex("exam_rule", "uk_exam_rule_version"),
      new ObsoleteIndex("exam_rule", "idx_exam_rule_subject"));

  public void validate(Connection connection) {
    try {
      requireHistory(connection, EXPECTED_FLYWAY_CHECKSUM);
      requireFinalSchema(connection);
      requireSeedMinimums(connection);
    } catch (SQLException exception) {
      throw new V9MigrationException(V9ErrorCode.V9_RUNTIME_SCHEMA_NOT_READY,
          "V9 runtime schema validation failed", exception);
    }
  }

  private static void requireHistory(Connection connection, int checksum) throws SQLException {
    String sql = """
        SELECT SUM(CASE WHEN version IN ('1','2','3','4','5','6','7','8','9')
                        THEN 1 ELSE 0 END),
               SUM(CASE WHEN version IN ('1','2','3','4','5','6','7','8')
                         AND success = 1 THEN 1 ELSE 0 END),
               SUM(CASE WHEN version = '9' AND type = 'JDBC'
                         AND description = 'soft exam taxonomy rule seed'
                         AND checksum = ? AND success = 1 THEN 1 ELSE 0 END)
        FROM flyway_schema_history WHERE version IS NOT NULL
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, checksum);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        if (result.getInt(1) != 9 || result.getInt(2) != 8 || result.getInt(3) != 1) {
          throw notReady("Flyway history is not the unique V1-V9 JDBC baseline");
        }
      }
    }
  }

  private static void requireFinalSchema(Connection connection) throws SQLException {
    if (queryCount(connection, """
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND (
          (table_name='exam_qualification' AND column_name IN
            ('domain_code','official_name','source_url','verified_at','verification_status',
             'catalog_version','catalog_status')) OR
          (table_name='exam_subject' AND column_name='official_name') OR
          (table_name='exam_rule' AND column_name IN
            ('period_code','effective_from','effective_to','published_at','version')))
        """) != 13 || queryCount(connection, """
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND is_nullable='NO' AND (
          (table_name='exam_qualification' AND column_name IN
            ('domain_code','official_name','verification_status','catalog_version','catalog_status')) OR
          (table_name='exam_subject' AND column_name='official_name') OR
          (table_name='exam_rule' AND column_name IN
            ('period_code','effective_from','effective_to','version')))
        """) != 10) {
      throw notReady("V9 final schema columns are not ready");
    }
    for (CheckDefinition expected : REQUIRED_CHECKS) {
      String actual = checkClause(connection, expected.table(), expected.name());
      String normalizedActual = actual == null ? null : normalizeCheckClause(actual);
      String normalizedExpected = normalizeCheckClause(expected.clause());
      if (normalizedActual == null || !normalizedActual.equals(normalizedExpected)) {
        throw notReady("CHECK definition drift: " + expected.table() + "." + expected.name());
      }
    }
    for (IndexDefinition expected : REQUIRED_INDEXES) {
      if (!expected.equals(indexDefinition(connection, expected.table(), expected.name()))) {
        throw notReady("index definition drift: " + expected.table() + "." + expected.name());
      }
    }
    for (ObsoleteIndex obsolete : OBSOLETE_INDEXES) {
      if (indexDefinition(connection, obsolete.table(), obsolete.name()) != null) {
        throw notReady("obsolete index still exists: " + obsolete.table() + "."
            + obsolete.name());
      }
    }
  }

  private static String checkClause(Connection connection, String table, String constraint)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT cc.check_clause
        FROM information_schema.table_constraints tc
        JOIN information_schema.check_constraints cc
          ON cc.constraint_schema=tc.constraint_schema
         AND cc.constraint_name=tc.constraint_name
        WHERE tc.constraint_schema=DATABASE() AND tc.table_schema=DATABASE()
          AND tc.table_name=? AND tc.constraint_name=? AND tc.constraint_type='CHECK'
        """)) {
      statement.setString(1, table);
      statement.setString(2, constraint);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        String clause = result.getString(1);
        return result.next() ? null : clause;
      }
    }
  }

  private static IndexDefinition indexDefinition(Connection connection, String table,
      String index) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT non_unique,index_type,seq_in_index,column_name
        FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
        ORDER BY seq_in_index
        """)) {
      statement.setString(1, table);
      statement.setString(2, index);
      try (ResultSet result = statement.executeQuery()) {
        int nonUnique = -1;
        String indexType = null;
        int expectedSequence = 1;
        List<String> columns = new ArrayList<>();
        while (result.next()) {
          int rowNonUnique = result.getInt(1);
          String rowIndexType = result.getString(2).toUpperCase(Locale.ROOT);
          if (result.getInt(3) != expectedSequence
              || (nonUnique >= 0 && nonUnique != rowNonUnique)
              || (indexType != null && !indexType.equals(rowIndexType))) {
            return null;
          }
          nonUnique = rowNonUnique;
          indexType = rowIndexType;
          columns.add(result.getString(4));
          expectedSequence++;
        }
        return columns.isEmpty() ? null
            : new IndexDefinition(table, index, nonUnique, indexType, List.copyOf(columns));
      }
    }
  }

  private static String normalizeCheckClause(String value) {
    StringBuilder normalized = new StringBuilder(value.length());
    boolean inString = false;
    boolean inQuotedIdentifier = false;
    boolean metadataEscapedQuotes = false;
    boolean pendingWhitespace = false;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '\\' && index + 1 < value.length()
          && value.charAt(index + 1) == '\''
          && (!inString || metadataEscapedQuotes)) {
        if (!inString) {
          removeCharsetIntroducer(normalized);
          metadataEscapedQuotes = true;
        }
        normalized.append('\'');
        inString = !inString;
        index++;
        pendingWhitespace = false;
        continue;
      }
      if (inString) {
        normalized.append(current);
        if (current == '\\' && index + 1 < value.length()) {
          normalized.append(value.charAt(++index));
        } else if (current == '\'' && index + 1 < value.length()
            && value.charAt(index + 1) == '\'') {
          normalized.append(value.charAt(++index));
        } else if (current == '\'') {
          inString = false;
        }
        continue;
      }
      if (inQuotedIdentifier) {
        if (current == '`') {
          inQuotedIdentifier = false;
        } else {
          normalized.append(Character.toLowerCase(current));
        }
        continue;
      }
      if (current == '`') {
        if (pendingWhitespace && normalized.length() > 0
            && needsTokenSeparator(normalized.charAt(normalized.length() - 1), 'a')) {
          normalized.append(' ');
        }
        inQuotedIdentifier = true;
        pendingWhitespace = false;
        continue;
      }
      if (Character.isWhitespace(current)) {
        pendingWhitespace = true;
        continue;
      }
      if (current == '\'') {
        removeCharsetIntroducer(normalized);
        normalized.append(current);
        inString = true;
        pendingWhitespace = false;
        continue;
      }
      if (pendingWhitespace && normalized.length() > 0
          && needsTokenSeparator(normalized.charAt(normalized.length() - 1), current)) {
        normalized.append(' ');
      }
      normalized.append(Character.toLowerCase(current));
      pendingWhitespace = false;
    }
    if (inString || inQuotedIdentifier) {
      throw new IllegalArgumentException("Unterminated CHECK definition token");
    }
    return removeDatabaseParenthesisWrappers(normalized.toString());
  }

  private static void removeCharsetIntroducer(StringBuilder normalized) {
    int end = normalized.length();
    int start = end;
    while (start > 0 && isIdentifierPart(normalized.charAt(start - 1))) {
      start--;
    }
    if (start < end && normalized.charAt(start) == '_') {
      String token = normalized.substring(start, end);
      if (token.matches("_[a-z0-9]+")) {
        normalized.delete(start, end);
      }
    }
  }

  private static String removeDatabaseParenthesisWrappers(String value) {
    String normalized = stripWholeExpressionWrappers(value);
    boolean changed;
    do {
      changed = false;
      List<Integer> opens = new ArrayList<>();
      boolean inString = false;
      for (int index = 0; index < normalized.length(); index++) {
        char current = normalized.charAt(index);
        if (inString) {
          if (current == '\\' && index + 1 < normalized.length()) {
            index++;
          } else if (current == '\'' && index + 1 < normalized.length()
              && normalized.charAt(index + 1) == '\'') {
            index++;
          } else if (current == '\'') {
            inString = false;
          }
          continue;
        }
        if (current == '\'') {
          inString = true;
        } else if (current == '(') {
          opens.add(index);
        } else if (current == ')' && !opens.isEmpty()) {
          int open = opens.remove(opens.size() - 1);
          String inner = normalized.substring(open + 1, index);
          if (isAtomicPredicate(inner) && !"not".equals(previousWord(normalized, open))) {
            normalized = joinTokenSafe(
                normalized.substring(0, open), inner, normalized.substring(index + 1));
            normalized = stripWholeExpressionWrappers(normalized);
            changed = true;
            break;
          }
        }
      }
    } while (changed);
    return normalized;
  }

  private static String joinTokenSafe(String... parts) {
    StringBuilder joined = new StringBuilder();
    for (String part : parts) {
      if (!part.isEmpty() && joined.length() > 0
          && needsTokenSeparator(joined.charAt(joined.length() - 1), part.charAt(0))) {
        joined.append(' ');
      }
      joined.append(part);
    }
    return joined.toString();
  }

  private static String stripWholeExpressionWrappers(String value) {
    String stripped = value;
    while (stripped.length() >= 2 && stripped.charAt(0) == '('
        && matchingParenthesis(stripped, 0) == stripped.length() - 1) {
      stripped = stripped.substring(1, stripped.length() - 1);
    }
    return stripped;
  }

  private static int matchingParenthesis(String value, int open) {
    int depth = 0;
    boolean inString = false;
    for (int index = open; index < value.length(); index++) {
      char current = value.charAt(index);
      if (inString) {
        if (current == '\\' && index + 1 < value.length()) {
          index++;
        } else if (current == '\'' && index + 1 < value.length()
            && value.charAt(index + 1) == '\'') {
          index++;
        } else if (current == '\'') {
          inString = false;
        }
      } else if (current == '\'') {
        inString = true;
      } else if (current == '(') {
        depth++;
      } else if (current == ')' && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  private static boolean isAtomicPredicate(String value) {
    return !containsTopLevelKeyword(value, "and")
        && !containsTopLevelKeyword(value, "or")
        && (containsTopLevelComparison(value)
            || containsTopLevelKeyword(value, "in")
            || containsTopLevelKeyword(value, "is"));
  }

  private static boolean containsTopLevelComparison(String value) {
    int depth = 0;
    boolean inString = false;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (inString) {
        if (current == '\\' && index + 1 < value.length()) {
          index++;
        } else if (current == '\'' && index + 1 < value.length()
            && value.charAt(index + 1) == '\'') {
          index++;
        } else if (current == '\'') {
          inString = false;
        }
      } else if (current == '\'') {
        inString = true;
      } else if (current == '(') {
        depth++;
      } else if (current == ')') {
        depth--;
      } else if (depth == 0 && (current == '=' || current == '<' || current == '>')) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsTopLevelKeyword(String value, String keyword) {
    int depth = 0;
    boolean inString = false;
    for (int index = 0; index <= value.length() - keyword.length(); index++) {
      char current = value.charAt(index);
      if (inString) {
        if (current == '\\' && index + 1 < value.length()) {
          index++;
        } else if (current == '\'' && index + 1 < value.length()
            && value.charAt(index + 1) == '\'') {
          index++;
        } else if (current == '\'') {
          inString = false;
        }
      } else if (current == '\'') {
        inString = true;
      } else if (current == '(') {
        depth++;
      } else if (current == ')') {
        depth--;
      } else if (depth == 0 && value.regionMatches(index, keyword, 0, keyword.length())
          && (index == 0 || !isIdentifierPart(value.charAt(index - 1)))
          && (index + keyword.length() == value.length()
              || !isIdentifierPart(value.charAt(index + keyword.length())))) {
        return true;
      }
    }
    return false;
  }

  private static String previousWord(String value, int position) {
    int end = position;
    while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
      end--;
    }
    int start = end;
    while (start > 0 && isIdentifierPart(value.charAt(start - 1))) {
      start--;
    }
    return value.substring(start, end);
  }

  private static boolean isIdentifierPart(char value) {
    return Character.isLetterOrDigit(value) || value == '_' || value == '$';
  }

  private static boolean needsTokenSeparator(char left, char right) {
    return (isIdentifierPart(left) || left == '\'' || left == ')')
        && isIdentifierPart(right);
  }

  private static void requireSeedMinimums(Connection connection) throws SQLException {
    if (queryCount(connection, "SELECT COUNT(*) FROM exam_qualification WHERE deleted = 0")
        < MINIMUM_QUALIFICATION_SEEDS
        || queryCount(connection, "SELECT COUNT(*) FROM exam_subject WHERE deleted = 0")
        < MINIMUM_SUBJECT_SEEDS) {
      throw notReady("V9 canonical seed minimums are not ready");
    }
  }

  private static int queryCount(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getInt(1);
    }
  }

  private static V9MigrationException notReady(String message) {
    return new V9MigrationException(V9ErrorCode.V9_RUNTIME_SCHEMA_NOT_READY, message);
  }

  private record CheckDefinition(String table, String name, String clause) {
  }

  private record IndexDefinition(String table, String name, int nonUnique, String indexType,
                                 List<String> columns) {
  }

  private record ObsoleteIndex(String table, String name) {
  }
}
