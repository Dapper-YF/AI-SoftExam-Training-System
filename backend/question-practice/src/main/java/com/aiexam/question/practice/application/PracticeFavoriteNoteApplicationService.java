package com.aiexam.question.practice.application;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.common.error.ResourceNotFoundException;
import com.aiexam.question.practice.dto.PracticeFavoriteDeleteDto;
import com.aiexam.question.practice.dto.PracticeFavoriteDto;
import com.aiexam.question.practice.dto.PracticeNoteDto;
import com.aiexam.question.practice.dto.request.PracticeFavoriteCreateRequest;
import com.aiexam.question.practice.dto.request.PracticeNoteCreateRequest;
import com.aiexam.question.practice.dto.request.PracticeNoteUpdateRequest;
import com.aiexam.question.practice.repository.PracticeFavoriteNoteRepository;
import com.aiexam.question.practice.repository.PracticeFavoriteNoteRepository.QuestionRef;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeFavoriteNoteApplicationService {
  private static final AtomicLong ID_SEQUENCE = new AtomicLong();
  private static final Set<String> NOTE_TARGET_TYPES = Set.of("QUESTION", "KNOWLEDGE_POINT", "PAPER");

  private final PracticeFavoriteNoteRepository repository;

  public PracticeFavoriteNoteApplicationService(PracticeFavoriteNoteRepository repository) {
    this.repository = repository;
  }

  public PageResponse<PracticeFavoriteDto> listFavorites(Long userId, long pageNo, long pageSize) {
    return repository.findFavorites(userId, pageNo(pageNo), pageSize(pageSize));
  }

  @Transactional
  public PracticeFavoriteDto createFavorite(Long userId, PracticeFavoriteCreateRequest request) {
    QuestionRef question = publishedQuestion(request.questionId());
    var state = repository.findFavoriteState(userId, request.questionId());
    if (state.isPresent() && !state.get().deleted()) {
      throw new BusinessException("CONFLICT", "题目已收藏", HttpStatus.CONFLICT);
    }
    if (state.isPresent()) {
      repository.reactivateFavorite(state.get().id(), request.favoriteGroup());
    } else {
      repository.insertFavorite(nextId(), userId, question, request.favoriteGroup());
    }
    return repository.findFavorite(userId, request.questionId())
        .orElseThrow(() -> new ResourceNotFoundException("收藏记录不存在"));
  }

  @Transactional
  public PracticeFavoriteDeleteDto deleteFavorite(Long userId, Long questionId) {
    boolean removed = repository.deleteFavorite(userId, questionId);
    if (!removed) {
      throw new ResourceNotFoundException("收藏记录不存在");
    }
    return new PracticeFavoriteDeleteDto(questionId, true);
  }

  @Transactional
  public PracticeFavoriteDto updateFavoriteGroup(Long userId, Long questionId, String favoriteGroup) {
    if (!repository.updateFavoriteGroup(userId, questionId, favoriteGroup == null ? null : favoriteGroup.trim())) {
      throw new ResourceNotFoundException("收藏记录不存在");
    }
    return repository.findFavorite(userId, questionId)
        .orElseThrow(() -> new ResourceNotFoundException("收藏记录不存在"));
  }

  public PageResponse<PracticeNoteDto> listNotes(Long userId, long pageNo, long pageSize) {
    return repository.findNotes(userId, pageNo(pageNo), pageSize(pageSize));
  }

  @Transactional
  public PracticeNoteDto createNote(Long userId, PracticeNoteCreateRequest request) {
    String targetType = normalize(request.targetType());
    if (!NOTE_TARGET_TYPES.contains(targetType)) {
      throw validation("不支持的笔记目标类型");
    }
    Long qualificationId = request.qualificationId();
    Long subjectId = request.subjectId();
    if ("QUESTION".equals(targetType)) {
      QuestionRef question = publishedQuestion(request.targetId());
      qualificationId = question.qualificationId();
      subjectId = question.subjectId();
    }
    Long noteId = nextId();
    repository.insertNote(
        noteId,
        userId,
        targetType,
        request.targetId(),
        qualificationId,
        subjectId,
        request.noteContent());
    return repository.findNote(userId, noteId)
        .orElseThrow(() -> new ResourceNotFoundException("笔记不存在"));
  }

  @Transactional
  public PracticeNoteDto updateNote(Long userId, Long id, PracticeNoteUpdateRequest request) {
    boolean updated = repository.updateNote(userId, id, request.noteContent());
    if (!updated) {
      throw new ResourceNotFoundException("笔记不存在");
    }
    return repository.findNote(userId, id)
        .orElseThrow(() -> new ResourceNotFoundException("笔记不存在"));
  }

  private QuestionRef publishedQuestion(Long questionId) {
    return repository.findPublishedQuestion(questionId)
        .orElseThrow(() -> new ResourceNotFoundException("题目不存在或未发布"));
  }

  private BusinessException validation(String message) {
    return new BusinessException(ErrorCode.VALIDATION_ERROR, message, HttpStatus.BAD_REQUEST);
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

  private String normalize(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private Long nextId() {
    return System.currentTimeMillis() * 100_000 + Math.floorMod(ID_SEQUENCE.getAndIncrement(), 100_000);
  }
}
