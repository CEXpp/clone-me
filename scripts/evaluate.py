#!/usr/bin/env python3
"""验证评测集；仅显式 --run 时调用访客 API，写操作从不自动重试。报告需人工复核。"""
import argparse
import http.cookiejar
import json
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


def load_cases(path):
    cases = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    ids = set()
    for case in cases:
        if not isinstance(case.get("id"), str) or case["id"] in ids or case.get("split") not in {"tuning", "holdout"}:
            raise ValueError("Each case needs a unique id and tuning/holdout split")
        if not isinstance(case.get("question"), str) or not 1 <= len(case["question"]) <= 2000:
            raise ValueError("Each question must contain 1-2000 characters")
        for field in ("expectedFacts", "forbiddenFacts", "expectedSourceIds"):
            if not isinstance(case.get(field), list) or not all(isinstance(value, str) for value in case[field]):
                raise ValueError(f"Invalid {field}")
        ids.add(case["id"])
    return cases


def evaluate(base, cases, output):
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}):
        raise ValueError("Use HTTPS, or a local HTTP fixture server")
    report = {"baseUrl": base, "startedAt": time.time(), "cases": []}
    for case in cases:
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

        def request(path, payload=None):
            headers = {}
            data = None
            if payload is not None:
                csrf = json.load(opener.open(base + "/api/auth/csrf", timeout=10))
                headers = {"Content-Type": "application/json", csrf["headerName"]: csrf["token"]}
                data = json.dumps(payload).encode()
            return json.load(opener.open(urllib.request.Request(base + path, data=data, headers=headers), timeout=90))

        entry = {"id": case["id"], "split": case["split"], "question": case["question"], "manualVerdict": None}
        started = time.monotonic()
        try:
            request("/api/public/session", {})
            answer = request("/api/public/chat", {"message": case["question"], "requestKey": str(uuid.uuid4())})
            sources = {item["id"] for item in answer.get("sources", [])}
            entry.update({"actual": answer, "latencyMs": round((time.monotonic() - started) * 1000),
                          "forbiddenLiteralMatches": [text for text in case["forbiddenFacts"] if text in answer.get("answer", "")],
                          "missingExpectedSources": [value for value in case["expectedSourceIds"] if value not in sources],
                          "expectedFactsForManualReview": case["expectedFacts"]})
        except Exception as error:
            entry["errorType"] = type(error).__name__
            entry["note"] = "No automatic retry; inspect history and billing before repeating this case"
            report["cases"].append(entry)
            output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            raise
        report["cases"].append(entry)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        # 保持低于创建会话的分钟限额；实际模型调用可能已占用部分时间。
        time.sleep(max(0, 7 - (time.monotonic() - started)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--run", action="store_true", help="Explicitly authorize potentially paid API calls")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--output", type=Path, default=Path(".cache/evaluation.json"))
    args = parser.parse_args()
    cases = load_cases(args.cases)
    print(f"Validated {len(cases)} cases; {sum(c['split']=='holdout' for c in cases)} held out")
    if not args.run:
        print("Validation only. Add --run after configuring the confirmed data and budget.")
        return
    args.output.parent.mkdir(parents=True, exist_ok=True)
    evaluate(args.base_url.rstrip("/"), cases, args.output)


if __name__ == "__main__":
    main()
