package com.aiexam.app.aiassistant;

import static com.aiexam.app.aiassistant.AiSmartPaperDtos.*;

import com.aiexam.common.error.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiSmartPaperService {
  private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
  private final JdbcTemplate jdbc;
  private final AiQuestionGenerationService generation;
  private final ObjectMapper json;

  public AiSmartPaperService(JdbcTemplate jdbc, AiQuestionGenerationService generation, ObjectMapper json) {
    this.jdbc = jdbc;
    this.generation = generation;
    this.json = json;
  }

  @Transactional
  public SmartPaperDto create(long userId, CreateRequest request) {
    if (request.qualificationId() == null || request.subjectId() == null) throw bad("资格和科目不能为空");
    int count = request.questionCount() == null ? 20 : Math.max(5, Math.min(request.questionCount(), 100));
    List<AiQuestionGenerationDtos.WeakPointDto> weak = generation.weakPoints(userId, request.qualificationId(), request.subjectId(), 5);
    List<Long> questionIds = selectQuestions(userId, request.qualificationId(), request.subjectId(), count);
    if (questionIds.isEmpty()) throw bad("当前资格和科目没有可组卷的已发布题目");
    List<String> weakNames = weak.stream().map(AiQuestionGenerationDtos.WeakPointDto::knowledgeName).toList();
    List<PlanStage> plan = buildPlan(request.targetExamDate(), weakNames);
    Instant now = Instant.now();
    long id = nextId();
    String name = "智能专项练习 " + now.toString().substring(0, 10);
    jdbc.update("""
        INSERT INTO ai_smart_paper
          (id,user_id,qualification_id,subject_id,paper_name,question_count,target_exam_date,question_ids_json,weak_points_json,plan_json,status,created_at,updated_at,deleted)
        VALUES (?,?,?,?,?,?,?,?,?,?, 'ACTIVE',?,?,0)
        """, id, userId, request.qualificationId(), request.subjectId(), name, questionIds.size(), request.targetExamDate(),
        write(questionIds), write(weakNames), write(plan), Timestamp.from(now), Timestamp.from(now));
    return new SmartPaperDto(id, name, request.qualificationId(), request.subjectId(), questionIds, weakNames, plan, now);
  }

  public List<SmartPaperDto> list(long userId, int limit) {
    return jdbc.query("""
        SELECT id,paper_name,qualification_id,subject_id,question_ids_json,weak_points_json,plan_json,created_at
        FROM ai_smart_paper WHERE user_id=? AND status='ACTIVE' AND deleted=0 ORDER BY updated_at DESC LIMIT ?
        """, (rs, row) -> new SmartPaperDto(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
        readLongs(rs.getString(5)), readStrings(rs.getString(6)), readPlan(rs.getString(7)), rs.getTimestamp(8).toInstant()),
        userId, Math.max(1, Math.min(limit, 30)));
  }

  private List<Long> selectQuestions(long userId, long qualificationId, long subjectId, int count) {
    return jdbc.query("""
        SELECT q.id
        FROM q_question q
        LEFT JOIN practice_mistake pm ON pm.question_id=q.id AND pm.user_id=? AND pm.status='ACTIVE' AND pm.deleted=0
        WHERE q.qualification_id=? AND q.subject_id=? AND q.status='PUBLISHED' AND q.deleted=0
        ORDER BY COALESCE(pm.mistake_count,0) DESC, q.difficulty='MEDIUM' DESC, q.updated_at DESC, q.id
        LIMIT ?
        """, (rs, row) -> rs.getLong(1), userId, qualificationId, subjectId, count);
  }

  private List<PlanStage> buildPlan(LocalDate examDate, List<String> weakNames) {
    if (examDate == null || !examDate.isAfter(LocalDate.now())) return List.of();
    long days = ChronoUnit.DAYS.between(LocalDate.now(), examDate);
    LocalDate firstEnd = LocalDate.now().plusDays(Math.max(1, days * 2 / 3));
    String focus = weakNames.isEmpty() ? "综合复习与错题回顾" : String.join("、", weakNames);
    return List.of(new PlanStage("补弱训练", LocalDate.now(), firstEnd, focus),
        new PlanStage("模拟巩固", firstEnd.plusDays(1), examDate, "综合组卷、错题复盘与考前回顾"));
  }

  private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); } }
  private List<Long> readLongs(String value) { try { return json.readValue(value, new TypeReference<List<Long>>() { }); } catch (Exception exception) { throw new IllegalStateException(exception); } }
  private List<String> readStrings(String value) { try { return json.readValue(value, new TypeReference<List<String>>() { }); } catch (Exception exception) { throw new IllegalStateException(exception); } }
  private List<PlanStage> readPlan(String value) { try { return json.readValue(value, new TypeReference<List<PlanStage>>() { }); } catch (Exception exception) { throw new IllegalStateException(exception); } }
  private static long nextId() { return IDS.incrementAndGet(); }
  private static BusinessException bad(String message) { return new BusinessException("AI_SMART_PAPER_VALIDATION", message, HttpStatus.BAD_REQUEST); }
}
