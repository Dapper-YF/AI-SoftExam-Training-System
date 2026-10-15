package com.aiexam.common.api;

import java.util.List;

public record PageResponse<T>(
    List<T> items,
    long pageNo,
    long pageSize,
    long total,
    boolean hasNext
) {
  public static <T> PageResponse<T> empty(long pageNo, long pageSize) {
    return new PageResponse<>(List.of(), pageNo, pageSize, 0, false);
  }
}
