package com.aiexam.foundation.application;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ResourceNotFoundException;
import com.aiexam.foundation.dto.ChapterNodeDto;
import com.aiexam.foundation.dto.ExamRuleDto;
import com.aiexam.foundation.dto.FoundationPageQuery;
import com.aiexam.foundation.dto.KnowledgeRelationDto;
import com.aiexam.foundation.dto.KnowledgeTagDto;
import com.aiexam.foundation.dto.QualificationDto;
import com.aiexam.foundation.dto.SubjectDto;
import com.aiexam.foundation.dto.SyllabusNodeDto;
import com.aiexam.foundation.dto.request.ChapterNodeCreateRequest;
import com.aiexam.foundation.dto.request.ExamRuleCreateRequest;
import com.aiexam.foundation.dto.request.KnowledgeRelationCreateRequest;
import com.aiexam.foundation.dto.request.KnowledgeTagCreateRequest;
import com.aiexam.foundation.dto.request.QualificationCreateRequest;
import com.aiexam.foundation.dto.request.QualificationUpdateRequest;
import com.aiexam.foundation.dto.request.SubjectCreateRequest;
import com.aiexam.foundation.dto.request.SyllabusNodeCreateRequest;
import com.aiexam.foundation.repository.FoundationRepository;
import com.aiexam.foundation.profile.repository.UserProfileRepository;
import com.aiexam.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FoundationApplicationService {
  private static final AtomicLong ID_SEQUENCE = new AtomicLong();

  private final FoundationRepository foundationRepository;
  private final UserProfileRepository userProfileRepository;

  public FoundationApplicationService(FoundationRepository foundationRepository) {
    this(foundationRepository, null);
  }

  @Autowired
  public FoundationApplicationService(FoundationRepository foundationRepository, UserProfileRepository userProfileRepository) {
    this.foundationRepository = foundationRepository;
    this.userProfileRepository = userProfileRepository;
  }

  public void validateStudentQualification(Long userId, Long qualificationId, java.util.Set<String> permissions) {
    if (permissions != null && (permissions.contains("admin:home:view") || permissions.contains("question:admin"))) return;
    if (userProfileRepository == null) return;
    Long bound = userProfileRepository.findByUserId(userId).map(profile -> profile.preparationQualificationId()).orElse(null);
    if (bound == null) throw new BusinessException("PREPARATION_PROFILE_REQUIRED", "请先完成备考资格和考区选择", HttpStatus.FORBIDDEN);
    if (!bound.equals(qualificationId)) throw new BusinessException("QUALIFICATION_NOT_BOUND", "当前账号未选择该考试资格", HttpStatus.FORBIDDEN);
  }

  public PageResponse<QualificationDto> listQualifications(long pageNo, long pageSize) {
    return foundationRepository.findEnabledQualifications(new FoundationPageQuery(pageNo, pageSize));
  }

  public PageResponse<SubjectDto> listSubjects(Long qualificationId, long pageNo, long pageSize) {
    requireEnabledQualification(qualificationId);
    return foundationRepository.findEnabledSubjects(qualificationId, new FoundationPageQuery(pageNo, pageSize));
  }

  public PageResponse<SyllabusNodeDto> listSyllabusNodes(Long subjectId, long pageNo, long pageSize) {
    requireEnabledSubject(subjectId);
    FoundationPageQuery pageQuery = new FoundationPageQuery(pageNo, pageSize);
    PageResponse<SyllabusNodeDto> rootPage = foundationRepository.findEnabledRootSyllabusNodes(subjectId, pageQuery);
    List<SyllabusNodeDto> descendants = findSyllabusDescendants(subjectId, rootPage.items());
    List<SyllabusNodeDto> roots = syllabusTree(concat(rootPage.items(), descendants));
    return new PageResponse<>(roots, rootPage.pageNo(), rootPage.pageSize(), rootPage.total(), rootPage.hasNext());
  }

  public PageResponse<ChapterNodeDto> listChapterNodes(Long subjectId, long pageNo, long pageSize) {
    requireEnabledSubject(subjectId);
    FoundationPageQuery pageQuery = new FoundationPageQuery(pageNo, pageSize);
    PageResponse<ChapterNodeDto> rootPage = foundationRepository.findEnabledRootChapterNodes(subjectId, pageQuery);
    List<ChapterNodeDto> descendants = findChapterDescendants(subjectId, rootPage.items());
    List<ChapterNodeDto> roots = chapterTree(concat(rootPage.items(), descendants));
    return new PageResponse<>(roots, rootPage.pageNo(), rootPage.pageSize(), rootPage.total(), rootPage.hasNext());
  }

  public PageResponse<KnowledgeTagDto> listKnowledgeTags(Long qualificationId, Long subjectId, long pageNo, long pageSize) {
    if (qualificationId != null) {
      requireEnabledQualification(qualificationId);
    }
    if (subjectId != null) {
      SubjectDto subject = requireEnabledSubject(subjectId);
      if (qualificationId != null && !qualificationId.equals(subject.qualificationId())) {
        throw validation("Subject does not belong to qualification");
      }
    }
    return foundationRepository.findEnabledTags(qualificationId, subjectId, new FoundationPageQuery(pageNo, pageSize));
  }

  public PageResponse<ExamRuleDto> listExamRules(Long qualificationId, Long subjectId, long pageNo, long pageSize) {
    requireEnabledQualification(qualificationId);
    if (subjectId != null) {
      SubjectDto subject = requireEnabledSubject(subjectId);
      if (!qualificationId.equals(subject.qualificationId())) {
        throw validation("Subject does not belong to qualification");
      }
    }
    return foundationRepository.findPublishedExamRules(qualificationId, subjectId, new FoundationPageQuery(pageNo, pageSize));
  }

  public void validateQuestionScope(Long qualificationId, Long subjectId) {
    if (foundationRepository.findEnabledQualificationById(qualificationId).isEmpty()) {
      throw validation("资格不存在或未启用");
    }
    SubjectDto subject = foundationRepository.findEnabledSubjectById(subjectId)
        .orElseThrow(() -> validation("科目不存在或未启用"));
    if (!qualificationId.equals(subject.qualificationId())) {
      throw validation("科目不属于该资格");
    }
  }

  public void validatePaperScope(Long qualificationId, Long subjectId) {
    if (foundationRepository.findEnabledQualificationById(qualificationId).isEmpty()) {
      throw validation("资格不存在或未启用");
    }
    if (subjectId == null) {
      return;
    }
    SubjectDto subject = foundationRepository.findEnabledSubjectById(subjectId)
        .orElseThrow(() -> validation("科目不存在或未启用"));
    if (!qualificationId.equals(subject.qualificationId())) {
      throw validation("科目不属于该资格");
    }
  }

  public void validateQuestionKnowledge(Long qualificationId, Long subjectId, String knowledgeType, Long knowledgeId) {
    validateQuestionScope(qualificationId, subjectId);
    String normalized = normalize(knowledgeType);
    if ("SYLLABUS".equals(normalized) || "SYLLABUS_NODE".equals(normalized)) {
      SyllabusNodeDto node = foundationRepository.findSyllabusNodeById(knowledgeId)
          .orElseThrow(() -> validation("考纲知识点不存在或未启用"));
      if (!isEnabled(node.status()) || !qualificationId.equals(node.qualificationId()) || !subjectId.equals(node.subjectId())) {
        throw validation("考纲知识点不属于当前资格和科目");
      }
      return;
    }
    if ("CHAPTER".equals(normalized) || "CHAPTER_NODE".equals(normalized)) {
      ChapterNodeDto node = foundationRepository.findChapterNodeById(knowledgeId)
          .orElseThrow(() -> validation("章节知识点不存在或未启用"));
      if (!isEnabled(node.status()) || !qualificationId.equals(node.qualificationId()) || !subjectId.equals(node.subjectId())) {
        throw validation("章节知识点不属于当前资格和科目");
      }
      return;
    }
    if ("TAG".equals(normalized)) {
      KnowledgeTagDto tag = foundationRepository.findTagById(knowledgeId)
          .orElseThrow(() -> validation("知识标签不存在或未启用"));
      if (!isEnabled(tag.status())) {
        throw validation("知识标签不存在或未启用");
      }
      if (tag.qualificationId() != null && !qualificationId.equals(tag.qualificationId())) {
        throw validation("知识标签不属于当前资格");
      }
      if (tag.subjectId() != null && !subjectId.equals(tag.subjectId())) {
        throw validation("知识标签不属于当前科目");
      }
      return;
    }
    throw validation("不支持的知识点类型");
  }

  @Transactional
  public QualificationDto createQualification(QualificationCreateRequest request) {
    String status = statusOrEnabled(request.status());
    validateQualificationMetadata(request, status);
    try {
      return foundationRepository.createQualification(nextId(), request, status, sortOrder(request.sortOrder()));
    } catch (DuplicateKeyException ex) {
      throw conflict("资格编码已存在");
    }
  }

  @Transactional
  public QualificationDto updateQualification(Long id, QualificationUpdateRequest request) {
    requireQualification(id);
    try {
      return foundationRepository.updateQualification(id, request, sortOrder(request.sortOrder()));
    } catch (DuplicateKeyException ex) {
      throw conflict("资格配置冲突");
    }
  }

  @Transactional
  public SubjectDto createSubject(SubjectCreateRequest request) {
    requireQualification(request.qualificationId());
    try {
      return foundationRepository.createSubject(nextId(), request, statusOrEnabled(request.status()), sortOrder(request.sortOrder()));
    } catch (DuplicateKeyException ex) {
      throw conflict("科目编码已存在");
    }
  }

  @Transactional
  public SyllabusNodeDto createSyllabusNode(SyllabusNodeCreateRequest request) {
    requireSubjectBelongsToQualification(request.qualificationId(), request.subjectId());
    if (request.parentId() != null) {
      SyllabusNodeDto parent = requireSyllabusNode(request.parentId());
      requireSyllabusParentBelongsToRequest(parent, request.qualificationId(), request.subjectId());
    }
    try {
      return foundationRepository.createSyllabusNode(
          nextId(),
          request,
          statusOrEnabled(request.status()),
          sortOrder(request.sortOrder()));
    } catch (DuplicateKeyException ex) {
      throw conflict("考纲知识点编码已存在");
    }
  }

  @Transactional
  public SyllabusNodeDto updateSyllabusNode(Long id, SyllabusNodeCreateRequest request) {
    requireSyllabusNode(id);
    requireSubjectBelongsToQualification(request.qualificationId(), request.subjectId());
    if (request.parentId() != null) {
      if (id.equals(request.parentId())) throw validation("知识点不能设置为自己的父节点");
      SyllabusNodeDto parent = requireSyllabusNode(request.parentId());
      requireSyllabusParentBelongsToRequest(parent, request.qualificationId(), request.subjectId());
    }
    try {
      return foundationRepository.updateSyllabusNode(
          id, request, statusOrEnabled(request.status()), sortOrder(request.sortOrder()));
    } catch (DuplicateKeyException ex) {
      throw conflict("考纲知识点编码已存在");
    }
  }

  @Transactional
  public ChapterNodeDto createChapterNode(ChapterNodeCreateRequest request) {
    requireSubjectBelongsToQualification(request.qualificationId(), request.subjectId());
    if (request.parentId() != null) {
      ChapterNodeDto parent = requireChapterNode(request.parentId());
      requireChapterParentBelongsToRequest(parent, request.qualificationId(), request.subjectId());
    }
    try {
      return foundationRepository.createChapterNode(
          nextId(),
          request,
          statusOrEnabled(request.status()),
          sortOrder(request.sortOrder()));
    } catch (DuplicateKeyException ex) {
      throw conflict("章节知识点编码已存在");
    }
  }

  @Transactional
  public ChapterNodeDto updateChapterNode(Long id, ChapterNodeCreateRequest request) {
    requireChapterNode(id);
    requireSubjectBelongsToQualification(request.qualificationId(), request.subjectId());
    if (request.parentId() != null) {
      if (id.equals(request.parentId())) {
        throw validation("章节不能设置为自己的父节点");
      }
      ChapterNodeDto parent = requireChapterNode(request.parentId());
      requireChapterParentBelongsToRequest(parent, request.qualificationId(), request.subjectId());
    }
    try {
      return foundationRepository.updateChapterNode(id, request, statusOrEnabled(request.status()),
          sortOrder(request.sortOrder()));
    } catch (DuplicateKeyException ex) {
      throw conflict("章节编码已存在");
    }
  }

  @Transactional
  public KnowledgeTagDto createKnowledgeTag(KnowledgeTagCreateRequest request) {
    if (request.qualificationId() != null) {
      requireQualification(request.qualificationId());
    }
    if (request.subjectId() != null) {
      SubjectDto subject = requireSubject(request.subjectId());
      if (request.qualificationId() == null || !request.qualificationId().equals(subject.qualificationId())) {
        throw validation("Knowledge tag subject must belong to qualification");
      }
    }
    try {
      return foundationRepository.createTag(nextId(), request, statusOrEnabled(request.status()));
    } catch (DuplicateKeyException ex) {
      throw conflict("知识标签编码已存在");
    }
  }

  @Transactional
  public KnowledgeTagDto updateKnowledgeTag(Long id, KnowledgeTagCreateRequest request) {
    requireTag(id);
    validateKnowledgeTagScope(request);
    try {
      return foundationRepository.updateTag(id, request, statusOrEnabled(request.status()));
    } catch (DuplicateKeyException ex) {
      throw conflict("知识点标签编码已存在");
    }
  }

  private void validateKnowledgeTagScope(KnowledgeTagCreateRequest request) {
    if (request.qualificationId() != null) {
      requireQualification(request.qualificationId());
    }
    if (request.subjectId() != null) {
      SubjectDto subject = requireSubject(request.subjectId());
      if (request.qualificationId() == null
          || !request.qualificationId().equals(subject.qualificationId())) {
        throw validation("Knowledge tag subject must belong to qualification");
      }
    }
  }

  @Transactional
  public KnowledgeRelationDto createKnowledgeRelation(KnowledgeRelationCreateRequest request) {
    KnowledgeScope source = resolveKnowledgeNodeScope(request.sourceType(), request.sourceId());
    KnowledgeScope target = resolveKnowledgeNodeScope(request.targetType(), request.targetId());
    requireCompatibleKnowledgeScope(source, target);
    try {
      return foundationRepository.createRelation(nextId(), request);
    } catch (DuplicateKeyException ex) {
      throw conflict("知识点映射关系已存在");
    }
  }

  @Transactional
  public ExamRuleDto createExamRule(ExamRuleCreateRequest request) {
    requireQualification(request.qualificationId());
    if (request.subjectId() != null) {
      throw validation("V9考试规则必须为资格级，subjectId必须为空");
    }
    if (!request.effectiveFrom().isBefore(request.effectiveTo())) {
      throw validation("考试规则生效开始时间必须早于结束时间");
    }
    try {
      return foundationRepository.createExamRule(nextId(), request, statusOrPublished(request.status()));
    } catch (DuplicateKeyException ex) {
      throw conflict("考试规则版本已存在");
    }
  }

  private QualificationDto requireQualification(Long qualificationId) {
    return foundationRepository.findQualificationById(qualificationId)
        .orElseThrow(() -> new ResourceNotFoundException("资格不存在"));
  }

  private QualificationDto requireEnabledQualification(Long qualificationId) {
    return foundationRepository.findEnabledQualificationById(qualificationId)
        .orElseThrow(() -> new ResourceNotFoundException("资格不存在或未启用"));
  }

  private SubjectDto requireSubject(Long subjectId) {
    return foundationRepository.findSubjectById(subjectId)
        .orElseThrow(() -> new ResourceNotFoundException("科目不存在"));
  }

  private SubjectDto requireEnabledSubject(Long subjectId) {
    return foundationRepository.findEnabledSubjectById(subjectId)
        .orElseThrow(() -> new ResourceNotFoundException("科目不存在或未启用"));
  }

  private void requireSubjectBelongsToQualification(Long qualificationId, Long subjectId) {
    SubjectDto subject = requireSubject(subjectId);
    if (!qualificationId.equals(subject.qualificationId())) {
      throw new BusinessException("VALIDATION_ERROR", "科目不属于该资格", HttpStatus.BAD_REQUEST);
    }
  }

  private SyllabusNodeDto requireSyllabusNode(Long nodeId) {
    return foundationRepository.findSyllabusNodeById(nodeId)
        .orElseThrow(() -> new ResourceNotFoundException("考纲知识点不存在"));
  }

  private ChapterNodeDto requireChapterNode(Long nodeId) {
    return foundationRepository.findChapterNodeById(nodeId)
        .orElseThrow(() -> new ResourceNotFoundException("章节知识点不存在"));
  }

  private KnowledgeTagDto requireTag(Long tagId) {
    return foundationRepository.findTagById(tagId)
        .orElseThrow(() -> new ResourceNotFoundException("Knowledge tag not found"));
  }

  private void requireSyllabusParentBelongsToRequest(SyllabusNodeDto parent, Long qualificationId, Long subjectId) {
    if (!qualificationId.equals(parent.qualificationId()) || !subjectId.equals(parent.subjectId())) {
      throw new BusinessException("VALIDATION_ERROR", "父级考纲知识点不属于当前资格和科目", HttpStatus.BAD_REQUEST);
    }
  }

  private void requireChapterParentBelongsToRequest(ChapterNodeDto parent, Long qualificationId, Long subjectId) {
    if (!qualificationId.equals(parent.qualificationId()) || !subjectId.equals(parent.subjectId())) {
      throw new BusinessException("VALIDATION_ERROR", "父级章节知识点不属于当前资格和科目", HttpStatus.BAD_REQUEST);
    }
  }

  private void requireKnowledgeNode(String type, Long id) {
    String normalized = normalize(type);
    if ("SYLLABUS".equals(normalized)) {
      requireSyllabusNode(id);
    } else if ("CHAPTER".equals(normalized)) {
      requireChapterNode(id);
    } else if ("TAG".equals(normalized)) {
      foundationRepository.findTagById(id).orElseThrow(() -> new ResourceNotFoundException("知识标签不存在"));
    } else {
      throw new BusinessException("VALIDATION_ERROR", "不支持的知识点类型", HttpStatus.BAD_REQUEST);
    }
  }

  private KnowledgeScope resolveKnowledgeNodeScope(String type, Long id) {
    String normalized = normalize(type);
    if ("SYLLABUS".equals(normalized)) {
      SyllabusNodeDto node = requireSyllabusNode(id);
      return requireActiveKnowledgeScope(normalized, id, node.qualificationId(), node.subjectId(), node.status());
    }
    if ("CHAPTER".equals(normalized)) {
      ChapterNodeDto node = requireChapterNode(id);
      return requireActiveKnowledgeScope(normalized, id, node.qualificationId(), node.subjectId(), node.status());
    }
    if ("TAG".equals(normalized)) {
      KnowledgeTagDto tag = foundationRepository.findTagById(id)
          .orElseThrow(() -> new ResourceNotFoundException("Knowledge tag not found"));
      return requireActiveKnowledgeScope(normalized, id, tag.qualificationId(), tag.subjectId(), tag.status());
    }
    throw validation("Unsupported knowledge node type");
  }

  private KnowledgeScope requireActiveKnowledgeScope(
      String type,
      Long id,
      Long qualificationId,
      Long subjectId,
      String status
  ) {
    if (!"ENABLED".equals(status)) {
      throw new ResourceNotFoundException("Knowledge node not found or disabled");
    }
    if (qualificationId == null || subjectId == null) {
      throw validation("Knowledge relation endpoint must have qualification and subject scope");
    }
    requireEnabledQualification(qualificationId);
    SubjectDto subject = requireEnabledSubject(subjectId);
    if (!qualificationId.equals(subject.qualificationId())) {
      throw validation("Knowledge node subject does not belong to qualification");
    }
    return new KnowledgeScope(type, id, qualificationId, subjectId);
  }

  private void requireCompatibleKnowledgeScope(KnowledgeScope source, KnowledgeScope target) {
    if (!source.qualificationId().equals(target.qualificationId())
        || !source.subjectId().equals(target.subjectId())) {
      throw validation("Knowledge relation endpoints must share qualification and subject scope");
    }
  }

  private record KnowledgeScope(String type, Long id, Long qualificationId, Long subjectId) {
  }

  private List<SyllabusNodeDto> syllabusTree(List<SyllabusNodeDto> nodes) {
    Map<Long, List<SyllabusNodeDto>> children = new LinkedHashMap<>();
    for (SyllabusNodeDto node : nodes) {
      children.computeIfAbsent(node.parentId(), ignored -> new ArrayList<>()).add(node);
    }
    return children.getOrDefault(null, List.of()).stream()
        .map(node -> withSyllabusChildren(node, children))
        .toList();
  }

  private SyllabusNodeDto withSyllabusChildren(SyllabusNodeDto node, Map<Long, List<SyllabusNodeDto>> children) {
    return new SyllabusNodeDto(
        node.id(),
        node.qualificationId(),
        node.subjectId(),
        node.parentId(),
        node.syllabusVersion(),
        node.nodeCode(),
        node.nodeName(),
        node.levelNo(),
        node.sortOrder(),
        node.status(),
        children.getOrDefault(node.id(), List.of()).stream()
            .map(child -> withSyllabusChildren(child, children))
            .toList());
  }

  private List<SyllabusNodeDto> findSyllabusDescendants(Long subjectId, List<SyllabusNodeDto> roots) {
    List<SyllabusNodeDto> descendants = new ArrayList<>();
    List<Long> parentIds = roots.stream().map(SyllabusNodeDto::id).toList();
    while (!parentIds.isEmpty()) {
      List<SyllabusNodeDto> children = foundationRepository.findEnabledSyllabusChildren(subjectId, parentIds);
      descendants.addAll(children);
      parentIds = children.stream().map(SyllabusNodeDto::id).toList();
    }
    return descendants;
  }

  private List<ChapterNodeDto> chapterTree(List<ChapterNodeDto> nodes) {
    Map<Long, List<ChapterNodeDto>> children = new LinkedHashMap<>();
    for (ChapterNodeDto node : nodes) {
      children.computeIfAbsent(node.parentId(), ignored -> new ArrayList<>()).add(node);
    }
    return children.getOrDefault(null, List.of()).stream()
        .map(node -> withChapterChildren(node, children))
        .toList();
  }

  private ChapterNodeDto withChapterChildren(ChapterNodeDto node, Map<Long, List<ChapterNodeDto>> children) {
    return new ChapterNodeDto(
        node.id(),
        node.qualificationId(),
        node.subjectId(),
        node.parentId(),
        node.bookVersion(),
        node.courseCode(),
        node.chapterCode(),
        node.chapterName(),
        node.levelNo(),
        node.sortOrder(),
        node.status(),
        children.getOrDefault(node.id(), List.of()).stream()
            .map(child -> withChapterChildren(child, children))
            .toList());
  }

  private List<ChapterNodeDto> findChapterDescendants(Long subjectId, List<ChapterNodeDto> roots) {
    List<ChapterNodeDto> descendants = new ArrayList<>();
    List<Long> parentIds = roots.stream().map(ChapterNodeDto::id).toList();
    while (!parentIds.isEmpty()) {
      List<ChapterNodeDto> children = foundationRepository.findEnabledChapterChildren(subjectId, parentIds);
      descendants.addAll(children);
      parentIds = children.stream().map(ChapterNodeDto::id).toList();
    }
    return descendants;
  }

  private <T> List<T> concat(List<T> first, List<T> second) {
    List<T> result = new ArrayList<>(first.size() + second.size());
    result.addAll(first);
    result.addAll(second);
    return result;
  }

  private <T> PageResponse<T> page(List<T> items, FoundationPageQuery pageQuery) {
    int from = (int) Math.min(pageQuery.offset(), items.size());
    int to = (int) Math.min(from + pageQuery.pageSize(), items.size());
    return new PageResponse<>(
        items.subList(from, to),
        pageQuery.pageNo(),
        pageQuery.pageSize(),
        items.size(),
        to < items.size());
  }

  private String statusOrEnabled(String status) {
    return status == null || status.isBlank() ? "ENABLED" : normalize(status);
  }

  private String statusOrPublished(String status) {
    return status == null || status.isBlank() ? "PUBLISHED" : normalize(status);
  }

  private void validateQualificationMetadata(QualificationCreateRequest request, String status) {
    if ("OFFICIAL_VERIFIED".equals(request.verificationStatus())
        && (request.sourceUrl() == null || request.sourceUrl().isBlank()
            || request.verifiedAt() == null)) {
      throw validation("官方核验资格必须提供来源地址和核验时间");
    }
    if ("RETIRED".equals(request.catalogStatus()) && "ENABLED".equals(status)) {
      throw validation("已退役资格不能启用");
    }
  }

  private String normalize(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private int sortOrder(Integer sortOrder) {
    return sortOrder == null ? 0 : sortOrder;
  }

  private Long nextId() {
    return System.currentTimeMillis() * 100_000 + Math.floorMod(ID_SEQUENCE.getAndIncrement(), 100_000);
  }

  private BusinessException conflict(String message) {
    return new BusinessException("CONFLICT", message, HttpStatus.CONFLICT);
  }

  private BusinessException validation(String message) {
    return new BusinessException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
  }

  private boolean isEnabled(String status) {
    return "ENABLED".equals(normalize(status));
  }
}
