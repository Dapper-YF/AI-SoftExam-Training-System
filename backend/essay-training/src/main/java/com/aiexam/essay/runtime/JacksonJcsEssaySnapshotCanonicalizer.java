package com.aiexam.essay.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class JacksonJcsEssaySnapshotCanonicalizer implements EssaySnapshotCanonicalizer {
  private final ObjectMapper objectMapper;

  public JacksonJcsEssaySnapshotCanonicalizer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String canonicalJson(JsonNode node) {
    if (node == null || node.isNull()) {
      return "null";
    }
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
      Iterator<Map.Entry<String, JsonNode>> iterator = object.fields();
      while (iterator.hasNext()) {
        fields.add(iterator.next());
      }
      fields.sort(Comparator.comparing(Map.Entry::getKey));
      StringBuilder builder = new StringBuilder("{");
      for (int i = 0; i < fields.size(); i++) {
        Map.Entry<String, JsonNode> field = fields.get(i);
        if (i > 0) {
          builder.append(',');
        }
        builder.append(quote(field.getKey())).append(':').append(canonicalJson(field.getValue()));
      }
      return builder.append('}').toString();
    }
    if (node.isArray()) {
      ArrayNode array = (ArrayNode) node;
      StringBuilder builder = new StringBuilder("[");
      for (int i = 0; i < array.size(); i++) {
        if (i > 0) {
          builder.append(',');
        }
        builder.append(canonicalJson(array.get(i)));
      }
      return builder.append(']').toString();
    }
    if (node.isTextual()) {
      return quote(node.textValue());
    }
    if (node.isNumber()) {
      return node.numberValue().toString();
    }
    if (node.isBoolean()) {
      return Boolean.toString(node.booleanValue());
    }
    throw new IllegalArgumentException("Unsupported JSON node for essay snapshot canonicalization: " + node.getNodeType());
  }

  @Override
  public String requestHash(String method, String path, JsonNode snapshot) {
    return sha256(method + "\n" + path + "\n" + canonicalJson(snapshot));
  }

  private String quote(String value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to quote JSON string", ex);
    }
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
