ALTER TABLE portal_content
  ADD COLUMN original_title VARCHAR(500) AFTER summary,
  ADD COLUMN original_summary VARCHAR(1000) AFTER original_title,
  ADD COLUMN source_published_at TIMESTAMP(3) AFTER published_at,
  ADD COLUMN previous_content_hash CHAR(64) AFTER content_hash,
  ADD COLUMN review_category VARCHAR(20) AFTER category,
  ADD COLUMN review_content_type VARCHAR(20) AFTER content_type,
  ADD COLUMN last_crawled_at TIMESTAMP(3) AFTER first_seen_at,
  ADD COLUMN change_summary VARCHAR(1000) AFTER last_crawled_at;

UPDATE portal_content
SET original_title = title,
    original_summary = summary,
    source_published_at = published_at,
    review_category = category,
    review_content_type = content_type,
    last_crawled_at = updated_at,
    change_summary = NULL;

ALTER TABLE portal_content
  MODIFY COLUMN original_title VARCHAR(500) NOT NULL,
  MODIFY COLUMN original_summary VARCHAR(1000) NOT NULL,
  MODIFY COLUMN source_published_at TIMESTAMP(3) NOT NULL,
  MODIFY COLUMN review_category VARCHAR(20) NOT NULL,
  MODIFY COLUMN review_content_type VARCHAR(20) NOT NULL,
  MODIFY COLUMN last_crawled_at TIMESTAMP(3) NOT NULL,
  DROP CHECK ck_portal_content_status,
  ADD CONSTRAINT ck_portal_content_review_category CHECK (review_category IN ('REGISTRATION', 'POLICY', 'OFFICIAL_RESOURCE')),
  ADD CONSTRAINT ck_portal_content_review_type CHECK (review_content_type IN ('NEWS', 'RESOURCE')),
  ADD CONSTRAINT ck_portal_content_status CHECK (status IN ('PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'OFFLINE'));
