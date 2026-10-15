ALTER TABLE portal_content
  ADD COLUMN is_pinned TINYINT NOT NULL DEFAULT 0 AFTER status,
  ADD COLUMN pin_order INT NOT NULL DEFAULT 0 AFTER is_pinned,
  ADD KEY idx_portal_content_pinned (status, is_pinned, pin_order, published_at, id);
