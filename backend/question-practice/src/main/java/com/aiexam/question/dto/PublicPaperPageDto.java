package com.aiexam.question.dto;

import com.aiexam.common.api.PageResponse;
import java.util.List;

public record PublicPaperPageDto(
    List<PublicPaperSummaryDto> items,
    long pageNo,
    long pageSize,
    boolean hasNext
) {
  public static PublicPaperPageDto from(PageResponse<PaperSummaryDto> page) {
    return new PublicPaperPageDto(
        page.items().stream().map(PublicPaperSummaryDto::from).toList(),
        page.pageNo(),
        page.pageSize(),
        page.hasNext());
  }
}
