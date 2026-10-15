package com.aiexam.app.learning;

import com.aiexam.common.api.PageResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LearningResourceService {
  private final LearningResourceRepository repository;
  private final LearningKnowledgeGraphRepository graphRepository;

  public LearningResourceService(LearningResourceRepository repository, LearningKnowledgeGraphRepository graphRepository) {
    this.repository = repository;
    this.graphRepository = graphRepository;
  }

  public PageResponse<LearningResourceDto> page(Long qualificationId, LearningResourceType type,
      LearningResourceStatus status, long pageNo, long pageSize) {
    return repository.findPage(qualificationId, type, status, pageNo, pageSize);
  }

  public LearningResourceDto detail(long id) { return repository.find(id); }

  public LearningResourceDto create(LearningResourceCreateRequest request) {
    return repository.insert(request, Instant.now());
  }

  public LearningResourceDto update(long id, LearningResourceUpdateRequest request) {
    return repository.update(id, request, Instant.now());
  }

  public LearningResourceDto changeStatus(long id, LearningResourceStatus status) {
    return repository.changeStatus(id, status, Instant.now());
  }

  public void delete(long id) { repository.delete(id, Instant.now()); }

  public LearningResourceVersionDto addVersion(long id, LearningResourceVersionRequest request) {
    return repository.addVersion(id, request, Instant.now());
  }

  public void publishVersion(long resourceId, long versionId) {
    repository.publishVersion(resourceId, versionId, Instant.now());
  }

  public List<LearningResourceVersionDto> versions(long resourceId) { return repository.versions(resourceId); }

  public LearningResourceVersionDto rebuildPreview(long versionId, LearningResourcePdfUploadService.Preview preview) {
    return repository.updatePreview(versionId, preview.pageCount(), preview.coverUrl(), Instant.now());
  }

  public List<LearningChapterDto> chapters(long versionId) { return repository.chapters(versionId); }

  public List<LearningKnowledgePointDto> chapterKnowledgePoints(long chapterId) { return repository.chapterKnowledgePoints(chapterId); }

  public List<LearningKnowledgePointDto> replaceChapterKnowledgePoints(long chapterId, List<Long> pointIds) {
    return repository.replaceChapterKnowledgePoints(chapterId, pointIds);
  }

  public List<LearningChapterDto> replaceChapters(long versionId, List<LearningChapterWriteRequest> chapters) {
    return repository.replaceChapters(versionId, chapters);
  }

  public List<LearningKnowledgePointDto> knowledgePoints(long qualificationId) {
    return repository.knowledgePoints(qualificationId);
  }

  public LearningKnowledgePointDto createKnowledgePoint(LearningKnowledgePointRequest request) {
    return repository.createKnowledgePoint(request, Instant.now());
  }

  public List<LearningKnowledgeGraphEdgeDto> knowledgeGraph(long qualificationId) {
    return graphRepository.edges(qualificationId);
  }
}
