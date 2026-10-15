package com.aiexam.common.trace;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.MDC;

public final class TraceContext {
  public static final String TRACE_ID = "traceId";
  private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

  private TraceContext() {
  }

  public static String currentTraceId() {
    String traceId = MDC.get(TRACE_ID);
    if (traceId == null || traceId.isBlank()) {
      traceId = newTraceId();
      MDC.put(TRACE_ID, traceId);
    }
    return traceId;
  }

  public static String newTraceId() {
    return FORMATTER.format(LocalDateTime.now()) + UUID.randomUUID().toString().substring(0, 8);
  }
}
