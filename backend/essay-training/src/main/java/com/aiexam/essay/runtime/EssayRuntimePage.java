package com.aiexam.essay.runtime;

import java.util.List;

public record EssayRuntimePage<T>(
    List<T> items,
    int pageNo,
    int pageSize,
    long total,
    boolean hasNext
) {
  public EssayRuntimePage {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
