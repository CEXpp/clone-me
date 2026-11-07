#!/usr/bin/env python3
"""笔记 RAG 离线评测入口：默认校验；--run 显式运行；--demo 不联网。"""
import argparse
from contextlib import closing
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import uuid

from rag_eval import (VARIANTS, attach_reviews, compare_reports, digest, load_cases,
                      make_report, markdown, require, review_template)

ROOT = Path(__file__).resolve().parents[1]


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def file_hash(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def environment(path, offline=False):
    env = dict(os.environ)
    if offline:
        return {key: value for key, value in env.items() if not re.match(
            r"^(CLONE_|DEEPSEEK_|SILICONFLOW_|RERANK_|TYPESAFE_|DASHSCOPE_|BAILIAN_|TURNSTILE_|SPRING_|DECISION_PROVIDER$|PORT$)", key)}
    if path and path.is_file():
        for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            match = re.fullmatch(r"\s*([A-Z][A-Z0-9_]*)\s*=(.*)", line)
            require(match is not None, f"Invalid .env assignment on line {number}")
            key, value = match.groups()
            require(re.match(r"^(CLONE_|DEEPSEEK_|SILICONFLOW_|RERANK_|TYPESAFE_|DASHSCOPE_|BAILIAN_|TURNSTILE_|SPRING_MAIL_|DECISION_PROVIDER$|PORT$)", key), f"Unsupported environment key: {key}")
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            env.setdefault(key, value)
    return env


def source_database(args, env):
    if args.snapshot:
        return args.snapshot.resolve()
    data = Path(args.data_dir or env.get("CLONE_DATA_DIR", "./data"))
    # 与 dev.ps1 的 Java 工作目录一致；命令行 --data-dir 则相对当前目录。
    if not data.is_absolute():
        data = (Path.cwd() if args.data_dir else ROOT / "backend") / data
    return data.resolve() / "app.db"


def snapshot(source, workspace):
    require(source.is_file(), "Source app.db does not exist; use --data-dir, --snapshot, or --demo")
    # 防止把评测输出放到业务数据目录内，或意外覆盖输入快照。
    require(source.parent not in workspace.parents and source.parent != workspace,
            "Evaluation output must be outside the source data directory")
    target = workspace / "app.db"
    with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as original:
        with closing(sqlite3.connect(target)) as copied:
            original.backup(copied)
            tables = {row[0] for row in copied.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            # 原始数据不写入；只清理副本。访客数据库不复制，附件也不复制。
            for table in ("admin_sessions", "recovery_codes", "admin_account", "rate_limits",
                          "ai_calls", "budget_days", "notification_events", "diagnostic_events", "jobs"):
                if table in tables:
                    copied.execute("DELETE FROM " + table)
            if "settings" in tables:
                copied.execute("DELETE FROM settings WHERE key='ai.total-limit-micros'")
            copied.commit()
            copied.execute("VACUUM")
            copied.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            copied.execute("PRAGMA journal_mode=DELETE")
    return target


def catalog(database, include_private):
    with closing(sqlite3.connect(database)) as sql:
        sql.row_factory = sqlite3.Row
        where = "" if include_private else " WHERE n.knowledge=1 AND n.deleted=0"
        rows = sql.execute("SELECT n.id,n.revision,n.title,n.plain_text,n.knowledge,n.deleted,n.indexed_revision,n.index_error,s.name AS section,b.name AS notebook FROM notes n JOIN note_sections s ON s.id=n.section_id JOIN notebooks b ON b.id=s.notebook_id" + where + " ORDER BY n.id")
        return [dict(row) for row in rows]


def ledger_usage(database):
    """含初始化、已完成题和中途终止的调用，避免部分报告漏掉实际费用。"""
    try:
        with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as sql:
            sql.row_factory = sqlite3.Row
            rows = sql.execute("SELECT stage,provider,model,status,SUM(COALESCE(actual,0)) AS actualMicros,SUM(CASE WHEN status!='SETTLED' THEN reserved ELSE 0 END) AS uncertainMicros,SUM(COALESCE(duration_ms,0)) AS durationMs,COUNT(*) AS calls FROM ai_calls GROUP BY stage,provider,model,status")
            return [dict(row) for row in rows]
    except sqlite3.Error:
        return None


def new_output(path):
    if path is None:
        path = ROOT / ".cache" / "evaluations" / (datetime.now().strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6])
    path = path.resolve()
    path.mkdir(parents=True, exist_ok=False)
    (path / ".gitignore").write_text("*\n", encoding="utf-8")
    return path


def report_files(output, meta, cases, results, reviews=None):
    report = make_report(meta, cases, results, reviews)
    write_json(output / "report.json", report)
    (output / "report.md").write_text(markdown(report), encoding="utf-8")
    template = [review_template(case, result) for result in results
                if not result.get("error") and result.get("answer", {}).get("status") == "ANSWERED"
                for case in cases if case["id"] == result["id"]]
    (output / "reviews.template.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in template), encoding="utf-8")
    write_json(output / "reviews.template.json", template)
    print(f"Report: {output / 'report.md'}")
    print(f"Completed: {report['integrity']['complete']}; blocking issues: {len(report['integrity']['issues'])}")
    return report


def summarize(args):
    original = json.loads(args.summarize.read_text(encoding="utf-8"))
    cases = json.loads((args.summarize.parent / "cases.json").read_text(encoding="utf-8"))
    output = new_output(args.output)
    write_json(output / "cases.json", cases)
    meta = dict(original["meta"], sourceReport=str(args.summarize.resolve()))
    report = report_files(output, meta, cases, original["entries"], args.reviews)
    return 0 if not report["integrity"]["issues"] else 3


def comparison(args):
    reports = [json.loads(path.read_text(encoding="utf-8")) for path in args.compare]
    result = compare_reports(*reports)
    failures = []
    for variant, metrics in result["comparisons"].items():
        for name in args.gate_metric:
            require(name in metrics, f"Missing gate metric: {variant}/{name}")
            if metrics[name]["delta"] < -args.max_drop:
                failures.append(f"{variant}/{name} dropped more than {args.max_drop}")
    result["regressions"] = failures
    output = new_output(args.output)
    write_json(output / "comparison.json", result)
    lines = ["# 配对评测对比", "", "delta = 新版 - 基线；耗时、费用与错误率越低越好。", "",
             "| 方案 | 指标 | 平均变化 | 题数 | 上升/下降/相同 |", "|---|---|---:|---:|---|"]
    for variant, metrics in result["comparisons"].items():
        for name, value in metrics.items():
            lines.append(f"| {variant} | {name} | {value['delta']:+.4f} | {value['n']} | {value['increased']}/{value['decreased']}/{value['unchanged']} |")
    lines += ["", "## 门槛检查", ""] + ["- " + item for item in failures]
    lines += ["", "小样本变化不代表统计显著；请查看失败题和人工评分。", ""]
    (output / "comparison.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"Comparison: {output / 'comparison.md'}")
    return 3 if failures or any(report["integrity"]["issues"] for report in reports) else 0


def execute(args, cases):
    variants = args.variants.split(",") if args.variants else (["lexical"] if args.demo else ["full", "no-rerank"])
    require(len(set(variants)) == len(variants) and set(variants) <= VARIANTS, "Variants: lexical,no-rerank,full; no duplicates")
    require(not args.demo or (variants == ["lexical"] and args.mode == "retrieval"), "Demo is lexical/retrieval only and never calls models")
    env = environment(args.env_file, offline=args.demo)
    require(args.jar.is_file(), "Build the backend JAR first (mvn -f backend/pom.xml verify)")
    output = new_output(args.output)
    workspace = output / "workspace"
    workspace.mkdir()
    (workspace / ".evaluation-workspace").write_text("clone-me-evaluation-v1", encoding="utf-8")
    if not args.demo:
        snapshot(source_database(args, env), workspace)
    selected = [case for case in cases if args.split == "all" or case["split"] == args.split]
    if args.limit is not None:
        selected = selected[:args.limit]
    require(selected, "No cases in the selected split")
    config = {"workspace": str(workspace), "maxCostCny": str(args.max_cost_cny), "variants": variants,
              "mode": args.mode, "cases": selected, "demo": args.demo, "reindex": args.reindex}
    if args.demo:
        config["seedNotes"] = json.loads((ROOT / "eval" / "demo-notes.json").read_text(encoding="utf-8"))
    write_json(output / "run.json", config)
    write_json(output / "cases.json", selected)
    meta = {"startedAt": datetime.now(timezone.utc).isoformat(), "datasetHash": digest(cases), "caseSetHash": digest(selected),
            "mode": args.mode, "variants": variants, "split": args.split, "demo": args.demo,
            "jarHash": file_hash(args.jar), "maxCostCny": str(args.max_cost_cny)}
    command = ["java", "--enable-native-access=ALL-UNNAMED", "-Xmx512m", "-jar", str(args.jar.resolve()),
               "--evaluate-rag", str(output / "run.json")]
    with (output / "runner.log").open("w", encoding="utf-8") as log:
        try:
            completed = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
            returncode = completed.returncode
        except subprocess.TimeoutExpired:
            returncode = 124
    manifest = output / "manifest.json"
    meta["manifest"] = json.loads(manifest.read_text(encoding="utf-8")) if manifest.exists() else {}
    meta["runnerExitCode"] = returncode
    meta["ledgerUsage"] = ledger_usage(workspace / "app.db")
    results_file = output / "results.jsonl"
    results = []
    if results_file.exists():
        for line in results_file.read_text(encoding="utf-8").splitlines():
            try:
                results.append(json.loads(line))
            except json.JSONDecodeError:
                require(returncode != 0, "Corrupted result row from completed runner")
    report = report_files(output, meta, selected, results, args.reviews)
    if returncode:
        print(f"Runner failed ({returncode}); inspect {output / 'runner.log'}. No automatic retry.", file=sys.stderr)
    return 3 if returncode or report["integrity"]["issues"] else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path)
    parser.add_argument("--run", action="store_true", help="Run on an isolated snapshot; may make billed model calls")
    parser.add_argument("--demo", action="store_true", help="Offline fictional notebook evaluation; never uses private data or model calls")
    parser.add_argument("--catalog", action="store_true", help="Export source note IDs/revisions/text for labeling")
    parser.add_argument("--include-private", action="store_true", help="Include private/deleted notes in the local catalog")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--data-dir")
    source.add_argument("--snapshot", type=Path)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--mode", choices=["retrieval", "answer"], default="retrieval")
    parser.add_argument("--variants", help="Comma-separated lexical,no-rerank,full")
    parser.add_argument("--split", choices=["tuning", "holdout", "all"], default="tuning")
    parser.add_argument("--limit", type=int, help="Evaluate only the first N cases in the selected split")
    parser.add_argument("--max-cost-cny", type=Decimal, default=Decimal("0.50"))
    parser.add_argument("--reindex", action="store_true", help="Explicitly rebuild only the copied index; may call the embedding model")
    parser.add_argument("--jar", type=Path, default=ROOT / "backend/target/clone-me-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--timeout", type=int, default=3600)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--summarize", type=Path, help="Rebuild a report locally; no model calls")
    parser.add_argument("--reviews", type=Path)
    parser.add_argument("--compare", nargs=2, type=Path, metavar=("BASELINE", "CANDIDATE"))
    parser.add_argument("--gate-metric", action="append", default=[], help="Higher-is-better metric for paired comparison")
    parser.add_argument("--max-drop", type=float, default=0.0)
    args = parser.parse_args()
    require(sum(bool(v) for v in (args.compare, args.summarize, args.catalog, args.demo, args.run)) <= 1,
            "Choose one action: run, demo, catalog, summarize or compare")
    require(args.max_cost_cny.is_finite() and 0 <= args.max_cost_cny <= 100, "max-cost-cny must be between 0 and 100")
    require(1 <= args.timeout <= 86400 and 0 <= args.max_drop <= 1, "Invalid timeout/max-drop")
    require(args.limit is None or 1 <= args.limit <= 500, "limit must be between 1 and 500")
    if args.compare:
        return comparison(args)
    if args.summarize:
        return summarize(args)
    if args.catalog:
        env = environment(args.env_file)
        output = new_output(args.output)
        workspace = output / "workspace"
        workspace.mkdir()
        db = snapshot(source_database(args, env), workspace)
        write_json(output / "catalog.json", catalog(db, args.include_private))
        print(f"Local catalog: {output / 'catalog.json'}")
        return 0
    if args.demo:
        args.cases = ROOT / "eval/cases.example.jsonl"
        args.split = "all"
        args.max_cost_cny = Decimal("0")
    require(args.cases is not None, "Specify --cases, --demo, --catalog, --summarize or --compare")
    cases = load_cases(args.cases)
    print(f"Validated {len(cases)} cases ({sum(c['split'] == 'holdout' for c in cases)} holdout)")
    if not args.run and not args.demo:
        print("Validation only; add --run to execute on a database snapshot.")
        return 0
    return execute(args, cases)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, sqlite3.Error, KeyError, TypeError, InvalidOperation) as error:
        print(f"Evaluation error: {error}", file=sys.stderr)
        raise SystemExit(2)
