package com.aiexam.foundation.auth.infra;

import com.aiexam.foundation.auth.domain.Argon2idPasswordPolicy;
import com.aiexam.foundation.auth.repository.PasswordHashingPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Conditional(DevTestAccountProfileCondition.class)
@ConditionalOnProperty(
    prefix = "aiexam.auth.dev-accounts",
    name = "enabled",
    havingValue = "true"
)
public class DevTestAccountInitializer implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(DevTestAccountInitializer.class);
  private static final AtomicInteger SEQUENCE = new AtomicInteger();
  private static final String QUESTION_READ = "question:read";
  private static final String QUESTION_CREATE = "question:create";
  private static final String QUESTION_UPDATE = "question:update";
  private static final String QUESTION_SUBMIT_REVIEW = "question:submit-review";
  private static final String QUESTION_REVIEW = "question:review";
  private static final String QUESTION_PUBLISH = "question:publish";
  private static final String QUESTION_IMPORT = "question:import";
  private static final String ADMIN_HOME_VIEW = "admin:home:view";
  private static final String FOUNDATION_READ = "foundation:read";
  private static final String FOUNDATION_WRITE = "foundation:write";
  private static final String STUDENT_HOME_VIEW = "student:home:view";
  private static final String PRACTICE_WORKSPACE_VIEW = "practice:workspace:view";
  private static final String EXAM_SIMULATOR_ENTER = "exam-simulator:enter";
  private static final String DEVELOPMENT_ACCOUNT_PASSWORD = "123456";

  private final JdbcTemplate jdbc;
  private final PasswordHashingPort passwordHashingPort;

  public DevTestAccountInitializer(
      JdbcTemplate jdbc,
      PasswordHashingPort passwordHashingPort
  ) {
    this.jdbc = jdbc;
    this.passwordHashingPort = passwordHashingPort;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    ensurePlatformRolesAndMenus();
    ensureQuestionRolesAndPermissions();
    ensureAccount("student", "test_student", "统一测试学员", "+8613900002001", "student.dev@aiexam.local",
        List.of("STUDENT"));
    ensureAccount("admin", "test_admin", "题库验收提交人", "+8613900002002", "admin.dev@aiexam.local",
        List.of("ADMIN", "STUDENT", "QUESTION_AUTHOR"));
    ensureAccount("question_reviewer", null, "题库验收审核发布人", "+8613900002003", "question-reviewer.dev@aiexam.local",
        List.of("QUESTION_REVIEWER", "QUESTION_PUBLISHER"));
    log.info("Development test accounts and question review permissions are ready");
  }

  private void ensureAccount(
      String username,
      String legacyUsername,
      String displayName,
      String fallbackMobile,
      String fallbackEmail,
      List<String> roleCodes
  ) {
    Optional<Long> existing = userId(username);
    if (existing.isPresent()) {
      normalizeExistingAccount(existing.get(), username, displayName, fallbackMobile, fallbackEmail, roleCodes);
      if (legacyUsername != null) {
        disableLegacyDuplicate(legacyUsername);
      }
      return;
    }

    String passwordHash = passwordHashingPort.hash(
        DEVELOPMENT_ACCOUNT_PASSWORD,
        Argon2idPasswordPolicy.frozenP0());
    Optional<Long> legacy = legacyUsername == null ? Optional.empty() : userId(legacyUsername);
    long userId;
    if (legacy.isPresent()) {
      userId = legacy.get();
      Timestamp now = now();
      jdbc.update(
          """
              UPDATE sys_user
                 SET username = ?, display_name = ?, password_hash = ?, password_changed_at = ?,
                     mobile = ?, mobile_verified_at = ?, email = ?,
                     status = 'ENABLED', failed_login_count = 0, last_failed_login_at = NULL,
                     locked_until = NULL, auth_version = auth_version + 1, version = version + 1,
                     updated_at = ?
               WHERE id = ?
              """,
          username, displayName, passwordHash, now, fallbackMobile, now, fallbackEmail, now, userId);
      upsertPasswordCredential(userId, username, passwordHash, now);
    } else {
      userId = nextId();
      Timestamp now = now();
      jdbc.update(
          """
              INSERT INTO sys_user
                (id, username, password_hash, password_changed_at, display_name, mobile, mobile_verified_at,
                 email, status, last_login_at, failed_login_count, last_failed_login_at, locked_until,
                 auth_version, version, created_at, updated_at, created_by, updated_by, deleted)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED', NULL, 0, NULL, NULL, 0, 0, ?, ?, NULL, NULL, 0)
              """,
          userId, username, passwordHash, now, displayName, fallbackMobile, now, fallbackEmail, now, now);
      upsertPasswordCredential(userId, username, passwordHash, now);
    }
    upsertLoginIdentifiersIfReady(userId, fallbackMobile, fallbackEmail, now());
    replaceRoles(userId, roleCodes);
    jdbc.update("DELETE FROM sys_auth_session WHERE user_id = ?", userId);
  }

  private void normalizeExistingAccount(
      long userId,
      String username,
      String displayName,
      String mobile,
      String email,
      List<String> roleCodes
  ) {
    Timestamp now = now();
    Optional<String> activePasswordHash = activePasswordHash(userId);
    boolean passwordResetRequired = activePasswordHash.isEmpty()
        || !passwordHashingPort.verify(DEVELOPMENT_ACCOUNT_PASSWORD, activePasswordHash.get());
    String passwordHash = passwordResetRequired
        ? passwordHashingPort.hash(DEVELOPMENT_ACCOUNT_PASSWORD, Argon2idPasswordPolicy.frozenP0())
        : activePasswordHash.get();
    jdbc.update(
        """
            UPDATE sys_user
               SET display_name = ?, mobile = ?, mobile_verified_at = ?, email = ?,
                   status = 'ENABLED', failed_login_count = 0,
                   last_failed_login_at = NULL, locked_until = NULL, password_hash = ?,
                   password_changed_at = CASE WHEN ? THEN ? ELSE password_changed_at END,
                   auth_version = auth_version + CASE WHEN ? THEN 1 ELSE 0 END,
                   version = version + CASE WHEN ? THEN 1 ELSE 0 END,
                   updated_at = ?
             WHERE id = ? AND username = ? AND deleted = 0
            """,
        displayName,
        mobile,
        now,
        email,
        passwordHash,
        passwordResetRequired,
        now,
        passwordResetRequired,
        passwordResetRequired,
        now,
        userId,
        username);
    if (passwordResetRequired) {
      upsertPasswordCredential(userId, username, passwordHash, now);
      jdbc.update("DELETE FROM sys_auth_session WHERE user_id = ?", userId);
    }
    upsertLoginIdentifiersIfReady(userId, mobile, email, now);
    replaceRoles(userId, roleCodes);
  }

  private void upsertPasswordCredential(
      long userId,
      String username,
      String passwordHash,
      Timestamp now
  ) {
    Integer active = jdbc.queryForObject(
        """
            SELECT COUNT(*) FROM sys_auth_credential
             WHERE user_id = ? AND credential_type = 'PASSWORD' AND status = 'ACTIVE'
            """,
        Integer.class,
        userId);
    if (active != null && active > 0) {
      jdbc.update(
          """
              UPDATE sys_auth_credential
                 SET credential_subject = ?, secret_hash = ?, changed_at = ?,
                     updated_at = ?, version = version + 1
               WHERE user_id = ? AND credential_type = 'PASSWORD' AND status = 'ACTIVE'
              """,
          username, passwordHash, now, now, userId);
      return;
    }
    jdbc.update(
        """
            INSERT INTO sys_auth_credential
              (id, user_id, credential_type, credential_subject, secret_hash, status,
               changed_at, expires_at, created_at, updated_at, version)
            VALUES (?, ?, 'PASSWORD', ?, ?, 'ACTIVE', ?, NULL, ?, ?, 0)
            """,
        nextId(), userId, username, passwordHash, now, now, now);
  }

  private void upsertLoginIdentifiersIfReady(
      long userId,
      String mobile,
      String email,
      Timestamp now
  ) {
    if (!loginIdentifierTableAvailable()) {
      return;
    }
    upsertLoginIdentifier(userId, "MOBILE", mobile, now);
    upsertLoginIdentifier(userId, "EMAIL", email, now);
  }

  private boolean loginIdentifierTableAvailable() {
    try {
      jdbc.queryForObject("SELECT COUNT(*) FROM sys_auth_login_identifier WHERE 1 = 0", Integer.class);
      return true;
    } catch (DataAccessException ex) {
      log.debug("V13 login identifier table is not available for dev seed yet");
      return false;
    }
  }

  private void upsertLoginIdentifier(
      long userId,
      String identifierType,
      String normalizedIdentifier,
      Timestamp now
  ) {
    Integer existing = jdbc.queryForObject(
        """
            SELECT COUNT(*) FROM sys_auth_login_identifier
             WHERE user_id = ? AND identifier_type = ?
            """,
        Integer.class,
        userId,
        identifierType);
    if (existing != null && existing > 0) {
      jdbc.update(
          """
              UPDATE sys_auth_login_identifier
                 SET normalized_identifier = ?, verified = 1, enabled = 1,
                     verified_at = COALESCE(verified_at, ?), disabled_at = NULL,
                     updated_at = ?, version = version + 1
               WHERE user_id = ? AND identifier_type = ?
              """,
          normalizedIdentifier,
          now,
          now,
          userId,
          identifierType);
      return;
    }
    jdbc.update(
        """
            INSERT INTO sys_auth_login_identifier
              (id, user_id, identifier_type, normalized_identifier, verified, enabled,
               verified_at, disabled_at, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, 1, 1, ?, NULL, ?, ?, 0)
            """,
        nextId(),
        userId,
        identifierType,
        normalizedIdentifier,
        now,
        now,
        now);
  }

  private void replaceRoles(long userId, List<String> roleCodes) {
    jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
    for (String roleCode : roleCodes) {
      Long roleId = roleId(roleCode);
      jdbc.update(
          "INSERT INTO sys_user_role (user_id, role_id, created_at) VALUES (?, ?, ?)",
          userId,
          roleId,
          now());
    }
  }

  private void ensureQuestionRolesAndPermissions() {
    long authorRoleId = ensureRole("QUESTION_AUTHOR", "题库作者");
    long reviewerRoleId = ensureRole("QUESTION_REVIEWER", "题库审核人");
    long publisherRoleId = ensureRole("QUESTION_PUBLISHER", "题库发布人");

    long read = ensurePermission("DEV_QUESTION_READ", "题库读取", QUESTION_READ, 10);
    long create = ensurePermission("DEV_QUESTION_CREATE", "题目创建", QUESTION_CREATE, 20);
    long update = ensurePermission("DEV_QUESTION_UPDATE", "题目更新", QUESTION_UPDATE, 30);
    long submit = ensurePermission(
        "DEV_QUESTION_SUBMIT_REVIEW", "题目提交审核", QUESTION_SUBMIT_REVIEW, 40);
    long review = ensurePermission("DEV_QUESTION_REVIEW", "题目审核", QUESTION_REVIEW, 50);
    long publish = ensurePermission("DEV_QUESTION_PUBLISH", "题目发布", QUESTION_PUBLISH, 60);
    long questionImport = ensurePermission("DEV_QUESTION_IMPORT", "题库导入", QUESTION_IMPORT, 70);

    replaceRolePermissions(authorRoleId, List.of(read, create, update, submit, questionImport));
    replaceRolePermissions(reviewerRoleId, List.of(read, review));
    replaceRolePermissions(publisherRoleId, List.of(read, publish));
    removeRolePermissions("ADMIN", List.of(QUESTION_REVIEW, QUESTION_PUBLISH));
  }

  private void ensurePlatformRolesAndMenus() {
    long adminRoleId = ensureRole("ADMIN", "Admin");
    long studentRoleId = ensureRole("STUDENT", "Student");

    long adminHome = ensureMenu(
        "admin.home", "Admin Home", "/admin/home", ADMIN_HOME_VIEW, "foundation", 10);
    long foundationRead = ensurePermission(
        "DEV_FOUNDATION_READ", "Foundation Read", FOUNDATION_READ, 20);
    long foundationWrite = ensurePermission(
        "DEV_FOUNDATION_WRITE", "Foundation Write", FOUNDATION_WRITE, 30);
    long studentHome = ensureMenu(
        "student.home", "Student Home", "/app/home", STUDENT_HOME_VIEW, "foundation", 10);
    long practiceWorkspace = ensureMenu(
        "practice.start", "Practice Workspace", "/app/practice/start",
        PRACTICE_WORKSPACE_VIEW, "question-practice", 20);
    long examSimulator = ensureMenu(
        "exam.simulator", "Exam Simulator", "/exam-simulator",
        EXAM_SIMULATOR_ENTER, "question-practice", 30);

    grantRolePermissions(adminRoleId, List.of(adminHome, foundationRead, foundationWrite));
    grantRolePermissions(studentRoleId, List.of(studentHome, practiceWorkspace, examSimulator));
  }

  private void removeRolePermissions(String roleCode, List<String> permissionCodes) {
    for (String permissionCode : permissionCodes) {
      jdbc.update(
          """
              DELETE FROM sys_role_menu
               WHERE role_id IN (
                 SELECT id FROM sys_role WHERE role_code = ? AND deleted = 0
               )
                 AND menu_id IN (
                   SELECT id FROM sys_menu WHERE permission_code = ? AND deleted = 0
                 )
              """,
          roleCode,
          permissionCode);
    }
  }

  private long ensureRole(String roleCode, String roleName) {
    Optional<Long> existing = jdbc.query(
        "SELECT id FROM sys_role WHERE role_code = ?",
        rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(),
        roleCode);
    Timestamp now = now();
    if (existing.isPresent()) {
      jdbc.update(
          "UPDATE sys_role SET role_name=?, status='ENABLED', deleted=0, updated_at=? WHERE id=?",
          roleName, now, existing.get());
      return existing.get();
    }
    long id = nextId();
    jdbc.update(
        """
            INSERT INTO sys_role
              (id, role_code, role_name, status, created_at, updated_at, created_by, updated_by, deleted)
            VALUES (?, ?, ?, 'ENABLED', ?, ?, NULL, NULL, 0)
            """,
        id, roleCode, roleName, now, now);
    return id;
  }

  private long ensurePermission(
      String menuCode,
      String menuName,
      String permissionCode,
      int sortOrder
  ) {
    Optional<Long> existing = jdbc.query(
        "SELECT id FROM sys_menu WHERE menu_code = ?",
        rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(),
        menuCode);
    Timestamp now = now();
    if (existing.isPresent()) {
      jdbc.update(
          """
              UPDATE sys_menu
                 SET menu_name=?, menu_type='API', route_path=NULL, permission_code=?,
                     sort_order=?, enabled=1, module_code='question-practice',
                     updated_at=?, deleted=0
               WHERE id=?
              """,
          menuName, permissionCode, sortOrder, now, existing.get());
      return existing.get();
    }
    long id = nextId();
    jdbc.update(
        """
            INSERT INTO sys_menu
              (id, parent_id, menu_code, menu_name, menu_type, route_path, permission_code,
               sort_order, enabled, module_code, created_at, updated_at, created_by, updated_by, deleted)
            VALUES (?, NULL, ?, ?, 'API', NULL, ?, ?, 1, 'question-practice', ?, ?, NULL, NULL, 0)
            """,
        id, menuCode, menuName, permissionCode, sortOrder, now, now);
    return id;
  }

  private long ensureMenu(
      String menuCode,
      String menuName,
      String routePath,
      String permissionCode,
      String moduleCode,
      int sortOrder
  ) {
    Optional<Long> existing = jdbc.query(
        "SELECT id FROM sys_menu WHERE menu_code = ?",
        rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(),
        menuCode);
    Timestamp now = now();
    if (existing.isPresent()) {
      jdbc.update(
          """
              UPDATE sys_menu
                 SET menu_name=?, menu_type='MENU', route_path=?, permission_code=?,
                     sort_order=?, enabled=1, module_code=?, updated_at=?, deleted=0
               WHERE id=?
              """,
          menuName, routePath, permissionCode, sortOrder, moduleCode, now, existing.get());
      return existing.get();
    }
    long id = nextId();
    jdbc.update(
        """
            INSERT INTO sys_menu
              (id, parent_id, menu_code, menu_name, menu_type, route_path, permission_code,
               sort_order, enabled, module_code, created_at, updated_at, created_by, updated_by, deleted)
            VALUES (?, NULL, ?, ?, 'MENU', ?, ?, ?, 1, ?, ?, ?, NULL, NULL, 0)
            """,
        id, menuCode, menuName, routePath, permissionCode, sortOrder, moduleCode, now, now);
    return id;
  }

  private void replaceRolePermissions(long roleId, List<Long> menuIds) {
    jdbc.update("DELETE FROM sys_role_menu WHERE role_id = ?", roleId);
    for (Long menuId : menuIds) {
      grantRolePermission(roleId, menuId);
    }
  }

  private void grantRolePermissions(long roleId, List<Long> menuIds) {
    for (Long menuId : menuIds) {
      grantRolePermission(roleId, menuId);
    }
  }

  private void grantRolePermission(long roleId, long menuId) {
    Integer existing = jdbc.queryForObject(
        "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = ? AND menu_id = ?",
        Integer.class,
        roleId,
        menuId);
    if (existing != null && existing > 0) {
      return;
    }
    jdbc.update(
        "INSERT INTO sys_role_menu (role_id, menu_id, created_at) VALUES (?, ?, ?)",
        roleId, menuId, now());
  }

  private Long roleId(String roleCode) {
    Long roleId = jdbc.queryForObject(
        "SELECT id FROM sys_role WHERE role_code = ? AND deleted = 0 AND status = 'ENABLED'",
        Long.class,
        roleCode);
    if (roleId == null) {
      throw new IllegalStateException("DEV_ACCOUNT_ROLE_NOT_FOUND:" + roleCode);
    }
    return roleId;
  }

  private void disableLegacyDuplicate(String legacyUsername) {
    userId(legacyUsername).ifPresent(userId -> {
      Timestamp now = now();
      jdbc.update("DELETE FROM sys_auth_session WHERE user_id = ?", userId);
      jdbc.update(
          """
              UPDATE sys_auth_credential
                 SET status = 'REVOKED', updated_at = ?, version = version + 1
               WHERE user_id = ? AND status = 'ACTIVE'
              """,
          now, userId);
      jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
      if (loginIdentifierTableAvailable()) {
        jdbc.update(
            """
                UPDATE sys_auth_login_identifier
                   SET enabled = 0, disabled_at = COALESCE(disabled_at, ?),
                       updated_at = ?, version = version + 1
                 WHERE user_id = ? AND enabled = 1
                """,
            now, now, userId);
      }
      jdbc.update(
          """
              UPDATE sys_user
                 SET status = 'DISABLED', deleted = 1, auth_version = auth_version + 1,
                     updated_at = ?, version = version + 1
               WHERE id = ?
              """,
          now, userId);
    });
  }

  private Optional<Long> userId(String username) {
    return jdbc.query(
        "SELECT id FROM sys_user WHERE username = ? AND deleted = 0",
        rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(),
        username);
  }

  private Optional<String> activePasswordHash(long userId) {
    return jdbc.query(
        """
            SELECT secret_hash FROM sys_auth_credential
             WHERE user_id = ? AND credential_type = 'PASSWORD' AND status = 'ACTIVE'
             ORDER BY changed_at DESC, id DESC
            """,
        rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(),
        userId);
  }

  private static Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  private static long nextId() {
    return Math.addExact(
        Math.multiplyExact(Instant.now().toEpochMilli(), 100_000L),
        Math.floorMod(SEQUENCE.incrementAndGet(), 100_000));
  }
}
