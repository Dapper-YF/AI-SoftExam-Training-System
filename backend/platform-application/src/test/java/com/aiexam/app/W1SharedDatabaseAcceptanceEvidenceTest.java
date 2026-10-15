package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "W1_SHARED_DB_EVIDENCE", matches = "true")
class W1SharedDatabaseAcceptanceEvidenceTest {
  private static final String EXPECTED_URL_PREFIX =
      "jdbc:mysql://127.0.0.1:33307/ai_exam_platform?";

  @Test
  void verifiesPublishedQuestionCorpusAndStudentPracticeClosure() {
    String url = required("W1_SHARED_DB_URL");
    assertThat(url).startsWith(EXPECTED_URL_PREFIX);
    var jdbc = new JdbcTemplate(new DriverManagerDataSource(
        url, required("W1_SHARED_DB_USERNAME"), required("W1_SHARED_DB_PASSWORD")));

    assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
        .isEqualTo("ai_exam_platform");
    assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(33307);
    assertThat(count(jdbc, """
        SELECT COUNT(*) FROM flyway_schema_history
        WHERE CAST(version AS UNSIGNED) BETWEEN 1 AND 12 AND success = 1
        """)).isEqualTo(12);
    assertThat(count(jdbc, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0"))
        .isZero();

    int questions = count(jdbc, "SELECT COUNT(*) FROM q_question WHERE deleted = 0");
    int versions = count(jdbc, "SELECT COUNT(*) FROM q_question_version WHERE deleted = 0");
    int stems = count(jdbc, "SELECT COUNT(*) FROM q_question_stem WHERE deleted = 0");
    int subQuestions = count(jdbc, "SELECT COUNT(*) FROM q_sub_question WHERE deleted = 0");
    int options = count(jdbc, "SELECT COUNT(*) FROM q_choice_option WHERE deleted = 0");
    int answerKeys = count(jdbc, "SELECT COUNT(*) FROM q_answer_key WHERE deleted = 0");
    int solutions = count(jdbc, "SELECT COUNT(*) FROM q_solution WHERE deleted = 0");
    int answerCoveredQuestions = count(jdbc, """
        SELECT COUNT(DISTINCT v.id)
        FROM q_question_version v
        JOIN q_sub_question sq ON sq.question_version_id = v.id AND sq.deleted = 0
        JOIN q_answer_key ak ON ak.sub_question_id = sq.id AND ak.deleted = 0
        WHERE v.deleted = 0
        """);
    int solutionCoveredQuestions = count(jdbc, """
        SELECT COUNT(DISTINCT v.id)
        FROM q_question_version v
        JOIN q_solution sol ON sol.question_version_id = v.id AND sol.deleted = 0
        WHERE v.deleted = 0
        """);
    int optionCoveredQuestions = count(jdbc, """
        SELECT COUNT(DISTINCT v.id)
        FROM q_question_version v
        JOIN q_sub_question sq ON sq.question_version_id = v.id AND sq.deleted = 0
        JOIN q_choice_option opt ON opt.sub_question_id = sq.id AND opt.deleted = 0
        WHERE v.deleted = 0
        """);

    System.out.printf(
        "W1_SHARED_QUESTION_COUNTS=questions:%d|versions:%d|stems:%d|subQuestions:%d|options:%d|answerKeys:%d|solutions:%d%n",
        questions, versions, stems, subQuestions, options, answerKeys, solutions);
    System.out.printf(
        "W1_SHARED_CONTENT_COVERAGE=answers:%d|solutions:%d|options:%d%n",
        answerCoveredQuestions, solutionCoveredQuestions, optionCoveredQuestions);

    assertThat(questions).isEqualTo(149);
    assertThat(versions).isEqualTo(149);
    assertThat(stems).isEqualTo(149);
    assertThat(subQuestions).isEqualTo(156);
    assertThat(options).isEqualTo(584);
    assertThat(answerKeys).isEqualTo(156);
    assertThat(solutions).isEqualTo(99);
    assertThat(answerCoveredQuestions).isEqualTo(149);
    assertThat(solutionCoveredQuestions).isEqualTo(92);
    assertThat(optionCoveredQuestions).isEqualTo(146);
    assertThat(count(jdbc, """
        SELECT COUNT(*)
        FROM q_question q
        JOIN q_question_version v ON v.id = q.current_published_version_id
        WHERE q.deleted = 0 AND v.deleted = 0
          AND q.status = 'PUBLISHED' AND v.status = 'PUBLISHED'
          AND q.current_working_version_id IS NULL
        """)).isEqualTo(149);

    int batchSourceFiles = count(jdbc, """
        SELECT COUNT(DISTINCT JSON_UNQUOTE(
          JSON_EXTRACT(canonical_json, '$.sourceTrace.documentSha256')))
        FROM q_question_import_item
        """);
    int publishedSourceFiles = count(jdbc,
        "SELECT COUNT(DISTINCT source_sha256) FROM q_question_version");
    int completeProvenance = count(jdbc, """
        SELECT COUNT(*) FROM q_question_version
        WHERE deleted = 0
          AND source_file IS NOT NULL AND source_file <> ''
          AND source_sha256 REGEXP '^[0-9A-Fa-f]{64}$'
          AND source_locator IS NOT NULL AND source_locator <> ''
          AND original_question_no IS NOT NULL
          AND import_batch_id IS NOT NULL
        """);
    int internalOnly = count(jdbc, """
        SELECT COUNT(*) FROM q_question_version
        WHERE deleted = 0 AND copyright_status = 'UNVERIFIED'
          AND usage_restriction = 'INTERNAL_ONLY'
        """);
    System.out.printf(
        "W1_SHARED_PROVENANCE=batchSources:%d|publishedSources:%d|complete:%d|unverifiedInternal:%d%n",
        batchSourceFiles, publishedSourceFiles, completeProvenance, internalOnly);
    assertThat(batchSourceFiles).isEqualTo(17);
    assertThat(publishedSourceFiles).isEqualTo(2);
    assertThat(completeProvenance).isEqualTo(149);
    assertThat(internalOnly).isEqualTo(149);

    assertThat(count(jdbc, "SELECT COUNT(*) FROM q_question_import_batch"))
        .isEqualTo(1);
    assertThat(count(jdbc, """
        SELECT COUNT(*) FROM q_question_import_batch
        WHERE status = 'CONFIRMED' AND total_count = 360 AND eligible_count = 149
          AND imported_count = 149 AND rights_status = 'UNVERIFIED'
          AND usage_restriction = 'INTERNAL_ONLY'
        """)).isEqualTo(1);
    assertThat(count(jdbc, "SELECT COUNT(*) FROM q_question_import_item"))
        .isEqualTo(360);
    assertThat(count(jdbc, """
        SELECT COUNT(*) FROM q_question_import_item
        WHERE import_eligible = 1 AND item_status = 'IMPORTED_DRAFT'
          AND question_id IS NOT NULL AND question_version_id IS NOT NULL
        """)).isEqualTo(149);
    assertThat(count(jdbc, """
        SELECT COUNT(*) FROM q_question_import_item
        WHERE import_eligible = 0 AND item_status = 'SKIPPED_INELIGIBLE'
          AND question_id IS NULL AND question_version_id IS NULL
        """)).isEqualTo(211);

    int highPm = taxonomyCount(jdbc, "HIGH_PM", null, null);
    int databaseEngineer = taxonomyCount(
        jdbc, "MIDDLE_DATABASE_SYSTEM_ENGINEER", null, null);
    int highComprehensive = taxonomyCount(
        jdbc, "HIGH_PM", "COMPREHENSIVE_KNOWLEDGE", null);
    int highCase = taxonomyCount(jdbc, "HIGH_PM", "CASE_ANALYSIS", null);
    int databaseBasics = taxonomyCount(
        jdbc, "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE", null);
    int singleChoice = taxonomyCount(jdbc, null, null, "SINGLE_CHOICE");
    int caseQuestions = taxonomyCount(jdbc, null, null, "CASE");
    int realQuestions = count(jdbc,
        "SELECT COUNT(*) FROM q_question_version WHERE deleted = 0 AND paper_kind = 'REAL_PAPER'");
    int mockQuestions = count(jdbc,
        "SELECT COUNT(*) FROM q_question_version WHERE deleted = 0 AND paper_kind = 'MOCK_PAPER'");
    System.out.printf(
        "W1_SHARED_TAXONOMY=highPm:%d|databaseEngineer:%d|highComprehensive:%d|highCase:%d|databaseBasics:%d%n",
        highPm, databaseEngineer, highComprehensive, highCase, databaseBasics);
    System.out.printf(
        "W1_SHARED_KIND=singleChoice:%d|case:%d|real:%d|mock:%d%n",
        singleChoice, caseQuestions, realQuestions, mockQuestions);
    assertThat(highPm).isEqualTo(74);
    assertThat(databaseEngineer).isEqualTo(75);
    assertThat(highComprehensive).isEqualTo(71);
    assertThat(highCase).isEqualTo(3);
    assertThat(databaseBasics).isEqualTo(75);
    assertThat(singleChoice).isEqualTo(146);
    assertThat(caseQuestions).isEqualTo(3);
    assertThat(realQuestions).isEqualTo(75);
    assertThat(mockQuestions).isEqualTo(74);

    int reviews = count(jdbc, "SELECT COUNT(*) FROM q_question_review");
    int approvedReviews = count(jdbc, """
        SELECT COUNT(*) FROM q_question_review
        WHERE decision = 'APPROVED' AND reviewer_id <> submitted_by
        """);
    int separatedWorkflow = count(jdbc, """
        SELECT COUNT(*) FROM q_question_version
        WHERE deleted = 0 AND status = 'PUBLISHED' AND review_decision = 'APPROVED'
          AND submitted_by IS NOT NULL AND reviewed_by IS NOT NULL
          AND published_by IS NOT NULL AND submitted_by <> reviewed_by
        """);
    System.out.printf(
        "W1_SHARED_REVIEW=reviews:%d|approvedNonSelf:%d|publishedSeparated:%d%n",
        reviews, approvedReviews, separatedWorkflow);
    assertThat(reviews).isEqualTo(149);
    assertThat(approvedReviews).isEqualTo(149);
    assertThat(separatedWorkflow).isEqualTo(149);

    int attempts = count(jdbc, "SELECT COUNT(*) FROM practice_attempt WHERE deleted = 0");
    int attemptQuestions = count(jdbc, "SELECT COUNT(*) FROM practice_attempt_question");
    int answers = count(jdbc, "SELECT COUNT(*) FROM practice_attempt_answer WHERE deleted = 0");
    int mistakes = count(jdbc, "SELECT COUNT(*) FROM practice_mistake WHERE deleted = 0");
    int idempotencyRows = count(jdbc, "SELECT COUNT(*) FROM practice_attempt_idempotency");
    System.out.printf(
        "W1_SHARED_PRACTICE=attempts:%d|questions:%d|answers:%d|mistakes:%d|idempotency:%d%n",
        attempts, attemptQuestions, answers, mistakes, idempotencyRows);
    assertThat(attempts).isEqualTo(1);
    assertThat(count(jdbc, """
        SELECT COUNT(*) FROM practice_attempt a
        JOIN sys_user u ON u.id = a.user_id
        WHERE a.deleted = 0 AND a.status = 'SUBMITTED' AND u.username = 'student'
          AND a.question_count = 1 AND a.correct_count = 0 AND a.wrong_count = 1
        """)).isEqualTo(1);
    assertThat(attemptQuestions).isEqualTo(1);
    assertThat(count(jdbc, """
        SELECT COUNT(*) FROM practice_attempt_question
        WHERE settlement_status = 'SETTLED' AND wrong_count = 1
        """)).isEqualTo(1);
    assertThat(answers).isEqualTo(1);
    assertThat(count(jdbc, """
        SELECT COUNT(*) FROM practice_attempt_answer
        WHERE deleted = 0 AND grading_status = 'WRONG' AND is_correct = 0
        """)).isEqualTo(1);
    assertThat(mistakes).isEqualTo(1);
    assertThat(count(jdbc, """
        SELECT COUNT(*) FROM practice_mistake m
        JOIN sys_user u ON u.id = m.user_id
        WHERE m.deleted = 0 AND u.username = 'student'
          AND m.status = 'ACTIVE' AND m.mistake_count = 1
        """)).isEqualTo(1);
    assertThat(idempotencyRows).isEqualTo(4);
    assertThat(count(jdbc, """
        SELECT COUNT(DISTINCT operation_code) FROM practice_attempt_idempotency
        WHERE status = 'COMPLETED'
        """)).isEqualTo(4);
    assertThat(count(jdbc, """
        SELECT COUNT(*) FROM practice_attempt_idempotency WHERE status <> 'COMPLETED'
        """)).isZero();
  }

  private int taxonomyCount(JdbcTemplate jdbc, String qualificationCode,
      String subjectCode, String questionType) {
    StringBuilder sql = new StringBuilder("""
        SELECT COUNT(*)
        FROM q_question q
        JOIN exam_qualification eq ON eq.id = q.qualification_id
        JOIN exam_subject es ON es.id = q.subject_id
        WHERE q.deleted = 0
        """);
    if (qualificationCode != null) {
      sql.append(" AND eq.qualification_code = '").append(qualificationCode).append("'");
    }
    if (subjectCode != null) {
      sql.append(" AND es.subject_code = '").append(subjectCode).append("'");
    }
    if (questionType != null) {
      sql.append(" AND q.question_type = '").append(questionType).append("'");
    }
    return count(jdbc, sql.toString());
  }

  private int count(JdbcTemplate jdbc, String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing environment variable: " + name);
    }
    return value;
  }
}
