package com.aiexam.essay.runtime;

public record EssayReviewIssueDto(
    String issueId,
    int paragraphNo,
    String issueType,
    String suggestion
) {
}
