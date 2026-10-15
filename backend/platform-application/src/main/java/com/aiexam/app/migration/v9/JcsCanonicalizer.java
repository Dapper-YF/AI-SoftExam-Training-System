package com.aiexam.app.migration.v9;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class JcsCanonicalizer {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JcsCanonicalizer() {
  }

  public static byte[] canonicalize(JsonNode node) {
    StringBuilder output = new StringBuilder();
    append(node, output);
    return output.toString().getBytes(StandardCharsets.UTF_8);
  }

  public static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public static String sha256Hex(byte[] bytes) {
    return HexFormat.of().formatHex(sha256(bytes));
  }

  private static void append(JsonNode node, StringBuilder output) {
    if (node.isObject()) {
      output.append('{');
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      names.sort(Comparator.naturalOrder());
      for (int index = 0; index < names.size(); index++) {
        if (index > 0) {
          output.append(',');
        }
        String name = names.get(index);
        appendString(name, output);
        output.append(':');
        append(node.get(name), output);
      }
      output.append('}');
      return;
    }
    if (node.isArray()) {
      output.append('[');
      for (int index = 0; index < node.size(); index++) {
        if (index > 0) {
          output.append(',');
        }
        append(node.get(index), output);
      }
      output.append(']');
      return;
    }
    if (node.isTextual()) {
      appendString(node.textValue(), output);
      return;
    }
    if (node.isIntegralNumber()) {
      output.append(node.bigIntegerValue());
      return;
    }
    if (node.isBoolean()) {
      output.append(node.booleanValue());
      return;
    }
    if (node.isNull()) {
      output.append("null");
      return;
    }
    throw new V9MigrationException(V9ErrorCode.V9_MANIFEST_INVALID,
        "JCS input contains an unsupported number or node type");
  }

  private static void appendString(String value, StringBuilder output) {
    try {
      output.append(MAPPER.writeValueAsString(value));
    } catch (JsonProcessingException exception) {
      throw new V9MigrationException(V9ErrorCode.V9_MANIFEST_INVALID,
          "JCS string serialization failed", exception);
    }
  }
}
