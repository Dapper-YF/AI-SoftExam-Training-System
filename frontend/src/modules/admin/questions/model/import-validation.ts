const MAX_IMPORT_BYTES = 10 * 1024 * 1024
const JSON_MIMES = new Set(['', 'application/json', 'text/json', 'application/octet-stream'])
const XLSX_MIMES = new Set(['', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'application/octet-stream'])

function readAsText(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error ?? new Error('file read failed'))
    reader.readAsText(file, 'utf-8')
  })
}

function readAsArrayBuffer(file: Blob): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => reader.result instanceof ArrayBuffer ? resolve(reader.result) : reject(new Error('file read failed'))
    reader.onerror = () => reject(reader.error ?? new Error('file read failed'))
    reader.readAsArrayBuffer(file)
  })
}

export async function validateQuestionImportFile(file: File): Promise<string | null> {
  if (file.size <= 0) return '导入文件不能为空'
  if (file.size > MAX_IMPORT_BYTES) return '导入文件不能超过 10MB'
  const name = file.name.toLowerCase()
  if (name.endsWith('.json')) {
    if (!JSON_MIMES.has(file.type.toLowerCase())) return 'JSON 文件的 MIME 类型不正确'
    try {
      const value = JSON.parse(await readAsText(file)) as unknown
      if (typeof value !== 'object' || value === null || Array.isArray(value)) return 'JSON 顶层必须是对象'
      const document = value as { schemaVersion?: unknown, questions?: unknown }
      if (document.schemaVersion !== 'question-import/1.0') return 'JSON schemaVersion 必须为 question-import/1.0'
      if (!Array.isArray(document.questions)) return 'JSON questions 必须是数组'
    } catch {
      return 'JSON 文件无法解析，请检查编码和语法'
    }
    return null
  }
  if (name.endsWith('.xlsx')) {
    if (!XLSX_MIMES.has(file.type.toLowerCase())) return 'XLSX 文件的 MIME 类型不正确'
    const signature = new Uint8Array(await readAsArrayBuffer(file.slice(0, 4)))
    if (signature.length < 4 || signature[0] !== 0x50 || signature[1] !== 0x4b || signature[2] !== 0x03 || signature[3] !== 0x04) {
      return 'XLSX 文件签名不正确'
    }
    return null
  }
  return '仅支持 question-import/1.0 JSON 或 Excel .xlsx 文件'
}
