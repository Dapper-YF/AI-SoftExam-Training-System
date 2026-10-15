package com.aiexam.foundation.profile.infra;

import com.aiexam.foundation.profile.dto.UserProfileDto;
import com.aiexam.foundation.profile.repository.UserProfileRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserProfileRepository implements UserProfileRepository {
  private final JdbcTemplate jdbc;

  public JdbcUserProfileRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<UserProfileDto> findByUserId(Long userId) {
    return optional("""
        SELECT u.id, u.username, u.display_name, u.mobile, u.email, u.preparation_qualification_id,
               q.qualification_name AS preparation_qualification_name, u.exam_region_code, u.exam_region_name,
               u.status, u.version, u.updated_at FROM sys_user u
          LEFT JOIN exam_qualification q ON q.id = u.preparation_qualification_id AND q.deleted = 0
         WHERE u.id = ? AND u.deleted = 0
        """, userId);
  }

  @Override
  public boolean updateDisplayName(Long userId, String displayName, Long expectedVersion) {
    int updated = jdbc.update("""
        UPDATE sys_user
           SET display_name = ?, updated_at = ?, version = version + 1
         WHERE id = ? AND deleted = 0 AND status = 'ENABLED' AND version = ?
        """, displayName, Timestamp.from(Instant.now()), userId, expectedVersion);
    return updated == 1;
  }

  @Override
  public boolean updatePreparation(Long userId, String displayName, Long qualificationId, String regionCode, String regionName, Long expectedVersion) {
    return jdbc.update("""
        UPDATE sys_user SET display_name=?, preparation_qualification_id=?, exam_region_code=?, exam_region_name=?,
          updated_at=?, version=version+1 WHERE id=? AND deleted=0 AND status='ENABLED' AND version=?
        """, displayName, qualificationId, regionCode, regionName, Timestamp.from(Instant.now()), userId, expectedVersion) == 1;
  }

  private Optional<UserProfileDto> optional(String sql, Object... args) {
    try {
      return Optional.ofNullable(jdbc.queryForObject(sql, this::profile, args));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  private UserProfileDto profile(ResultSet rs, int rowNum) throws SQLException {
    return new UserProfileDto(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getString("display_name"),
        maskMobile(rs.getString("mobile")),
        maskEmail(rs.getString("email")),
        (Long) rs.getObject("preparation_qualification_id"),
        rs.getString("preparation_qualification_name"),
        rs.getString("exam_region_code"),
        rs.getString("exam_region_name"),
        rs.getString("status"),
        rs.getLong("version"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static String maskMobile(String mobile) {
    if (mobile == null || mobile.length() < 8) {
      return null;
    }
    return mobile.substring(0, Math.min(6, mobile.length() - 4)) + "****" + mobile.substring(mobile.length() - 4);
  }

  private static String maskEmail(String email) {
    if (email == null || !email.contains("@")) {
      return null;
    }
    int at = email.indexOf('@');
    String local = email.substring(0, at);
    String domain = email.substring(at + 1);
    String maskedLocal = local.isEmpty() ? "*" : local.substring(0, 1) + "***";
    String masked = maskedLocal + "@" + domain;
    return masked.length() <= 128 ? masked : masked.substring(0, 128);
  }
}
