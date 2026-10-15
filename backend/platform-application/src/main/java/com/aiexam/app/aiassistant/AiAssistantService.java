package com.aiexam.app.aiassistant;

import com.aiexam.app.aiassistant.AiAssistantDtos.*;
import com.aiexam.ai.port.ChatModelPort;
import com.aiexam.ai.port.ChatModelResult;
import com.aiexam.ai.port.KnowledgeRetrievalPort;
import com.aiexam.ai.port.KnowledgeRetrievalPort.Evidence;
import com.aiexam.common.error.BusinessException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

@Service
public class AiAssistantService {
  private static final int MAX_FRAGMENT_CHARS = 1200;
  private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
  private final JdbcTemplate jdbc;
  private final AiAssistantProperties properties;
  private final ChatModelPort chatModel;
  private final OcrClient ocr;
  private final AiAssistantRateLimiter rateLimiter;
  private final KnowledgeRetrievalPort retrieval;

  public AiAssistantService(
      JdbcTemplate jdbc,
      AiAssistantProperties properties,
      ChatModelPort chatModel,
      OcrClient ocr,
      AiAssistantRateLimiter rateLimiter,
      KnowledgeRetrievalPort retrieval
  ) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.chatModel = chatModel;
    this.ocr = ocr;
    this.rateLimiter = rateLimiter;
    this.retrieval = retrieval;
  }

  @Transactional
  public SessionDto createSession(long userId, CreateSessionRequest request) {
    Long qualificationId = request.qualificationId();
    Long subjectId = request.subjectId();
    if (request.questionId() != null) {
      Long[] scope = questionScope(request.questionId());
      qualificationId = scope[0];
      subjectId = scope[1];
    }
    long id = id();
    Instant now = Instant.now();
    jdbc.update("""
        INSERT INTO ai_assistant_session
          (id,user_id,qualification_id,subject_id,question_id,title,status,created_at,updated_at,deleted)
        VALUES (?,?,?,?,?,?,'ACTIVE',?,?,0)
        """, id, userId, qualificationId, subjectId, request.questionId(), trim(request.title()),
        Timestamp.from(now), Timestamp.from(now));
    return new SessionDto(id, qualificationId, subjectId, request.questionId(), trim(request.title()), now);
  }

  public List<SessionDto> recentSessions(long userId, Long questionId) {
    String questionFilter = questionId == null ? "" : " AND question_id=?";
    List<Object> args = new ArrayList<>();
    args.add(userId);
    args.add(Timestamp.from(Instant.now().minusSeconds(properties.conversationRetentionDays() * 86400L)));
    if (questionId != null) args.add(questionId);
    return jdbc.query("""
        SELECT id,qualification_id,subject_id,question_id,title,updated_at
        FROM ai_assistant_session
        WHERE user_id=? AND deleted=0 AND status='ACTIVE'
          AND updated_at >= ?
        """ + questionFilter + " ORDER BY updated_at DESC,id DESC LIMIT 20",
        (rs, row) -> new SessionDto(rs.getLong(1), nullableLong(rs, 2), nullableLong(rs, 3),
            nullableLong(rs, 4), rs.getString(5), rs.getTimestamp(6).toInstant()),
        appendRetentionArgs(args));
  }

  @Transactional
  public SessionDto currentSession(long userId) {
    Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(properties.conversationRetentionDays() * 86400L));
    List<SessionDto> sessions = jdbc.query("""
        SELECT id,qualification_id,subject_id,question_id,title,updated_at
        FROM ai_assistant_session
        WHERE user_id=? AND deleted=0 AND status='ACTIVE'
          AND question_id IS NULL AND updated_at >= ?
        ORDER BY updated_at DESC,id DESC LIMIT 1
        """, (rs, row) -> new SessionDto(rs.getLong(1), nullableLong(rs, 2), nullableLong(rs, 3),
            nullableLong(rs, 4), rs.getString(5), rs.getTimestamp(6).toInstant()), userId, cutoff);
    if (!sessions.isEmpty()) return sessions.get(0);
    return createSession(userId, new CreateSessionRequest(null, null, null, "学习答疑"));
  }

  public SessionDetailDto detail(long userId, long sessionId) {
    SessionDto session = session(userId, sessionId);
    List<MessageDto> messages = jdbc.query("""
        SELECT id,role_code,content,answer_status,created_at FROM ai_assistant_message
        WHERE session_id=? ORDER BY created_at,id
        """, (rs, row) -> {
          long messageId = rs.getLong(1);
          return new MessageDto(messageId, rs.getString(2), rs.getString(3), null,
              List.of(), rs.getTimestamp(5).toInstant());
        }, sessionId);
    return new SessionDetailDto(session, messages);
  }

  @Transactional
  public MessageDto ask(long userId, long sessionId, AskRequest request) {
    rateLimiter.acquire(userId);
    SessionDto session = session(userId, sessionId);
    String question = required(request.content(), "问题不能为空");
    boolean hasAttachment = request.attachmentId() != null;
    if (hasAttachment) {
      question += "\n题图识别内容：\n" + attachmentText(userId, sessionId, request.attachmentId());
    }
    Instant now = Instant.now();
    saveMessage(sessionId, "USER", question, null, null, null, null, false, now);

    String scenario = scenario(question, session.questionId(), hasAttachment);
    String conversation = conversationContext(sessionId);
    String modelQuestion = conversation.isBlank()
        ? question
        : "前面对话：\n" + conversation + "\n\n当前问题：\n" + question;
    List<CitationDto> evidence = "CASUAL_CHAT".equals(scenario)
        ? new ArrayList<>() : new ArrayList<>(questionEvidence(session.questionId()));
    KnowledgeRetrievalPort.RetrievalDecision retrievalDecision = "CASUAL_CHAT".equals(scenario)
        ? new KnowledgeRetrievalPort.RetrievalDecision(List.of(), List.of(), List.of(), false, "CASUAL_CHAT")
        : retrieval.retrieve(normalizeQuestion(question), 8);
    if (!"CASUAL_CHAT".equals(scenario) && retrievalDecision.grounded()) {
      evidence.addAll(knowledgeEvidence(retrievalDecision.hybrid()));
    }
    String evidenceHash = sha256(evidence.stream().map(CitationDto::excerpt).reduce("", String::concat));
    String cacheKey = sha256(session.qualificationId() + "|" + session.subjectId() + "|" + session.questionId()
        + "|" + normalizeQuestion(modelQuestion) + "|" + scenario + "|" + retrievalDecision.reason()
        + "|" + evidenceHash + "|v9|" + properties.answerProvider() + "|" + properties.deepseekModel());
    String cached = cachedAnswer(cacheKey);
    String answer;
    String status;
    String mode;
    String modelProvider = properties.answerProvider();
    String modelCode = properties.deepseekModel();
    long modelDurationMs = 0;
    boolean cacheHit = cached != null;
    if (cacheHit) {
      answer = cached;
      status = "ANSWERED";
      mode = evidence.isEmpty() ? "SUPPLEMENTARY" : "GROUNDED";
    } else {
      String context = evidence.stream()
          .map(CitationDto::excerpt)
          .reduce("", (left, right) -> left + "\n\n" + right);
      ChatModelResult modelResult = chatModel.generate(promptFor(scenario, modelQuestion), context);
      modelProvider = modelResult.provider();
      modelCode = modelResult.model();
      modelDurationMs = modelResult.durationMs();
      answer = stripInternalSourceReferences(modelResult.content());
      if (!modelResult.succeeded()) {
        answer = evidence.isEmpty()
            ? "当前知识库未覆盖这个问题，且 AI 答疑服务暂时不可用，请稍后重试。"
            : groundedFallback(question, evidence, scenario);
      }
      status = modelResult.succeeded() ? "ANSWERED" : modelResult.status();
      mode = evidence.isEmpty() ? "SUPPLEMENTARY" : "GROUNDED";
    }

    long messageId = saveMessage(sessionId, "ASSISTANT", answer, status, cacheKey, properties.deepseekModel(), scenario, cacheHit, now);
    for (int index = 0; index < evidence.size(); index++) {
      CitationDto citation = evidence.get(index);
      if (citation.fragmentId() != null) {
        jdbc.update("INSERT INTO ai_assistant_citation (id,assistant_message_id,fragment_id,rank_no,created_at) VALUES (?,?,?,?,?)",
            id(), messageId, citation.fragmentId(), index + 1, Timestamp.from(now));
      }
    }
    writeCallLog(messageId, userId, scenario, "ANSWERED".equals(status), modelProvider, modelCode, modelDurationMs,
        "ANSWERED".equals(status) ? null : status);
    jdbc.update("UPDATE ai_assistant_session SET updated_at=? WHERE id=?", Timestamp.from(now), sessionId);
    return new MessageDto(messageId, "ASSISTANT", answer, status, citations(messageId), now);
  }

  @Transactional
  public void feedback(long userId, long messageId, FeedbackRequest request) {
    String code = required(request.feedbackCode(), "反馈类型不能为空").toUpperCase(Locale.ROOT);
    if (!Set.of("HELPFUL", "NOT_HELPFUL", "REPORT").contains(code)) throw bad("不支持的反馈类型");
    int owned = jdbc.queryForObject("""
        SELECT COUNT(*) FROM ai_assistant_message m JOIN ai_assistant_session s ON s.id=m.session_id
        WHERE m.id=? AND s.user_id=?
        """, Integer.class, messageId, userId);
    if (owned != 1) throw notFound();
    jdbc.update("""
        INSERT INTO ai_assistant_feedback (id,message_id,user_id,feedback_code,reason,created_at)
        VALUES (?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE feedback_code=VALUES(feedback_code),reason=VALUES(reason),created_at=VALUES(created_at)
        """, id(), messageId, userId, code, trim(request.reason()), Timestamp.from(Instant.now()));
  }

  @Transactional
  public AttachmentDto upload(long userId, long sessionId, MultipartFile file) {
    session(userId, sessionId);
    if (file == null || file.isEmpty() || file.getSize() > properties.maxImageBytes()
        || file.getContentType() == null || !file.getContentType().startsWith("image/")) {
      throw bad("仅支持不超过限制的图片文件");
    }
    long attachmentId = id();
    Path root = Path.of(properties.uploadRoot()).toAbsolutePath().normalize();
    String suffix = Optional.ofNullable(file.getOriginalFilename()).filter(name -> name.contains("."))
        .map(name -> name.substring(name.lastIndexOf('.'))).orElse(".img");
    Path target = root.resolve("attachments").resolve(attachmentId + suffix).normalize();
    if (!target.startsWith(root)) throw bad("无效文件路径");
    byte[] bytes;
    try {
      Files.createDirectories(target.getParent());
      file.transferTo(target);
      validateImage(target);
      bytes = Files.readAllBytes(target);
    } catch (IOException exception) {
      throw new BusinessException("AI_ASSISTANT_UPLOAD_FAILED", "题图保存失败", HttpStatus.BAD_REQUEST);
    }
    OcrClient.Result ocrResult = ocr.recognize(bytes, file.getContentType());
    String ocrText = ocrResult == null ? null : ocrResult.text();
    String ocrStatus = ocrResult == null
        ? (properties.ocrEndpoint() == null || properties.ocrEndpoint().isBlank() ? "MANUAL_REQUIRED" : "FAILED")
        : "RECOGNIZED";
    String detectedType = ocrText == null ? null : detectType(ocrText);
    Instant now = Instant.now();
    jdbc.update("""
        INSERT INTO ai_assistant_attachment
          (id,session_id,storage_key,original_name,content_type,file_size,content_hash,ocr_text,ocr_status,detected_question_type,created_at,updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """, attachmentId, sessionId, target.toString(), safeName(file.getOriginalFilename()), file.getContentType(),
        file.getSize(), sha256(bytes), ocrText, ocrStatus, detectedType, Timestamp.from(now), Timestamp.from(now));
    return new AttachmentDto(attachmentId, ocrStatus, ocrText, detectedType,
        ocrResult == null ? null : ocrResult.confidence(), ocrResult == null ? null : ocrResult.engine());
  }

  @Transactional
  public AttachmentDto confirmOcr(long userId, long attachmentId, String text) {
    return confirmOcr(userId, attachmentSession(attachmentId), attachmentId, text);
  }

  @Transactional
  public AttachmentDto confirmOcr(long userId, long sessionId, long attachmentId, String text) {
    session(userId, sessionId);
    String clean = required(text, "OCR 识别文本不能为空");
    String type = detectType(clean);
    int updated = jdbc.update("""
        UPDATE ai_assistant_attachment SET ocr_text=?,ocr_status='CONFIRMED',detected_question_type=?,updated_at=?
        WHERE id=? AND session_id=?
        """, clean, type, Timestamp.from(Instant.now()), attachmentId, sessionId);
    if (updated != 1) throw notFound();
    return new AttachmentDto(attachmentId, "CONFIRMED", clean, type, null, "MANUAL");
  }

  @Transactional
  public KnowledgeDocumentDto createKnowledge(KnowledgeDocumentRequest request) {
    String rawContent = required(request.content(), "资料正文不能为空");
    String content = cleanKnowledgeContent(rawContent);
    long documentId = id();
    Instant now = Instant.now();
    jdbc.update("""
        INSERT INTO knowledge_document
          (id,content_domain,qualification_id,subject_id,document_code,title,source_name,source_url,copyright_basis,status,current_version,created_at,updated_at,deleted)
        VALUES (?,?,?,?,?,?,?,?,?,'DRAFT',1,?,?,0)
        """, documentId, "AI_ASSISTANT", request.qualificationId(), request.subjectId(),
        required(request.documentCode(), "资料编码不能为空"), required(request.title(), "标题不能为空"),
        required(request.sourceName(), "来源不能为空"), trim(request.sourceUrl()),
        required(request.copyrightBasis(), "必须填写授权依据"), Timestamp.from(now), Timestamp.from(now));
    jdbc.update("""
        INSERT INTO knowledge_document_version (id,document_id,version_no,raw_content,content_hash,created_at,created_by)
        VALUES (?,?,1,?,?,?,NULL)
        """, id(), documentId, rawContent, sha256(rawContent), Timestamp.from(now));
    int number = 0;
    for (String fragment : split(content)) {
      long fragmentId = id();
      int fragmentNo = ++number;
      jdbc.update("""
          INSERT INTO knowledge_fragment
            (id,fragment_key,document_id,fragment_no,knowledge_type,knowledge_id,content,content_hash,
             status,index_status,content_kind,quality_status,created_at,updated_at,deleted)
          VALUES (?,?,?,?,?,?,?,?,'DRAFT','READY','SOURCE','NEEDS_REVIEW',?,?,0)
          """, fragmentId, "admin:" + documentId + ":" + fragmentNo, documentId, fragmentNo,
          trim(request.knowledgeType()), request.knowledgeId(), fragment,
          sha256(fragment), Timestamp.from(now), Timestamp.from(now));
    }
    return new KnowledgeDocumentDto(documentId, request.documentCode(), request.title(), "DRAFT", number,
        0, 0, 0, "READY", now);
  }

  @Transactional
  public KnowledgeDocumentDto createKnowledgeFromUpload(KnowledgeDocumentRequest request, MultipartFile file) {
    if (file == null || file.isEmpty() || file.getSize() > 20L * 1024 * 1024) {
      throw bad("资料文件不能为空且不能超过 20 MiB");
    }
    String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
    if (!(name.endsWith(".pdf") || name.endsWith(".txt") || name.endsWith(".docx"))) throw bad("P1 资料导入仅支持 PDF、DOCX 或 TXT");
    try {
      byte[] bytes = file.getBytes();
      String content;
      if (name.endsWith(".pdf")) {
        try (var document = Loader.loadPDF(bytes)) {
          PDFTextStripper stripper = new PDFTextStripper();
          stripper.setSortByPosition(true);
          content = stripper.getText(document);
        }
      } else if (name.endsWith(".docx")) {
        try (var document = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
          StringBuilder text = new StringBuilder();
          document.getParagraphs().forEach(paragraph -> text.append(paragraph.getText()).append('\n'));
          document.getTables().forEach(table -> table.getRows().forEach(row -> row.getTableCells()
              .forEach(cell -> text.append(cell.getText()).append('\n'))));
          content = text.toString();
        }
      } else {
        content = new String(bytes, StandardCharsets.UTF_8);
      }
      if (content == null || content.trim().isEmpty()) throw bad("资料文件未提取到正文");
      return createKnowledge(new KnowledgeDocumentRequest(request.qualificationId(), request.subjectId(),
          request.documentCode(), request.title(), request.sourceName(), request.sourceUrl(),
          request.copyrightBasis(), content, request.knowledgeType(), request.knowledgeId()));
    } catch (IOException exception) {
      throw new BusinessException("AI_KNOWLEDGE_FILE_PARSE_FAILED", "资料文件解析失败", HttpStatus.BAD_REQUEST);
    }
  }

  @Transactional public KnowledgeDocumentDto publishKnowledge(long id) { return setKnowledgeStatus(id, "PUBLISHED"); }
  @Transactional public KnowledgeDocumentDto offlineKnowledge(long id) { return setKnowledgeStatus(id, "OFFLINE"); }

  public List<KnowledgeDocumentDto> listKnowledge() {
    return jdbc.query("""
        SELECT d.id,d.document_code,d.title,d.status,COUNT(f.id),
               SUM(CASE WHEN f.index_status='INDEXED' THEN 1 ELSE 0 END),
               SUM(CASE WHEN f.quality_status='APPROVED' THEN 1 ELSE 0 END),
               SUM(CASE WHEN f.index_status='FAILED' THEN 1 ELSE 0 END),d.updated_at
        FROM knowledge_document d LEFT JOIN knowledge_fragment f ON f.document_id=d.id AND f.deleted=0
        WHERE d.content_domain='AI_ASSISTANT' AND d.deleted=0
        GROUP BY d.id,d.document_code,d.title,d.status,d.updated_at ORDER BY d.updated_at DESC
        """, (rs, row) -> {
      int total = rs.getInt(5);
      int indexed = rs.getInt(6);
      int reviewed = rs.getInt(7);
      int failed = rs.getInt(8);
      return new KnowledgeDocumentDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
          total, indexed, reviewed, failed, KnowledgeIndexingService.processingStatus(total, indexed, failed),
          rs.getTimestamp(9).toInstant());
    });
  }

  @Transactional
  public KnowledgeDocumentDto reviewKnowledge(long documentId) {
    List<Long> fragmentIds = jdbc.query("""
        SELECT f.id FROM knowledge_fragment f
        JOIN knowledge_document d ON d.id=f.document_id AND d.deleted=0
        WHERE f.document_id=? AND d.content_domain='AI_ASSISTANT' AND f.deleted=0
          AND f.index_status='INDEXED'
        """, (rs, row) -> rs.getLong(1), documentId);
    if (fragmentIds.isEmpty()) {
      throw new BusinessException("AI_KNOWLEDGE_NOT_INDEXED",
          "知识切片完成向量化后才能审核", HttpStatus.CONFLICT);
    }
    Instant now = Instant.now();
    for (Long fragmentId : fragmentIds) {
      jdbc.update("UPDATE knowledge_fragment SET quality_status='APPROVED',updated_at=? WHERE id=?",
          Timestamp.from(now), fragmentId);
      jdbc.update("""
          INSERT INTO knowledge_review_record
            (id,fragment_id,review_status,reviewer_id,review_note,source_verified,retrieval_verified,created_at)
          VALUES (?,?,'REVIEWED',NULL,'后台文档级审核通过',1,1,?)
          """, id(), fragmentId, Timestamp.from(now));
    }
    jdbc.update("UPDATE knowledge_document SET reviewed_at=?,updated_at=? WHERE id=?",
        Timestamp.from(now), Timestamp.from(now), documentId);
    return listKnowledge().stream().filter(item -> item.id().equals(documentId))
        .findFirst().orElseThrow(this::notFound);
  }

  public List<KnowledgeFragmentDto> listKnowledgeFragments(long documentId) {
    return jdbc.query("""
        SELECT f.id,f.fragment_no,f.fragment_key,f.content,f.status,f.index_status,f.quality_status,
               f.content_kind,f.source_pages,f.embedding_model,f.embedding_version,
               f.embedding_dimension,f.qdrant_point_id,f.embedded_at
          FROM knowledge_fragment f
          JOIN knowledge_document d ON d.id=f.document_id AND d.deleted=0
         WHERE f.document_id=? AND d.content_domain='AI_ASSISTANT' AND f.deleted=0
         ORDER BY f.fragment_no
        """, (rs, row) -> new KnowledgeFragmentDto(
        rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
        rs.getString(11), rs.getObject(12, Integer.class), rs.getString(13),
        rs.getTimestamp(14) == null ? null : rs.getTimestamp(14).toInstant()), documentId);
  }

  private KnowledgeDocumentDto setKnowledgeStatus(long documentId, String status) {
    if ("PUBLISHED".equals(status)) {
      Integer pending = jdbc.queryForObject("""
          SELECT COUNT(*) FROM knowledge_fragment f
          JOIN knowledge_document d ON d.id=f.document_id
          WHERE f.document_id=? AND d.content_domain='AI_ASSISTANT' AND f.deleted=0
            AND (f.index_status<>'INDEXED' OR f.quality_status<>'APPROVED')
          """, Integer.class, documentId);
      if (pending == null || pending > 0) {
        throw new BusinessException("AI_KNOWLEDGE_NOT_READY",
            "全部切片完成向量化并审核通过后才能发布", HttpStatus.CONFLICT);
      }
    }
    Instant now = Instant.now();
    int updated = jdbc.update("""
        UPDATE knowledge_document SET status=?,published_at=IF(?='PUBLISHED',?,published_at),
          invalidated_at=IF(?='OFFLINE',?,invalidated_at),updated_at=?
        WHERE id=? AND content_domain='AI_ASSISTANT' AND deleted=0
        """, status, status, Timestamp.from(now), status, Timestamp.from(now), Timestamp.from(now), documentId);
    if (updated == 0) throw notFound();
    jdbc.update("UPDATE knowledge_fragment SET status=?,updated_at=? WHERE document_id=? AND deleted=0",
        status, Timestamp.from(now), documentId);
    return listKnowledge().stream().filter(item -> item.id().equals(documentId)).findFirst().orElseThrow(this::notFound);
  }

  private SessionDto session(long userId, long sessionId) {
    try {
      return jdbc.queryForObject("""
          SELECT id,qualification_id,subject_id,question_id,title,updated_at FROM ai_assistant_session
          WHERE id=? AND user_id=? AND deleted=0 AND status='ACTIVE'
            AND updated_at >= ?
          """, (rs, row) -> new SessionDto(rs.getLong(1), nullableLong(rs, 2), nullableLong(rs, 3),
          nullableLong(rs, 4), rs.getString(5), rs.getTimestamp(6).toInstant()), sessionId, userId,
          Timestamp.from(Instant.now().minusSeconds(properties.conversationRetentionDays() * 86400L)));
    } catch (EmptyResultDataAccessException exception) { throw notFound(); }
  }

  private Object[] appendRetentionArgs(List<Object> args) {
    return args.toArray();
  }

  private Long[] questionScope(long questionId) {
    try {
      return jdbc.queryForObject("""
          SELECT qualification_id,subject_id FROM q_question WHERE id=? AND status='PUBLISHED' AND deleted=0
          """, (rs, row) -> new Long[] {rs.getLong(1), rs.getLong(2)}, questionId);
    } catch (EmptyResultDataAccessException exception) {
      throw new BusinessException("AI_ASSISTANT_QUESTION_NOT_AVAILABLE", "当前题目不可用于答疑", HttpStatus.NOT_FOUND);
    }
  }

  private long saveMessage(long sessionId, String role, String content, String status, String cacheKey, String model,
                           String scenario, boolean cacheHit, Instant now) {
    long messageId = id();
    jdbc.update("""
        INSERT INTO ai_assistant_message
          (id,session_id,role_code,content,answer_status,cache_key,cache_hit,model_code,prompt_version,scenario,created_at)
        VALUES (?,?,?,?,?,?,?,?,'v1',?,?)
        """, messageId, sessionId, role, content, status, cacheKey, cacheHit ? 1 : 0, model, scenario, Timestamp.from(now));
    return messageId;
  }

  private List<CitationDto> questionEvidence(Long questionId) {
    if (questionId == null) return List.of();
    return jdbc.query("""
        SELECT content FROM q_solution WHERE question_id=? AND deleted=0 AND solution_type='STANDARD'
        ORDER BY version_no DESC LIMIT 1
        """, (rs, row) -> new CitationDto(null, "题库解析", excerpt(rs.getString(1))), questionId);
  }

  private List<CitationDto> citations(long messageId) {
    return jdbc.query("""
        SELECT f.id,d.title,f.content,f.fragment_key,f.content_kind,f.source_pages
        FROM ai_assistant_citation c
        JOIN knowledge_fragment f ON f.id=c.fragment_id
        JOIN knowledge_document d ON d.id=f.document_id
        WHERE c.assistant_message_id=?
        ORDER BY c.rank_no
        """, (rs, row) -> new CitationDto(rs.getLong(1), rs.getString(2), excerpt(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getString(6)), messageId);
  }

  private List<CitationDto> knowledgeEvidence(List<Evidence> results) {
    return results.stream()
        .map(result -> publishedCitation(result.fragmentKey()))
        .flatMap(Optional::stream)
        .toList();
  }

  private Optional<CitationDto> publishedCitation(String fragmentKey) {
    List<CitationDto> rows = jdbc.query("""
        SELECT f.id,d.title,f.content,f.fragment_key,f.content_kind,f.source_pages
        FROM knowledge_fragment f JOIN knowledge_document d ON d.id=f.document_id
        WHERE f.fragment_key=? AND d.content_domain='AI_ASSISTANT' AND d.status='PUBLISHED'
          AND f.status='PUBLISHED' AND f.index_status='INDEXED' AND f.invalidated_at IS NULL
          AND d.invalidated_at IS NULL
        """, (rs, row) -> new CitationDto(rs.getLong(1), rs.getString(2), excerpt(rs.getString(3)),
            rs.getString(4), rs.getString(5), rs.getString(6)), fragmentKey);
    return rows.stream().findFirst();
  }

  private String cachedAnswer(String cacheKey) {
    List<String> rows = jdbc.query("""
        SELECT content FROM ai_assistant_message WHERE cache_key=? AND answer_status='ANSWERED'
        ORDER BY created_at DESC LIMIT 1
        """, (rs, row) -> rs.getString(1), cacheKey);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private String stripInternalSourceReferences(String content) {
    if (content == null) return null;
    return content.replaceAll("[（(]?教材页码[:：][^）)\\n]*[）)]?", "")
        .replaceAll("(?m)^\\s*教材依据\\s*\\n(?:\\s*[-：:].*\\n?)+", "")
        .replace("给定知识片段", "")
        .replace("知识片段", "")
        .trim();
  }

  private String conversationContext(long sessionId) {
    List<String> rows = jdbc.query("""
        SELECT role_code,content FROM ai_assistant_message
        WHERE session_id=? ORDER BY created_at DESC,id DESC LIMIT 6
        """, (rs, row) -> rs.getString(1) + ": " + excerpt(rs.getString(2)), sessionId);
    java.util.Collections.reverse(rows);
    return String.join("\n", rows);
  }

  private String attachmentText(long userId, long sessionId, long attachmentId) {
    List<String> rows = jdbc.query("""
        SELECT ocr_text FROM ai_assistant_attachment a JOIN ai_assistant_session s ON s.id=a.session_id
        WHERE a.id=? AND a.session_id=? AND s.user_id=? AND a.ocr_status='CONFIRMED'
        """, (rs, row) -> rs.getString(1), attachmentId, sessionId, userId);
    if (rows.isEmpty()) throw bad("请先确认题图 OCR 结果");
    return rows.get(0);
  }

  private long attachmentSession(long attachmentId) {
    try { return jdbc.queryForObject("SELECT session_id FROM ai_assistant_attachment WHERE id=?", Long.class, attachmentId); }
    catch (EmptyResultDataAccessException exception) { throw notFound(); }
  }

  private void writeCallLog(long messageId, long userId, String scenario, boolean success, String provider, String model,
                            long durationMs, String errorCode) {
    jdbc.update("""
        INSERT INTO ai_assistant_call_log (id,assistant_message_id,user_id,scenario,provider_code,model_code,success,duration_ms,error_code,created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?)
        """, id(), messageId, userId, scenario, provider, model, success ? 1 : 0,
        Math.toIntExact(Math.min(durationMs, Integer.MAX_VALUE)), errorCode, Timestamp.from(Instant.now()));
  }

  private void validateImage(Path path) throws IOException {
    BufferedImage image = ImageIO.read(path.toFile());
    if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0
        || (long) image.getWidth() * image.getHeight() > properties.maxImagePixels()) {
      Files.deleteIfExists(path);
      throw new IOException("invalid image");
    }
  }

  static String scenario(String question, Long questionId) {
    return scenario(question, questionId, false);
  }

  static String scenario(String question, Long questionId, boolean hasAttachment) {
    String text = question.toLowerCase(Locale.ROOT);
    if (questionId == null && !hasAttachment && isCasualConversation(text)) return "CASUAL_CHAT";
    if (text.contains("对比") || text.contains("比较") || text.contains("区别")
        || text.contains("差异") || text.contains("不同") || text.contains("分别")) {
      return "CONCEPT_COMPARISON";
    }
    if (text.contains("计算") || text.contains("求") || text.contains("多少")
        || text.contains("如何算") || text.contains("掩码")
        || text.matches("(?s).*\\d+\\s*[+*/=\\-].*")) {
      return "CALCULATION_STEP_BY_STEP";
    }
    return questionId != null ? "QUESTION_EXPLANATION" : "GENERAL_QA";
  }

  private static boolean isCasualConversation(String text) {
    String clean = text.replaceAll("[\\s，。！？!?,.、]+", "");
    if (clean.length() > 24) return false;
    return clean.matches(".*(你好|您好|嗨|哈喽|早上好|晚上好|谢谢|感谢|辛苦了|再见|拜拜|晚安|你是谁|你能帮我吗|没事了).*");
  }

  static String promptFor(String scenario, String question) {
    if ("CASUAL_CHAT".equals(scenario)) {
      return "请用自然、简短、友好的方式直接回复，不要输出结论、判断依据、易错点、分析过程或题目解析格式：\n" + question;
    }
    if ("GENERAL_QA".equals(scenario)) {
      return "请直接回答用户当前问题，并在必要时补充简短解释或例子。"
          + "如果提供了知识库证据，只能使用与问题直接相关的证据；证据无关或为空时不得套用既有答案模板，"
          + "应明确说明知识库未覆盖，再基于通用知识简要回答。不要输出内部思考过程：\n" + question;
    }
    return switch (scenario) {
      case "CONCEPT_COMPARISON" -> "请按定义、适用场景、关键区别、常见混淆和答题判断方法回答：\n" + question;
      case "CALCULATION_STEP_BY_STEP" -> "请按已知条件、公式依据、逐步计算、结果和易错点回答；条件不足时要求补充，不得编造数字：\n" + question;
      default -> "请结合题目上下文，简洁解释结论、判断依据和易错点：\n" + question;
    };
  }

  private static String groundedFallback(String question, List<CitationDto> evidence, String scenario) {
    String prefix = "CALCULATION_STEP_BY_STEP".equals(scenario) ? "请根据以下条件逐步核对：\n" : "可以先这样理解：\n";
    return prefix + evidence.get(0).excerpt() + "\n\n这与您的问题有关：" + question;
  }

  /**
   * Keeps Markdown structure in the retrieval text. Length is only a guard for
   * oversized paragraphs or sentences, rather than the primary segmentation rule.
   */
  static String cleanKnowledgeContent(String content) {
    String clean = content.replace("\uFEFF", "")
        .replace("\u00A0", " ")
        .replace("\r\n", "\n")
        .replace('\r', '\n');
    List<String> lines = new ArrayList<>();
    boolean previousBlank = false;
    for (String rawLine : clean.split("\\n", -1)) {
      String line = rawLine
          .replaceAll("[\\t\\x0B\\f]+", " ")
          .replaceAll(" {2,}", " ")
          .stripTrailing();
      boolean blankLine = line.isBlank();
      if (blankLine && previousBlank) continue;
      lines.add(blankLine ? "" : line);
      previousBlank = blankLine;
    }
    return String.join("\n", lines).trim();
  }

  static List<String> split(String content) {
    String clean = content.replace("\r", "").trim();
    if (clean.isEmpty()) return List.of();

    List<String> fragments = new ArrayList<>();
    List<String> headings = new ArrayList<>();
    List<String> block = new ArrayList<>();
    String[] lines = clean.split("\\n", -1);

    for (String line : lines) {
      java.util.regex.Matcher heading = java.util.regex.Pattern
          .compile("^(#{1,6})\\s+(.+?)\\s*$").matcher(line);
      if (heading.matches()) {
        appendBlock(fragments, headings, block);
        block.clear();
        int level = heading.group(1).length();
        while (headings.size() >= level) headings.remove(headings.size() - 1);
        headings.add(heading.group(2).replaceAll("\\s+#+\\s*$", "").trim());
      } else if (line.isBlank()) {
        appendBlock(fragments, headings, block);
        block.clear();
      } else {
        block.add(line);
      }
    }
    appendBlock(fragments, headings, block);
    return fragments;
  }

  private static void appendBlock(List<String> fragments, List<String> headings, List<String> block) {
    String text = String.join("\n", block).trim();
    if (text.isEmpty()) return;
    String context = headings.isEmpty() ? text : String.join(" > ", headings) + "\n\n" + text;
    fragments.addAll(splitLongText(context));
  }

  private static List<String> splitLongText(String text) {
    if (text.length() <= MAX_FRAGMENT_CHARS) return List.of(text);
    List<String> fragments = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String sentence : text.split("(?<=[。！？!?；;.!])\\s*")) {
      if (sentence.length() > MAX_FRAGMENT_CHARS) {
        flush(fragments, current);
        for (int index = 0; index < sentence.length(); index += MAX_FRAGMENT_CHARS) {
          fragments.add(sentence.substring(index, Math.min(sentence.length(), index + MAX_FRAGMENT_CHARS)).trim());
        }
      } else if (current.length() > 0 && current.length() + sentence.length() > MAX_FRAGMENT_CHARS) {
        flush(fragments, current);
        current.append(sentence);
      } else {
        current.append(sentence);
      }
    }
    flush(fragments, current);
    return fragments.stream().filter(item -> !item.isBlank()).toList();
  }

  private static void flush(List<String> fragments, StringBuilder current) {
    if (current.length() > 0) {
      fragments.add(current.toString().trim());
      current.setLength(0);
    }
  }

  private static String detectType(String value) {
    String text = value.toUpperCase(Locale.ROOT);
    if (text.contains("计算") || text.matches("(?s).*\\d+\\s*[+*/=].*")) return "CALCULATION";
    if (text.contains("单选")) return "SINGLE_CHOICE";
    if (text.contains("多选")) return "MULTIPLE_CHOICE";
    long optionLabels = text.lines()
        .map(String::trim)
        .filter(line -> line.matches("^[A-D](?:[.、．:：]|\\s|$).*"))
        .count();
    if (optionLabels >= 2) return "SINGLE_CHOICE";
    if (text.contains("案例")) return "CASE";
    if (text.contains("论文")) return "ESSAY_WRITING";
    return "SHORT_ANSWER";
  }

  private static String normalizeQuestion(String value) { return value.replaceAll("\\s+", " ").trim(); }
  private static String excerpt(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 500)); }
  private static String required(String value, String message) { if (value == null || value.trim().isEmpty()) throw bad(message); return value.trim(); }
  private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private static String safeName(String name) { return name == null ? "image" : name.replaceAll("[\\\\/]", "_"); }
  private static long id() { return IDS.incrementAndGet(); }
  private static Long nullableLong(java.sql.ResultSet rs, int index) throws java.sql.SQLException { long value = rs.getLong(index); return rs.wasNull() ? null : value; }
  private static BusinessException bad(String message) { return new BusinessException("AI_ASSISTANT_VALIDATION", message, HttpStatus.BAD_REQUEST); }
  private BusinessException notFound() { return new BusinessException("AI_ASSISTANT_NOT_FOUND", "答疑资源不存在", HttpStatus.NOT_FOUND); }
  private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
  private static String sha256(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception exception) { throw new IllegalStateException(exception); } }
}
