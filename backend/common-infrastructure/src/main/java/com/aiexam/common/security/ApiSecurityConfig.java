package com.aiexam.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class ApiSecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectProvider<DevBearerTokenFilter> devBearerTokenFilter,
      PlatformCookieAuthenticationFilter platformCookieAuthenticationFilter,
      AuthCsrfOriginFilter authCsrfOriginFilter,
      JsonAuthenticationEntryPoint authenticationEntryPoint,
      JsonAccessDeniedHandler accessDeniedHandler
  ) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(authenticationEntryPoint)
            .accessDeniedHandler(accessDeniedHandler))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.ico").permitAll()
            .requestMatchers(HttpMethod.GET, "/assets/**", "/static/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/runtime/commit").permitAll()
            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/auth/capabilities").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/foundation/qualifications").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/foundation/qualifications/*/subjects").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/foundation/subjects/*/syllabus-nodes").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/foundation/subjects/*/chapter-nodes").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/foundation/knowledge-tags").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/public/practice/catalog").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/public/papers").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/public/portal/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/public/question-assets/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/public/learning/resources/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/public/exam-schedules").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/learning/resource-files/**", "/api/v1/learning/resource-covers/**", "/api/v1/learning/resource-pages/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/registration-challenges").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/sms-login-challenges").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/sms-login").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/password-reset-challenges").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/password/reset").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/auth/dev/challenges/*/verification-code").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/security/rate-limit-probe").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/security/internal-error-probe").permitAll()
            .requestMatchers("/api/v1/exam-simulator/**").permitAll()
            .requestMatchers("/api/v1/admin/question-imports/**").authenticated()
            .requestMatchers("/api/v1/admin/questions/**").authenticated()
            .requestMatchers("/api/v1/admin/practice-reviews/**").authenticated()
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/v1/security/authenticated-probe").authenticated()
            .requestMatchers("/api/v1/security/admin-probe").hasRole("ADMIN")
            .requestMatchers("/api/v1/**").authenticated()
            .anyRequest().authenticated())
        .addFilterBefore(authCsrfOriginFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(platformCookieAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    devBearerTokenFilter.ifAvailable(filter ->
        http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class));
    return http.build();
  }
}
