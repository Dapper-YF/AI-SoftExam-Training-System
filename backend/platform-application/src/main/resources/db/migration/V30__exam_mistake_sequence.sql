INSERT IGNORE INTO app_id_sequence (entity_name, next_id)
SELECT 'practice_mistake', COALESCE(MAX(id), 0) + 1 FROM practice_mistake;
