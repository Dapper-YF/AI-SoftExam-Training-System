package com.aiexam.question.application;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ResourceNotFoundException;
import com.aiexam.foundation.application.FoundationApplicationService;
import com.aiexam.question.dto.PaperDetailDto;
import com.aiexam.question.dto.PaperQuestionSummaryDto;
import com.aiexam.question.dto.PaperSummaryDto;
import com.aiexam.question.dto.QuestionSummaryDto;
import com.aiexam.question.dto.request.PaperCreateRequest;
import com.aiexam.question.dto.request.PaperQuestionAddRequest;
import com.aiexam.question.dto.request.PaperReviewDraftRequest;
import com.aiexam.question.dto.request.PaperUpdateRequest;
import com.aiexam.question.repository.PaperRepository;
import com.aiexam.question.repository.QuestionDetailRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperApplicationService {
  private static final AtomicLong ID_SEQUENCE = new AtomicLong();
  private static final Set<String> PAPER_TYPES = Set.of("REAL", "MOCK", "SMART", "DAILY");
  private static final BigDecimal SCORE_TOLERANCE = new BigDecimal("0.01");
  private static final Pattern IMAGE_MARKER = Pattern.compile(
      "\\[图片:\\s*([0-9a-f]{16}\\.(?:png|jpe?g|gif|webp))]", Pattern.CASE_INSENSITIVE);

  private final PaperRepository paperRepository;
  private final QuestionDetailRepository questionDetailRepository;
  private final FoundationApplicationService foundationApplicationService;
  @Value("${aiexam.question-assets.root:}")
  private String imageRoot = "";

  public PaperApplicationService(
      PaperRepository paperRepository,
      QuestionDetailRepository questionDetailRepository,
      FoundationApplicationService foundationApplicationService
  ) {
    this.paperRepository = paperRepository;
    this.questionDetailRepository = questionDetailRepository;
    this.foundationApplicationService = foundationApplicationService;
  }

  @Transactional
  public PaperDetailDto createPaper(PaperCreateRequest request) {
    requirePaperType(request.paperType());
    foundationApplicationService.validatePaperScope(request.qualificationId(), request.subjectId());
    Long paperId = nextId();
    paperRepository.insertPaper(paperId, request, "DRAFT");
    return adminDetail(paperId);
  }

  @Transactional
  public PaperDetailDto updatePaper(Long paperId, PaperUpdateRequest request) {
    PaperSummaryDto paper = requirePaper(paperId);
    if (!"DRAFT".equals(normalize(paper.status()))) {
      throw conflict("已发布试卷不允许修改核心字段");
    }
    String paperType = request.paperType() == null ? paper.paperType() : request.paperType();
    requirePaperType(paperType);
    Long qualificationId = request.qualificationId() == null ? paper.qualificationId() : request.qualificationId();
    Long subjectId = request.subjectId() == null ? paper.subjectId() : request.subjectId();
    foundationApplicationService.validatePaperScope(qualificationId, subjectId);
    paperRepository.updatePaper(paperId, request);
    return adminDetail(paperId);
  }

  @Transactional
  public PaperDetailDto saveReviewDraft(Long paperId, PaperReviewDraftRequest request) {
    PaperSummaryDto paper = requirePaper(paperId);
    if (!"DRAFT".equals(normalize(paper.status()))) {
      throw conflict("只有草稿试卷可以保存审核修改");
    }
    if (request.paper() != null) {
      updatePaper(paperId, request.paper());
    }
    paperRepository.clearPaperQuestions(paperId);
    for (PaperReviewDraftRequest.QuestionBinding binding : request.questions()) {
      addQuestion(paperId, new PaperQuestionAddRequest(
          binding.questionId(), binding.sectionCode(), binding.questionNo(), binding.score()));
    }
    return adminDetail(paperId);
  }

  @Transactional
  public PaperDetailDto addQuestion(Long paperId, PaperQuestionAddRequest request) {
    PaperSummaryDto paper = requirePaper(paperId);
    if (!"DRAFT".equals(normalize(paper.status()))) {
      throw conflict("只有草稿试卷允许添加题目");
    }
    QuestionSummaryDto question = questionDetailRepository.findQuestionSummary(request.questionId())
        .orElseThrow(() -> new ResourceNotFoundException("题目不存在"));
    if (!Set.of("PUBLISHED", "DRAFT").contains(normalize(question.status()))) {
      throw validation("只能添加已发布题目");
    }
    if (!paper.qualificationId().equals(question.qualificationId())) {
      throw validation("题目资格与试卷不一致");
    }
    if (paper.subjectId() != null && !paper.subjectId().equals(question.subjectId())) {
      throw validation("题目科目与试卷不一致");
    }
    if (paperRepository.existsPaperQuestionNo(paperId, request.questionNo())) {
      throw conflict("同一试卷内题号已存在");
    }
    if (paperRepository.existsPaperQuestionId(paperId, request.questionId())) {
      throw conflict("同一试卷内题目已存在");
    }
    paperRepository.insertPaperQuestion(nextId(), paperId, request, questionSnapshot(question, request.score()));
    return adminDetail(paperId);
  }

  @Transactional
  public PaperDetailDto publishPaper(Long paperId) {
    return publishPaper(paperId, false);
  }

  @Transactional
  public PaperDetailDto publishImportedPaper(Long paperId) {
    return publishPaper(paperId, true);
  }

  private PaperDetailDto publishPaper(Long paperId, boolean validateImportedContent) {
    PaperSummaryDto paper = requirePaper(paperId);
    if (!"DRAFT".equals(normalize(paper.status()))) {
      throw conflict("试卷已发布");
    }
    if (paperRepository.countPaperQuestions(paperId) < 1) {
      throw validation("发布前试卷至少包含一道题");
    }
    if ("REAL".equals(normalize(paper.paperType()))) {
      validateCompleteRealPaper(paperId);
    }
    if (paperRepository.countUnpublishedPaperQuestions(paperId) > 0) {
      throw validation("试卷包含未发布题目");
    }
    if (validateImportedContent) {
      refreshPaperQuestionSnapshots(paperId);
    }
    if (validateImportedContent
        && paperRepository.countPaperQuestionsWithoutKnowledgeTag(paperId) > 0) {
      throw validation("试卷中仍有题目没有知识点标签，请补充后再发布");
    }
    if (validateImportedContent) {
      validatePaperImages(paperId);
    }
    BigDecimal scoreSum = paperRepository.sumPaperQuestionScores(paperId);
    if (scoreSum.subtract(paper.totalScore()).abs().compareTo(SCORE_TOLERANCE) > 0) {
      throw validation("试卷总分与题目分值合计不一致");
    }
    paperRepository.publishPaper(paperId, ruleSnapshot(paper));
    return adminDetail(paperId);
  }

  private void refreshPaperQuestionSnapshots(Long paperId) {
    for (PaperQuestionSummaryDto question : paperRepository.findPaperQuestions(paperId)) {
      paperRepository.updatePaperQuestionSnapshot(paperId, question.questionId(),
          questionSnapshot(new QuestionSummaryDto(
              question.questionId(), null, question.subjectId(), question.questionType(),
              question.title(), null, null, "PUBLISHED", question.contentVersion()),
              question.score()));
    }
  }

  @Transactional
  public void discardDraftPaper(Long paperId) {
    PaperSummaryDto paper = requirePaper(paperId);
    if (!"DRAFT".equals(normalize(paper.status()))) {
      throw conflict("只有草稿试卷可以驳回");
    }
    paperRepository.discardDraftPaper(paperId);
  }

  private void validateCompleteRealPaper(Long paperId) {
    List<PaperQuestionSummaryDto> questions = paperRepository.findPaperQuestions(paperId);
    int expected = 1;
    for (PaperQuestionSummaryDto question : questions) {
      if (question.questionNo() == null || question.questionNo() != expected) {
        throw validation("REAL 真题试卷的题号必须从 1 开始且连续，不能包含跳号或散题");
      }
      expected++;
    }
  }

  private void validatePaperImages(Long paperId) {
    Path root = imageRoot == null || imageRoot.isBlank()
        ? null : Path.of(imageRoot).toAbsolutePath().normalize();
    for (String content : paperRepository.findPaperStemContents(paperId)) {
      if (content == null || !content.contains("[图片:")) {
        continue;
      }
      Matcher matcher = IMAGE_MARKER.matcher(content);
      boolean matched = false;
      while (matcher.find()) {
        matched = true;
        if (root == null) {
          throw validation("题目包含图片，但题目图片存储目录未配置");
        }
        Path image = root.resolve(matcher.group(1)).normalize();
        if (!image.startsWith(root) || !Files.isRegularFile(image)) {
          throw validation("题目图片不存在：" + matcher.group(1));
        }
      }
      if (!matched) {
        throw validation("题干中存在无法识别的图片标记");
      }
    }
  }

  public PageResponse<PaperSummaryDto> listPublishedPapers(
      Long qualificationId,
      Long subjectId,
      String paperType,
      Integer yearNo,
      long pageNo,
      long pageSize
  ) {
    if (paperType != null && !paperType.isBlank()) {
      requirePaperType(paperType);
    }
    return paperRepository.findPublishedPapers(
        qualificationId,
        subjectId,
        normalize(paperType),
        yearNo,
        pageNo(pageNo),
        pageSize(pageSize));
  }

  public PaperDetailDto getPublishedPaper(Long paperId) {
    return paperRepository.findPublishedPaperDetail(paperId)
        .orElseThrow(() -> new ResourceNotFoundException("试卷不存在"));
  }

  public PaperDetailDto getAdminPaper(Long paperId) {
    return adminDetail(paperId);
  }

  @Transactional
  public PaperDetailDto copyPaper(Long paperId, String paperName) {
    PaperDetailDto source = adminDetail(paperId);
    String copiedName = paperName == null || paperName.isBlank()
        ? source.paperName() + " - 副本"
        : paperName.trim();
    PaperDetailDto copy = createPaper(new PaperCreateRequest(
        source.qualificationId(), source.subjectId(), copiedName, source.paperType(),
        source.yearNo(), source.totalScore(), source.durationMinutes()));
    List<PaperReviewDraftRequest.QuestionBinding> bindings = source.questions().stream()
        .map(question -> new PaperReviewDraftRequest.QuestionBinding(
            question.questionId(), question.questionNo(), question.questionType(), question.score()))
        .toList();
    return saveReviewDraft(copy.paperId(), new PaperReviewDraftRequest(null, bindings));
  }

  public PageResponse<PaperSummaryDto> listAdminPapers(String status, String paperType,
      Long qualificationId, Long subjectId, String keyword, Integer yearNo, long pageNo, long pageSize) {
    if (paperType != null && !paperType.isBlank()) requirePaperType(paperType);
    return paperRepository.findAdminPapers(status, normalize(paperType), qualificationId, subjectId, keyword, yearNo, pageNo(pageNo), pageSize(pageSize));
  }

  private PaperDetailDto adminDetail(Long paperId) {
    return paperRepository.findAdminPaperDetail(paperId)
        .orElseThrow(() -> new ResourceNotFoundException("试卷不存在"));
  }

  private PaperSummaryDto requirePaper(Long paperId) {
    return paperRepository.findPaperSummary(paperId)
        .orElseThrow(() -> new ResourceNotFoundException("试卷不存在"));
  }

  private void requirePaperType(String paperType) {
    if (!PAPER_TYPES.contains(normalize(paperType))) {
      throw validation("不支持的试卷类型");
    }
  }

  private String questionSnapshot(QuestionSummaryDto question, BigDecimal score) {
    return "{"
        + "\"questionId\":" + question.questionId()
        + ",\"questionType\":\"" + json(question.questionType()) + "\""
        + ",\"title\":\"" + json(question.title()) + "\""
        + ",\"contentVersion\":" + question.contentVersion()
        + ",\"score\":" + score
        + "}";
  }

  private String ruleSnapshot(PaperSummaryDto paper) {
    return "{"
        + "\"qualificationId\":" + paper.qualificationId()
        + ",\"subjectId\":" + (paper.subjectId() == null ? "null" : paper.subjectId())
        + ",\"paperType\":\"" + json(paper.paperType()) + "\""
        + ",\"totalScore\":" + paper.totalScore()
        + ",\"durationMinutes\":" + paper.durationMinutes()
        + ",\"publishedAt\":\"" + Instant.now() + "\""
        + "}";
  }

  private String json(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private BusinessException validation(String message) {
    return new BusinessException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
  }

  private BusinessException conflict(String message) {
    return new BusinessException("CONFLICT", message, HttpStatus.CONFLICT);
  }

  private String normalize(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private long pageNo(long pageNo) {
    return pageNo < 1 ? 1 : pageNo;
  }

  private long pageSize(long pageSize) {
    if (pageSize < 1) {
      return 20;
    }
    return Math.min(pageSize, 100);
  }

  private Long nextId() {
    return System.currentTimeMillis() * 100_000 + Math.floorMod(ID_SEQUENCE.getAndIncrement(), 100_000);
  }
}
