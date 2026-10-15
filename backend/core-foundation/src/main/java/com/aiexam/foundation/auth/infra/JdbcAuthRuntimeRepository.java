package com.aiexam.foundation.auth.infra;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.foundation.auth.domain.AuthClientEvidence;
import com.aiexam.foundation.auth.domain.IssuedChallenge;
import com.aiexam.foundation.auth.domain.IssuedOpaqueTokens;
import com.aiexam.foundation.auth.domain.LoginUserSnapshot;
import com.aiexam.foundation.auth.domain.PasswordResetAccountContext;
import com.aiexam.foundation.auth.dto.AuthContext;
import com.aiexam.foundation.auth.dto.AuthContext.AuthMenuDto;
import com.aiexam.foundation.auth.dto.AuthContext.AuthUserDto;
import com.aiexam.foundation.auth.dto.ChallengeDto;
import com.aiexam.foundation.auth.dto.CommittedAuthSession;
import com.aiexam.foundation.auth.dto.RegistrationResultDto;
import com.aiexam.foundation.auth.dto.RotatedAuthSession;
import com.aiexam.foundation.auth.dto.SessionExpiryDto;
import com.aiexam.foundation.auth.dto.request.PasswordResetRequest;
import com.aiexam.foundation.auth.dto.request.RegisterRequest;
import com.aiexam.foundation.auth.dto.request.SmsLoginRequest;
import com.aiexam.foundation.auth.repository.AuthRuntimeRepository;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "aiexam.auth", name = "persistence", havingValue = "jdbc", matchIfMissing = true)
public class JdbcAuthRuntimeRepository implements AuthRuntimeRepository {
  private static final int MAX_ACTIVE_SESSIONS = 5;
  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  private final JdbcTemplate jdbc;

  public JdbcAuthRuntimeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @PostConstruct
  void validateAuthSchema() {
    if (!isHealthy()) {
      throw new IllegalStateException("AUTH_V13_SCHEMA_NOT_READY");
    }
  }

  @Override
  public boolean isHealthy() {
    try {
      Integer count = jdbc.queryForObject(
          """
              SELECT COUNT(*)
               FROM information_schema.tables
               WHERE table_schema = DATABASE()
                 AND table_name IN (
                   'sys_auth_credential','sys_auth_session','sys_auth_idempotency',
                   'sys_auth_challenge','sys_login_audit','sys_auth_login_identifier'
                 )
              """,
          Integer.class);
      return count != null && count == 6;
    } catch (DataAccessException ex) {
      return false;
    }
  }

  @Override
  public IssuedChallenge createRegistrationChallenge(
      String normalizedMobile,
      String requestId,
      String codeHash,
      Instant requestNow
  ) {
    return createChallenge(
        "REGISTER_CHALLENGE",
        "REGISTER",
        normalizedMobile,
        normalizedMobile,
        "MOBILE",
        normalizedMobile,
        sha256(normalizedMobile),
        requestId,
        codeHash,
        requestNow,
        false);
  }

  @Override
  public RegistrationResultDto registerStudent(
      RegisterRequest request,
      String normalizedNickname,
      String displayName,
      String normalizedMobile,
      String normalizedEmail,
      String passwordHash,
      Instant requestNow,
      boolean verificationRequired,
      boolean adminRegistration
  ) {
    String requestHash = sha256("REGISTER|" + normalizedNickname + "|" + normalizedMobile + "|" + normalizedEmail + "|" + request.challengeId());
    Optional<RegistrationResultDto> replay = replayRegistration(normalizedNickname, request.requestId().toString(), requestHash);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (!verificationRequired) {
      rejectIfChallengeRateLimited("REGISTER", sha256(normalizedMobile), requestNow);
    }
    long idempotencyId = insertProcessingIdempotency(
        "REGISTER",
        normalizedNickname,
        request.requestId().toString(),
        requestHash,
        requestNow);

    ChallengeRow challenge = null;
    if (verificationRequired) {
      challenge = findChallenge(request.challengeId().toString())
          .orElseThrow(() -> challengeInvalid());
      if (challenge.synthetic() || !"REGISTER".equals(challenge.purpose()) || challenge.expiresAt().isBefore(requestNow)
          || !"PENDING".equals(challenge.status()) || !sha256(request.verificationCode()).equals(challenge.codeHash())
          || !sha256(normalizedMobile).equals(challenge.mobileHmac())) {
        recordInvalidChallengeAttempt(challenge, requestNow);
        failIdempotency(idempotencyId, ErrorCode.AUTH_CHALLENGE_INVALID, 400, requestNow);
        throw challengeInvalid();
      }
    }

    long userId = nextId();
    try {
      jdbc.update(
          """
              INSERT INTO sys_user
                (id, username, password_hash, password_changed_at, display_name, mobile, mobile_verified_at, email,
                 status, last_login_at, failed_login_count, last_failed_login_at, locked_until, auth_version, version,
                 created_at, updated_at, created_by, updated_by, deleted)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED', NULL, 0, NULL, NULL, 0, 0, ?, ?, NULL, NULL, 0)
              """,
          userId,
          normalizedNickname,
          passwordHash,
          ts(requestNow),
          displayName,
          normalizedMobile,
          verificationRequired ? ts(requestNow) : null,
          normalizedEmail,
          ts(requestNow),
          ts(requestNow));
      jdbc.update(
          """
              INSERT INTO sys_auth_credential
                (id, user_id, credential_type, credential_subject, secret_hash, status, changed_at, expires_at, created_at, updated_at, version)
              VALUES (?, ?, 'PASSWORD', ?, ?, 'ACTIVE', ?, NULL, ?, ?, 0)
              """,
          nextId(),
          userId,
          normalizedNickname,
          passwordHash,
          ts(requestNow),
          ts(requestNow),
          ts(requestNow));
      insertLoginIdentifier(
          userId,
          "MOBILE",
          normalizedMobile,
          verificationRequired,
          verificationRequired ? requestNow : null,
          requestNow);
      if (normalizedEmail != null) {
        insertLoginIdentifier(userId, "EMAIL", normalizedEmail, false, null, requestNow);
      }
    } catch (DuplicateKeyException ex) {
      failIdempotency(idempotencyId, ErrorCode.AUTH_IDEMPOTENCY_CONFLICT, 409, requestNow);
      if (usernameExists(normalizedNickname)) {
        throw new BusinessException(ErrorCode.AUTH_USERNAME_CONFLICT, "昵称已存在", HttpStatus.CONFLICT);
      }
      throw new BusinessException(ErrorCode.AUTH_IDENTIFIER_CONFLICT, "手机号或邮箱已被使用", HttpStatus.CONFLICT);
    }

    String roleCode = adminRegistration ? "ADMIN" : "STUDENT";
    Long roleId = jdbc.queryForObject(
        "SELECT id FROM sys_role WHERE role_code = ? AND deleted = 0 AND status = 'ENABLED'",
        Long.class, roleCode);
    jdbc.update("INSERT INTO sys_user_role (user_id, role_id, created_at) VALUES (?, ?, ?)", userId, roleId, ts(requestNow));
    if (adminRegistration) {
      Long studentRoleId = jdbc.queryForObject(
          "SELECT id FROM sys_role WHERE role_code = 'STUDENT' AND deleted = 0 AND status = 'ENABLED'",
          Long.class);
      jdbc.update("INSERT INTO sys_user_role (user_id, role_id, created_at) VALUES (?, ?, ?)", userId, studentRoleId, ts(requestNow));
    }
    if (challenge != null) {
      jdbc.update(
          """
              UPDATE sys_auth_challenge
                 SET status = 'CONSUMED', consumed_at = ?, updated_at = ?, version = version + 1
               WHERE id = ? AND status = 'PENDING'
              """,
          ts(requestNow), ts(requestNow), challenge.id());
    }
    completeIdempotency(idempotencyId, 201, String.valueOf(userId), requestNow);
    audit(nextId(), userId, null, idempotencyId, "REGISTER", "SUCCESS", null, sha256(normalizedNickname), AuthClientEvidence.empty(), requestNow);
    return new RegistrationResultDto(userId, normalizedNickname, "REGISTERED", requestNow);
  }

  @Override
  public Optional<LoginUserSnapshot> lockUserForLogin(String identifierType, String normalizedIdentifier, Instant requestNow) {
    if ("DEV_SEED_USERNAME".equals(identifierType)) {
      return lockDevSeedUserForLogin(normalizedIdentifier);
    }
    return optional(
        """
            SELECT u.id, u.username, u.display_name, u.mobile, c.secret_hash AS password_hash,
                   u.status, u.locked_until, u.auth_version
              FROM sys_auth_login_identifier i
              JOIN sys_user u
                ON u.id = i.user_id
              JOIN sys_auth_credential c
                ON c.user_id = u.id AND c.credential_type = 'PASSWORD' AND c.status = 'ACTIVE'
             WHERE i.identifier_type = ?
               AND i.normalized_identifier = ?
               AND i.enabled = 1
               AND u.deleted = 0
             ORDER BY i.id
             LIMIT 1
             FOR UPDATE
            """,
        (rs, rowNum) -> new LoginUserSnapshot(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            maskMobile(rs.getString("mobile")),
            rs.getString("password_hash"),
            rs.getString("status"),
            instant(rs, "locked_until"),
            rs.getLong("auth_version")),
        identifierType,
        normalizedIdentifier);
  }

  @Override
  public void recordLoginFailure(String identifierType, String normalizedIdentifier, Instant requestNow) {
    if ("DEV_SEED_USERNAME".equals(identifierType)) {
      recordDevSeedLoginFailure(normalizedIdentifier, requestNow);
      return;
    }
    jdbc.update(
        """
            UPDATE sys_user u
              JOIN sys_auth_login_identifier i
                ON i.user_id = u.id
               SET u.failed_login_count = u.failed_login_count + 1,
                   u.last_failed_login_at = ?,
                   u.locked_until = CASE WHEN u.failed_login_count + 1 >= 5 THEN DATE_ADD(?, INTERVAL 15 MINUTE) ELSE u.locked_until END,
                   u.updated_at = ?,
                   u.version = u.version + 1
             WHERE i.identifier_type = ?
               AND i.normalized_identifier = ?
               AND i.enabled = 1
               AND u.deleted = 0
            """,
        ts(requestNow), ts(requestNow), ts(requestNow), identifierType, normalizedIdentifier);
  }

  private Optional<LoginUserSnapshot> lockDevSeedUserForLogin(String username) {
    return optional(
        """
            SELECT u.id, u.username, u.display_name, u.mobile, c.secret_hash AS password_hash,
                   u.status, u.locked_until, u.auth_version
              FROM sys_user u
              JOIN sys_auth_credential c
                ON c.user_id = u.id AND c.credential_type = 'PASSWORD' AND c.status = 'ACTIVE'
             WHERE u.username = ?
               AND u.username IN ('student', 'admin', 'question_reviewer')
               AND u.deleted = 0
             ORDER BY u.id
             LIMIT 1
             FOR UPDATE
            """,
        (rs, rowNum) -> new LoginUserSnapshot(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            maskMobile(rs.getString("mobile")),
            rs.getString("password_hash"),
            rs.getString("status"),
            instant(rs, "locked_until"),
            rs.getLong("auth_version")),
        username);
  }

  private void recordDevSeedLoginFailure(String username, Instant requestNow) {
    jdbc.update(
        """
            UPDATE sys_user u
               SET u.failed_login_count = u.failed_login_count + 1,
                   u.last_failed_login_at = ?,
                   u.locked_until = CASE WHEN u.failed_login_count + 1 >= 5 THEN DATE_ADD(?, INTERVAL 15 MINUTE) ELSE u.locked_until END,
                   u.updated_at = ?,
                   u.version = u.version + 1
             WHERE u.username = ?
               AND u.username IN ('student', 'admin', 'question_reviewer')
               AND u.deleted = 0
            """,
        ts(requestNow), ts(requestNow), ts(requestNow), username);
  }

  @Override
  public CommittedAuthSession createLoginSessionUserFirst(
      LoginUserSnapshot user,
      IssuedOpaqueTokens tokens,
      AuthClientEvidence clientEvidence,
      Instant requestNow
  ) {
    List<SessionRow> active = jdbc.query(
        """
            SELECT id, session_no, family_id, generation, user_id, auth_version, access_expires_at, refresh_expires_at
              FROM sys_auth_session
             WHERE user_id = ? AND status = 'ACTIVE' AND archived_at IS NULL
             ORDER BY last_used_at ASC, id ASC
             FOR UPDATE
            """,
        this::sessionRow,
        user.userId());
    while (active.size() >= MAX_ACTIVE_SESSIONS) {
      SessionRow victim = active.remove(0);
      revokeFamily(victim.familyId(), "MAX_ACTIVE_SESSIONS", requestNow);
    }

    long sessionId = nextId();
    String sessionNo = UUID.randomUUID().toString();
    String familyId = UUID.randomUUID().toString();
    jdbc.update(
        """
            INSERT INTO sys_auth_session
              (id, session_no, family_id, generation, parent_session_id, user_id, access_token_hash, refresh_token_hash,
               status, family_revoked_at, family_revoke_reason, auth_version, issued_at, access_expires_at,
               refresh_expires_at, last_used_at, rotated_at, revoked_at, replayed_at, revoke_reason,
               client_ip_hash, user_agent_summary, version, created_at, updated_at, archived_at)
            VALUES (?, ?, ?, 1, NULL, ?, ?, ?, 'ACTIVE', NULL, NULL, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?, 0, ?, ?, NULL)
            """,
        sessionId,
        sessionNo,
        familyId,
        user.userId(),
        tokens.accessTokenHash(),
        tokens.refreshTokenHash(),
        user.authVersion(),
        ts(requestNow),
        ts(tokens.accessExpiresAt()),
        ts(tokens.refreshExpiresAt()),
        ts(requestNow),
        clientEvidence.clientIpHash(),
        clientEvidence.userAgentSummary(),
        ts(requestNow),
        ts(requestNow));
    jdbc.update(
        """
            UPDATE sys_user
               SET last_login_at = ?, failed_login_count = 0, last_failed_login_at = NULL, locked_until = NULL,
                   updated_at = ?, version = version + 1
             WHERE id = ?
            """,
        ts(requestNow), ts(requestNow), user.userId());
    audit(nextId(), user.userId(), sessionId, null, "LOGIN", "SUCCESS", null, sha256(user.username()), clientEvidence, requestNow);
    return new CommittedAuthSession(
        authContext(user.userId(), sessionNo, tokens.accessExpiresAt(), tokens.refreshExpiresAt())
            .orElseThrow(() -> new IllegalStateException("AUTH_CONTEXT_MISSING_AFTER_LOGIN")),
        tokens.accessToken(),
        tokens.refreshToken());
  }

  @Override
  public Optional<AuthContext> findAuthContext(PlatformPrincipal principal, Instant requestNow) {
    if (principal == null || principal.sessionId() == null) {
      return Optional.empty();
    }
    return optional(
        """
            SELECT access_expires_at, refresh_expires_at
              FROM sys_auth_session
             WHERE session_no = ? AND user_id = ? AND status = 'ACTIVE'
               AND access_expires_at > ? AND refresh_expires_at > ?
            """,
        (rs, rowNum) -> authContext(
            principal.userId(),
            principal.sessionId(),
            instant(rs, "access_expires_at"),
            instant(rs, "refresh_expires_at")).orElse(null),
        principal.sessionId(),
        principal.userId(),
        ts(requestNow),
        ts(requestNow)).filter(context -> context != null);
  }

  @Override
  public Optional<PlatformPrincipal> findPrincipalByAccessTokenHash(String accessTokenHash, Instant requestNow) {
    Optional<SessionPrincipalRow> session = optional(
        """
            SELECT s.id, s.session_no, s.user_id, s.auth_version, u.username
              FROM sys_auth_session s
              JOIN sys_user u ON u.id = s.user_id AND u.deleted = 0 AND u.status = 'ENABLED'
             WHERE s.access_token_hash = ?
               AND s.status = 'ACTIVE'
               AND s.access_expires_at > ?
               AND s.refresh_expires_at > ?
             LIMIT 1
            """,
        (rs, rowNum) -> new SessionPrincipalRow(
            rs.getLong("id"),
            rs.getString("session_no"),
            rs.getLong("user_id"),
            rs.getLong("auth_version"),
            rs.getString("username")),
        accessTokenHash,
        ts(requestNow),
        ts(requestNow));
    if (session.isEmpty()) {
      return Optional.empty();
    }
    SessionPrincipalRow row = session.get();
    jdbc.update(
        "UPDATE sys_auth_session SET last_used_at = ?, updated_at = ?, version = version + 1 WHERE id = ?",
        ts(requestNow), ts(requestNow), row.id());
    return Optional.of(new PlatformPrincipal(
        row.userId(),
        row.username(),
        row.sessionNo(),
        roles(row.userId()),
        permissions(row.userId()),
        row.authVersion()));
  }

  @Override
  public RotatedAuthSession rotateRefresh(String refreshTokenHash, IssuedOpaqueTokens nextTokens, Instant requestNow) {
    Optional<SessionRow> maybeCurrent = optional(
        """
            SELECT id, session_no, family_id, generation, user_id, auth_version, access_expires_at, refresh_expires_at
              FROM sys_auth_session
             WHERE refresh_token_hash = ?
             FOR UPDATE
            """,
        this::sessionRow,
        refreshTokenHash);
    if (maybeCurrent.isEmpty()) {
      throw sessionExpired();
    }
    SessionRow current = maybeCurrent.get();
    String status = jdbc.queryForObject("SELECT status FROM sys_auth_session WHERE id = ?", String.class, current.id());
    if (!"ACTIVE".equals(status)) {
      revokeFamily(current.familyId(), "REFRESH_REPLAY", requestNow);
      jdbc.update(
          """
              UPDATE sys_auth_session
                 SET status = 'REPLAYED', replayed_at = ?, family_revoked_at = ?, family_revoke_reason = 'REFRESH_REPLAY',
                     updated_at = ?, version = version + 1
               WHERE id = ?
              """,
          ts(requestNow), ts(requestNow), ts(requestNow), current.id());
      throw sessionExpired();
    }
    jdbc.update(
        """
            UPDATE sys_auth_session
               SET status = 'ROTATED', rotated_at = ?, updated_at = ?, version = version + 1
             WHERE id = ? AND status = 'ACTIVE'
            """,
        ts(requestNow), ts(requestNow), current.id());

    long nextSessionId = nextId();
    jdbc.update(
        """
            INSERT INTO sys_auth_session
              (id, session_no, family_id, generation, parent_session_id, user_id, access_token_hash, refresh_token_hash,
               status, family_revoked_at, family_revoke_reason, auth_version, issued_at, access_expires_at,
               refresh_expires_at, last_used_at, rotated_at, revoked_at, replayed_at, revoke_reason,
               client_ip_hash, user_agent_summary, version, created_at, updated_at, archived_at)
            SELECT ?, ?, family_id, generation + 1, id, user_id, ?, ?, 'ACTIVE', NULL, NULL, auth_version,
                   ?, ?, ?, ?, NULL, NULL, NULL, NULL, client_ip_hash, user_agent_summary, 0, ?, ?, NULL
              FROM sys_auth_session
             WHERE id = ?
            """,
        nextSessionId,
        UUID.randomUUID().toString(),
        nextTokens.accessTokenHash(),
        nextTokens.refreshTokenHash(),
        ts(requestNow),
        ts(nextTokens.accessExpiresAt()),
        ts(nextTokens.refreshExpiresAt()),
        ts(requestNow),
        ts(requestNow),
        ts(requestNow),
        current.id());
    audit(nextId(), current.userId(), nextSessionId, null, "REFRESH", "SUCCESS", null, null, AuthClientEvidence.empty(), requestNow);
    return new RotatedAuthSession(
        new SessionExpiryDto(nextTokens.accessExpiresAt(), nextTokens.refreshExpiresAt()),
        nextTokens.accessToken(),
        nextTokens.refreshToken());
  }

  @Override
  public void logout(PlatformPrincipal principal, String refreshTokenHash, Instant requestNow) {
    if (principal == null && refreshTokenHash == null) {
      return;
    }
    if (refreshTokenHash != null) {
      optional(
          "SELECT family_id FROM sys_auth_session WHERE refresh_token_hash = ? FOR UPDATE",
          (rs, rowNum) -> rs.getString("family_id"),
          refreshTokenHash).ifPresent(familyId -> revokeFamily(familyId, "LOGOUT", requestNow));
    } else {
      optional(
          "SELECT family_id FROM sys_auth_session WHERE session_no = ? AND user_id = ? FOR UPDATE",
          (rs, rowNum) -> rs.getString("family_id"),
          principal.sessionId(),
          principal.userId()).ifPresent(familyId -> revokeFamily(familyId, "LOGOUT", requestNow));
    }
    audit(nextId(), principal == null ? null : principal.userId(), null, null, "LOGOUT", "SUCCESS", null, null, AuthClientEvidence.empty(), requestNow);
  }

  @Override
  public IssuedChallenge createSmsLoginChallenge(
      String normalizedMobile,
      String requestId,
      String codeHash,
      Instant requestNow
  ) {
    String mobileHash = identifierHmac("MOBILE", normalizedMobile);
    boolean synthetic = !verifiedMobileMatches(normalizedMobile);
    return createChallenge(
        "SMS_LOGIN_CHALLENGE",
        "SMS_LOGIN",
        identifierScope("MOBILE", normalizedMobile),
        "MOBILE",
        "MOBILE",
        normalizedMobile,
        mobileHash,
        requestId,
        codeHash,
        requestNow,
        synthetic);
  }

  @Override
  public Optional<LoginUserSnapshot> consumeSmsLoginChallenge(
      String normalizedMobile,
      SmsLoginRequest request,
      String verificationCodeHash,
      Instant requestNow
  ) {
    ChallengeRow challenge = findChallenge(request.challengeId().toString()).orElse(null);
    String mobileHash = identifierHmac("MOBILE", normalizedMobile);
    if (challenge == null) {
      return Optional.empty();
    }
    boolean valid = !challenge.synthetic()
        && "SMS_LOGIN".equals(challenge.purpose())
        && "MOBILE".equals(challenge.subjectRef())
        && mobileHash.equals(challenge.mobileHmac())
        && verificationCodeHash.equals(challenge.codeHash())
        && !challenge.expiresAt().isBefore(requestNow)
        && "PENDING".equals(challenge.status());
    if (!valid) {
      recordInvalidChallengeAttempt(challenge, requestNow);
      return Optional.empty();
    }
    Optional<LoginUserSnapshot> user = optional(
        """
            SELECT u.id, u.username, u.display_name, u.mobile, c.secret_hash AS password_hash,
                   u.status, u.locked_until, u.auth_version
              FROM sys_auth_login_identifier i
              JOIN sys_user u
                ON u.id = i.user_id
              JOIN sys_auth_credential c
                ON c.user_id = u.id AND c.credential_type = 'PASSWORD' AND c.status = 'ACTIVE'
             WHERE i.identifier_type = 'MOBILE'
               AND i.normalized_identifier = ?
               AND i.verified = 1
               AND i.enabled = 1
               AND u.mobile_verified_at IS NOT NULL
               AND u.status = 'ENABLED'
               AND u.deleted = 0
               AND (u.locked_until IS NULL OR u.locked_until <= ?)
             ORDER BY i.id
             LIMIT 1
             FOR UPDATE
            """,
        (rs, rowNum) -> new LoginUserSnapshot(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            maskMobile(rs.getString("mobile")),
            rs.getString("password_hash"),
            rs.getString("status"),
            instant(rs, "locked_until"),
            rs.getLong("auth_version")),
        normalizedMobile,
        ts(requestNow));
    if (user.isEmpty()) {
      recordInvalidChallengeAttempt(challenge, requestNow);
      return Optional.empty();
    }
    int consumed = jdbc.update(
        """
            UPDATE sys_auth_challenge
               SET status = 'CONSUMED', consumed_at = ?, updated_at = ?, version = version + 1
             WHERE id = ? AND status = 'PENDING'
            """,
        ts(requestNow), ts(requestNow), challenge.id());
    return consumed == 1 ? user : Optional.empty();
  }

  @Override
  public IssuedChallenge createPasswordResetChallenge(
      String identifierType,
      String normalizedIdentifier,
      String requestId,
      String codeHash,
      Instant requestNow
  ) {
    String identifierHash = identifierHmac(identifierType, normalizedIdentifier);
    boolean synthetic = !loginIdentifierMatches(identifierType, normalizedIdentifier);
    return createChallenge(
        "PASSWORD_RESET_CHALLENGE",
        "PASSWORD_RESET",
        identifierScope(identifierType, normalizedIdentifier),
        identifierType,
        identifierType,
        normalizedIdentifier,
        identifierHash,
        requestId,
        codeHash,
        requestNow,
        synthetic);
  }

  @Override
  public Optional<PasswordResetAccountContext> findPasswordResetAccountContext(
      String identifierType,
      String normalizedIdentifier,
      PasswordResetRequest request,
      String verificationCodeHash,
      Instant requestNow
  ) {
    ChallengeRow challenge = findChallenge(request.challengeId().toString())
        .orElse(null);
    String identifierHash = identifierHmac(identifierType, normalizedIdentifier);
    if (challenge == null) {
      return Optional.empty();
    }
    if (!passwordResetChallengeMatches(challenge, identifierType, identifierHash, verificationCodeHash, requestNow)) {
      recordInvalidChallengeAttempt(challenge, requestNow);
      return Optional.empty();
    }
    return optional(
        """
            SELECT u.id,
                   u.username,
                   (
                     SELECT mi.normalized_identifier
                       FROM sys_auth_login_identifier mi
                      WHERE mi.user_id = u.id
                        AND mi.identifier_type = 'MOBILE'
                        AND mi.enabled = 1
                      ORDER BY mi.id
                      LIMIT 1
                   ) AS normalized_mobile,
                   (
                     SELECT ei.normalized_identifier
                       FROM sys_auth_login_identifier ei
                      WHERE ei.user_id = u.id
                        AND ei.identifier_type = 'EMAIL'
                        AND ei.enabled = 1
                      ORDER BY ei.id
                      LIMIT 1
                   ) AS normalized_email
              FROM sys_auth_login_identifier i
              JOIN sys_user u
                ON u.id = i.user_id
             WHERE i.identifier_type = ?
               AND i.normalized_identifier = ?
               AND i.enabled = 1
               AND u.deleted = 0
               AND u.status = 'ENABLED'
             ORDER BY i.id
             LIMIT 1
            """,
        (rs, rowNum) -> new PasswordResetAccountContext(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("normalized_mobile"),
            rs.getString("normalized_email")),
        identifierType,
        normalizedIdentifier);
  }

  @Override
  public void resetPassword(
      String identifierType,
      String normalizedIdentifier,
      PasswordResetRequest request,
      String passwordHash,
      Instant requestNow
  ) {
    String identifierHash = identifierHmac(identifierType, normalizedIdentifier);
    String requestHash = sha256("PASSWORD_RESET|" + request.challengeId() + "|" + identifierType + "|" + identifierHash);
    long idempotencyId = insertProcessingIdempotency(
        "PASSWORD_RESET",
        request.challengeId().toString(),
        request.requestId().toString(),
        requestHash,
        requestNow);
    ChallengeRow challenge = findChallenge(request.challengeId().toString())
        .orElseThrow(() -> challengeInvalid());
    if (!passwordResetChallengeMatches(
        challenge,
        identifierType,
        identifierHash,
        sha256(request.verificationCode()),
        requestNow)) {
      recordInvalidChallengeAttempt(challenge, requestNow);
      failIdempotency(idempotencyId, ErrorCode.AUTH_CHALLENGE_INVALID, 400, requestNow);
      throw challengeInvalid();
    }
    Long userId = jdbc.queryForObject(
        """
            SELECT u.id
              FROM sys_auth_login_identifier i
              JOIN sys_user u
                ON u.id = i.user_id
             WHERE i.identifier_type = ?
               AND i.normalized_identifier = ?
               AND i.enabled = 1
               AND u.deleted = 0
               AND u.status = 'ENABLED'
             ORDER BY i.id
             LIMIT 1
            """,
        Long.class,
        identifierType,
        normalizedIdentifier);
    jdbc.update(
        """
            UPDATE sys_auth_credential
               SET status = 'ROTATED', updated_at = ?, version = version + 1
             WHERE user_id = ? AND credential_type = 'PASSWORD' AND status = 'ACTIVE'
            """,
        ts(requestNow), userId);
    jdbc.update(
        """
            INSERT INTO sys_auth_credential
              (id, user_id, credential_type, credential_subject, secret_hash, status, changed_at, expires_at, created_at, updated_at, version)
            VALUES (?, ?, 'PASSWORD', ?, ?, 'ACTIVE', ?, NULL, ?, ?, 0)
            """,
        nextId(),
        userId,
        identifierType + ":" + identifierHash,
        passwordHash,
        ts(requestNow),
        ts(requestNow),
        ts(requestNow));
    jdbc.update(
        """
            UPDATE sys_user
               SET password_hash = ?, password_changed_at = ?, auth_version = auth_version + 1,
                   updated_at = ?, version = version + 1
             WHERE id = ?
            """,
        passwordHash, ts(requestNow), ts(requestNow), userId);
    jdbc.update(
        """
            UPDATE sys_auth_challenge
               SET status = 'CONSUMED', consumed_at = ?, updated_at = ?, version = version + 1
             WHERE id = ?
            """,
        ts(requestNow), ts(requestNow), challenge.id());
    jdbc.query(
        "SELECT DISTINCT family_id FROM sys_auth_session WHERE user_id = ? AND status = 'ACTIVE' FOR UPDATE",
        (rs, rowNum) -> rs.getString("family_id"),
        userId).forEach(familyId -> revokeFamily(familyId, "PASSWORD_CHANGED", requestNow));
    completeIdempotency(idempotencyId, 204, String.valueOf(userId), requestNow);
    audit(nextId(), userId, null, idempotencyId, "PASSWORD_RESET", "SUCCESS", null, identifierHash, AuthClientEvidence.empty(), requestNow);
  }

  private IssuedChallenge createChallenge(
      String operation,
      String purpose,
      String actorScope,
      String subjectRef,
      String deliveryChannel,
      String deliveryAddress,
      String deliveryHash,
      String requestId,
      String codeHash,
      Instant requestNow,
      boolean synthetic
  ) {
    String requestHash = sha256(operation + "|" + actorScope + "|" + deliveryHash);
    Optional<IssuedChallenge> replay = replayChallenge(operation, actorScope, requestId, requestHash);
    if (replay.isPresent()) {
      return replay.get();
    }
    rejectIfChallengeRateLimited(purpose, deliveryHash, requestNow);
    long idempotencyId = insertProcessingIdempotency(operation, actorScope, requestId, requestHash, requestNow);
    long challengeId = nextId();
    String challengeNo = UUID.randomUUID().toString();
    Instant expiresAt = requestNow.plusSeconds(300);
    jdbc.update(
        """
            INSERT INTO sys_auth_challenge
              (id, challenge_no, idempotency_id, purpose, subject_ref, is_synthetic, mobile_hmac, masked_mobile,
               code_hash, status, delivery_status, request_id, expires_at, attempt_count, consumed_at, locked_at,
               delivery_attempt_count, next_delivery_at, client_ip_hash, device_fingerprint_hash, created_at,
               updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, 0, NULL, NULL, ?, NULL, NULL, NULL, ?, ?, 0)
            """,
        challengeId,
        challengeNo,
        idempotencyId,
        purpose,
        subjectRef,
        synthetic ? 1 : 0,
        deliveryHash,
        maskDelivery(deliveryChannel, deliveryAddress),
        codeHash,
        synthetic ? "SYNTHETIC_DONE" : "SENT",
        requestId,
        ts(expiresAt),
        synthetic ? 0 : 1,
        ts(requestNow),
        ts(requestNow));
    completeIdempotency(idempotencyId, 202, challengeNo, requestNow);
    return new IssuedChallenge(
        new ChallengeDto(UUID.fromString(challengeNo), expiresAt, 60),
        !synthetic,
        deliveryChannel,
        deliveryAddress);
  }

  private void rejectIfChallengeRateLimited(String purpose, String deliveryHash, Instant now) {
    Integer count = jdbc.queryForObject(
        """
            SELECT COUNT(*)
              FROM sys_auth_challenge
             WHERE purpose = ?
               AND mobile_hmac = ?
               AND created_at > DATE_SUB(?, INTERVAL 60 SECOND)
               AND status IN ('PENDING', 'VERIFIED')
            """,
        Integer.class,
        purpose,
        deliveryHash,
        ts(now));
    if (count != null && count > 0) {
      throw new BusinessException(ErrorCode.AUTH_RATE_LIMITED, "请求过于频繁，请稍后重试", HttpStatus.TOO_MANY_REQUESTS);
    }
  }

  private Optional<IssuedChallenge> replayChallenge(String operation, String actorScope, String requestId, String requestHash) {
    Optional<IdempotencyRow> row = idempotency(operation, actorScope, requestId);
    if (row.isEmpty()) {
      return Optional.empty();
    }
    if (!requestHash.equals(row.get().requestHash())) {
      throw new BusinessException(ErrorCode.AUTH_IDEMPOTENCY_CONFLICT, "幂等请求冲突", HttpStatus.CONFLICT);
    }
    if ("PROCESSING".equals(row.get().status())) {
      throw new BusinessException(ErrorCode.AUTH_REQUEST_PROCESSING, "请求正在处理", HttpStatus.CONFLICT);
    }
    ChallengeRow challenge = findChallengeByIdempotency(row.get().id())
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_IDEMPOTENCY_CONFLICT, "幂等状态异常", HttpStatus.CONFLICT));
    return Optional.of(new IssuedChallenge(
        new ChallengeDto(UUID.fromString(challenge.challengeNo()), challenge.expiresAt(), 60),
        false,
        null));
  }

  private Optional<RegistrationResultDto> replayRegistration(String actorScope, String requestId, String requestHash) {
    Optional<IdempotencyRow> row = idempotency("REGISTER", actorScope, requestId);
    if (row.isEmpty()) {
      return Optional.empty();
    }
    if (!requestHash.equals(row.get().requestHash())) {
      throw new BusinessException(ErrorCode.AUTH_IDEMPOTENCY_CONFLICT, "幂等请求冲突", HttpStatus.CONFLICT);
    }
    if ("PROCESSING".equals(row.get().status())) {
      throw new BusinessException(ErrorCode.AUTH_REQUEST_PROCESSING, "请求正在处理", HttpStatus.CONFLICT);
    }
    Long userId = Long.valueOf(row.get().resultRef());
    return optional(
        "SELECT id, username, created_at FROM sys_user WHERE id = ? AND deleted = 0",
        (rs, rowNum) -> new RegistrationResultDto(
            rs.getLong("id"),
            rs.getString("username"),
            "REGISTERED",
            instant(rs, "created_at")),
        userId);
  }

  private long insertProcessingIdempotency(String operation, String actorScope, String requestId, String requestHash, Instant now) {
    long id = nextId();
    try {
      jdbc.update(
          """
              INSERT INTO sys_auth_idempotency
                (id, operation, actor_scope, request_id, request_hash, status, owner_token, execution_epoch,
                 lease_expires_at, http_status, response_snapshot_json, result_ref, error_code, created_at,
                 updated_at, expires_at, archived_at, version)
              VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, 1, ?, NULL, NULL, NULL, NULL, ?, ?, ?, NULL, 0)
              """,
          id,
          operation,
          actorScope,
          requestId,
          requestHash,
          UUID.randomUUID().toString(),
          ts(now.plusSeconds(30)),
          ts(now),
          ts(now),
          ts(now.plusSeconds(86400)));
      return id;
    } catch (DuplicateKeyException ex) {
      Optional<IdempotencyRow> row = idempotency(operation, actorScope, requestId);
      if (row.isPresent() && !requestHash.equals(row.get().requestHash())) {
        throw new BusinessException(ErrorCode.AUTH_IDEMPOTENCY_CONFLICT, "幂等请求冲突", HttpStatus.CONFLICT);
      }
      throw new BusinessException(ErrorCode.AUTH_REQUEST_PROCESSING, "请求正在处理", HttpStatus.CONFLICT);
    }
  }

  private void completeIdempotency(long id, int httpStatus, String resultRef, Instant now) {
    jdbc.update(
        """
            UPDATE sys_auth_idempotency
               SET status = 'COMPLETED', owner_token = NULL, lease_expires_at = NULL, http_status = ?,
                   response_snapshot_json = JSON_OBJECT('resultRef', ?), result_ref = ?, updated_at = ?, version = version + 1
             WHERE id = ?
            """,
        httpStatus, resultRef, resultRef, ts(now), id);
  }

  private void failIdempotency(long id, String errorCode, int httpStatus, Instant now) {
    jdbc.update(
        """
            UPDATE sys_auth_idempotency
               SET status = 'FAILED', owner_token = NULL, lease_expires_at = NULL, http_status = ?,
                   response_snapshot_json = JSON_OBJECT('errorCode', ?), error_code = ?, updated_at = ?, version = version + 1
             WHERE id = ?
            """,
        httpStatus, errorCode, errorCode, ts(now), id);
  }

  private Optional<IdempotencyRow> idempotency(String operation, String actorScope, String requestId) {
    return optional(
        """
            SELECT id, request_hash, status, result_ref
              FROM sys_auth_idempotency
             WHERE operation = ? AND actor_scope = ? AND request_id = ?
             FOR UPDATE
            """,
        (rs, rowNum) -> new IdempotencyRow(
            rs.getLong("id"),
            rs.getString("request_hash"),
            rs.getString("status"),
            rs.getString("result_ref")),
        operation,
        actorScope,
        requestId);
  }

  private Optional<ChallengeRow> findChallengeByIdempotency(long idempotencyId) {
    return optional(
        """
            SELECT id, challenge_no, purpose, subject_ref, is_synthetic, mobile_hmac, code_hash, status, expires_at
              FROM sys_auth_challenge
             WHERE idempotency_id = ?
            """,
        this::challengeRow,
        idempotencyId);
  }

  private Optional<ChallengeRow> findChallenge(String challengeNo) {
    return optional(
        """
            SELECT id, challenge_no, purpose, subject_ref, is_synthetic, mobile_hmac, code_hash, status, expires_at
              FROM sys_auth_challenge
             WHERE challenge_no = ?
             FOR UPDATE
            """,
        this::challengeRow,
        challengeNo);
  }

  private void recordInvalidChallengeAttempt(ChallengeRow challenge, Instant now) {
    jdbc.update(
        """
            UPDATE sys_auth_challenge
               SET attempt_count = attempt_count + 1,
                   status = CASE WHEN attempt_count + 1 >= 5 THEN 'LOCKED' ELSE status END,
                   locked_at = CASE WHEN attempt_count + 1 >= 5 THEN ? ELSE locked_at END,
                   updated_at = ?,
                   version = version + 1
             WHERE id = ? AND status = 'PENDING'
            """,
        ts(now), ts(now), challenge.id());
  }

  private Optional<AuthContext> authContext(Long userId, String sessionNo, Instant accessExpiresAt, Instant refreshExpiresAt) {
    return optional(
        """
            SELECT u.id, u.username, u.display_name, u.mobile, u.status
              FROM sys_user u
             WHERE u.id = ? AND u.deleted = 0 AND u.status = 'ENABLED'
            """,
        (rs, rowNum) -> new AuthContext(
            new AuthUserDto(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                maskMobile(rs.getString("mobile")),
                rs.getString("status")),
            roles(userId),
            permissions(userId),
            menus(userId),
            featureFlags(),
            accessExpiresAt,
            refreshExpiresAt),
        userId);
  }

  private List<String> roles(Long userId) {
    return jdbc.queryForList(
        """
            SELECT r.role_code
              FROM sys_user_role ur
              JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 'ENABLED'
             WHERE ur.user_id = ?
             ORDER BY r.role_code
            """,
        String.class,
        userId);
  }

  private List<String> permissions(Long userId) {
    return jdbc.queryForList(
        """
            SELECT DISTINCT m.permission_code
              FROM sys_user_role ur
              JOIN sys_role_menu rm ON rm.role_id = ur.role_id
              JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.enabled = 1
             WHERE ur.user_id = ? AND m.permission_code IS NOT NULL AND m.permission_code <> ''
             ORDER BY m.permission_code
            """,
        String.class,
        userId);
  }

  private List<AuthMenuDto> menus(Long userId) {
    return jdbc.query(
        """
            SELECT DISTINCT m.menu_code, m.menu_name, m.route_path, m.module_code
              FROM sys_user_role ur
              JOIN sys_role_menu rm ON rm.role_id = ur.role_id
              JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.enabled = 1 AND m.menu_type = 'MENU'
             WHERE ur.user_id = ?
             ORDER BY m.menu_code
            """,
        (rs, rowNum) -> new AuthMenuDto(
            rs.getString("menu_code"),
            rs.getString("menu_name"),
            rs.getString("route_path"),
            rs.getString("module_code")),
        userId);
  }

  private Map<String, Boolean> featureFlags() {
    return jdbc.query(
        "SELECT feature_code, enabled FROM sys_feature_flag WHERE deleted = 0",
        rs -> {
          java.util.LinkedHashMap<String, Boolean> values = new java.util.LinkedHashMap<>();
          while (rs.next()) {
            values.put(rs.getString("feature_code"), rs.getInt("enabled") == 1);
          }
          return values;
        });
  }

  private void revokeFamily(String familyId, String reason, Instant requestNow) {
    jdbc.update(
        """
            UPDATE sys_auth_session
               SET revoked_at = CASE WHEN status = 'ACTIVE' THEN ? ELSE revoked_at END,
                   revoke_reason = CASE WHEN status = 'ACTIVE' THEN ? ELSE revoke_reason END,
                   status = CASE WHEN status = 'ACTIVE' THEN 'REVOKED' ELSE status END,
                   family_revoked_at = ?,
                   family_revoke_reason = ?,
                   updated_at = ?,
                   version = version + 1
             WHERE family_id = ? AND archived_at IS NULL
            """,
        ts(requestNow), reason, ts(requestNow), reason, ts(requestNow), familyId);
  }

  private void audit(
      long id,
      Long userId,
      Long sessionId,
      Long idempotencyId,
      String eventType,
      String outcome,
      String reasonCode,
      String identifierHash,
      AuthClientEvidence evidence,
      Instant requestNow
  ) {
    jdbc.update(
        """
            INSERT INTO sys_login_audit
              (id, event_no, user_id, session_id, idempotency_id, event_type, outcome, reason_code,
               identifier_hash, masked_mobile, client_ip_hash, user_agent_summary, trace_id, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, ?)
            """,
        id,
        UUID.randomUUID().toString(),
        userId,
        sessionId,
        idempotencyId,
        eventType,
        outcome,
        reasonCode,
        identifierHash,
        evidence.clientIpHash(),
        evidence.userAgentSummary(),
        ts(requestNow));
  }

  private boolean usernameExists(String username) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username = ? AND deleted = 0", Integer.class, username);
    return count != null && count > 0;
  }

  private void insertLoginIdentifier(
      long userId,
      String identifierType,
      String normalizedIdentifier,
      boolean verified,
      Instant verifiedAt,
      Instant requestNow
  ) {
    jdbc.update(
        """
            INSERT INTO sys_auth_login_identifier
              (id, user_id, identifier_type, normalized_identifier, verified, enabled,
               verified_at, disabled_at, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, 1, ?, NULL, ?, ?, 0)
            """,
        nextId(),
        userId,
        identifierType,
        normalizedIdentifier,
        verified ? 1 : 0,
        verifiedAt == null ? null : ts(verifiedAt),
        ts(requestNow),
        ts(requestNow));
  }

  private boolean passwordResetChallengeMatches(
      ChallengeRow challenge,
      String identifierType,
      String identifierHash,
      String verificationCodeHash,
      Instant requestNow
  ) {
    return !challenge.synthetic()
        && "PASSWORD_RESET".equals(challenge.purpose())
        && !challenge.expiresAt().isBefore(requestNow)
        && "PENDING".equals(challenge.status())
        && identifierType.equals(challenge.subjectRef())
        && identifierHash.equals(challenge.mobileHmac())
        && verificationCodeHash.equals(challenge.codeHash());
  }

  private boolean loginIdentifierMatches(String identifierType, String normalizedIdentifier) {
    Integer count = jdbc.queryForObject(
        """
            SELECT COUNT(*)
              FROM sys_auth_login_identifier i
              JOIN sys_user u
                ON u.id = i.user_id
             WHERE i.identifier_type = ?
               AND i.normalized_identifier = ?
               AND i.enabled = 1
               AND u.deleted = 0
               AND u.status = 'ENABLED'
            """,
        Integer.class,
        identifierType,
        normalizedIdentifier);
    return count != null && count > 0;
  }

  private boolean verifiedMobileMatches(String normalizedMobile) {
    Integer count = jdbc.queryForObject(
        """
            SELECT COUNT(*)
              FROM sys_auth_login_identifier i
              JOIN sys_user u
                ON u.id = i.user_id
             WHERE i.identifier_type = 'MOBILE'
               AND i.normalized_identifier = ?
               AND i.verified = 1
               AND i.enabled = 1
               AND u.mobile_verified_at IS NOT NULL
               AND u.status = 'ENABLED'
               AND u.deleted = 0
            """,
        Integer.class,
        normalizedMobile);
    return count != null && count > 0;
  }

  private String identifierScope(String identifierType, String normalizedIdentifier) {
    return identifierType + "|" + identifierHmac(identifierType, normalizedIdentifier);
  }

  private String identifierHmac(String identifierType, String normalizedIdentifier) {
    return sha256(identifierType + "|" + normalizedIdentifier);
  }

  private ChallengeRow challengeRow(ResultSet rs, int rowNum) throws SQLException {
    return new ChallengeRow(
        rs.getLong("id"),
        rs.getString("challenge_no"),
        rs.getString("purpose"),
        rs.getString("subject_ref"),
        rs.getInt("is_synthetic") == 1,
        rs.getString("mobile_hmac"),
        rs.getString("code_hash"),
        rs.getString("status"),
        instant(rs, "expires_at"));
  }

  private SessionRow sessionRow(ResultSet rs, int rowNum) throws SQLException {
    return new SessionRow(
        rs.getLong("id"),
        rs.getString("session_no"),
        rs.getString("family_id"),
        rs.getInt("generation"),
        rs.getLong("user_id"),
        rs.getLong("auth_version"),
        instant(rs, "access_expires_at"),
        instant(rs, "refresh_expires_at"));
  }

  private <T> Optional<T> optional(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    try {
      return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  private BusinessException challengeInvalid() {
    return new BusinessException(ErrorCode.AUTH_CHALLENGE_INVALID, "验证码无效", HttpStatus.BAD_REQUEST);
  }

  private BusinessException sessionExpired() {
    return new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED, "会话已过期", HttpStatus.UNAUTHORIZED);
  }

  private static long nextId() {
    return System.currentTimeMillis() * 1000L + Math.floorMod(SEQUENCE.incrementAndGet(), 1000);
  }

  private static Timestamp ts(Instant instant) {
    return Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required by the platform runtime", ex);
    }
  }

  private static String maskMobile(String mobile) {
    if (mobile == null || mobile.length() < 8) {
      return null;
    }
    return mobile.substring(0, Math.min(6, mobile.length() - 4)) + "****" + mobile.substring(mobile.length() - 4);
  }

  private static String maskDelivery(String deliveryChannel, String deliveryAddress) {
    if ("EMAIL".equals(deliveryChannel)) {
      return maskEmail(deliveryAddress);
    }
    return maskMobile(deliveryAddress);
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
    return masked.length() <= 32 ? masked : masked.substring(0, 32);
  }

  private record ChallengeRow(
      long id,
      String challengeNo,
      String purpose,
      String subjectRef,
      boolean synthetic,
      String mobileHmac,
      String codeHash,
      String status,
      Instant expiresAt
  ) {
  }

  private record SessionRow(
      long id,
      String sessionNo,
      String familyId,
      int generation,
      long userId,
      long authVersion,
      Instant accessExpiresAt,
      Instant refreshExpiresAt
  ) {
  }

  private record SessionPrincipalRow(
      long id,
      String sessionNo,
      long userId,
      long authVersion,
      String username
  ) {
  }

  private record IdempotencyRow(long id, String requestHash, String status, String resultRef) {
  }
}
