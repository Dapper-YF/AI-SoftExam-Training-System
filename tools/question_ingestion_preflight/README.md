# Question Ingestion Preflight

Offline, repeatable preflight tooling for question source documents.

This tool does not write the production database, does not publish content, and
does not modify source files. It reads XLSX, PDF, DOCX, and detects whether
legacy DOC files can be converted. Dry-run output is written to a caller-provided
directory as:

- `canonical-questions.jsonl`
- `manifest.json`
- `errors.csv`
- `duplicates.csv`
- `assets.json`
- `quality-report.md`
- `unresolved-items.csv`

Every emitted question record uses:

- `schemaVersion=question-import/1.0`
- `rightsStatus=UNVERIFIED`
- `importStatus=PREFLIGHT`

Mixed source files are fail-closed unless the tool can build an auditable
`splitPlan` from page/title anchors. Current split rules support:

- Database engineer mixed AM/PM sources: `COMPREHENSIVE_KNOWLEDGE` then
  `APPLIED_TECHNOLOGY`.
- Advanced PM mixed mock sources: `COMPREHENSIVE_KNOWLEDGE`,
  `CASE_ANALYSIS`, then `ESSAY`.

When anchors are incomplete, `manifest.json` records `FAILED_CLOSED`,
`detectedAnchors`, `requiredRules`, and `missingRules`; no mixed-scope
canonical records are emitted.

Run:

```powershell
python tools/question_ingestion_preflight/preflight.py `
  --input "E:\Study\AI高项培训系统\docs\原始资料\题库\2004-2021年数据库真题及答案解析" `
  --input "E:\Study\AI高项培训系统\docs\原始资料\题库\2026年信息系统项目管理师一模试卷-知乎知学堂（有答案）.pdf" `
  --output "C:\tmp\question-ingestion-preflight-20260717"
```

The output directory must be new or empty. The tool refuses to overwrite an
existing evidence bundle.

Generate the independent structure report for the row-oriented source XLSX:

```powershell
python tools/question_ingestion_preflight/preflight.py `
  --input "E:\Study\AI高项培训系统\docs\原始资料\题库\soft_exam_final_20260716_094114.xlsx" `
  --output "C:\tmp\question-xlsx-structure-20260717" `
  --xlsx-structure-only
```

Test:

```powershell
python -m pytest tools/question_ingestion_preflight/tests
```
