package com.aiexam.app.controller;

import com.aiexam.app.learning.LearningResourcePdfUploadService;
import java.io.IOException;
import java.nio.file.Files;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningResourceFileController {
  private final LearningResourcePdfUploadService uploadService;

  public LearningResourceFileController(LearningResourcePdfUploadService uploadService) {
    this.uploadService = uploadService;
  }

  @GetMapping("/resource-files/{token}")
  public ResponseEntity<InputStreamResource> file(@PathVariable(name = "token") String token) throws IOException {
    var path = uploadService.locate(token);
    return ResponseEntity.ok()
        .contentLength(Files.size(path))
        .contentType(MediaType.APPLICATION_PDF)
        .header("X-Frame-Options", "SAMEORIGIN")
        .body(new InputStreamResource(Files.newInputStream(path)));
  }

  @GetMapping("/resource-covers/{token}")
  public ResponseEntity<InputStreamResource> cover(@PathVariable(name = "token") String token) throws IOException {
    var path = uploadService.locateCover(token);
    return ResponseEntity.ok().contentLength(Files.size(path)).contentType(MediaType.IMAGE_PNG)
        .body(new InputStreamResource(Files.newInputStream(path)));
  }

  @GetMapping("/resource-pages/{token}/{page}")
  public ResponseEntity<byte[]> page(@PathVariable(name = "token") String token, @PathVariable(name = "page") int page) {
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(uploadService.renderPage(token, page));
  }
}
