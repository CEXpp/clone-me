#!/usr/bin/env python3
"""使用 OSV 官方 API 核对发布依赖；未知严重度保守阻断，网络失败不冒充扫描通过。"""
import argparse
import json
from pathlib import Path
import urllib.request


def request(url, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as response:
        raw = response.read(8_000_001)
    if len(raw) > 8_000_000:
        raise ValueError("Vulnerability response is too large")
    return json.loads(raw)


def scan(sbom):
    packages = [c for c in sbom.get("components", []) if c.get("purl", "").startswith("pkg:maven/")]
    if not packages:
        raise ValueError("No Maven components found in SBOM")
    findings = []
    for offset in range(0, len(packages), 100):
        chunk = packages[offset:offset + 100]
        results = request("https://api.osv.dev/v1/querybatch", {"queries": [{"package": {"purl": p["purl"]}} for p in chunk]})["results"]
        if len(results) != len(chunk):
            raise ValueError("Vulnerability response does not cover all packages")
        for component, result in zip(chunk, results):
            if result.get("next_page_token"):
                raise ValueError("Paginated vulnerability result requires manual review")
            for item in result.get("vulns", []):
                vulnerability = request("https://api.osv.dev/v1/vulns/" + item["id"])
                severity = vulnerability.get("database_specific", {}).get("severity", "UNKNOWN").upper()
                findings.append({"component": component["purl"], "id": item["id"], "severity": severity,
                                 "summary": vulnerability.get("summary", "")})
    return {"source": "https://api.osv.dev", "packagesChecked": len(packages), "findings": findings}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("sbom", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = scan(json.loads(args.sbom.read_text(encoding="utf-8")))
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    blocked = [v for v in result["findings"] if v["severity"] not in {"LOW", "MODERATE", "MEDIUM"}]
    print(f'Checked {result["packagesChecked"]} Maven components; {len(blocked)} high, critical or unknown-severity findings')
    raise SystemExit(1 if blocked else 0)


if __name__ == "__main__":
    main()
