package com.aiexam.app.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/v1/public/question-assets")
public class QuestionImageController {
  private static final Logger log = LoggerFactory.getLogger(QuestionImageController.class);
  private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");
  private final String imageRoot;

  public QuestionImageController(@Value("${aiexam.question-assets.root:}") String imageRoot) {
    this.imageRoot = imageRoot == null ? "" : imageRoot.trim();
  }

  @GetMapping("/{fileName:.+}")
  public ResponseEntity<Resource> image(@PathVariable("fileName") String fileName) throws IOException {
    if (!fileName.matches("[0-9A-Fa-f]{16}\\.(?i:png|jpe?g|gif|webp)") || imageRoot.isBlank()) {
      return ResponseEntity.notFound().build();
    }
    try {
      Path root = Path.of(imageRoot).toRealPath();
      Path image = root.resolve(fileName).normalize();
      if (!image.startsWith(root) || !Files.isRegularFile(image)) {
        return ResponseEntity.notFound().build();
      }
      String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
      if (!EXTENSIONS.contains(extension)) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }
      MediaType mediaType = switch (extension) {
        case "png" -> MediaType.IMAGE_PNG;
        case "gif" -> MediaType.IMAGE_GIF;
        case "webp" -> MediaType.parseMediaType("image/webp");
        default -> MediaType.IMAGE_JPEG;
      };
      return ResponseEntity.ok()
          .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic())
          .header("X-Content-Type-Options", "nosniff")
          .contentType(mediaType)
          .body(new FileSystemResource(image));
    } catch (IOException | IllegalArgumentException exception) {
      log.warn("Question image cannot be served: fileName={}, root={}", fileName, imageRoot, exception);
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }
}
