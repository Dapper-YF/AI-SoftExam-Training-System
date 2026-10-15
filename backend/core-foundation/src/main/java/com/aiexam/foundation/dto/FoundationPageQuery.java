package com.aiexam.foundation.dto;

public record FoundationPageQuery(long pageNo, long pageSize) {
  public static final long MAX_PAGE_SIZE = 100;

  public FoundationPageQuery {
    if (pageNo < 1) {
      pageNo = 1;
    }
    if (pageSize < 1) {
      pageSize = 20;
    }
    if (pageSize > MAX_PAGE_SIZE) {
      pageSize = MAX_PAGE_SIZE;
    }
  }

  public long offset() {
    return (pageNo - 1) * pageSize;
  }
}
