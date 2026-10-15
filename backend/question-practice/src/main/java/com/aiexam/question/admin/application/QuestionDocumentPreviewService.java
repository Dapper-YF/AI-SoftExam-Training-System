package com.aiexam.question.admin.application;

import com.aiexam.common.error.BusinessException;
import com.aiexam.question.admin.dto.QuestionDocumentPreviewDto;
import com.aiexam.question.admin.dto.QuestionDocumentPreviewDto.CandidateAsset;
import com.aiexam.question.admin.dto.QuestionDocumentPreviewDto.CandidateQuestion;
import com.aiexam.question.admin.dto.QuestionDocumentPreviewDto.Diagnostics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Imports Word and PDF objective papers. Directly readable text is preferred; embedded Word
 * screenshots and scanned PDF pages are sent to the local OCR gateway and then merged back at
 * their original document position. The OCR result is a review candidate only, never a publish
 * decision.
 */
@Service
public class QuestionDocumentPreviewService {
  private static final long MAX_BYTES = 50L * 1024 * 1024;
  private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
  private static final int MAX_PDF_PAGES = 160;
  private static final int PDF_OCR_DPI = 200;
  private static final int MIN_DIRECT_PDF_TEXT = 80;
  private static final Pattern QUESTION_HEADER = Pattern.compile(
      "(?m)^\\s*(\\d{1,3})(?:\\s*(?:~|～|—|-)\\s*(\\d{1,3}))?[、.．)）]\\s*(?![A-H][、.．)）])");
  private static final Pattern OPTION_HEADER = Pattern.compile(
      "(?<![A-Za-z0-9])([A-H])[、.．)）]\\s*");
  private static final Pattern INLINE_ANSWER = Pattern.compile(
      "(?is)(?:答案|正确答案|参考答案)\\s*[:：]\\s*([A-H]+)");
  private static final Pattern SOLUTION = Pattern.compile(
      "(?is)(?:解析|答案解析)\\s*[:：]\\s*(.+)");
  private static final Pattern ANSWER_SECTION_HEADER = Pattern.compile(
      "(?m)^.*(?:答案及解析|答案与解析)\\s*$");
  private static final Pattern ANSWER_RANGE = Pattern.compile(
      "(?m)(\\d{1,3})\\s*(?:-|—|~|～|至)\\s*(\\d{1,3})\\s*[.、:：]?\\s*([A-H]+)");
  private static final Pattern ANSWER_SINGLE = Pattern.compile(
      "(?m)(?:^|\\s)(\\d{1,3})\\s*[.、:：]\\s*([A-H]+)(?=\\s|$)");
  private static final Pattern IMAGE_MARKER = Pattern.compile(
      "\\[图片:\\s*([0-9a-f]{16}\\.(?:png|jpe?g|gif|webp))]", Pattern.CASE_INSENSITIVE);

  private final String imageRoot;
  private final String ocrEndpoint;
  private final String ocrToken;
  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  /** Kept public for focused parser tests that intentionally run without an OCR service. */
  public QuestionDocumentPreviewService(String imageRoot) {
    this(imageRoot, "", "", new ObjectMapper());
  }

  @Autowired
  public QuestionDocumentPreviewService(
      @Value("${aiexam.question-assets.root:}") String imageRoot,
      @Value("${OCR_ENDPOINT:}") String ocrEndpoint,
      @Value("${OCR_TOKEN:}") String ocrToken,
      ObjectMapper json
  ) {
    this.imageRoot = imageRoot == null ? "" : imageRoot.trim();
    this.ocrEndpoint = ocrEndpoint == null ? "" : ocrEndpoint.trim();
    this.ocrToken = ocrToken == null ? "" : ocrToken.trim();
    this.json = json;
  }

  public QuestionDocumentPreviewDto preview(MultipartFile file) {
    if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
      throw invalid("文件不能为空且不能超过 50 MiB");
    }
    String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
    String lower = fileName.toLowerCase(Locale.ROOT);
    try {
      byte[] bytes = file.getBytes();
      String sha256 = sha256(bytes);
      ExtractedDocument document;
      if (lower.endsWith(".docx")) {
        requireSignature(bytes, true);
        document = extractDocx(bytes);
      } else if (lower.endsWith(".doc")) {
        requireSignature(bytes, false);
        document = extractDoc(bytes);
      } else if (lower.endsWith(".pdf")) {
        requirePdfSignature(bytes);
        document = extractPdf(bytes);
      } else {
        throw invalid("题卷导入仅支持 .doc、.docx 或 .pdf 文件");
      }
      String extension = lower.endsWith(".docx") ? "docx" : lower.endsWith(".pdf") ? "pdf" : "doc";
      persistSourceDocument(bytes, sha256, extension);
      List<CandidateAsset> assets = persistAssets(document.images());
      OcrMerge merge = mergeOcrText(document.blocks(), document.images());
      ParsedQuestions parsed = parseQuestions(normalize(merge.text()));
      List<String> warnings = new ArrayList<>(parsed.warnings());
      warnings.addAll(merge.warnings());
      if (!assets.isEmpty()) {
        warnings.add("已保留 " + assets.size() + " 张内嵌图片（Word 原图）用于后台对照；整页截图不会直接展示给刷题用户。");
      }
      Diagnostics diagnostics = diagnostics(parsed.questions(), parsed.answerCount(),
          parsed.maxAnswerNo(), assets.size(), merge.failedImages());
      return new QuestionDocumentPreviewDto(
          UUID.randomUUID().toString(), fileName, document.format(), 0, sha256,
          normalize(merge.text()), parsed.questions(), assets, assets, diagnostics,
          List.copyOf(warnings));
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException exception) {
      throw new BusinessException("QUESTION_DOCUMENT_PARSE_FAILED",
          "题卷文件解析失败，请确认文件未损坏", HttpStatus.BAD_REQUEST);
    } catch (Exception exception) {
      throw new BusinessException("QUESTION_DOCUMENT_PARSE_FAILED",
          "题卷文件解析失败：" + safeMessage(exception), HttpStatus.BAD_REQUEST);
    }
  }

  private ExtractedDocument extractDocx(byte[] bytes) throws IOException {
    try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
      List<SourceBlock> blocks = new ArrayList<>();
      List<RawImage> images = new ArrayList<>();
      int[] order = {1};
      document.getBodyElements().forEach(element -> {
        if (element instanceof XWPFParagraph paragraph) {
          appendParagraph(paragraph, blocks, images, order);
        } else if (element instanceof XWPFTable table) {
          table.getRows().forEach(row -> row.getTableCells().forEach(cell ->
              cell.getParagraphs().forEach(paragraph -> appendParagraph(paragraph, blocks, images, order))));
        }
      });
      // Some Word producers use VML/legacy drawing markup. POI exposes those pictures through
      // getAllPictures() but not through XWPFRun#getEmbeddedPictures(). Preserve them for OCR
      // rather than silently losing a complete page of a historical paper.
      Set<String> seenParts = new LinkedHashSet<>();
      images.forEach(image -> seenParts.add(image.relationId().replaceFirst("#\\d+$", "")));
      for (XWPFPictureData picture : document.getAllPictures()) {
        String partName = picture.getPackagePart().getPartName().getName();
        if (seenParts.add(partName)) {
          int imageOrder = order[0]++;
          String sourceId = partName + "#" + imageOrder;
          images.add(new RawImage(sourceId, imageOrder, picture.suggestFileExtension(), picture.getData()));
          blocks.add(SourceBlock.image(sourceId));
        }
      }
      appendMissingZipImages(bytes, blocks, images, order);
      return new ExtractedDocument("DOCX", blocks, images);
    }
  }

  private void appendMissingZipImages(byte[] documentBytes, List<SourceBlock> blocks,
      List<RawImage> images, int[] order) throws IOException {
    Set<String> seenParts = new LinkedHashSet<>();
    images.forEach(image -> seenParts.add(image.relationId().replaceFirst("#\\d+$", "")));
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(documentBytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        String normalizedName = name.startsWith("/") ? name : "/" + name;
        if (!entry.isDirectory() && name.startsWith("word/media/")
            && seenParts.add(normalizedName)) {
          byte[] image = zip.readAllBytes();
          String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "png";
          int imageOrder = order[0]++;
          String sourceId = name + "#" + imageOrder;
          images.add(new RawImage(sourceId, imageOrder, extension, image));
          blocks.add(SourceBlock.image(sourceId));
        }
      }
    }
  }

  private void appendParagraph(XWPFParagraph paragraph, List<SourceBlock> blocks,
      List<RawImage> images, int[] order) {
    String text = paragraph.getText();
    if (text != null && !text.isBlank()) {
      blocks.add(SourceBlock.text(text));
    }
    for (XWPFRun run : paragraph.getRuns()) {
      for (XWPFPicture picture : run.getEmbeddedPictures()) {
        XWPFPictureData data = picture.getPictureData();
        if (data == null) {
          continue;
        }
        int imageOrder = order[0]++;
        String sourceId = data.getPackagePart().getPartName().getName() + "#" + imageOrder;
        images.add(new RawImage(sourceId, imageOrder, data.suggestFileExtension(), data.getData()));
        blocks.add(SourceBlock.image(sourceId));
      }
    }
  }

  private ExtractedDocument extractDoc(byte[] bytes) throws IOException {
    try (var document = new HWPFDocument(new ByteArrayInputStream(bytes));
         var extractor = new WordExtractor(document)) {
      List<RawImage> images = new ArrayList<>();
      List<SourceBlock> blocks = new ArrayList<>();
      String text = extractor.getText();
      if (text != null && !text.isBlank()) {
        blocks.add(SourceBlock.text(text));
      }
      int order = 1;
      for (Picture picture : document.getPicturesTable().getAllPictures()) {
        String sourceId = "doc-picture-" + order;
        images.add(new RawImage(sourceId, order++, picture.suggestFileExtension(), picture.getContent()));
        blocks.add(SourceBlock.image(sourceId));
      }
      return new ExtractedDocument("DOC", blocks, images);
    }
  }

  /**
   * Extracts each PDF page in reading order. Pages with a usable text layer stay text-only for
   * speed and accuracy; scanned pages become source images and use the existing local OCR path.
   */
  private ExtractedDocument extractPdf(byte[] bytes) throws IOException {
    try (PDDocument document = Loader.loadPDF(bytes)) {
      if (document.isEncrypted()) {
        throw invalid("加密 PDF 无法导入，请先移除打开密码后重试");
      }
      if (document.getNumberOfPages() > MAX_PDF_PAGES) {
        throw invalid("PDF 页数不能超过 " + MAX_PDF_PAGES + " 页");
      }
      PDFTextStripper stripper = new PDFTextStripper();
      PDFRenderer renderer = new PDFRenderer(document);
      List<SourceBlock> blocks = new ArrayList<>();
      List<RawImage> images = new ArrayList<>();
      for (int page = 0; page < document.getNumberOfPages(); page++) {
        stripper.setStartPage(page + 1);
        stripper.setEndPage(page + 1);
        String pageText = normalize(stripper.getText(document));
        if (usablePdfText(pageText)) {
          blocks.add(SourceBlock.text(pageText));
          continue;
        }
        BufferedImage rendered = renderer.renderImageWithDPI(page, PDF_OCR_DPI, ImageType.RGB);
        byte[] image = png(rendered);
        String sourceId = "pdf-page-" + (page + 1);
        images.add(new RawImage(sourceId, page + 1, "png", image));
        blocks.add(SourceBlock.image(sourceId));
      }
      return new ExtractedDocument("PDF", blocks, images);
    }
  }

  private boolean usablePdfText(String pageText) {
    if (pageText.length() < MIN_DIRECT_PDF_TEXT) return false;
    long visible = pageText.chars().filter(value -> !Character.isWhitespace(value)).count();
    return visible >= MIN_DIRECT_PDF_TEXT;
  }

  private byte[] png(BufferedImage image) throws IOException {
    try (var output = new java.io.ByteArrayOutputStream()) {
      if (!ImageIO.write(image, "png", output)) throw new IOException("PDF 页面无法转换为图片");
      return output.toByteArray();
    }
  }

  private OcrMerge mergeOcrText(List<SourceBlock> blocks, List<RawImage> images) {
    Map<String, RawImage> imageById = new LinkedHashMap<>();
    images.forEach(image -> imageById.put(image.relationId(), image));
    StringBuilder merged = new StringBuilder();
    List<String> warnings = new ArrayList<>();
    int failed = 0;
    for (SourceBlock block : blocks) {
      if (block.text() != null) {
        merged.append(block.text()).append('\n');
        continue;
      }
      RawImage image = imageById.get(block.imageId());
      OcrResult result = image == null ? null : ocr(image);
      if (result == null || result.text().isBlank()) {
        failed++;
        warnings.add("来源图片 " + (image == null ? "?" : image.order())
            + " 未识别出文字，管理员可在审核页保留原图并稍后重试 OCR。");
      } else {
        merged.append(result.text()).append('\n');
        if (result.confidence() != null && result.confidence() < 0.85D) {
          warnings.add("来源图片 " + image.order() + " OCR 置信度较低（"
              + String.format(Locale.ROOT, "%.0f%%", result.confidence() * 100) + "），请重点核对。");
        }
      }
    }
    if (!images.isEmpty() && ocrEndpoint.isBlank()) {
      warnings.add("图片文字尚未识别：本地 PaddleOCR 服务不可用，不能创建完整真题卷。");
    }
    return new OcrMerge(merged.toString(), failed, List.copyOf(warnings));
  }

  private OcrResult ocr(RawImage image) {
    if (ocrEndpoint.isBlank() || image.bytes().length > MAX_IMAGE_BYTES) {
      return null;
    }
    try {
      Map<String, String> payload = Map.of("imageBase64", Base64.getEncoder().encodeToString(image.bytes()),
          "contentType", mediaType(normalizeExtension(image.extension())));
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(ocrEndpoint))
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(45))
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
      if (!ocrToken.isBlank()) {
        request.header("Authorization", "Bearer " + ocrToken);
      }
      for (int attempt = 0; attempt < 2; attempt++) {
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 == 2) {
          JsonNode root = json.readTree(response.body());
          String text = root.path("text").asText("").trim();
          if (!text.isBlank()) {
            return new OcrResult(text, root.path("confidence").isNumber()
                ? root.path("confidence").doubleValue() : null);
          }
        }
      }
    } catch (Exception ignored) {
      // A failed image must not discard the uploaded Word file or its directly readable text.
    }
    return null;
  }

  private List<CandidateAsset> persistAssets(List<RawImage> images) throws Exception {
    if (images.isEmpty()) return List.of();
    if (imageRoot.isBlank()) {
      throw new BusinessException("QUESTION_IMAGE_ROOT_NOT_CONFIGURED",
          "题卷包含图片，但题目图片存储目录尚未配置", HttpStatus.SERVICE_UNAVAILABLE);
    }
    Path root = Path.of(imageRoot).toAbsolutePath().normalize();
    Files.createDirectories(root);
    List<CandidateAsset> result = new ArrayList<>();
    for (RawImage image : images) {
      String extension = normalizeExtension(image.extension());
      String hash = sha256(image.bytes());
      String fileName = hash.substring(0, 16) + "." + extension;
      Path target = root.resolve(fileName).normalize();
      if (!target.startsWith(root)) throw invalid("题目图片路径非法");
      if (!Files.exists(target)) Files.write(target, image.bytes(), StandardOpenOption.CREATE_NEW);
      ImageSize size = imageSize(image.bytes());
      result.add(new CandidateAsset(UUID.randomUUID().toString(), image.relationId(), image.order(),
          fileName, "/api/v1/public/question-assets/" + fileName, mediaType(extension),
          image.bytes().length, size.width(), size.height(), null));
    }
    return List.copyOf(result);
  }

  private void persistSourceDocument(byte[] bytes, String sha256, String extension) throws IOException {
    if (imageRoot.isBlank()) {
      throw new BusinessException("QUESTION_IMAGE_ROOT_NOT_CONFIGURED",
          "题卷原文件存储目录尚未配置", HttpStatus.SERVICE_UNAVAILABLE);
    }
    Path assetRoot = Path.of(imageRoot).toAbsolutePath().normalize();
    Path sourceRoot = assetRoot.resolve(".source-documents").normalize();
    if (!sourceRoot.startsWith(assetRoot)) throw invalid("题卷原文件存储路径非法");
    Files.createDirectories(sourceRoot);
    Path sourceFile = sourceRoot.resolve(sha256 + "." + extension).normalize();
    if (!sourceFile.startsWith(sourceRoot)) throw invalid("题卷原文件名非法");
    if (!Files.exists(sourceFile)) Files.write(sourceFile, bytes, StandardOpenOption.CREATE_NEW);
  }

  private ParsedQuestions parseQuestions(String text) {
    int firstQuestion = firstQuestionOffset(text);
    int answerSection = answerSectionOffset(text, firstQuestion);
    String questionText = answerSection < 0 ? text : text.substring(0, answerSection);
    String answerText = answerSection < 0 ? "" : text.substring(answerSection);
    questionText = repairOcrGroupHeaders(questionText);
    answerText = repairOcrGroupHeaders(answerText).replace("^~", "~");
    Map<Integer, String> answers = parseConcentratedAnswers(answerText);
    List<Header> headers = new ArrayList<>();
    Matcher matcher = QUESTION_HEADER.matcher(questionText);
    while (matcher.find()) headers.add(new Header(Integer.parseInt(matcher.group(1)),
        matcher.group(2) == null ? null : Integer.parseInt(matcher.group(2)), matcher.start(), matcher.end()));
    List<CandidateQuestion> questions = new ArrayList<>();
    for (int index = 0; index < headers.size(); index++) {
      Header header = headers.get(index);
      int end = index + 1 < headers.size() ? headers.get(index + 1).start() : questionText.length();
      String body = questionText.substring(header.end(), end).trim();
      if (body.isBlank()) continue;
      if (header.endNo() != null && header.endNo() >= header.questionNo()) {
        questions.addAll(splitGroup(header, body, answers));
      } else {
        questions.add(candidate(header.questionNo(), parseBody(body), answers.get(header.questionNo()), List.of()));
      }
    }
    List<String> warnings = new ArrayList<>();
    if (questions.isEmpty()) warnings.add("未识别到题目，请检查 Word 是否主要由图片组成或 OCR 服务是否可用。");
    return new ParsedQuestions(List.copyOf(questions), answers.size(),
        answers.keySet().stream().max(Integer::compareTo).orElse(0), List.copyOf(warnings));
  }

  private List<CandidateQuestion> splitGroup(Header header, String body, Map<Integer, String> answers) {
    int declaredCount = header.endNo() - header.questionNo() + 1;
    List<ParsedBody> optionGroups = splitOptionGroups(body);
    int count = Math.max(declaredCount, optionGroups.size());
    List<CandidateQuestion> result = new ArrayList<>();
    for (int offset = 0; offset < count; offset++) {
      List<String> warnings = new ArrayList<>();
      if (optionGroups.size() != declaredCount) warnings.add("连续题组选项分组与题号范围不一致，请核对本题题号和选项。");
      ParsedBody parsed = optionGroups.size() > offset ? optionGroups.get(offset) : parseBody(body);
      String stem = parsed.stem().isBlank() ? clean(body) : parsed.stem();
      ParsedBody withSharedStem = new ParsedBody(stem + "\n（连续题组第 " + (offset + 1) + " 空）",
          parsed.options(), parsed.answer(), parsed.solution());
      result.add(candidate(header.questionNo() + offset, withSharedStem,
          answers.get(header.questionNo() + offset), warnings));
    }
    return result;
  }

  private List<ParsedBody> splitOptionGroups(String body) {
    body = body.replaceAll("(?m)^\\s*\\d{1,3}\\s*[、.．]\\s*([A-H])[、.．]\\s*", "$1. ");
    Matcher matcher = OPTION_HEADER.matcher(body);
    List<OptionMatch> matches = new ArrayList<>();
    while (matcher.find()) matches.add(new OptionMatch(matcher.group(1), matcher.start(), matcher.end()));
    List<Integer> starts = new ArrayList<>();
    for (OptionMatch match : matches) if ("A".equals(match.key())) starts.add(match.start());
    if (starts.size() < 2) return List.of(parseBody(body));
    String commonStem = clean(body.substring(0, starts.get(0)));
    List<ParsedBody> groups = new ArrayList<>();
    for (int index = 0; index < starts.size(); index++) {
      int end = index + 1 < starts.size() ? starts.get(index + 1) : body.length();
      ParsedBody parsed = parseBody(body.substring(starts.get(index), end));
      groups.add(new ParsedBody(commonStem, parsed.options(), parsed.answer(), parsed.solution()));
    }
    return groups;
  }

  /** OCR commonly joins a two-question heading such as "27.28.". */
  private String repairOcrGroupHeaders(String text) {
    Matcher matcher = Pattern.compile("(?m)^(\\s*)(\\d{1,3})[、.．](\\d{1,3})[、.．]").matcher(text);
    StringBuffer repaired = new StringBuffer();
    while (matcher.find()) {
      int start = Integer.parseInt(matcher.group(2));
      int end = Integer.parseInt(matcher.group(3));
      if (end > start && end - start <= 5) {
        matcher.appendReplacement(repaired, Matcher.quoteReplacement(matcher.group(1) + start + "~" + end + "."));
      } else {
        matcher.appendReplacement(repaired, Matcher.quoteReplacement(matcher.group()));
      }
    }
    matcher.appendTail(repaired);
    return repaired.toString();
  }

  private CandidateQuestion candidate(int questionNo, ParsedBody parsed, String fallbackAnswer,
      List<String> initialWarnings) {
    String answer = parsed.answer() == null ? fallbackAnswer : parsed.answer();
    List<String> warnings = new ArrayList<>(initialWarnings);
    if (parsed.options().size() < 2) warnings.add("未识别到完整选项");
    if (answer == null || answer.isBlank()) warnings.add("未识别到答案");
    String type = answer != null && answer.length() > 1 ? "MULTI_CHOICE" : "SINGLE_CHOICE";
    return new CandidateQuestion(questionNo, type, parsed.stem(), parsed.options(), answer,
        parsed.solution(), BigDecimal.ONE, imageMarkers(parsed.stem()), List.copyOf(warnings));
  }

  private ParsedBody parseBody(String body) {
    Matcher answerMatcher = INLINE_ANSWER.matcher(body);
    String answer = answerMatcher.find() ? answerMatcher.group(1).toUpperCase(Locale.ROOT) : null;
    Matcher solutionMatcher = SOLUTION.matcher(body);
    String solution = solutionMatcher.find() ? clean(solutionMatcher.group(1)) : null;
    int bodyEnd = body.length();
    if (answerMatcher.find(0)) bodyEnd = Math.min(bodyEnd, answerMatcher.start());
    if (solutionMatcher.find(0)) bodyEnd = Math.min(bodyEnd, solutionMatcher.start());
    String questionBody = body.substring(0, bodyEnd).trim();
    Matcher optionMatcher = OPTION_HEADER.matcher(questionBody);
    List<OptionMatch> matches = new ArrayList<>();
    while (optionMatcher.find()) matches.add(new OptionMatch(optionMatcher.group(1), optionMatcher.start(), optionMatcher.end()));
    if (matches.isEmpty()) return new ParsedBody(clean(questionBody), List.of(), answer, solution);
    String stem = clean(questionBody.substring(0, matches.get(0).start()));
    List<String> options = new ArrayList<>();
    for (int index = 0; index < matches.size(); index++) {
      OptionMatch option = matches.get(index);
      int end = index + 1 < matches.size() ? matches.get(index + 1).start() : questionBody.length();
      String value = clean(questionBody.substring(option.end(), end));
      if (!value.isBlank()) options.add(option.key() + ". " + value);
    }
    return new ParsedBody(stem, List.copyOf(options), answer, solution);
  }

  private Map<Integer, String> parseConcentratedAnswers(String text) {
    Map<Integer, String> answers = new LinkedHashMap<>();
    Matcher ranges = ANSWER_RANGE.matcher(text);
    while (ranges.find()) {
      int start = Integer.parseInt(ranges.group(1));
      int end = Integer.parseInt(ranges.group(2));
      String value = ranges.group(3).toUpperCase(Locale.ROOT);
      if (end >= start && value.length() == end - start + 1) {
        for (int number = start; number <= end; number++) answers.put(number, String.valueOf(value.charAt(number - start)));
      }
    }
    Matcher singles = ANSWER_SINGLE.matcher(text);
    while (singles.find()) answers.put(Integer.parseInt(singles.group(1)), singles.group(2).toUpperCase(Locale.ROOT));
    return answers;
  }

  private Diagnostics diagnostics(List<CandidateQuestion> questions, int answerCount, int maxAnswerNo,
      int imageCount, int failedImages) {
    List<Integer> numbers = questions.stream().map(CandidateQuestion::questionNo).sorted().toList();
    Set<Integer> unique = new LinkedHashSet<>(numbers);
    int maximum = Math.max(75, Math.max(unique.stream().max(Integer::compareTo).orElse(0), maxAnswerNo));
    List<Integer> missing = new ArrayList<>();
    for (int number = 1; number <= maximum; number++) if (!unique.contains(number)) missing.add(number);
    List<Integer> duplicates = numbers.stream().filter(number -> Collections.frequency(numbers, number) > 1).distinct().toList();
    List<Integer> withoutOptions = questions.stream().filter(question -> question.options().size() < 2).map(CandidateQuestion::questionNo).toList();
    List<Integer> withoutAnswers = questions.stream().filter(question -> question.answer() == null || question.answer().isBlank()).map(CandidateQuestion::questionNo).toList();
    boolean ready = questions.size() == 75 && missing.isEmpty() && duplicates.isEmpty()
        && withoutOptions.isEmpty() && withoutAnswers.isEmpty() && failedImages == 0;
    return new Diagnostics(questions.size(), imageCount, answerCount, List.copyOf(missing), List.copyOf(duplicates),
        withoutOptions, withoutAnswers, failedImages, ready);
  }

  private int firstQuestionOffset(String text) { Matcher m = QUESTION_HEADER.matcher(text); return m.find() ? m.start() : -1; }
  private int answerSectionOffset(String text, int firstQuestion) { Matcher m = ANSWER_SECTION_HEADER.matcher(text); while (m.find()) if (firstQuestion >= 0 && m.start() > firstQuestion) return m.start(); return -1; }
  private void requireSignature(byte[] bytes, boolean docx) { boolean valid = docx ? bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' : bytes.length >= 8 && (bytes[0] & 0xff) == 0xd0 && (bytes[1] & 0xff) == 0xcf && (bytes[2] & 0xff) == 0x11 && (bytes[3] & 0xff) == 0xe0; if (!valid) throw invalid("文件扩展名与真实 Word 格式不一致"); }
  private void requirePdfSignature(byte[] bytes) { if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') throw invalid("文件扩展名与真实 PDF 格式不一致"); }
  private String normalize(String value) { return value == null ? "" : value.replace("\r", "").replace('\u00a0', ' ').replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim(); }
  private String clean(String value) { return value == null ? "" : value.replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n").trim(); }
  private List<String> imageMarkers(String content) { List<String> names = new ArrayList<>(); Matcher matcher = IMAGE_MARKER.matcher(content == null ? "" : content); while (matcher.find()) names.add(matcher.group(1)); return List.copyOf(names); }
  private String normalizeExtension(String extension) { String value = extension == null ? "" : extension.toLowerCase(Locale.ROOT); return switch (value) { case "jpeg", "jpg" -> "jpg"; case "gif" -> "gif"; case "webp" -> "webp"; default -> "png"; }; }
  private String mediaType(String extension) { return switch (extension) { case "jpg" -> "image/jpeg"; case "gif" -> "image/gif"; case "webp" -> "image/webp"; default -> "image/png"; }; }
  private ImageSize imageSize(byte[] bytes) { try { BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes)); return image == null ? new ImageSize(null, null) : new ImageSize(image.getWidth(), image.getHeight()); } catch (IOException exception) { return new ImageSize(null, null); } }
  private String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
  private String safeMessage(Exception exception) { String message = exception.getMessage(); return message == null || message.isBlank() ? "无法读取文件内容" : message; }
  private BusinessException invalid(String message) { return new BusinessException("QUESTION_DOCUMENT_FILE_INVALID", message, HttpStatus.BAD_REQUEST); }

  private record ExtractedDocument(String format, List<SourceBlock> blocks, List<RawImage> images) {}
  private record SourceBlock(String text, String imageId) { static SourceBlock text(String value) { return new SourceBlock(value, null); } static SourceBlock image(String value) { return new SourceBlock(null, value); } }
  private record RawImage(String relationId, int order, String extension, byte[] bytes) {}
  private record OcrResult(String text, Double confidence) {}
  private record OcrMerge(String text, int failedImages, List<String> warnings) {}
  private record Header(int questionNo, Integer endNo, int start, int end) {}
  private record OptionMatch(String key, int start, int end) {}
  private record ParsedBody(String stem, List<String> options, String answer, String solution) {}
  private record ParsedQuestions(List<CandidateQuestion> questions, int answerCount, int maxAnswerNo, List<String> warnings) {}
  private record ImageSize(Integer width, Integer height) {}
}
