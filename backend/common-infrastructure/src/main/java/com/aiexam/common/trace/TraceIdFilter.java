package com.aiexam.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceIdFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceId = request.getHeader("X-Trace-Id");
    if (traceId == null || traceId.isBlank()) {
      traceId = TraceContext.newTraceId();
    }
    MDC.put(TraceContext.TRACE_ID, traceId);
    response.setHeader("X-Trace-Id", traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(TraceContext.TRACE_ID);
    }
  }
}
