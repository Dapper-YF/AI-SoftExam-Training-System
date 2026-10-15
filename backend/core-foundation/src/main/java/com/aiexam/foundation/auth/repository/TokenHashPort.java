package com.aiexam.foundation.auth.repository;

public interface TokenHashPort {
  String hash(String opaqueToken);
}
