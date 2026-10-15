"""
通过 REST API 将 20 个软考资格的章节/考纲数据增量导入到运行中的后端。

═══════════════════════════════════════════════════════════
用法（开发环境 — 无需用户名密码）:
  python scripts/import_qualifications_api.py

  默认连接 http://localhost:8000，使用 Bearer dev-admin-token 认证。
  前提：后端以 dev 或 test profile 启动，且启用了 dev-bearer。

用法（自定义后端地址）:
  $env:API_BASE_URL="http://192.168.1.100:8000"
  python scripts/import_qualifications_api.py

用法（生产环境 — 用户名密码登录）:
  $env:BEARER_TOKEN=""
  $env:ADMIN_USERNAME="admin"
  $env:ADMIN_PASSWORD="yourpassword"
  python scripts/import_qualifications_api.py

预演（不实际调 API，仅打印请求内容）:
  $env:DRY_RUN="1"
  python scripts/import_qualifications_api.py

依赖: pip install requests
═══════════════════════════════════════════════════════════
"""

import json
import os
import sys
import time
from typing import Optional

import requests

# ── 配置 ──────────────────────────────────────────────

API_BASE = os.environ.get("API_BASE_URL", "http://localhost:8000")

# 开发环境：设置 BEARER_TOKEN=dev-admin-token 即可跳过登录
# 生产环境：设置 ADMIN_USERNAME + ADMIN_PASSWORD，脚本自动调用 /api/v1/auth/login
BEARER_TOKEN = os.environ.get("BEARER_TOKEN", "dev-admin-token")
ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME", "")
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "")

JSON_PATH = r"F:\网页下载2\miaoda-code-ruankao_20_qualifications.json.json"

DRY_RUN = os.environ.get("DRY_RUN", "0") == "1"
DELAY_SECONDS = float(os.environ.get("API_DELAY", "0.05"))  # 请求间隔

# ── 映射表 ──────────────────────────────────────────────

LEVEL_MAP = {"高级": "ADVANCED", "中级": "INTERMEDIATE", "初级": "PRIMARY"}

ADVANCED_SUBJECT_ORDER = {"综合知识": 1, "案例分析": 2, "论文": 3}
STANDARD_SUBJECT_ORDER = {"基础知识": 1, "应用技术": 2}

SUBJECT_CODE_MAP = {
    "综合知识": "COMPREHENSIVE_KNOWLEDGE",
    "案例分析": "CASE_ANALYSIS",
    "论文": "ESSAY",
    "基础知识": "BASIC_KNOWLEDGE",
    "应用技术": "APPLIED_TECHNOLOGY",
}

SUBJECT_TYPE_MAP = {
    "综合知识": "COMPREHENSIVE",
    "案例分析": "CASE",
    "论文": "ESSAY",
    "基础知识": "COMPREHENSIVE",
    "应用技术": "APPLICATION",
}

QUAL_SORT_ORDER = {
    "HIGH_PM": 10,
    "HIGH_SYSTEM_PLANNING_MANAGER": 20,
    "HIGH_SYSTEM_ANALYST": 30,
    "HIGH_SYSTEM_ARCHITECT": 40,
    "HIGH_NETWORK_PLANNER": 50,
    "MIDDLE_SOFTWARE_DESIGNER": 110,
    "MIDDLE_NETWORK_ENGINEER": 120,
    "MIDDLE_INFORMATION_SYSTEM_SUPERVISOR": 130,
    "MIDDLE_SYSTEM_INTEGRATION_PM": 140,
    "MIDDLE_EMBEDDED_SYSTEM_DESIGNER": 150,
    "MIDDLE_ECOMMERCE_DESIGNER": 160,
    "MIDDLE_DATABASE_SYSTEM_ENGINEER": 170,
    "MIDDLE_SOFTWARE_TESTER": 180,
    "MIDDLE_INFORMATION_SYSTEM_MANAGER": 190,
    "MIDDLE_INFORMATION_SECURITY_ENGINEER": 200,
    "MIDDLE_MULTIMEDIA_APPLICATION_DESIGNER": 210,
    "LOW_PROGRAMMER": 310,
    "LOW_NETWORK_ADMINISTRATOR": 320,
    "LOW_INFORMATION_SYSTEM_OPERATOR": 330,
    "LOW_INFORMATION_PROCESSING_TECHNICIAN": 340,
}


class ApiImporter:
    def __init__(self):
        self.session = requests.Session()
        self.token: Optional[str] = None
        self.qual_cache: dict[str, int] = {}   # qualification_code → DB id
        self.subj_cache: dict[tuple, int] = {}  # (qual_code, subject_name) → DB id
        self.stats = {"qualifications": 0, "subjects": 0, "chapters": 0, "syllabus": 0, "errors": 0}

    # ── 认证 ───────────────────────────────────────

    def login(self):
        """获取认证凭据。dev 环境用 Bearer token，否则用用户名密码登录。"""
        if BEARER_TOKEN:
            print(f"[auth] 使用 Bearer token 认证（跳过登录）")
            self.session.headers["Authorization"] = f"Bearer {BEARER_TOKEN}"
            return

        if not ADMIN_USERNAME or not ADMIN_PASSWORD:
            raise RuntimeError(
                "未配置认证凭据。请设置环境变量:\n"
                "  开发环境: BEARER_TOKEN=dev-admin-token\n"
                "  生产环境: ADMIN_USERNAME=xxx ADMIN_PASSWORD=xxx"
            )

        print(f"[auth] 登录 {API_BASE} (用户: {ADMIN_USERNAME})...")
        resp = self.session.post(f"{API_BASE}/api/v1/auth/login", json={
            "identifier": ADMIN_USERNAME,
            "password": ADMIN_PASSWORD,
        })
        if resp.status_code == 200:
            print("  登录成功")
            return

        raise RuntimeError(f"登录失败: {resp.status_code} {resp.text[:300]}")

    # ── HTTP 辅助 ──────────────────────────────────

    def _post(self, path: str, body: dict) -> dict:
        """POST 到 Admin API，返回响应 data。"""
        if DRY_RUN:
            print(f"  [DRY-RUN] POST {path}: {json.dumps(body, ensure_ascii=False)[:120]}")
            return {"id": -1}

        time.sleep(DELAY_SECONDS)
        resp = self.session.post(f"{API_BASE}{path}", json=body)
        if 200 <= resp.status_code < 300:
            payload = resp.json()
            # ApiResponse 包装: {"code":200, "data":{...}}
            data = payload.get("data", payload)
            return data
        else:
            self.stats["errors"] += 1
            msg = resp.text[:300]
            print(f"  ERROR {resp.status_code} POST {path}: {msg}", file=sys.stderr)
            raise RuntimeError(f"API error: {resp.status_code} {msg}")

    # ── 资格 ───────────────────────────────────────

    def import_qualification(self, q: dict) -> int:
        """导入一个资格，返回 DB id。若已存在则返回已有 id。"""
        code = q["code"]
        if code in self.qual_cache:
            return self.qual_cache[code]

        level = LEVEL_MAP[q["level"]]
        sort_order = QUAL_SORT_ORDER.get(code, 0)
        name = q["name"]

        body = {
            "qualificationCode": code,
            "domainCode": "SOFT_EXAM",
            "qualificationName": name,
            "officialName": name,
            "levelCode": level,
            "verificationStatus": "THIRD_PARTY_CANDIDATE",
            "catalogVersion": "2026",
            "catalogStatus": "ACTIVE",
            "status": "ENABLED",
            "sortOrder": sort_order,
        }

        try:
            data = self._post("/api/v1/admin/qualifications", body)
            qual_id = int(data.get("id", -1))
            self.qual_cache[code] = qual_id
            self.stats["qualifications"] += 1
            print(f"  [qual] {name} → id={qual_id}")
            return qual_id
        except RuntimeError:
            # 可能已存在，尝试查询
            print(f"  [qual] {name} 可能已存在，尝试查询...")
            resp = self.session.get(f"{API_BASE}/api/v1/foundation/qualifications?pageSize=50")
            items = resp.json().get("data", {}).get("items", [])
            for item in items:
                if item.get("qualificationCode") == code or item.get("code") == code:
                    qual_id = int(item["id"])
                    self.qual_cache[code] = qual_id
                    print(f"  [qual] {name} 已存在 → id={qual_id}")
                    return qual_id
            raise

    # ── 科目 ───────────────────────────────────────

    def import_subject(self, qual_code: str, qual_id: int, subject: dict) -> int:
        """导入一个科目，返回 DB id。"""
        name = subject["subjectName"]
        cache_key = (qual_code, name)
        if cache_key in self.subj_cache:
            return self.subj_cache[cache_key]

        subj_code = SUBJECT_CODE_MAP.get(name, name)
        subj_type = SUBJECT_TYPE_MAP.get(name, "COMPREHENSIVE")
        sort_order = ADVANCED_SUBJECT_ORDER.get(name) or STANDARD_SUBJECT_ORDER.get(name) or 0

        body = {
            "qualificationId": qual_id,
            "subjectCode": subj_code,
            "subjectName": name,
            "officialName": name,
            "subjectType": subj_type,
            "sortOrder": sort_order,
            "status": "ENABLED",
        }

        try:
            data = self._post("/api/v1/admin/subjects", body)
            subj_id = int(data.get("id", -1))
            self.subj_cache[cache_key] = subj_id
            self.stats["subjects"] += 1
            print(f"    [subject] {name} → id={subj_id}")
            return subj_id
        except RuntimeError:
            # 查询已存在的科目
            resp = self.session.get(
                f"{API_BASE}/api/v1/foundation/qualifications/{qual_id}/subjects?pageSize=20"
            )
            items = resp.json().get("data", {}).get("items", [])
            for item in items:
                if item.get("subjectName") == name or item.get("name") == name:
                    subj_id = int(item["id"])
                    self.subj_cache[cache_key] = subj_id
                    print(f"    [subject] {name} 已存在 → id={subj_id}")
                    return subj_id
            raise

    # ── 树节点（章节/考纲） ────────────────────────

    def import_tree_nodes(
        self,
        nodes: list,
        qual_id: int,
        subj_id: int,
        qual_code: str,
        kind: str,  # "chapter" | "syllabus"
        parent_id: Optional[int] = None,
    ):
        """递归导入树节点。先创建当前节点，再递归子节点。"""
        for idx, node in enumerate(nodes):
            code = node["code"]
            name = node["name"]
            level_no = node["levelNo"]
            sort_order = idx + 1
            children = node.get("children", [])

            if kind == "chapter":
                body = {
                    "qualificationId": qual_id,
                    "subjectId": subj_id,
                    "parentId": parent_id,
                    "bookVersion": "2026",
                    "chapterCode": code,
                    "chapterName": name,
                    "levelNo": level_no,
                    "sortOrder": sort_order,
                    "status": "ENABLED",
                }
                path = "/api/v1/admin/chapter-nodes"
            else:
                body = {
                    "qualificationId": qual_id,
                    "subjectId": subj_id,
                    "parentId": parent_id,
                    "syllabusVersion": "2026",
                    "nodeCode": code,
                    "nodeName": name,
                    "levelNo": level_no,
                    "sortOrder": sort_order,
                    "status": "ENABLED",
                }
                path = "/api/v1/admin/syllabus-nodes"

            try:
                data = self._post(path, body)
                node_id = int(data.get("id", -1))
                self.stats["chapters" if kind == "chapter" else "syllabus"] += 1
            except RuntimeError:
                # 静默跳过已存在的节点（依赖数据库 UNIQUE 约束）
                print(f"      [{kind}] {code} {name} 跳过（可能已存在）", file=sys.stderr)
                node_id = None

            if children and node_id:
                self.import_tree_nodes(children, qual_id, subj_id, qual_code, kind, parent_id=node_id)

    # ── 主流程 ─────────────────────────────────────

    def run(self):
        with open(JSON_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)

        qualifications = data["qualifications"]

        if DRY_RUN:
            print(f"\n[DRY RUN] 跳过登录与健康检查，模拟导入 {len(qualifications)} 个资格...\n")
        else:
            # 健康检查
            try:
                health = self.session.get(f"{API_BASE}/api/v1/health", timeout=5)
                if health.status_code == 200:
                    print(f"[health] {API_BASE} 服务正常")
                else:
                    print(f"[health] 警告: {API_BASE} 返回 {health.status_code}", file=sys.stderr)
            except Exception as e:
                print(f"[health] 无法连接 {API_BASE}: {e}", file=sys.stderr)
                raise RuntimeError(f"后端不可达: {API_BASE}") from e

            self.login()
            print(f"\n开始导入 {len(qualifications)} 个资格...\n")

        for q in qualifications:
            code = q["code"]
            name = q["name"]
            print(f"--- {code} {name} ---")

            try:
                qual_id = self.import_qualification(q)
            except Exception as e:
                print(f"  跳过资格 {name}: {e}", file=sys.stderr)
                continue

            for subject in q["subjects"]:
                subj_name = subject["subjectName"]
                try:
                    subj_id = self.import_subject(code, qual_id, subject)
                except Exception as e:
                    print(f"  跳过科目 {subj_name}: {e}", file=sys.stderr)
                    continue

                # 导入章节
                chapters = subject.get("chapters", [])
                if chapters:
                    print(f"    [chapter] 导入 {subj_name} 章节树...")
                    self.import_tree_nodes(chapters, qual_id, subj_id, code, "chapter")

                # 导入考纲
                syllabus = subject.get("syllabus", [])
                if syllabus:
                    print(f"    [syllabus] 导入 {subj_name} 考纲树...")
                    self.import_tree_nodes(syllabus, qual_id, subj_id, code, "syllabus")

        # ── 报告 ─────────────────────────────────
        print(f"\n{'='*50}")
        print(f"导入完成！统计:")
        print(f"  资格:     {self.stats['qualifications']}")
        print(f"  科目:     {self.stats['subjects']}")
        print(f"  章节节点: {self.stats['chapters']}")
        print(f"  考纲节点: {self.stats['syllabus']}")
        print(f"  错误:     {self.stats['errors']}")
        if DRY_RUN:
            print("  (DRY RUN — 未实际调用 API)")


if __name__ == "__main__":
    importer = ApiImporter()
    importer.run()
