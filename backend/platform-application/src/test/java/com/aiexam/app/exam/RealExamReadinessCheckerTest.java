package com.aiexam.app.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

class RealExamReadinessCheckerTest {

  private static class StubJdbcTemplate extends JdbcTemplate {
    private int queryCalls;
    private Integer result;

    StubJdbcTemplate returning(Integer result) {
      this.result = result;
      return this;
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType) {
      queryCalls++;
      return requiredType.cast(result);
    }

    int queryCalls() {
      return queryCalls;
    }
  }

  @Test
  void disabledGuardDoesNotTouchPartiallyInitializedDatabases() {
    StubJdbcTemplate jdbc = new StubJdbcTemplate();

    new RealExamReadinessChecker(jdbc, false).run(new DefaultApplicationArguments(new String[0]));

    assertThat(jdbc.queryCalls()).isZero();
  }

  @Test
  void requiredGuardRejectsMissingRealPaperInventory() {
    StubJdbcTemplate jdbc = new StubJdbcTemplate().returning(0);

    assertThatThrownBy(() ->
        new RealExamReadinessChecker(jdbc, true).run(new DefaultApplicationArguments(new String[0])))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EXAM_REAL_PAPER_NOT_READY");
  }

  @Test
  void requiredGuardAcceptsPublishedBoundRealPaper() {
    StubJdbcTemplate jdbc = new StubJdbcTemplate().returning(3);

    new RealExamReadinessChecker(jdbc, true).run(new DefaultApplicationArguments(new String[0]));
  }
}
