package com.aiexam.app.schedule;

import java.time.Instant;

public record RegionalScheduleDto(Long id, String regionCode, String regionName, String examPeriodCode,
                                  String nodeType, Instant scheduledAt, String note, String status) {}
