package com.aiexam.question.practice.repository;

import com.aiexam.common.api.PageResponse;
import com.aiexam.question.practice.dto.PracticeFavoriteDto;
import com.aiexam.question.practice.dto.PracticeNoteDto;
import java.util.Optional;

public interface PracticeFavoriteNoteRepository {
  Optional<QuestionRef> findPublishedQuestion(Long questionId);

  Optional<FavoriteState> findFavoriteState(Long userId, Long questionId);

  void insertFavorite(Long id, Long userId, QuestionRef question, String favoriteGroup);

  void reactivateFavorite(Long id, String favoriteGroup);

  boolean deleteFavorite(Long userId, Long questionId);

  boolean updateFavoriteGroup(Long userId, Long questionId, String favoriteGroup);

  Optional<PracticeFavoriteDto> findFavorite(Long userId, Long questionId);

  PageResponse<PracticeFavoriteDto> findFavorites(Long userId, long pageNo, long pageSize);

  void insertNote(Long id, Long userId, String targetType, Long targetId, Long qualificationId, Long subjectId, String noteContent);

  boolean updateNote(Long userId, Long id, String noteContent);

  Optional<PracticeNoteDto> findNote(Long userId, Long id);

  PageResponse<PracticeNoteDto> findNotes(Long userId, long pageNo, long pageSize);

  record QuestionRef(Long questionId, Long qualificationId, Long subjectId, String title) {
  }

  record FavoriteState(Long id, boolean deleted) {
  }
}
