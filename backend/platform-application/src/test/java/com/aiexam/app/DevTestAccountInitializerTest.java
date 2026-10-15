package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.foundation.auth.infra.Argon2idPasswordHashingPort;
import com.aiexam.foundation.auth.infra.DevAccountInitializationProductionGuard;
import com.aiexam.foundation.auth.infra.DevTestAccountInitializer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class DevTestAccountInitializerTest {
  private static final String DEVELOPMENT_PASSWORD = "123456";

  private JdbcTemplate jdbc;
  private Argon2idPasswordHashingPort hashing;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder()
        .setType(EmbeddedDatabaseType.H2)
        .generateUniqueName(true)
        .build());
    hashing = new Argon2idPasswordHashingPort();
    createTables();
    seedRole(1L, "STUDENT");
    seedRole(2L, "ADMIN");
    seedLegacyAdminDecisionPermission(11L, "LEGACY_QUESTION_REVIEW", "question:review");
    seedLegacyAdminDecisionPermission(12L, "LEGACY_QUESTION_PUBLISH", "question:publish");
    seedLegacyUser(101L, "test_student", "+8613900001001", 1L);
    seedLegacyUser(102L, "test_admin", "+8613900001002", 2L);
  }

  @Test
  void seedsIndependentQuestionSubmitterAndReviewerPublisherWithMinimalPermissions() throws Exception {
    DevTestAccountInitializer initializer =
        new DevTestAccountInitializer(jdbc, hashing);

    initializer.run(new DefaultApplicationArguments(new String[0]));
    String studentHash = credentialHash("student");
    String adminHash = credentialHash("admin");
    String reviewerHash = credentialHash("question_reviewer");
    initializer.run(new DefaultApplicationArguments(new String[0]));

    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM sys_user WHERE deleted=0",
        Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForList(
        "SELECT username FROM sys_user WHERE deleted=0 ORDER BY username",
        String.class)).containsExactly("admin", "question_reviewer", "student");
    assertThat(jdbc.queryForObject(
        """
            SELECT r.role_code FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role r ON r.id=ur.role_id
            WHERE u.username='student'
            """,
        String.class)).isEqualTo("STUDENT");
    assertThat(roles("admin")).containsExactly("ADMIN", "QUESTION_AUTHOR", "STUDENT");
    assertThat(roles("question_reviewer"))
        .containsExactly("QUESTION_PUBLISHER", "QUESTION_REVIEWER");
    assertThat(permissions("admin")).containsExactly(
        "admin:home:view", "exam-simulator:enter", "foundation:read", "foundation:write",
        "practice:workspace:view",
        "question:create", "question:import", "question:read", "question:submit-review",
        "question:update", "student:home:view");
    assertThat(permissions("question_reviewer")).containsExactly(
        "question:publish", "question:read", "question:review");
    assertThat(permissions("student")).containsExactly(
        "exam-simulator:enter", "practice:workspace:view", "student:home:view");
    assertThat(permissions("admin")).doesNotContain("question:review", "question:publish");
    assertThat(permissions("question_reviewer"))
        .doesNotContain("question:create", "question:update", "question:submit-review");
    assertThat(hashing.verify(DEVELOPMENT_PASSWORD, credentialHash("student"))).isTrue();
    assertThat(hashing.verify(DEVELOPMENT_PASSWORD, credentialHash("admin"))).isTrue();
    assertThat(hashing.verify(DEVELOPMENT_PASSWORD, credentialHash("question_reviewer"))).isTrue();
    assertThatThrownBy(() -> com.aiexam.foundation.auth.domain.PasswordPolicy.validateAndNormalize(
        DEVELOPMENT_PASSWORD, "student", "+8613900002001", "student.dev@aiexam.local"))
        .isInstanceOf(com.aiexam.common.error.BusinessException.class);
    assertThat(credentialHash("student")).isEqualTo(studentHash);
    assertThat(credentialHash("admin")).isEqualTo(adminHash);
    assertThat(credentialHash("question_reviewer")).isEqualTo(reviewerHash);
    assertThat(List.of(studentHash, adminHash, reviewerHash))
        .allSatisfy(hash -> assertThat(hash).startsWith("$argon2id$"))
        .doesNotContain(DEVELOPMENT_PASSWORD);
    assertThat(credentialHash("question_reviewer")).startsWith("$argon2id$");
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM sys_auth_credential WHERE status='ACTIVE'",
        Integer.class)).isEqualTo(3);
    assertThat(loginIdentifiers("student"))
        .containsExactly("EMAIL:student.dev@aiexam.local", "MOBILE:+8613900002001");
    assertThat(loginIdentifiers("admin"))
        .containsExactly("EMAIL:admin.dev@aiexam.local", "MOBILE:+8613900002002");
    assertThat(loginIdentifiers("question_reviewer"))
        .containsExactly("EMAIL:question-reviewer.dev@aiexam.local", "MOBILE:+8613900002003");
  }

  @Test
  void accountGuardAllowsOnlyDevOrTestAndAlwaysRejectsProduction() {
    MockEnvironment dev = new MockEnvironment();
    dev.setActiveProfiles("dev");
    new DevAccountInitializationProductionGuard(dev).rejectAccountsOutsideDevOrTest();

    MockEnvironment test = new MockEnvironment();
    test.setActiveProfiles("test");
    new DevAccountInitializationProductionGuard(test).rejectAccountsOutsideDevOrTest();

    MockEnvironment localDev = new MockEnvironment();
    localDev.setActiveProfiles(" local-dev ");
    new DevAccountInitializationProductionGuard(localDev).rejectAccountsOutsideDevOrTest();

    MockEnvironment upperCaseDev = new MockEnvironment();
    upperCaseDev.setActiveProfiles(" DEV ");
    new DevAccountInitializationProductionGuard(upperCaseDev).rejectAccountsOutsideDevOrTest();

    MockEnvironment production = new MockEnvironment();
    production.setActiveProfiles("dev", "production");
    assertThatThrownBy(() -> new DevAccountInitializationProductionGuard(production)
        .rejectAccountsOutsideDevOrTest())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DEV_ACCOUNT_INITIALIZATION_FORBIDDEN_OUTSIDE_DEV_OR_TEST");

    MockEnvironment prodWithWhitespace = new MockEnvironment();
    prodWithWhitespace.setActiveProfiles("dev", " Production ");
    assertThatThrownBy(() -> new DevAccountInitializationProductionGuard(prodWithWhitespace)
        .rejectAccountsOutsideDevOrTest())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DEV_ACCOUNT_INITIALIZATION_FORBIDDEN_OUTSIDE_DEV_OR_TEST");

    MockEnvironment staging = new MockEnvironment();
    staging.setActiveProfiles("dev", " STAGING ");
    assertThatThrownBy(() -> new DevAccountInitializationProductionGuard(staging)
        .rejectAccountsOutsideDevOrTest())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DEV_ACCOUNT_INITIALIZATION_FORBIDDEN_OUTSIDE_DEV_OR_TEST");

    assertThatThrownBy(() -> new DevAccountInitializationProductionGuard(new MockEnvironment())
        .rejectAccountsOutsideDevOrTest())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DEV_ACCOUNT_INITIALIZATION_FORBIDDEN_OUTSIDE_DEV_OR_TEST");

    MockEnvironment mysqlOnly = new MockEnvironment();
    mysqlOnly.setActiveProfiles("mysql");
    assertThatThrownBy(() -> new DevAccountInitializationProductionGuard(mysqlOnly)
        .rejectAccountsOutsideDevOrTest())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DEV_ACCOUNT_INITIALIZATION_FORBIDDEN_OUTSIDE_DEV_OR_TEST");
  }

  @Test
  void repairsExistingFixedAccountPasswordWithoutCreatingAnotherUser() throws Exception {
    DevTestAccountInitializer initializer =
        new DevTestAccountInitializer(jdbc, hashing);
    initializer.run(new DefaultApplicationArguments(new String[0]));
    String wrongHash = hashing.hash(
        UUID.randomUUID().toString(),
        com.aiexam.foundation.auth.domain.Argon2idPasswordPolicy.frozenP0());
    jdbc.update(
        """
            UPDATE sys_auth_credential
               SET secret_hash = ?
             WHERE user_id = (SELECT id FROM sys_user WHERE username = 'student')
               AND credential_type = 'PASSWORD' AND status = 'ACTIVE'
            """,
        wrongHash);

    initializer.run(new DefaultApplicationArguments(new String[0]));

    assertThat(hashing.verify(DEVELOPMENT_PASSWORD, credentialHash("student"))).isTrue();
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM sys_user WHERE username='student' AND deleted=0",
        Integer.class)).isEqualTo(1);
  }

  private String credentialHash(String username) {
    return jdbc.queryForObject(
        """
            SELECT c.secret_hash FROM sys_auth_credential c
            JOIN sys_user u ON u.id=c.user_id
            WHERE u.username=? AND c.status='ACTIVE'
            """,
        String.class,
        username);
  }

  private List<String> roles(String username) {
    return jdbc.queryForList(
        """
            SELECT r.role_code FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role r ON r.id=ur.role_id
            WHERE u.username=? AND r.deleted=0
            ORDER BY r.role_code
            """,
        String.class,
        username);
  }

  private List<String> permissions(String username) {
    return jdbc.queryForList(
        """
            SELECT DISTINCT m.permission_code FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role_menu rm ON rm.role_id=ur.role_id
            JOIN sys_menu m ON m.id=rm.menu_id AND m.enabled=1 AND m.deleted=0
            WHERE u.username=? AND m.permission_code IS NOT NULL
            ORDER BY m.permission_code
            """,
        String.class,
        username);
  }

  private List<String> loginIdentifiers(String username) {
    return jdbc.queryForList(
        """
            SELECT i.identifier_type || ':' || i.normalized_identifier
              FROM sys_user u
              JOIN sys_auth_login_identifier i ON i.user_id = u.id
             WHERE u.username = ? AND i.enabled = 1
             ORDER BY i.identifier_type
            """,
        String.class,
        username);
  }

  private void seedRole(long id, String roleCode) {
    Timestamp now = Timestamp.from(Instant.parse("2026-07-16T00:00:00Z"));
    jdbc.update(
        """
            INSERT INTO sys_role
              (id,role_code,role_name,status,created_at,updated_at,created_by,updated_by,deleted)
            VALUES (?,?,?,'ENABLED',?,?,NULL,NULL,0)
            """,
        id,
        roleCode,
        roleCode,
        now,
        now);
  }

  private void seedLegacyUser(long id, String username, String mobile, long roleId) {
    Timestamp now = Timestamp.from(Instant.parse("2026-07-16T00:00:00Z"));
    String hash = hashing.hash(
        UUID.randomUUID().toString(),
        com.aiexam.foundation.auth.domain.Argon2idPasswordPolicy.frozenP0());
    jdbc.update(
        """
            INSERT INTO sys_user
              (id,username,password_hash,password_changed_at,display_name,mobile,mobile_verified_at,
               email,status,last_login_at,failed_login_count,last_failed_login_at,locked_until,
               auth_version,version,created_at,updated_at,created_by,updated_by,deleted)
            VALUES (?,?,?,?,?,?,?,NULL,'ENABLED',NULL,0,NULL,NULL,0,0,?,?,NULL,NULL,0)
            """,
        id, username, hash, now, username, mobile, now, now, now);
    jdbc.update(
        """
            INSERT INTO sys_auth_credential
              (id,user_id,credential_type,credential_subject,secret_hash,status,changed_at,
               expires_at,created_at,updated_at,version)
            VALUES (?,?,'PASSWORD',?,?,'ACTIVE',?,NULL,?,?,0)
            """,
        id + 1_000, id, username, hash, now, now, now);
    jdbc.update(
        "INSERT INTO sys_user_role(user_id,role_id,created_at) VALUES (?,?,?)",
        id,
        roleId,
        now);
  }

  private void seedLegacyAdminDecisionPermission(long id, String menuCode, String permissionCode) {
    Timestamp now = Timestamp.from(Instant.parse("2026-07-16T00:00:00Z"));
    jdbc.update(
        """
            INSERT INTO sys_menu
              (id,parent_id,menu_code,menu_name,menu_type,route_path,permission_code,sort_order,
               enabled,module_code,created_at,updated_at,created_by,updated_by,deleted)
            VALUES (?,NULL,?,?,'API',NULL,?,1,1,'question-practice',?,?,NULL,NULL,0)
            """,
        id, menuCode, menuCode, permissionCode, now, now);
    jdbc.update(
        "INSERT INTO sys_role_menu(role_id,menu_id,created_at) VALUES (2,?,?)",
        id, now);
  }

  private void createTables() {
    jdbc.execute("""
        CREATE TABLE sys_user (
          id BIGINT PRIMARY KEY,
          username VARCHAR(64) NOT NULL UNIQUE,
          password_hash VARCHAR(255) NOT NULL,
          password_changed_at TIMESTAMP,
          display_name VARCHAR(64) NOT NULL,
          mobile VARCHAR(32) UNIQUE,
          mobile_verified_at TIMESTAMP,
          email VARCHAR(128),
          status VARCHAR(24) NOT NULL,
          last_login_at TIMESTAMP,
          failed_login_count INT NOT NULL,
          last_failed_login_at TIMESTAMP,
          locked_until TIMESTAMP,
          auth_version BIGINT NOT NULL,
          version BIGINT NOT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT,
          updated_by BIGINT,
          deleted TINYINT NOT NULL
        )
        """);
    jdbc.execute("""
        CREATE TABLE sys_auth_credential (
          id BIGINT PRIMARY KEY,
          user_id BIGINT NOT NULL,
          credential_type VARCHAR(24) NOT NULL,
          credential_subject VARCHAR(128) NOT NULL,
          secret_hash VARCHAR(255) NOT NULL,
          status VARCHAR(16) NOT NULL,
          changed_at TIMESTAMP NOT NULL,
          expires_at TIMESTAMP,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          version BIGINT NOT NULL
        )
        """);
    jdbc.execute("""
        CREATE TABLE sys_role (
          id BIGINT PRIMARY KEY,
          role_code VARCHAR(64) NOT NULL UNIQUE,
          role_name VARCHAR(64) NOT NULL,
          status VARCHAR(24) NOT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT,
          updated_by BIGINT,
          deleted TINYINT NOT NULL
        )
        """);
    jdbc.execute("""
        CREATE TABLE sys_user_role (
          user_id BIGINT,
          role_id BIGINT,
          created_at TIMESTAMP,
          PRIMARY KEY (user_id,role_id)
        )
        """);
    jdbc.execute("""
        CREATE TABLE sys_menu (
          id BIGINT PRIMARY KEY,
          parent_id BIGINT,
          menu_code VARCHAR(96) NOT NULL UNIQUE,
          menu_name VARCHAR(96) NOT NULL,
          menu_type VARCHAR(24) NOT NULL,
          route_path VARCHAR(255),
          permission_code VARCHAR(128),
          sort_order INT NOT NULL,
          enabled TINYINT NOT NULL,
          module_code VARCHAR(64) NOT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT,
          updated_by BIGINT,
          deleted TINYINT NOT NULL
        )
        """);
    jdbc.execute("""
        CREATE TABLE sys_role_menu (
          role_id BIGINT,
          menu_id BIGINT,
          created_at TIMESTAMP,
          PRIMARY KEY (role_id,menu_id)
        )
        """);
    jdbc.execute("CREATE TABLE sys_auth_session (user_id BIGINT)");
    jdbc.execute("""
        CREATE TABLE sys_auth_login_identifier (
          id BIGINT PRIMARY KEY,
          user_id BIGINT NOT NULL,
          identifier_type VARCHAR(16) NOT NULL,
          normalized_identifier VARCHAR(320) NOT NULL,
          verified TINYINT NOT NULL,
          enabled TINYINT NOT NULL,
          verified_at TIMESTAMP,
          disabled_at TIMESTAMP,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          version BIGINT NOT NULL,
          UNIQUE(identifier_type, normalized_identifier),
          UNIQUE(user_id, identifier_type)
        )
        """);
  }
}
