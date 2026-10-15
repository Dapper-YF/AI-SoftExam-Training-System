package com.aiexam.app.portal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PortalContentReviewRequest(
    @NotBlank @Size(max = 500) String title,
    @NotBlank @Size(max = 1000) String summary,
    @NotNull PortalContentCategory category,
    @NotNull PortalContentType contentType
) {
}
