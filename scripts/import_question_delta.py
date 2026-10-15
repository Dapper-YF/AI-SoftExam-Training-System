from __future__ import annotations

import argparse
import json
import uuid

import requests


BASE = __import__("os").environ.get("IMPORT_BASE", "http://127.0.0.1:8000/api/v1")


def login(identifier: str) -> requests.Session:
    session = requests.Session()
    response = session.post(BASE + "/auth/login", json={"identifier": identifier, "password": "123456"})
    if response.status_code >= 400:
        print(response.status_code, response.text)
    response.raise_for_status()
    session.get(BASE + "/auth/csrf").raise_for_status()
    access = session.cookies.get("AIE_ACCESS")
    csrf = session.cookies.get("XSRF-TOKEN")
    origin = BASE.removesuffix("/api/v1")
    session.headers.update({
        "Cookie": f"AIE_ACCESS={access}; XSRF-TOKEN={csrf}",
        "X-XSRF-TOKEN": csrf,
        "Origin": origin,
    })
    return session


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", required=True)
    args = parser.parse_args()
    admin = login("admin")
    mutation = str(uuid.uuid4())
    with open(args.file, "rb") as handle:
        response = admin.post(
            BASE + "/admin/question-imports/preflight",
            data={"schemaVersion": "question-import/1.0", "clientMutationId": mutation},
            files={"file": ("question-delta.jsonl", handle, "application/x-ndjson")},
        )
    if response.status_code >= 400:
        print(response.status_code, response.text)
    response.raise_for_status()
    preflight = response.json()["data"]
    print(json.dumps({"phase": "preflight", "status": preflight.get("status"),
                      "eligible": preflight.get("eligibleCount"),
                      "total": preflight.get("totalCount"),
                      "token": preflight.get("token")}, ensure_ascii=False))
    if preflight.get("status") != "READY_TO_CONFIRM":
        raise RuntimeError("preflight did not produce READY_TO_CONFIRM")

    token = preflight["token"]
    reviewer = login("question_reviewer")
    confirm = admin.post(
        f"{BASE}/admin/question-imports/{token}/confirm",
        json={"clientMutationId": str(uuid.uuid4())},
    )
    if confirm.status_code >= 400:
        print(confirm.status_code, confirm.text)
    confirm.raise_for_status()
    confirmed = confirm.json()["data"]
    print(json.dumps({"phase": "confirm", "batchId": confirmed.get("batchId"),
                      "imported": confirmed.get("importedCount"),
                      "skipped": confirmed.get("skippedCount")}, ensure_ascii=False))

    batch_id = confirmed["batchId"]
    publish = reviewer.post(
        f"{BASE}/admin/question-imports/{batch_id}/publish",
        json={"clientMutationId": str(uuid.uuid4())},
    )
    publish.raise_for_status()
    print(json.dumps({"phase": "publish", "batchId": batch_id,
                      "published": publish.json()["data"].get("publishedCount")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
