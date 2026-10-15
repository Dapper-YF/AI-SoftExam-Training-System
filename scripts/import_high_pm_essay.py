import json
import uuid

import requests

BASE = "http://127.0.0.1:8000/api/v1"


def login(identifier: str) -> requests.Session:
    session = requests.Session()
    response = session.post(BASE + "/auth/login", json={"identifier": identifier, "password": "123456"})
    if response.status_code >= 400:
        print(response.status_code, response.text)
        response.raise_for_status()
    session.get(BASE + "/auth/csrf").raise_for_status()
    access = session.cookies.get("AIE_ACCESS")
    csrf = session.cookies.get("XSRF-TOKEN")
    session.headers.update({
        "Cookie": f"AIE_ACCESS={access}; XSRF-TOKEN={csrf}",
        "X-XSRF-TOKEN": csrf,
        "Origin": "http://127.0.0.1:8000",
    })
    return session


def main() -> None:
    admin = login("admin")
    existing = admin.get(BASE + "/admin/questions", params={
        "qualificationId": 1, "subjectId": 3, "questionType": "ESSAY", "pageNo": 1, "pageSize": 20,
    })
    existing.raise_for_status()
    items = existing.json()["data"]["items"]
    if items:
        print(json.dumps({"status": "already-present", "items": items}, ensure_ascii=False))
        return

    payload = {
        "qualificationId": 1,
        "subjectId": 3,
        "questionType": "ESSAY",
        "title": "2025年5月信息系统项目管理师论文题（第2批次回忆版）",
        "yearNo": 2025,
        "difficulty": "MEDIUM",
        "sourceType": "MANUAL",
        "sourceRef": "user-provided://codex-clipboard-24f53583-75b2-4c50-83f1-d41fa09e3dd4.png",
        "stems": [{
            "stemType": "ESSAY_REQUIREMENT",
            "stemKey": "E1",
            "content": "请以“论信息系统项目的不确定性绩效域”为题进行论述。\n\n1. 概要叙述你参与管理过的信息系统项目（项目背景、规模、目的、内容、组织结构、周期、成果等），并说明你承担的工作。\n\n2. 结合项目，论述不确定性绩效域可以帮助实现的项目目标、与其他7个绩效域的关系，以及项目面临的不确定性和应对方法。",
            "sortOrder": 1,
        }],
        "subQuestions": [{
            "subQuestionNo": 1,
            "subQuestionType": "ESSAY_WRITING",
            "content": "请在答题区完成论文，要求结合本人真实项目经历。",
            "score": 75,
            "answerFormat": "TEXT",
            "gradingMode": "MANUAL",
            "sortOrder": 1,
            "metadataJson": '{"maxWords":6000,"durationMinutes":120}',
            "options": [],
            "answerKeys": [{"answerFormat": "TEXT", "answerValue": "A complete essay grounded in the candidate's real project and covering all three requirements.", "answerJson": None}],
            "solutions": [{"solutionType": "STANDARD", "content": "主观论文题，人工评分应检查项目真实性、三项论述要求和应对方法的针对性。", "sortOrder": 1}],
            "scoringRules": [{"ruleType": "MANUAL", "ruleJson": '{"mode":"MANUAL","tolerance":null,"caseSensitive":null}', "sortOrder": 1}],
            "scoringPoints": [
                {"pointCode": "P1", "pointText": "项目背景和个人职责真实完整。", "score": 25, "sortOrder": 1},
                {"pointCode": "P2", "pointText": "说明项目目标及与其他绩效域的关系。", "score": 25, "sortOrder": 2},
                {"pointCode": "P3", "pointText": "识别不确定性并提出针对性应对方法。", "score": 25, "sortOrder": 3},
            ],
        }],
        "knowledgeBindings": [],
    }
    response = admin.post(BASE + "/admin/questions", json={
        "clientMutationId": str(uuid.uuid4()),
        "baseVersionId": None,
        "payload": payload,
        "copyrightStatus": "UNVERIFIED",
        "usageRestriction": "internal development and manual review only; do not publish as official paper",
    })
    if response.status_code >= 400:
        print(response.status_code, response.text)
        response.raise_for_status()
    created = response.json()["data"]
    question_id = created["questionId"]
    version_id = created["questionVersionId"]
    row_version = created.get("rowVersion", 0)

    response = admin.post(f"{BASE}/admin/questions/{question_id}/versions/{version_id}/submit-review", json={
        "clientMutationId": str(uuid.uuid4()), "expectedRowVersion": row_version,
        "reason": "user-provided recall question imported for internal development",
    })
    response.raise_for_status()
    row_version = response.json()["data"].get("rowVersion", row_version)

    reviewer = login("question_reviewer")
    response = reviewer.post(f"{BASE}/admin/questions/{question_id}/versions/{version_id}/review", json={
        "clientMutationId": str(uuid.uuid4()), "expectedRowVersion": row_version,
        "decision": "APPROVED", "reason": "Reviewed for internal development use",
    })
    response.raise_for_status()
    row_version = response.json()["data"].get("rowVersion", row_version)
    response = reviewer.post(f"{BASE}/admin/questions/{question_id}/versions/{version_id}/publish", json={
        "clientMutationId": str(uuid.uuid4()), "expectedRowVersion": row_version,
        "reason": "Publish reviewed internal question",
    })
    response.raise_for_status()
    print(json.dumps({"status": "published", "questionId": question_id, "versionId": version_id}, ensure_ascii=False))


if __name__ == "__main__":
    main()
