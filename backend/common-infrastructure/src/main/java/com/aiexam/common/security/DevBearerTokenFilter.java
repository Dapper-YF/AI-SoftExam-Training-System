package com.aiexam.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Conditional(DevBearerTokenProfileCondition.class)
@ConditionalOnProperty(prefix = "aiexam.security.dev-bearer", name = "enabled", havingValue = "true")
public class DevBearerTokenFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication existing = SecurityContextHolder.getContext().getAuthentication();
    if (existing != null && existing.getPrincipal() instanceof PlatformPrincipal) {
      filterChain.doFilter(request, response);
      return;
    }
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      String token = authorization.substring("Bearer ".length());
      if ("dev-admin-token".equals(token)) {
        authenticate(new PlatformPrincipal(
                1L,
                "dev-admin",
                "dev-bearer-admin",
                List.of("ADMIN", "STUDENT", "QUESTION_AUTHOR"),
                List.of(
                    "admin:home:view",
                    "foundation:read",
                    "foundation:write",
                    "student:home:view",
                    "practice:workspace:view",
                    "exam-simulator:enter",
                    "question:read",
                    "question:create",
                    "question:update",
                    "question:submit-review",
                    "question:import"),
                0),
            "ROLE_ADMIN", "ROLE_STUDENT", "ROLE_QUESTION_AUTHOR",
            "admin:home:view", "foundation:read", "foundation:write",
            "student:home:view", "practice:workspace:view", "exam-simulator:enter",
            "question:read", "question:create", "question:update",
            "question:submit-review", "question:import");
      } else if ("dev-student-token".equals(token)) {
        authenticate(new PlatformPrincipal(
                2L,
                "dev-student",
                "dev-bearer-student",
                List.of("STUDENT"),
                List.of("student:home:view", "practice:workspace:view", "exam-simulator:enter"),
                0),
            "ROLE_STUDENT", "student:home:view", "practice:workspace:view", "exam-simulator:enter");
      }
    }
    filterChain.doFilter(request, response);
  }

  private void authenticate(PlatformPrincipal principal, String... authorities) {
    List<SimpleGrantedAuthority> grantedAuthorities = List.of(authorities).stream()
        .map(SimpleGrantedAuthority::new)
        .toList();
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, null, grantedAuthorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
