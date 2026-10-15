package com.aiexam.essay.runtime;

import com.fasterxml.jackson.databind.JsonNode;

public interface EssaySnapshotCanonicalizer {
  String canonicalJson(JsonNode node);

  String requestHash(String method, String path, JsonNode snapshot);
}
