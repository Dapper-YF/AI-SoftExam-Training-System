package com.aiexam.foundation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record QualificationCreateRequest(
    @NotBlank @Size(max = 64) String qualificationCode,
    @NotBlank @Pattern(regexp = "SOFT_EXAM") String domainCode,
    @NotBlank @Size(max = 96) String qualificationName,
    @NotBlank @Size(max = 96) String officialName,
    @NotBlank @Pattern(regexp = "PRIMARY|INTERMEDIATE|ADVANCED") String levelCode,
    @Size(max = 512) String sourceUrl,
    Instant verifiedAt,
    @NotBlank @Pattern(regexp = "THIRD_PARTY_CANDIDATE|OFFICIAL_VERIFIED|VERIFICATION_EXPIRED")
    String verificationStatus,
    @NotBlank @Size(max = 32) String catalogVersion,
    @NotBlank @Pattern(regexp = "ACTIVE|RETIRED") String catalogStatus,
    @Pattern(regexp = "DRAFT|ENABLED|DISABLED") String status,
    Integer sortOrder
) {
}
