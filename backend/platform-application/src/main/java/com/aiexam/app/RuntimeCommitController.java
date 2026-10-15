package com.aiexam.app;

import com.aiexam.common.api.ApiResponse;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runtime")
public class RuntimeCommitController {
  private final String commit;

  public RuntimeCommitController(@Value("${aiexam.runtime.commit:UNKNOWN}") String commit) {
    this.commit = commit;
  }

  @GetMapping("/commit")
  public ApiResponse<Map<String, String>> commit() {
    return ApiResponse.ok(Map.of("commit", commit));
  }
}
