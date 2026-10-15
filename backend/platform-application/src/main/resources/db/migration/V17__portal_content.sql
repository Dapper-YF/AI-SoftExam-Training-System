CREATE TABLE portal_content (
  id BIGINT NOT NULL,
  source_name VARCHAR(100) NOT NULL,
  source_url VARCHAR(700) NOT NULL,
  title VARCHAR(500) NOT NULL,
  summary VARCHAR(1000) NOT NULL,
  published_at TIMESTAMP(3) NOT NULL,
  category VARCHAR(20) NOT NULL,
  content_type VARCHAR(20) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  first_seen_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_portal_content_source_url UNIQUE (source_url),
  CONSTRAINT ck_portal_content_category CHECK (category IN ('REGISTRATION', 'POLICY', 'OFFICIAL_RESOURCE')),
  CONSTRAINT ck_portal_content_type CHECK (content_type IN ('NEWS', 'RESOURCE')),
  CONSTRAINT ck_portal_content_status CHECK (status = 'PUBLISHED')
);

CREATE INDEX idx_portal_content_listing
  ON portal_content (content_type, category, status, published_at DESC, id DESC);

CREATE TABLE portal_crawl_run (
  id BIGINT NOT NULL,
  started_at TIMESTAMP(3) NOT NULL,
  finished_at TIMESTAMP(3),
  success BOOLEAN NOT NULL,
  fetched_count INT NOT NULL,
  created_count INT NOT NULL,
  updated_count INT NOT NULL,
  error_summary VARCHAR(1000),
  PRIMARY KEY (id)
);

CREATE INDEX idx_portal_crawl_run_started_at ON portal_crawl_run (started_at DESC, id DESC);
