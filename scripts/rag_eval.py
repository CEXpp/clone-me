"""评测数据与确定性指标。只依赖 Python 标准库，不调用模型。"""
import hashlib
import json
import math
import re
import statistics
import unicodedata
from pathlib import Path

VARIANTS = {"lexical", "no-rerank", "full"}
REFUSALS = {"INSUFFICIENT_EVIDENCE", "ANSWER_WITHHELD", "SOURCE_WITHDRAWN"}


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def normalize(value):
    return "".join(unicodedata.normalize("NFC", value).split())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def text(value, maximum=2000, blank=False):
    return (isinstance(value, str) and (blank or bool(value.strip()))
            and len(value.encode("utf-16-le")) // 2 <= maximum
            and not any((ord(c) < 32 or 127 <= ord(c) <= 159) and c not in "\n\r\t" for c in value))


def strings(value, maximum=32):
    return isinstance(value, list) and len(value) <= maximum and all(text(v) for v in value)


def load_cases(path):
    require(path.stat().st_size <= 10_000_000, "Dataset exceeds 10MB")
    cases = [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    require(0 < len(cases) <= 500, "Expected 1-500 cases")
    seen, groups = set(), {}
    for case in cases:
        require(isinstance(case, dict) and case.get("schemaVersion") == 2, "Use schemaVersion: 2 (note evidence, not chunk IDs)")
        identity = case.get("id")
        require(isinstance(identity, str) and re.fullmatch(r"[a-zA-Z0-9_.-]{1,80}", identity)
                and identity not in seen, "Case id must be unique and contain 1-80 ASCII letters/digits/_.-")
        seen.add(identity)
        require(text(case.get("group"), 80) and case.get("split") in {"tuning", "holdout"}, f"{identity}: group/split required")
        group, split = case["group"], case["split"]
        require(groups.setdefault(group, split) == split, f"{identity}: paraphrases from one group cannot cross tuning/holdout")
        require(text(case.get("question")) and type(case.get("answerable")) is bool, f"{identity}: question/answerable required")
        require(strings(case.get("expectedFacts")), f"{identity}: expectedFacts must be a string array")
        evidence = case.get("evidence")
        require(isinstance(evidence, list) and len(evidence) <= 16, f"{identity}: evidence must be a list of required fact groups")
        require(bool(evidence) == case["answerable"], f"{identity}: answerable requires evidence; unanswerable requires []")
        require(bool(case["expectedFacts"]) == case["answerable"], f"{identity}: answerable requires expectedFacts; unanswerable requires []")
        for alternatives in evidence:
            require(isinstance(alternatives, list) and 0 < len(alternatives) <= 10, f"{identity}: each evidence group needs alternatives")
            for anchor in alternatives:
                require(isinstance(anchor, dict) and text(anchor.get("noteId"), 200)
                        and type(anchor.get("revision")) is int and anchor["revision"] > 0
                        and text(anchor.get("quote"), 1200), f"{identity}: invalid noteId/revision/quote")
                require("headingPath" not in anchor or text(anchor["headingPath"], 2000, blank=True), f"{identity}: invalid headingPath")
        for field in ("tags", "forbiddenNoteIds", "forbiddenPhrases", "dialogue"):
            case.setdefault(field, [])
            require(strings(case[field], 5 if field == "dialogue" else 32), f"{identity}: invalid {field}")
    return cases


def matches(anchor, hit):
    return (anchor["noteId"] == hit["noteId"] and anchor["revision"] == hit["revision"]
            and ("headingPath" not in anchor or anchor["headingPath"] == hit.get("headingPath", ""))
            and normalize(anchor["quote"]) in normalize(hit["content"]))


def covered(groups, hits):
    # 标准原文可能横跨子块边界：仅合并同笔记、同修订、同小节且 ordinal 连续的块。
    # 不把不相邻的片段或不同小节拼成伪证据。
    chains, merged = {}, list(hits)
    for hit in hits:
        if "ordinal" in hit:
            key = hit["noteId"], hit["revision"], hit.get("headingPath", "")
            chains.setdefault(key, {})[hit["ordinal"]] = hit
    for pieces in chains.values():
        previous, combined = None, None
        for ordinal, hit in sorted(pieces.items()):
            if previous is not None and ordinal == previous + 1:
                combined = dict(hit, content=combined["content"] + "\n" + hit["content"])
                merged.append(combined)
            else:
                combined = hit
            previous = ordinal
    return [any(matches(anchor, hit) for anchor in alternatives for hit in merged) for alternatives in groups]


def coverage(groups, hits):
    found = covered(groups, hits)
    return sum(found) / len(found) if found else None


def score_case(case, result, mode):
    stages, error = result.get("stages", {}), result.get("error")
    groups = case["evidence"]
    metrics = {"completed": float(not error), "degraded": float(bool(result.get("fallbackReason")))}
    for stage in ("fused", "reranked"):
        hits = stages.get(stage, [])
        if groups:
            first = next((i for i in range(1, min(5, len(hits)) + 1)
                          if any(covered(groups, hits[:i]))), None)
            metrics[f"{stage}.hit5"] = float(first is not None)
            metrics[f"{stage}.mrr5"] = 1 / first if first else 0.0
            metrics[f"{stage}.recall5"] = coverage(groups, hits[:5])
            metrics[f"{stage}.recall30"] = coverage(groups, hits[:30])
    for stage in ("selected", "budget", "context"):
        if groups:
            metrics[f"{stage}.coverage"] = coverage(groups, stages.get(stage, []))
    metrics["forbiddenSourceHit"] = float(any(hit["noteId"] in case["forbiddenNoteIds"]
                                              for hits in stages.values() for hit in hits))
    if result.get("error") != "NOT_RUN":
        metrics["latencyMs"] = result.get("latencyMs", 0)
    metrics["actualCny"] = sum(int(row.get("actualMicros", 0)) for row in result.get("usage", [])) / 1_000_000
    metrics["uncertainCny"] = sum(int(row.get("uncertainMicros", 0)) for row in result.get("usage", [])) / 1_000_000
    if mode == "answer":
        answer = result.get("answer", {})
        status, response = answer.get("status"), answer.get("answer", "")
        replied, refused = status == "ANSWERED", status in REFUSALS
        metrics["behaviorCorrect"] = float(not error and (replied if case["answerable"] else refused))
        if case["answerable"]:
            metrics["answerableAnswerRate"] = float(not error and replied)
            metrics["cited.coverage"] = coverage(groups, stages.get("cited", []))
            if not error:
                metrics["overRefusal"] = float(refused)
                metrics["factLiteralCoverage"] = sum(normalize(f) in normalize(response) for f in case["expectedFacts"]) / len(case["expectedFacts"])
        else:
            metrics["unanswerableRefusalRate"] = float(not error and refused)
        metrics["forbiddenPhraseHit"] = float(any(phrase in response for phrase in case["forbiddenPhrases"]))
    return metrics


def failure_reason(case, result, mode):
    if result.get("error"):
        return result["error"]
    metrics = score_case(case, result, mode)
    if metrics["forbiddenSourceHit"]:
        return "FORBIDDEN_SOURCE"
    if metrics.get("forbiddenPhraseHit"):
        return "FORBIDDEN_LITERAL"
    if case["answerable"]:
        if metrics["fused.recall30"] < 1:
            return "RECALL_MISS"
        if metrics["selected.coverage"] < 1:
            return "RANK_OR_PARENT_SELECTION_LOSS"
        if metrics["budget.coverage"] < 1:
            return "CONTEXT_BUDGET_LOSS"
        if metrics["context.coverage"] < 1:
            return "EVIDENCE_FILTER_LOSS"
        if mode == "answer" and result.get("answer", {}).get("status") != "ANSWERED":
            return "ANSWER_WITHHELD"
    elif mode == "answer" and result.get("answer", {}).get("status") == "ANSWERED":
        return "UNEXPECTED_ANSWER"
    return ""  # 无自动失败不等于语义正确；需要人工评分。


def aggregate(entries):
    names = sorted({name for entry in entries for name in entry["metrics"]})
    result = {}
    for name in names:
        values = [entry["metrics"][name] for entry in entries if entry["metrics"].get(name) is not None]
        if values:
            result[name] = {"value": statistics.mean(values), "n": len(values)}
    latencies = sorted(entry["metrics"]["latencyMs"] for entry in entries if "latencyMs" in entry["metrics"])
    if latencies:
        result["latencyP95Ms"] = {"value": latencies[math.ceil(len(latencies) * .95) - 1], "n": len(latencies)}
    for name in ("actualCny", "uncertainCny"):
        result[name + "Total"] = {"value": sum(entry["metrics"][name] for entry in entries), "n": len(entries)}
    return result


def review_template(case, result):
    answer = result.get("answer", {}).get("answer", "")
    return {"id": case["id"], "variant": result["variant"], "caseHash": digest(case),
            "answerHash": hashlib.sha256(answer.encode()).hexdigest(),
            "question": case["question"], "expectedFacts": case["expectedFacts"], "answer": answer,
            "correct": None, "faithful": None, "citationsSupported": None, "notes": ""}


def attach_reviews(entries, cases, path):
    if path is None:
        return
    by_id = {case["id"]: case for case in cases}
    by_key = {(entry["id"], entry["variant"]): entry for entry in entries}
    seen = set()
    content = path.read_text(encoding="utf-8-sig")
    reviews = json.loads(content) if content.lstrip().startswith("[") else [json.loads(line) for line in content.splitlines() if line.strip()]
    require(isinstance(reviews, list), "Reviews must be a JSON array or JSONL")
    for review in reviews:
        key = review.get("id"), review.get("variant")
        require(key in by_key and key not in seen, "Unknown or duplicate review")
        seen.add(key)
        entry = by_key[key]
        expected = review_template(by_id[key[0]], entry)
        require(not entry.get("error") and entry.get("answer", {}).get("status") == "ANSWERED", "Only completed answers can receive semantic grades")
        require(all(review.get(k) == expected[k] for k in ("caseHash", "answerHash")), "Stale review: case or answer changed")
        for field in ("correct", "faithful", "citationsSupported"):
            value = review.get(field)
            require(value is None or type(value) is bool, "Review grades must be true, false, or null")
            if value is not None:
                entry["metrics"]["human." + field] = float(value)
        require(isinstance(review.get("notes", ""), str), "Review notes must be text")
        entry["review"] = review


def make_report(meta, cases, results, reviews=None):
    require(meta.get("caseSetHash") == digest(cases), "Case snapshot hash mismatch")
    by_id = {case["id"]: case for case in cases}
    expected = {(case["id"], variant) for case in cases for variant in meta["variants"]}
    actual = [(row.get("id"), row.get("variant")) for row in results]
    require(len(actual) == len(set(actual)) and set(actual) <= expected, "Duplicate or unexpected result rows")
    rows = list(results)
    for identity, variant in sorted(expected - set(actual)):
        rows.append({"id": identity, "variant": variant, "error": "NOT_RUN", "stages": {}, "usage": []})
    entries = []
    for row in rows:
        case = by_id[row["id"]]
        entry = dict(row)
        entry.update({"question": case["question"], "group": case["group"], "split": case["split"], "tags": case["tags"],
                      "expectedFacts": case["expectedFacts"], "metrics": score_case(case, row, meta["mode"]),
                      "failure": failure_reason(case, row, meta["mode"])})
        entries.append(entry)
    attach_reviews(entries, cases, reviews)
    summary = {variant: aggregate([e for e in entries if e["variant"] == variant]) for variant in meta["variants"]}
    slices = {}
    for dimension in ("split", "tags"):
        values = sorted({v for case in cases for v in (case[dimension] if dimension == "tags" else [case[dimension]])})
        slices[dimension] = {value: {variant: aggregate([e for e in entries if e["variant"] == variant
                  and (value in e[dimension] if dimension == "tags" else e[dimension] == value)])
                  for variant in meta["variants"]} for value in values}
    issues = [f"{e['variant']}/{e['id']}: {e['failure']}" for e in entries if e.get("error")
              or e["metrics"].get("forbiddenSourceHit") or e["metrics"].get("forbiddenPhraseHit")]
    if meta.get("runnerExitCode", 0) != 0:
        issues.append(f"Runner exit code: {meta['runnerExitCode']}; inspect runner.log")
    ablations = {}
    if len(meta["variants"]) > 1:
        baseline = "no-rerank" if "no-rerank" in meta["variants"] else meta["variants"][0]
        base = {e["id"]: e for e in entries if e["variant"] == baseline}
        for variant in meta["variants"]:
            if variant != baseline:
                ablations[variant + " minus " + baseline] = paired_metrics([(base[e["id"]], e) for e in entries if e["variant"] == variant])
    return {"schemaVersion": 2, "meta": meta, "summary": summary, "slices": slices, "entries": entries, "ablations": ablations,
            "integrity": {"complete": not any(e.get("error") for e in entries) and meta.get("runnerExitCode", 0) == 0, "issues": issues},
            "semanticReview": {"eligible": sum(e.get("answer", {}).get("status") == "ANSWERED" and not e.get("error") for e in entries),
                               "graded": sum("human.correct" in e["metrics"] for e in entries)}}


def paired_metrics(pairs):
    names = sorted({name for a, b in pairs for name in a["metrics"].keys() & b["metrics"].keys()})
    metrics = {}
    for name in names:
        differences = [b["metrics"][name] - a["metrics"][name] for a, b in pairs
                       if a["metrics"].get(name) is not None and b["metrics"].get(name) is not None]
        if differences:
            metrics[name] = {"delta": statistics.mean(differences), "n": len(differences),
                             "increased": sum(d > 0 for d in differences), "decreased": sum(d < 0 for d in differences),
                             "unchanged": sum(d == 0 for d in differences)}
    return metrics


def compare_reports(before, after):
    require(before.get("schemaVersion") == 2 and after.get("schemaVersion") == 2, "Compare schemaVersion 2 reports")
    for key in ("datasetHash", "caseSetHash", "mode"):
        require(before["meta"].get(key) == after["meta"].get(key), f"Cannot compare different {key}")
    require(before["meta"].get("manifest", {}).get("corpusHash")
            and before["meta"]["manifest"]["corpusHash"] == after["meta"].get("manifest", {}).get("corpusHash"),
            "Cannot compare different or unknown corpus snapshots")
    old = {(e["id"], e["variant"]): e for e in before["entries"]}
    new = {(e["id"], e["variant"]): e for e in after["entries"]}
    require(set(old) == set(new), "Paired comparison requires the same cases and variants")
    comparisons = {}
    for variant in before["meta"]["variants"]:
        pairs = [(old[k], new[k]) for k in old if k[1] == variant]
        comparisons[variant] = paired_metrics(pairs)
    return {"schemaVersion": 2, "comparisons": comparisons,
            "beforeIntegrity": before["integrity"], "afterIntegrity": after["integrity"],
            "note": "delta = after - before; latency, cost, leaks and refusal errors are lower-is-better. No significance claim."}


def markdown(report):
    lines = ["# 笔记 RAG 评测报告", "", f"模式：{report['meta']['mode']}；分组：{report['meta']['split']}。",
             "", "自动指标衡量标注证据匹配与流程行为；答案事实正确性需查看人工评分及其覆盖数量。", "",
             "| 方案 | 完成率 | 召回 Recall@30 | 重排 MRR@5 | 最终证据覆盖 | P95 ms | 已结算元 | 待对账元 |", "|---|---:|---:|---:|---:|---:|---:|---:|"]
    def show(stats, name):
        item = stats.get(name)
        return f"{item['value']:.4f} (n={item['n']})" if item else "—"
    for variant, stats in report["summary"].items():
        fields = [show(stats, name) for name in ("completed", "fused.recall30", "reranked.mrr5", "context.coverage", "latencyP95Ms", "actualCnyTotal", "uncertainCnyTotal")]
        lines.append("| " + " | ".join([variant] + fields) + " |")
    lines += ["", "重排 MRR@5 在 lexical/no-rerank 方案中指融合顺序的对应位置。各指标分母见 n；负例不参与证据召回均值。",
              "", f"语义评分：{report['semanticReview']['graded']} / {report['semanticReview']['eligible']} 条已完成答案有人工正确性评分。",
              "", "| 方案 | 应答题作答率 | 无答案题拒答率 | 人工正确率 | 人工忠实度 | 人工引用支持率 |", "|---|---:|---:|---:|---:|---:|"]
    for variant, stats in report["summary"].items():
        fields = [show(stats, name) for name in ("answerableAnswerRate", "unanswerableRefusalRate", "human.correct", "human.faithful", "human.citationsSupported")]
        lines.append("| " + " | ".join([variant] + fields) + " |")
    if report["ablations"]:
        lines += ["", "## 同一快照下的方案差异", "", "delta 为前一个方案减去基线，详情见 report.json 的 ablations。", ""]
        for label, metrics in report["ablations"].items():
            lines.append("- " + label + "：" + "; ".join(f"{name} {metrics[name]['delta']:+.4f}" for name in ("reranked.mrr5", "context.coverage") if name in metrics))
    ledger = report["meta"].get("ledgerUsage")
    if ledger is not None:
        actual = sum(int(row.get("actualMicros", 0)) for row in ledger) / 1_000_000
        uncertain = sum(int(row.get("uncertainMicros", 0)) for row in ledger) / 1_000_000
        lines += ["", f"全轮账本（含索引初始化和中途终止调用）：已结算 {actual:.6f} 元，待对账 {uncertain:.6f} 元。"]
    lines += ["", "## 完整性", "", f"全部请求完成：{report['integrity']['complete']}。"]
    lines += ["- " + issue for issue in report["integrity"]["issues"]]
    lines += ["", "## 需要检查的题", ""]
    for entry in report["entries"]:
        if entry["failure"]:
            question = entry["question"].replace("\n", " ")
            lines.append(f"- {entry['variant']} / {entry['id']}：{entry['failure']}；{question}")
    lines += ["", "每题原文、排名、耗时、费用与模型配置在 report.json；原始输出为 results.jsonl。",
              "文档和报告包含私人资料，请保存在本机忽略目录。", ""]
    return "\n".join(lines)
