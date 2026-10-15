UPDATE exam_qualification
SET level_code = CASE level_code
  WHEN 'LOW' THEN 'PRIMARY'
  WHEN 'MIDDLE' THEN 'INTERMEDIATE'
  WHEN 'HIGH' THEN 'ADVANCED'
  ELSE level_code
END;

UPDATE exam_subject
SET subject_type = CASE subject_type
  WHEN 'KNOWLEDGE' THEN 'COMPREHENSIVE'
  ELSE subject_type
END;
