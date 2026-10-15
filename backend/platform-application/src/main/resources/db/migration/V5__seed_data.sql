INSERT INTO sys_role (id, role_code, role_name, status, created_at, updated_at, deleted)
VALUES
  (1, 'ADMIN', '管理员', 'ENABLED', NOW(3), NOW(3), 0),
  (2, 'STUDENT', '考生', 'ENABLED', NOW(3), NOW(3), 0);

INSERT INTO sys_feature_flag (id, feature_code, feature_name, module_code, enabled, created_at, updated_at, deleted)
VALUES
  (1, 'question-practice.enabled', '刷题备考系统', 'question-practice', 1, NOW(3), NOW(3), 0),
  (2, 'essay-training.enabled', 'AI论文系统', 'essay-training', 1, NOW(3), NOW(3), 0),
  (3, 'ai-capability.enabled', 'AI能力层', 'ai-capability', 1, NOW(3), NOW(3), 0);

INSERT INTO exam_qualification (id, qualification_code, qualification_name, level_code, status, sort_order, created_at, updated_at, deleted)
VALUES (1, 'HIGH_PM', '信息系统项目管理师', 'HIGH', 'ENABLED', 1, NOW(3), NOW(3), 0);

INSERT INTO exam_subject (id, qualification_id, subject_code, subject_name, subject_type, sort_order, status, created_at, updated_at, deleted)
VALUES
  (1, 1, 'COMPREHENSIVE', '综合知识', 'KNOWLEDGE', 1, 'ENABLED', NOW(3), NOW(3), 0),
  (2, 1, 'CASE_ANALYSIS', '案例分析', 'CASE', 2, 'ENABLED', NOW(3), NOW(3), 0),
  (3, 1, 'ESSAY', '论文', 'ESSAY', 3, 'ENABLED', NOW(3), NOW(3), 0);

INSERT INTO kp_syllabus_node (id, qualification_id, subject_id, parent_id, syllabus_version, node_code, node_name, level_no, sort_order, status, created_at, updated_at, deleted)
VALUES
  (20001, 1, 2, NULL, '2026', 'PM-SCHEDULE', '项目进度管理', 1, 1, 'ENABLED', NOW(3), NOW(3), 0),
  (20002, 1, 1, NULL, '2026', 'PM-SCOPE', '项目范围管理', 1, 2, 'ENABLED', NOW(3), NOW(3), 0);

INSERT INTO kp_tag (id, qualification_id, subject_id, tag_code, tag_name, tag_type, status, created_at, updated_at, deleted)
VALUES
  (30001, 1, 2, 'CASE-SHORT-ANSWER', '案例问答', 'CASE', 'ENABLED', NOW(3), NOW(3), 0),
  (30002, 1, 3, 'ESSAY-MATERIAL', '论文素材点', 'ESSAY_MATERIAL', 'ENABLED', NOW(3), NOW(3), 0);

INSERT INTO ai_model_config (id, model_code, provider_code, model_name, enabled, created_at, updated_at, deleted)
VALUES (1, 'mock-model', 'mock-provider', 'Mock Model', 1, NOW(3), NOW(3), 0);

INSERT INTO ai_prompt_template (id, scenario, template_code, template_version, template_content, enabled, created_at, updated_at, deleted)
VALUES
  (1, 'QUESTION_QA', 'question-qa', 'v1', '请基于题目内容解释知识点。', 1, NOW(3), NOW(3), 0),
  (2, 'ESSAY_REVIEW', 'essay-review', 'v1', '请按评分规则批改论文。', 1, NOW(3), NOW(3), 0);
