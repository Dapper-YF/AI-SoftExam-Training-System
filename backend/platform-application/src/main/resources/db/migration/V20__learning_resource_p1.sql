-- P1 learning resources: logical resources, immutable content versions,
-- chapter navigation, qualification-scoped knowledge points and graph edges.

CREATE TABLE learning_resource (
  id BIGINT NOT NULL AUTO_INCREMENT,
  qualification_id BIGINT NOT NULL,
  resource_code VARCHAR(120) NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  title VARCHAR(300) NOT NULL,
  summary VARCHAR(1000) NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  copyright_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
  current_version_id BIGINT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  row_version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_learning_resource_code UNIQUE (qualification_id, resource_code),
  CONSTRAINT fk_learning_resource_qualification
    FOREIGN KEY (qualification_id) REFERENCES exam_qualification (id),
  CONSTRAINT ck_learning_resource_type CHECK (resource_type IN ('OFFICIAL_TUTORIAL', 'COURSE_HANDOUT', 'MIND_MAP', 'QUICK_REVIEW', 'ESSAY_MATERIAL')),
  CONSTRAINT ck_learning_resource_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
  CONSTRAINT ck_learning_resource_copyright CHECK (copyright_status IN ('UNVERIFIED', 'AUTHORIZED', 'INTERNAL_ONLY', 'RESTRICTED')),
  CONSTRAINT ck_learning_resource_deleted CHECK (deleted IN (0, 1)),
  KEY idx_learning_resource_listing (qualification_id, resource_type, status, deleted, sort_order, id)
);

CREATE TABLE learning_resource_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  resource_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  version_label VARCHAR(120) NULL,
  source_name VARCHAR(300) NULL,
  source_ref VARCHAR(700) NULL,
  file_url VARCHAR(1000) NOT NULL,
  file_name VARCHAR(300) NOT NULL,
  mime_type VARCHAR(120) NOT NULL,
  file_size BIGINT NOT NULL,
  file_sha256 CHAR(64) NOT NULL,
  page_count INT NULL,
  cover_url VARCHAR(1000) NULL,
  extracted_text_status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  usage_restriction VARCHAR(255) NOT NULL DEFAULT 'INTERNAL_ONLY',
  created_by BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_learning_resource_version_no UNIQUE (resource_id, version_no),
  CONSTRAINT uk_learning_resource_version_sha256 UNIQUE (resource_id, file_sha256),
  CONSTRAINT ck_learning_resource_version_text_status CHECK (extracted_text_status IN ('NOT_STARTED', 'PROCESSING', 'READY', 'FAILED')),
  CONSTRAINT ck_learning_resource_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
  CONSTRAINT ck_learning_resource_version_deleted CHECK (deleted IN (0, 1)),
  CONSTRAINT ck_learning_resource_version_file_size CHECK (file_size > 0),
  CONSTRAINT ck_learning_resource_version_page_count CHECK (page_count IS NULL OR page_count > 0),
  KEY idx_learning_resource_version_status (resource_id, status, deleted, version_no)
);

ALTER TABLE learning_resource
  ADD CONSTRAINT fk_learning_resource_current_version
  FOREIGN KEY (current_version_id) REFERENCES learning_resource_version (id);

ALTER TABLE learning_resource_version
  ADD CONSTRAINT fk_learning_resource_version_resource
  FOREIGN KEY (resource_id) REFERENCES learning_resource (id);

CREATE TABLE learning_resource_chapter (
  id BIGINT NOT NULL AUTO_INCREMENT,
  version_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  chapter_code VARCHAR(120) NOT NULL,
  chapter_title VARCHAR(300) NOT NULL,
  level_no INT NOT NULL DEFAULT 1,
  page_no INT NOT NULL,
  end_page_no INT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_learning_resource_chapter_code UNIQUE (version_id, chapter_code),
  CONSTRAINT ck_learning_resource_chapter_level CHECK (level_no > 0),
  CONSTRAINT ck_learning_resource_chapter_page CHECK (page_no > 0 AND (end_page_no IS NULL OR end_page_no >= page_no)),
  CONSTRAINT ck_learning_resource_chapter_status CHECK (status IN ('ENABLED', 'DISABLED')),
  CONSTRAINT ck_learning_resource_chapter_deleted CHECK (deleted IN (0, 1)),
  KEY idx_learning_resource_chapter_tree (version_id, parent_id, sort_order, id),
  CONSTRAINT fk_learning_resource_chapter_version
    FOREIGN KEY (version_id) REFERENCES learning_resource_version (id),
  CONSTRAINT fk_learning_resource_chapter_parent
    FOREIGN KEY (parent_id) REFERENCES learning_resource_chapter (id)
);

CREATE TABLE learning_knowledge_point (
  id BIGINT NOT NULL AUTO_INCREMENT,
  qualification_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  point_code VARCHAR(120) NOT NULL,
  point_name VARCHAR(300) NOT NULL,
  description VARCHAR(2000) NULL,
  level_no INT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_learning_knowledge_point_code UNIQUE (qualification_id, point_code),
  CONSTRAINT ck_learning_knowledge_point_level CHECK (level_no > 0),
  CONSTRAINT ck_learning_knowledge_point_status CHECK (status IN ('ENABLED', 'DISABLED')),
  CONSTRAINT ck_learning_knowledge_point_deleted CHECK (deleted IN (0, 1)),
  KEY idx_learning_knowledge_point_tree (qualification_id, parent_id, sort_order, id),
  CONSTRAINT fk_learning_knowledge_point_qualification
    FOREIGN KEY (qualification_id) REFERENCES exam_qualification (id),
  CONSTRAINT fk_learning_knowledge_point_parent
    FOREIGN KEY (parent_id) REFERENCES learning_knowledge_point (id)
);

CREATE TABLE learning_chapter_knowledge_point (
  chapter_id BIGINT NOT NULL,
  knowledge_point_id BIGINT NOT NULL,
  relation_type VARCHAR(24) NOT NULL DEFAULT 'COVERS',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (chapter_id, knowledge_point_id),
  CONSTRAINT ck_learning_chapter_knowledge_relation CHECK (relation_type IN ('COVERS', 'EMPHASIZES', 'EXAMPLE')),
  CONSTRAINT fk_learning_chapter_knowledge_chapter
    FOREIGN KEY (chapter_id) REFERENCES learning_resource_chapter (id),
  CONSTRAINT fk_learning_chapter_knowledge_point
    FOREIGN KEY (knowledge_point_id) REFERENCES learning_knowledge_point (id)
);

CREATE TABLE learning_knowledge_point_edge (
  id BIGINT NOT NULL AUTO_INCREMENT,
  qualification_id BIGINT NOT NULL,
  source_point_id BIGINT NOT NULL,
  target_point_id BIGINT NOT NULL,
  relation_type VARCHAR(24) NOT NULL,
  created_by BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_learning_knowledge_edge UNIQUE (source_point_id, target_point_id, relation_type),
  CONSTRAINT ck_learning_knowledge_edge_not_self CHECK (source_point_id <> target_point_id),
  CONSTRAINT ck_learning_knowledge_edge_type CHECK (relation_type IN ('PREREQUISITE', 'RELATED', 'CONTAINS')),
  CONSTRAINT fk_learning_knowledge_edge_qualification
    FOREIGN KEY (qualification_id) REFERENCES exam_qualification (id),
  CONSTRAINT fk_learning_knowledge_edge_source
    FOREIGN KEY (source_point_id) REFERENCES learning_knowledge_point (id),
  CONSTRAINT fk_learning_knowledge_edge_target
    FOREIGN KEY (target_point_id) REFERENCES learning_knowledge_point (id),
  KEY idx_learning_knowledge_edge_graph (qualification_id, source_point_id, relation_type, target_point_id)
);
