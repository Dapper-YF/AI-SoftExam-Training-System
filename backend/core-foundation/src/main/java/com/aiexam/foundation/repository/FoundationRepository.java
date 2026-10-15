package com.aiexam.foundation.repository;

import com.aiexam.common.api.PageResponse;
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
import java.util.List;
import java.util.Optional;

public interface FoundationRepository {
  PageResponse<QualificationDto> findEnabledQualifications(FoundationPageQuery pageQuery);

  Optional<QualificationDto> findQualificationById(Long id);

  Optional<QualificationDto> findEnabledQualificationById(Long id);

  QualificationDto createQualification(Long id, QualificationCreateRequest request, String status, int sortOrder);

  QualificationDto updateQualification(Long id, QualificationUpdateRequest request, int sortOrder);

  PageResponse<SubjectDto> findEnabledSubjects(Long qualificationId, FoundationPageQuery pageQuery);

  Optional<SubjectDto> findSubjectById(Long id);

  Optional<SubjectDto> findEnabledSubjectById(Long id);

  SubjectDto createSubject(Long id, SubjectCreateRequest request, String status, int sortOrder);

  List<SyllabusNodeDto> findEnabledSyllabusNodes(Long subjectId);

  PageResponse<SyllabusNodeDto> findEnabledRootSyllabusNodes(Long subjectId, FoundationPageQuery pageQuery);

  List<SyllabusNodeDto> findEnabledSyllabusChildren(Long subjectId, List<Long> parentIds);

  Optional<SyllabusNodeDto> findSyllabusNodeById(Long id);

  SyllabusNodeDto createSyllabusNode(Long id, SyllabusNodeCreateRequest request, String status, int sortOrder);

  SyllabusNodeDto updateSyllabusNode(Long id, SyllabusNodeCreateRequest request, String status, int sortOrder);

  List<ChapterNodeDto> findEnabledChapterNodes(Long subjectId);

  PageResponse<ChapterNodeDto> findEnabledRootChapterNodes(Long subjectId, FoundationPageQuery pageQuery);

  List<ChapterNodeDto> findEnabledChapterChildren(Long subjectId, List<Long> parentIds);

  Optional<ChapterNodeDto> findChapterNodeById(Long id);

  ChapterNodeDto createChapterNode(Long id, ChapterNodeCreateRequest request, String status, int sortOrder);

  ChapterNodeDto updateChapterNode(Long id, ChapterNodeCreateRequest request, String status, int sortOrder);

  PageResponse<KnowledgeTagDto> findEnabledTags(Long qualificationId, Long subjectId, FoundationPageQuery pageQuery);

  Optional<KnowledgeTagDto> findTagById(Long id);

  KnowledgeTagDto createTag(Long id, KnowledgeTagCreateRequest request, String status);

  KnowledgeTagDto updateTag(Long id, KnowledgeTagCreateRequest request, String status);

  KnowledgeRelationDto createRelation(Long id, KnowledgeRelationCreateRequest request);

  PageResponse<ExamRuleDto> findPublishedExamRules(Long qualificationId, Long subjectId, FoundationPageQuery pageQuery);

  ExamRuleDto createExamRule(Long id, ExamRuleCreateRequest request, String status);
}
