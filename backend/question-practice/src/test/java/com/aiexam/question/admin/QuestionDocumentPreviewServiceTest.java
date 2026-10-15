package com.aiexam.question.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.question.admin.application.QuestionDocumentPreviewService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MultipartFile;

class QuestionDocumentPreviewServiceTest {

  @TempDir
  Path tempDirectory;

  @Test
  void parsesInlineOptionsConcentratedAnswersAndEmbeddedImages() throws Exception {
    byte[] document = objectiveDocument();
    var service = new QuestionDocumentPreviewService(tempDirectory.toString());

    var preview = service.preview(new BytesMultipartFile("2021上午真题.docx", document));

    assertThat(preview.format()).isEqualTo("DOCX");
    assertThat(preview.fileSha256()).hasSize(64);
    assertThat(tempDirectory.resolve(".source-documents")
        .resolve(preview.fileSha256() + ".docx")).hasBinaryContent(document);
    assertThat(preview.questions()).hasSize(2);
    assertThat(preview.questions().get(0).content()).isEqualTo("第一题题干");
    assertThat(preview.questions().get(0).options()).containsExactly(
        "A. 甲", "B. 乙", "C. 丙", "D. 丁");
    assertThat(preview.questions().get(0).answer()).isEqualTo("A");
    assertThat(preview.questions().get(1).answer()).isEqualTo("B");
    assertThat(preview.assets()).singleElement().satisfies(asset -> {
      assertThat(asset.fileName()).matches("[0-9a-f]{16}\\.png");
      assertThat(asset.previewUrl()).endsWith(asset.fileName());
      assertThat(tempDirectory.resolve(asset.fileName())).isRegularFile();
    });
    assertThat(preview.diagnostics().unassignedImageCount()).isEqualTo(1);
    assertThat(preview.diagnostics().readyForReview()).isFalse();
  }

  @Test
  void rejectsAFileWhoseExtensionDoesNotMatchItsSignature() {
    var service = new QuestionDocumentPreviewService(tempDirectory.toString());

    assertThatThrownBy(() -> service.preview(
        new BytesMultipartFile("伪造题卷.docx", "not-a-word-file".getBytes())))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("扩展名");
  }

  @Test
  void extractsAllImagesFromTheRepositoryMorningPaperSample() throws Exception {
    Path workspace = Path.of("").toAbsolutePath().normalize();
    while (workspace != null && !Files.isDirectory(workspace.resolve("docs"))) {
      workspace = workspace.getParent();
    }
    assertThat(workspace).isNotNull();
    Path sample = workspace.resolve(Path.of("docs", "原始资料", "题库",
        "2004-2021年数据库真题及答案解析",
        "2021年上半年数据库系统工程师上午真题及答案解析.docx")).normalize();
    assertThat(sample).isRegularFile();
    var service = new QuestionDocumentPreviewService(tempDirectory.toString());

    var preview = service.preview(
        new BytesMultipartFile(sample.getFileName().toString(), Files.readAllBytes(sample)));

    assertThat(preview.assets()).hasSize(39);
    assertThat(preview.unassignedAssets()).hasSize(39);
    assertThat(preview.diagnostics().detectedQuestionCount()).isGreaterThan(0);
    assertThat(preview.diagnostics().readyForReview()).isFalse();
    assertThat(preview.warnings()).anyMatch(value -> value.contains("39 张内嵌图片"));
  }

  private byte[] objectiveDocument() throws Exception {
    try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
      document.createParagraph().createRun().setText("2021年上午真题");
      document.createParagraph().createRun()
          .setText("1. 第一题题干 A.甲   B.乙   C.丙   D.丁");
      document.createParagraph().createRun().setText("2、第二题题干");
      document.createParagraph().createRun().setText("A、选项一");
      document.createParagraph().createRun().setText("B、选项二");
      XWPFRun imageRun = document.createParagraph().createRun();
      imageRun.addPicture(new java.io.ByteArrayInputStream(png()), XWPFDocument.PICTURE_TYPE_PNG,
          "diagram.png", Units.toEMU(20), Units.toEMU(20));
      document.createParagraph().createRun().setText("2021年上午答案及解析");
      document.createParagraph().createRun().setText("1-2 AB");
      document.write(output);
      return output.toByteArray();
    }
  }

  private byte[] png() throws IOException {
    BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
    try (var output = new ByteArrayOutputStream()) {
      ImageIO.write(image, "png", output);
      return output.toByteArray();
    }
  }

  private record BytesMultipartFile(String originalFilename, byte[] content)
      implements MultipartFile {
    @Override public String getName() { return "file"; }
    @Override public String getOriginalFilename() { return originalFilename; }
    @Override public String getContentType() { return null; }
    @Override public boolean isEmpty() { return content.length == 0; }
    @Override public long getSize() { return content.length; }
    @Override public byte[] getBytes() { return content; }
    @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(content); }
    @Override public void transferTo(java.io.File dest) throws IOException {
      Files.write(dest.toPath(), content);
    }
  }
}
