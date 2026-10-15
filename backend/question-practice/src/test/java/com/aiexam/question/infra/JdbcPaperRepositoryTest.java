package com.aiexam.question.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JdbcPaperRepositoryTest {
  @Test
  void publishedPaperPageSqlSeparatesDynamicFiltersFromOrderBy() {
    String sql = JdbcPaperRepository.publishedPapersPageSql(
        " WHERE deleted = 0 AND status = 'PUBLISHED' AND qualification_id = ? AND subject_id = ?");

    assertThat(sql)
        .contains("subject_id = ? ORDER BY updated_at DESC")
        .doesNotContain("?ORDER BY");
  }
}
