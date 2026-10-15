package com.aiexam.app.learning;

import com.aiexam.common.error.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LearningResourcePdfUploadService {
  private static final Pattern NUMBERED_HEADING = Pattern.compile("^((?:\\d+\\.){1,5}\\d+|第\\s*[一二三四五六七八九十百]+章)\\s*[、.：:]?\\s*(.{2,300})$");
  private static final long MAX_BYTES = 200L * 1024 * 1024;
  private final Path root;

  public LearningResourcePdfUploadService(
      @Value("${aiexam.learning-resource.file-root:./var/learning-resources}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  public UploadedPdf store(MultipartFile file) {
    if (file == null || file.isEmpty() || file.getSize() <= 0) {
      throw invalid("LEARNING_RESOURCE_FILE_EMPTY", "PDF 文件不能为空");
    }
    if (file.getSize() > MAX_BYTES) {
      throw invalid("LEARNING_RESOURCE_FILE_TOO_LARGE", "PDF 文件不能超过 200 MiB");
    }
    String originalName = file.getOriginalFilename();
    if (originalName == null || originalName.contains("/") || originalName.contains("\\")
        || !originalName.toLowerCase().endsWith(".pdf")) {
      throw invalid("LEARNING_RESOURCE_FILE_TYPE_INVALID", "仅支持 PDF 文件，且文件名必须以 .pdf 结尾");
    }
    String token = UUID.randomUUID().toString();
    Path directory = root.resolve("pdf").normalize();
    Path target = directory.resolve(token + ".pdf").normalize();
    if (!target.startsWith(directory)) {
      throw invalid("LEARNING_RESOURCE_FILE_PATH_INVALID", "文件保存路径无效");
    }
    try {
      Files.createDirectories(directory);
      try (InputStream input = file.getInputStream()) {
        byte[] header = input.readNBytes(5);
        if (header.length < 5 || header[0] != '%' || header[1] != 'P' || header[2] != 'D'
            || header[3] != 'F' || header[4] != '-') {
          throw invalid("LEARNING_RESOURCE_FILE_SIGNATURE_INVALID", "文件内容不是有效 PDF");
        }
      }
      try (InputStream input = file.getInputStream()) {
        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
      }
      Integer pageCount = null;
      String coverToken = null;
      try (var document = Loader.loadPDF(target.toFile())) {
        pageCount = document.getNumberOfPages();
        if (pageCount > 0) {
          coverToken = token;
          Files.createDirectories(root.resolve("covers"));
          var image = new PDFRenderer(document).renderImageWithDPI(0, 120);
          javax.imageio.ImageIO.write(image, "png", coverPath(token).toFile());
        }
      } catch (Exception ignored) {
        // A valid uploaded PDF remains usable even if optional preview generation fails.
      }
      return new UploadedPdf(token, originalName, file.getContentType(), file.getSize(), sha256(target), pageCount, coverToken);
    } catch (IOException exception) {
      delete(token);
      throw new BusinessException("LEARNING_RESOURCE_FILE_UPLOAD_FAILED", "PDF 文件保存失败", HttpStatus.BAD_REQUEST);
    }
  }

  public Path locate(String token) {
    if (token == null || !token.matches("[0-9a-f-]{36}")) {
      throw new BusinessException("LEARNING_RESOURCE_FILE_NOT_FOUND", "文件不存在", HttpStatus.NOT_FOUND);
    }
    Path path = root.resolve("pdf").resolve(token + ".pdf").normalize();
    if (!path.startsWith(root) || !Files.isRegularFile(path)) {
      throw new BusinessException("LEARNING_RESOURCE_FILE_NOT_FOUND", "文件不存在", HttpStatus.NOT_FOUND);
    }
    return path;
  }

  public Path locateCover(String token) {
    if (token == null || !token.matches("[0-9a-f-]{36}")) {
      throw new BusinessException("LEARNING_RESOURCE_COVER_NOT_FOUND", "封面不存在", HttpStatus.NOT_FOUND);
    }
    Path path = coverPath(token);
    if (!path.startsWith(root) || !Files.isRegularFile(path)) {
      throw new BusinessException("LEARNING_RESOURCE_COVER_NOT_FOUND", "封面不存在", HttpStatus.NOT_FOUND);
    }
    return path;
  }

  public Preview rebuildPreview(String token) {
    Path pdf = locate(token);
    try (var document = Loader.loadPDF(pdf.toFile())) {
      Integer pageCount = document.getNumberOfPages();
      if (pageCount <= 0) return new Preview(null, null);
      Files.createDirectories(root.resolve("covers"));
      var image = new PDFRenderer(document).renderImageWithDPI(0, 120);
      javax.imageio.ImageIO.write(image, "png", coverPath(token).toFile());
      return new Preview(pageCount, "/api/v1/learning/resource-covers/" + token);
    } catch (Exception exception) {
      throw invalid("LEARNING_RESOURCE_PDF_PARSE_FAILED", "PDF 页数或封面解析失败");
    }
  }

  public byte[] renderPage(String token, int page) {
    if (page < 1) throw invalid("LEARNING_RESOURCE_PAGE_INVALID", "页码必须大于 0");
    Path pdf = locate(token);
    try (var document = Loader.loadPDF(pdf.toFile())) {
      if (page > document.getNumberOfPages()) {
        throw invalid("LEARNING_RESOURCE_PAGE_NOT_FOUND", "页码超出 PDF 范围");
      }
      var image = new PDFRenderer(document).renderImageWithDPI(page - 1, 120);
      try (var output = new java.io.ByteArrayOutputStream()) {
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
      }
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw invalid("LEARNING_RESOURCE_PAGE_RENDER_FAILED", "PDF 页面渲染失败");
    }
  }

  public List<LearningChapterWriteRequest> extractChapters(String token) {
    Path pdf = locate(token);
    List<LearningChapterWriteRequest> result = new ArrayList<>();
    try (var document = Loader.loadPDF(pdf.toFile())) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      int order = 0;
      for (int page = 1; page <= document.getNumberOfPages(); page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        for (String raw : stripper.getText(document).split("\\R")) {
          String line = raw.trim();
          Matcher matcher = NUMBERED_HEADING.matcher(line);
          if (!matcher.matches()) continue;
          String code = matcher.group(1).replaceAll("\\s+", "");
          int level = code.startsWith("第") ? 1 : (int) code.chars().filter(value -> value == '.').count() + 1;
          result.add(new LearningChapterWriteRequest(code, matcher.group(2).trim(), null, level, page, null, order++));
        }
      }
      return result;
    } catch (Exception exception) {
      throw invalid("LEARNING_RESOURCE_PDF_TEXT_PARSE_FAILED", "PDF 目录提取失败");
    }
  }

  public List<LearningResourceSearchResult> search(String token, String keyword) {
    if (keyword == null || keyword.isBlank()) return List.of();
    String query = keyword.trim().toLowerCase();
    List<LearningResourceSearchResult> matches = new ArrayList<>();
    try (var document = Loader.loadPDF(locate(token).toFile())) {
      PDFTextStripper stripper = new PDFTextStripper();
      for (int page = 1; page <= document.getNumberOfPages(); page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String text = stripper.getText(document).replaceAll("\\s+", " ").trim();
        int index = text.toLowerCase().indexOf(query);
        if (index >= 0) {
          int start = Math.max(0, index - 45);
          int end = Math.min(text.length(), index + keyword.length() + 80);
          matches.add(new LearningResourceSearchResult(page, text.substring(start, end)));
        }
        if (matches.size() >= 100) break;
      }
      return matches;
    } catch (Exception exception) {
      throw invalid("LEARNING_RESOURCE_PDF_SEARCH_FAILED", "PDF 全文搜索失败");
    }
  }

  private Path coverPath(String token) {
    return root.resolve("covers").resolve(token + ".png").normalize();
  }

  public void delete(String token) {
    try { Files.deleteIfExists(root.resolve("pdf").resolve(token + ".pdf").normalize()); }
    catch (IOException ignored) { }
  }

  private static String sha256(Path path) throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static BusinessException invalid(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }

  public record UploadedPdf(String token, String fileName, String mimeType, long fileSize, String sha256,
                            Integer pageCount, String coverToken) { }
  public record Preview(Integer pageCount, String coverUrl) { }
}
