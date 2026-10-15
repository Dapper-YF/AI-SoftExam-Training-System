# Local PaddleOCR Gateway

This service is used by the Spring Boot AI assistant image OCR flow.

## Start

```powershell
$py = "C:\Users\Dapper\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
& $py scripts/ocr/paddle_ocr_server.py
```

The service listens on `http://127.0.0.1:18080` by default. The first OCR request downloads the PaddleOCR models if they are not cached.

Configure the backend in the same PowerShell session:

```powershell
$env:OCR_ENDPOINT = "http://127.0.0.1:18080/ocr"
$env:OCR_TOKEN = ""
```

The request contract is compatible with `OcrClient`:

```json
{"imageBase64":"...","contentType":"image/jpeg"}
```

The response contains `text`, plus an average recognition `confidence` for diagnostics.

## Batch PDF OCR

```powershell
$py = "C:\Users\Dapper\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
& $py scripts/ocr/ocr_pdfs_to_markdown.py `
  --source "docs/原始资料/知识库资料/【论文资料】-论文基础-张立台-2026.pdf" `
  --source "docs/原始资料/知识库资料/0502 信息系统项目管理师（第四版）.pdf"
```

The output is written under `knowledge-base/sources/ocr/` as one Markdown document per PDF, with page headings and OCR confidence values. A `.md.partial` file is used until the PDF finishes so an interrupted run is distinguishable from a complete result.
