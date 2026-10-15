package com.aiexam.app.schedule;

import com.aiexam.common.error.BusinessException;
import com.aiexam.foundation.profile.ExamRegionCatalog;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RegionalScheduleService {
  private static final Set<String> NODE_TYPES = Set.of("REGISTRATION", "ADMISSION_TICKET", "EXAM", "RESULT");
  private final JdbcTemplate jdbc;
  public RegionalScheduleService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  public List<RegionalScheduleDto> published(String regionCode, String period) { return list(regionCode, period, "PUBLISHED"); }
  public List<RegionalScheduleDto> all(String regionCode, String period) { return list(regionCode, period, null); }
  public RegionalScheduleDto save(RegionalScheduleRequest request) {
    String code = request.regionCode().trim().toUpperCase(); String name;
    try { name = ExamRegionCatalog.requireName(code); } catch (IllegalArgumentException e) { throw invalid("考区无效"); }
    String type = request.nodeType().trim().toUpperCase(); if (!NODE_TYPES.contains(type)) throw invalid("时间节点类型无效");
    String period = request.examPeriodCode().trim().toUpperCase(); Instant now = Instant.now();
    jdbc.update("INSERT INTO exam_region_schedule(region_code,region_name,exam_period_code,node_type,scheduled_at,note,status,created_at,updated_at) VALUES(?,?,?,?,?,?, 'DRAFT', ?, ?) ON DUPLICATE KEY UPDATE scheduled_at=VALUES(scheduled_at),note=VALUES(note),updated_at=VALUES(updated_at)", code,name,period,type,request.scheduledAt()==null?null:Timestamp.from(request.scheduledAt()),request.note(),Timestamp.from(now),Timestamp.from(now));
    return list(code, period, null).stream().filter(item -> item.nodeType().equals(type)).findFirst().orElseThrow();
  }
  public RegionalScheduleDto publish(long id, boolean publish) {
    jdbc.update("UPDATE exam_region_schedule SET status=?,updated_at=? WHERE id=?", publish ? "PUBLISHED" : "DRAFT", Timestamp.from(Instant.now()), id);
    return jdbc.queryForObject("SELECT * FROM exam_region_schedule WHERE id=?", this::map, id);
  }
  private List<RegionalScheduleDto> list(String code, String period, String status) {
    String sql = "SELECT * FROM exam_region_schedule WHERE region_code=? AND exam_period_code=?" + (status == null ? "" : " AND status='PUBLISHED'") + " ORDER BY FIELD(node_type,'REGISTRATION','ADMISSION_TICKET','EXAM','RESULT')";
    return jdbc.query(sql, this::map, code.trim().toUpperCase(), period.trim().toUpperCase());
  }
  private RegionalScheduleDto map(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new RegionalScheduleDto(rs.getLong("id"),rs.getString("region_code"),rs.getString("region_name"),rs.getString("exam_period_code"),rs.getString("node_type"),rs.getTimestamp("scheduled_at")==null?null:rs.getTimestamp("scheduled_at").toInstant(),rs.getString("note"),rs.getString("status")); }
  private BusinessException invalid(String message) { return new BusinessException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST); }
}
