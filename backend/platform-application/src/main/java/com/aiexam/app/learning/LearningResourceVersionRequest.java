package com.aiexam.app.learning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LearningResourceVersionRequest(
    @Size(max = 120) String versionLabel,
    @Size(max = 300) String sourceName,
    @Size(max = 700) String sourceRef,
    @NotBlank @Size(max = 1000) String fileUrl,
    @NotBlank @Size(max = 300) String fileName,
    @NotBlank @Size(max = 120) String mimeType,
    @NotNull @Positive Long fileSize,
    @NotBlank @Size(min = 64, max = 64) String fileSha256,
    @Positive Integer pageCount,
    @Size(max = 1000) String coverUrl,
    @Size(max = 255) String usageRestriction
) {}
