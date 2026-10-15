package com.aiexam.common.api;

import com.aiexam.common.trace.TraceContext;
import com.fasterxml.jackson.annotation.JsonInclude;

public record ApiResponse<T>(
    boolean success,
    String errorCode,
    String message,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    T data,
    String traceId
) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, "OK", "success", data, TraceContext.currentTraceId());
  }

  public static <T> ApiResponse<T> created(T data) {
    return new ApiResponse<>(true, "CREATED", "created", data, TraceContext.currentTraceId());
  }

  public static <T> ApiResponse<T> fail(String code, String message, T data) {
    return new ApiResponse<>(false, code, message, data, TraceContext.currentTraceId());
  }
}
