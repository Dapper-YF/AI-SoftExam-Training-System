package com.aiexam.app.schedule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record RegionalScheduleRequest(@NotBlank @Size(max = 32) String regionCode,
                                      @NotBlank @Size(max = 32) String examPeriodCode,
                                      @NotBlank @Size(max = 32) String nodeType,
                                      Instant scheduledAt, @Size(max = 255) String note) {}
