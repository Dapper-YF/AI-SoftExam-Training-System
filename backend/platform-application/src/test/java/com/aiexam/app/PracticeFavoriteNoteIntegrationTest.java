package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "aiexam.auth.persistence=unavailable",
        "aiexam.auth.password-adapter=unavailable",
        "aiexam.auth.challenge.dev-delivery.enabled=true",
        "aiexam.security.dev-bearer.enabled=true",
        "aiexam.test.context=practice-favorite-note"
    }
)
class PracticeFavoriteNoteIntegrationTest {
  @LocalServerPort
  int port;

  @Autowired
  TestRestTemplate restTemplate;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetPracticeReviewTables() {
    createTables();
    for (String table : List.of("practice_note", "practice_favorite", "q_question")) {
      jdbcTemplate.execute("DELETE FROM " + table);
    }
    insertQuestion(1001L, "PUBLISHED", 1L, 1L, "Published question");
    insertQuestion(1002L, "DRAFT", 1L, 1L, "Draft question");
  }

  @Test
  void studentCanFavoriteAndListPublishedQuestion() {
    ResponseEntity<Map> created = postStudent("/api/v1/practice/favorites", Map.of(
        "questionId", 1001,
        "favoriteGroup", "important"));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).containsEntry("success", true);
    Map<String, Object> favorite = data(created);
    assertThat(favorite).containsEntry("questionId", 1001);
    assertThat(favorite).containsEntry("favoriteGroup", "important");

    ResponseEntity<Map> listed = getStudent("/api/v1/practice/favorites?pageNo=1&pageSize=10");

    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page = data(listed);
    assertThat(page).containsEntry("pageNo", 1);
    assertThat(page).containsEntry("pageSize", 10);
    assertThat((List<?>) page.get("items")).hasSize(1);
  }

  @Test
  void duplicateFavoriteReturnsConflict() {
    assertThat(postStudent("/api/v1/practice/favorites", Map.of("questionId", 1001)).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    ResponseEntity<Map> duplicated = postStudent("/api/v1/practice/favorites", Map.of("questionId", 1001));

    assertThat(duplicated.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(duplicated.getBody()).containsEntry("success", false);
    assertThat(duplicated.getBody()).containsEntry("errorCode", "CONFLICT");
  }

  @Test
  void cancelFavoriteDoesNotRemoveNote() {
    assertThat(postStudent("/api/v1/practice/favorites", Map.of("questionId", 1001)).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    Long noteId = id(postStudent("/api/v1/practice/notes", Map.of(
        "targetType", "QUESTION",
        "targetId", 1001,
        "noteContent", "review scope baseline")));

    ResponseEntity<Map> deleted = deleteStudent("/api/v1/practice/favorites/1001");
    ResponseEntity<Map> favorites = getStudent("/api/v1/practice/favorites?pageNo=1&pageSize=10");
    ResponseEntity<Map> notes = getStudent("/api/v1/practice/notes?pageNo=1&pageSize=10");

    assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List<?>) data(favorites).get("items")).isEmpty();
    List<Map<String, Object>> noteItems = (List<Map<String, Object>>) data(notes).get("items");
    assertThat(noteItems).extracting(item -> ((Number) item.get("id")).longValue()).containsExactly(noteId);
  }

  @Test
  void studentCanCreateAndUpdateQuestionNote() {
    Long noteId = id(postStudent("/api/v1/practice/notes", Map.of(
        "targetType", "QUESTION",
        "targetId", 1001,
        "noteContent", "first note")));

    ResponseEntity<Map> updated = putStudent("/api/v1/practice/notes/" + noteId, Map.of(
        "noteContent", "updated note"));

    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> note = data(updated);
    assertThat(note).containsEntry("id", noteId);
    assertThat(note).containsEntry("noteContent", "updated note");
  }

  @Test
  void studentCanCreateKnowledgePointNote() {
    ResponseEntity<Map> created = postStudent("/api/v1/practice/notes", Map.of(
        "targetType", "KNOWLEDGE_POINT",
        "targetId", 2001,
        "qualificationId", 1,
        "subjectId", 1,
        "noteContent", "knowledge point note"));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(data(created)).containsEntry("targetType", "KNOWLEDGE_POINT");
    assertThat(data(created)).containsEntry("targetId", 2001);
  }

  @Test
  void userCanOnlySeeAndUpdateOwnFavoritesAndNotes() {
    postAdmin("/api/v1/practice/favorites", Map.of("questionId", 1001));
    Long adminNoteId = id(postAdmin("/api/v1/practice/notes", Map.of(
        "targetType", "QUESTION",
        "targetId", 1001,
        "noteContent", "admin note")));
    Long studentNoteId = id(postStudent("/api/v1/practice/notes", Map.of(
        "targetType", "QUESTION",
        "targetId", 1001,
        "noteContent", "student note")));

    ResponseEntity<Map> studentFavorites = getStudent("/api/v1/practice/favorites?pageNo=1&pageSize=10");
    ResponseEntity<Map> studentNotes = getStudent("/api/v1/practice/notes?pageNo=1&pageSize=10");
    ResponseEntity<Map> updateOther = putStudent("/api/v1/practice/notes/" + adminNoteId, Map.of(
        "noteContent", "cross user update"));

    assertThat((List<?>) data(studentFavorites).get("items")).isEmpty();
    List<Map<String, Object>> noteItems = (List<Map<String, Object>>) data(studentNotes).get("items");
    assertThat(noteItems).extracting(item -> ((Number) item.get("id")).longValue()).containsExactly(studentNoteId);
    assertThat(updateOther.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(updateOther.getBody()).containsEntry("errorCode", "NOT_FOUND");
  }

  @Test
  void unpublishedQuestionCannotBeFavorited() {
    ResponseEntity<Map> response = postStudent("/api/v1/practice/favorites", Map.of("questionId", 1002));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "NOT_FOUND");
  }

  private void insertQuestion(Long id, String status, Long qualificationId, Long subjectId, String title) {
    jdbcTemplate.update("""
        INSERT INTO q_question (
          id, qualification_id, subject_id, question_type, title, year_no, difficulty,
          source_type, source_ref, status, content_version, created_at, updated_at, deleted
        ) VALUES (?, ?, ?, 'SINGLE_CHOICE', ?, 2026, 'MEDIUM', 'MANUAL', NULL, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """, id, qualificationId, subjectId, title, status);
  }

  private ResponseEntity<Map> postStudent(String path, Object body) {
    return exchange(path, HttpMethod.POST, body, "dev-student-token");
  }

  private ResponseEntity<Map> postAdmin(String path, Object body) {
    return exchange(path, HttpMethod.POST, body, "dev-admin-token");
  }

  private ResponseEntity<Map> putStudent(String path, Object body) {
    return exchange(path, HttpMethod.PUT, body, "dev-student-token");
  }

  private ResponseEntity<Map> deleteStudent(String path) {
    return exchange(path, HttpMethod.DELETE, null, "dev-student-token");
  }

  private ResponseEntity<Map> getStudent(String path) {
    return exchange(path, HttpMethod.GET, null, "dev-student-token");
  }

  private ResponseEntity<Map> exchange(String path, HttpMethod method, Object body, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), Map.class);
  }

  private Long id(ResponseEntity<Map> response) {
    assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CREATED);
    return ((Number) data(response).get("id")).longValue();
  }

  private Map<String, Object> data(ResponseEntity<Map> response) {
    return (Map<String, Object>) response.getBody().get("data");
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private void createTables() {
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_question (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          subject_id BIGINT NOT NULL,
          question_type VARCHAR(32) NOT NULL,
          title VARCHAR(180) NULL,
          year_no INT NULL,
          difficulty VARCHAR(24) NULL,
          source_type VARCHAR(32) NULL,
          source_ref VARCHAR(255) NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
          content_version INT NOT NULL DEFAULT 1,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS practice_favorite (
          id BIGINT NOT NULL PRIMARY KEY,
          user_id BIGINT NOT NULL,
          question_id BIGINT NOT NULL,
          qualification_id BIGINT NOT NULL,
          subject_id BIGINT NULL,
          favorite_group VARCHAR(96) NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0,
          CONSTRAINT uk_practice_favorite_user_question UNIQUE (user_id, question_id)
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS practice_note (
          id BIGINT NOT NULL PRIMARY KEY,
          user_id BIGINT NOT NULL,
          target_type VARCHAR(32) NOT NULL,
          target_id BIGINT NOT NULL,
          qualification_id BIGINT NULL,
          subject_id BIGINT NULL,
          note_content LONGTEXT NOT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
  }
}
