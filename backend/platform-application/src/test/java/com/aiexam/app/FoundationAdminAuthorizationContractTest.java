package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexam.foundation.controller.FoundationAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class FoundationAdminAuthorizationContractTest {
  @Test
  void foundationAdminUsesExistingAdminRoleWithoutNewSeedOnlyPermission() {
    PreAuthorize authorization = FoundationAdminController.class.getAnnotation(PreAuthorize.class);

    assertThat(authorization).isNotNull();
    assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(authorization.value()).doesNotContain("foundation:write");
  }
}
