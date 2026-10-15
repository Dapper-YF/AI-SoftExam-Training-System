import { ApiClientError } from '@/api/client'

const codeMessages: Record<string, string> = {
  QUESTION_ADMIN_FORBIDDEN: '当前账号无权执行此题库操作',
  QUESTION_ADMIN_VALIDATION_ERROR: '题目内容不符合要求，请检查必填项和题型规则',
  QUESTION_ADMIN_CONFLICT: '题目状态或版本已变化，请刷新后重试',
  QUESTION_REVIEW_SELF_FORBIDDEN: '禁止审核自己提交的题目',
  QUESTION_SELF_REVIEW_FORBIDDEN: '禁止审核自己提交的题目，请切换审核账号',
  QUESTION_SELF_PUBLISH_FORBIDDEN: '禁止发布自己提交的题目，请切换发布账号',
  QUESTION_IMPORT_TYPE_COMBINATION_INVALID: '题型与题目结构不匹配',
  QUESTION_IMPORT_FIELD_INVALID: '导入字段格式不正确',
  QUESTION_IMPORT_STEM_REQUIRED: '题干或案例材料不能为空',
  QUESTION_IMPORT_OPTIONS_INVALID: '选项不完整或顺序不连续',
  QUESTION_IMPORT_OPTION_REFERENCE_INVALID: '正确答案必须引用本题的有效选项',
  QUESTION_IMPORT_ANSWER_FORMAT_MISMATCH: '答案格式与题型不匹配',
  QUESTION_IMPORT_ANSWER_KIND_MISMATCH: '答案内容与题型不匹配',
  QUESTION_IMPORT_SCORING_MODE_MISMATCH: '评分方式与题型不匹配',
  QUESTION_IMPORT_SCORE_CLOSURE_INVALID: '评分点合计必须等于题目分值',
  QUESTION_IMPORT_SORT_ORDER_INVALID: '题目、选项或评分点顺序必须从 1 连续编号',
  QUESTION_IMPORT_KNOWLEDGE_MAPPING_REQUIRED: '知识点或标签必须使用有效平台 ID',
  QUESTION_IMPORT_FILE_EMPTY: '导入文件不能为空',
  QUESTION_DOCUMENT_FILE_INVALID: '试卷文件格式不正确：请确认扩展名与实际格式一致；RTF 请先另存为 .docx，加密 PDF 请先移除打开密码',
  QUESTION_DOCUMENT_PARSE_FAILED: '试卷文件解析失败，请确认文件未损坏后重试',
  QUESTION_IMAGE_ROOT_NOT_CONFIGURED: '题卷图片存储目录未配置，暂时无法解析含图片的 Word 或扫描 PDF',
  QUESTION_IMPORT_FILE_SIZE_INVALID: '导入文件不能超过 10 MiB',
  QUESTION_IMPORT_FILE_TYPE_INVALID: '仅支持 question-import/1.0 JSON 或九 Sheet XLSX 文件',
  QUESTION_IMPORT_FILE_MIME_INVALID: '文件 MIME 类型与扩展名不一致',
  QUESTION_IMPORT_FILE_SIGNATURE_INVALID: '文件内容与 JSON/XLSX 格式不一致',
  QUESTION_IMPORT_SCHEMA_VERSION_INVALID: '导入文件必须使用 question-import/1.0',
  QUESTION_IMPORT_UPLOAD_NOT_FOUND: '预检文件已失效，请重新选择文件',
  QUESTION_IMPORT_NO_ELIGIBLE_ROWS: '没有可导入的题目，请先修正失败明细',
  QUESTION_IMPORT_BATCH_NOT_FOUND: '导入批次不存在或已清理',
  QUESTION_IMPORT_ROLLBACK_CONFLICT: '该批次当前不能回滚，请刷新批次状态',
}

const statusMessages: Record<number, string> = {
  400: '提交内容不符合题型规则，请检查后重试',
  401: '登录状态已失效，请重新登录',
  403: '当前账号无权执行此操作',
  404: '题目或导入批次不存在',
  409: '数据版本或工作流状态已变化，请刷新后重试',
  413: '文件过大，请选择不超过 10MB 的文件',
  415: '文件格式不受支持，请选择 JSON 或 XLSX 文件',
  422: '提交内容校验失败，请检查标记字段',
  429: '操作过于频繁，请稍后重试',
  500: '题库服务暂时不可用，请稍后重试',
  502: '题库服务暂时不可用，请稍后重试',
  503: '题库服务暂时不可用，请稍后重试',
  504: '题库服务响应超时，请稍后重试',
}

export function resolveAdminQuestionError(error: unknown, fallback = '操作失败，请稍后重试'): string {
  if (error instanceof ApiClientError) {
    if (error.code && codeMessages[error.code]) return codeMessages[error.code]!
    if (error.status && statusMessages[error.status]) return statusMessages[error.status]!
  }
  return fallback
}

export function resolveImportItemError(code?: string | null): string {
  if (!code) return '校验未通过'
  return codeMessages[code] ?? '导入记录未通过校验'
}
