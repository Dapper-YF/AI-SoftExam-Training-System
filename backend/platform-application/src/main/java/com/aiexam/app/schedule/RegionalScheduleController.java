package com.aiexam.app.schedule;

import com.aiexam.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RegionalScheduleController {
  private final RegionalScheduleService service;
  public RegionalScheduleController(RegionalScheduleService service) { this.service = service; }
  @GetMapping("/public/exam-schedules") public ApiResponse<List<RegionalScheduleDto>> published(@RequestParam String regionCode, @RequestParam String examPeriodCode) { return ApiResponse.ok(service.published(regionCode, examPeriodCode)); }
  @GetMapping("/admin/exam-schedules") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<List<RegionalScheduleDto>> all(@RequestParam String regionCode, @RequestParam String examPeriodCode) { return ApiResponse.ok(service.all(regionCode, examPeriodCode)); }
  @PutMapping("/admin/exam-schedules") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<RegionalScheduleDto> save(@Valid @RequestBody RegionalScheduleRequest request) { return ApiResponse.ok(service.save(request)); }
  @PostMapping("/admin/exam-schedules/{id}/publish") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<RegionalScheduleDto> publish(@PathVariable long id) { return ApiResponse.ok(service.publish(id, true)); }
  @PostMapping("/admin/exam-schedules/{id}/offline") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<RegionalScheduleDto> offline(@PathVariable long id) { return ApiResponse.ok(service.publish(id, false)); }
}
