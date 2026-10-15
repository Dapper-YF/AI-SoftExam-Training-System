package com.aiexam.app.migration.v9;

public final class V9MigrationException extends RuntimeException {
  private final V9ErrorCode errorCode;

  public V9MigrationException(V9ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public V9MigrationException(V9ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public V9ErrorCode errorCode() {
    return errorCode;
  }
}
