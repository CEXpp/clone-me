"""不联网的评测回归：标注、指标、报告完整性、版本对比和 SQLite 在线快照。"""
import copy
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from evaluate import environment, snapshot
from rag_eval import (compare_reports, digest, load_cases, make_report, review_template,
                      score_case)


def case(identity="q1", answerable=True):
    return {"schemaVersion": 2, "id": identity, "group": "index", "split": "tuning",
            "question": "索引有什么代价？", "answerable": answerable,
            "evidence": [[{"noteId": "n1", "revision": 2, "quote": "写入成本"}]] if answerable else [],
            "expectedFacts": ["写入成本"] if answerable else [], "tags": [], "dialogue": [],
            "forbiddenNoteIds": ["private"], "forbiddenPhrases": ["秘密"]}


def hit(note="n1", content="增加写入成本", revision=2):
    return {"id": "random-chunk", "noteId": note, "revision": revision,
            "headingPath": "索引", "content": content, "score": .9}


def result():
    return {"id": "q1", "variant": "full", "stages": {stage: [hit()] for stage in
            ("fused", "reranked", "selected", "budget", "context", "cited")},
            "answer": {"status": "ANSWERED", "answer": "索引增加写入成本。"}, "latencyMs": 100,
            "usage": [{"actualMicros": 123, "uncertainMicros": 456}]}


def meta(cases):
    return {"mode": "answer", "split": "tuning", "variants": ["full"],
            "datasetHash": digest(cases), "caseSetHash": digest(cases), "manifest": {"corpusHash": "frozen"}}


class EvaluationTests(unittest.TestCase):
    def test_offline_demo_does_not_read_real_model_configuration(self):
        with patch.dict("os.environ", {"DEEPSEEK_API_KEY": "fixture-key", "SILICONFLOW_API_KEY": "fixture-key",
                                      "RERANK_API_KEY": "fixture-key", "SPRING_PROFILES_ACTIVE": "prod"}):
            env = environment(Path("file-should-not-be-read"), offline=True)
            self.assertNotIn("DEEPSEEK_API_KEY", env)
            self.assertNotIn("SILICONFLOW_API_KEY", env)
            self.assertNotIn("RERANK_API_KEY", env)
            self.assertNotIn("SPRING_PROFILES_ACTIVE", env)

    def test_evidence_crossing_child_boundary_requires_adjacent_original_blocks(self):
        row = result()
        row["stages"]["fused"] = [dict(hit(content="索引增加写入"), ordinal=3),
                                      dict(hit(content="成本。"), ordinal=4)]
        self.assertEqual(1, score_case(case(), row, "retrieval")["fused.recall30"])
        row["stages"]["fused"][1]["ordinal"] = 5
        self.assertEqual(0, score_case(case(), row, "retrieval")["fused.recall30"])

    def test_multiple_required_facts_and_alternative_sources(self):
        test = case()
        test["evidence"].append([{"noteId": "n2", "revision": 1, "quote": "全部回滚"},
                                 {"noteId": "n3", "revision": 7, "quote": "全部撤销"}])
        row = result()
        scores = score_case(test, row, "answer")
        self.assertEqual(.5, scores["fused.recall5"])
        row["stages"]["fused"].append(hit("n3", "全部撤销", 7))
        self.assertEqual(1, score_case(test, row, "answer")["fused.recall5"])

    def test_ranking_positions_and_duplicate_hits_do_not_inflate_recall(self):
        row = result()
        row["stages"]["fused"] = [hit("wrong"), hit(), hit()]
        scores = score_case(case(), row, "retrieval")
        self.assertEqual(.5, scores["fused.mrr5"])
        self.assertEqual(1, scores["fused.recall5"])
        row["stages"]["fused"] = [hit("wrong")] * 5 + [hit()]
        scores = score_case(case(), row, "retrieval")
        self.assertEqual(0, scores["fused.hit5"])
        self.assertEqual(1, scores["fused.recall30"])

    def test_heading_revision_and_unicode_whitespace_matching(self):
        test, row = case(), result()
        test["evidence"][0][0]["headingPath"] = "索引"
        row["stages"]["context"] = [hit(content="写入\n成本")]
        self.assertEqual(1, score_case(test, row, "retrieval")["context.coverage"])
        row["stages"]["context"][0]["revision"] = 1
        self.assertEqual(0, score_case(test, row, "retrieval")["context.coverage"])

    def test_negative_cases_do_not_receive_perfect_retrieval_scores(self):
        test, row = case(answerable=False), result()
        scores = score_case(test, row, "answer")
        self.assertNotIn("context.coverage", scores)
        self.assertEqual(0, scores["unanswerableRefusalRate"])
        row["answer"]["status"] = "INSUFFICIENT_EVIDENCE"
        self.assertEqual(1, score_case(test, row, "answer")["unanswerableRefusalRate"])

    def test_missing_results_errors_and_private_sources_block_integrity(self):
        tests = [case()]
        report = make_report(meta(tests), tests, [])
        self.assertFalse(report["integrity"]["complete"])
        self.assertEqual(0, report["summary"]["full"]["context.coverage"]["value"])
        row = result()
        row["stages"]["fused"].append(hit("private"))
        report = make_report(meta(tests), tests, [row])
        self.assertEqual(1, len(report["integrity"]["issues"]))
        self.assertEqual(.000123, report["summary"]["full"]["actualCnyTotal"]["value"])
        self.assertEqual(.000456, report["summary"]["full"]["uncertainCnyTotal"]["value"])

    def test_ungraded_semantics_are_not_assumed_correct(self):
        tests = [case()]
        report = make_report(meta(tests), tests, [result()])
        self.assertNotIn("human.correct", report["summary"]["full"])
        self.assertEqual({"eligible": 1, "graded": 0}, report["semanticReview"])

    def test_runner_failure_blocks_even_if_all_rows_were_flushed(self):
        tests = [case()]
        metadata = dict(meta(tests), runnerExitCode=1)
        self.assertFalse(make_report(metadata, tests, [result()])["integrity"]["complete"])

    def test_same_run_ablation_is_paired_by_case(self):
        tests = [case()]
        metadata = dict(meta(tests), variants=["full", "no-rerank"])
        baseline = result()
        baseline["variant"] = "no-rerank"
        baseline["stages"]["context"] = []
        report = make_report(metadata, tests, [result(), baseline])
        self.assertEqual(1, report["ablations"]["full minus no-rerank"]["context.coverage"]["delta"])

    def test_stale_manual_reviews_are_rejected(self):
        test, row = case(), result()
        grade = review_template(test, row)
        grade["correct"] = True
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "review.jsonl"
            path.write_text(json.dumps(grade) + "\n", encoding="utf-8")
            report = make_report(meta([test]), [test], [row], path)
            self.assertEqual(1, report["summary"]["full"]["human.correct"]["value"])
            row["answer"]["answer"] = "另一个答案"
            with self.assertRaisesRegex(ValueError, "Stale review"):
                make_report(meta([test]), [test], [row], path)

    def test_pretty_json_review_keeps_partial_grades_and_false_values(self):
        test, row = case(), result()
        grade = dict(review_template(test, row), correct=False)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "reviews.json"
            path.write_text(json.dumps([grade], indent=2), encoding="utf-8")
            report = make_report(meta([test]), [test], [row], path)
            self.assertEqual({"value": 0.0, "n": 1}, report["summary"]["full"]["human.correct"])
            self.assertNotIn("human.faithful", report["summary"]["full"])

    def test_comparison_is_paired_and_rejects_data_drift(self):
        tests = [case()]
        before = make_report(meta(tests), tests, [result()])
        changed = result()
        changed["stages"]["context"] = []
        after = make_report(meta(tests), tests, [changed])
        compared = compare_reports(before, after)
        self.assertEqual(-1, compared["comparisons"]["full"]["context.coverage"]["delta"])
        after["meta"]["manifest"]["corpusHash"] = "different"
        with self.assertRaisesRegex(ValueError, "corpus"):
            compare_reports(before, after)

    def test_mixed_answerable_and_unanswerable_comparison_keeps_metric_denominators(self):
        tests = [case(), case("negative", answerable=False)]
        negative = result()
        negative["id"] = "negative"
        negative["answer"]["status"] = "INSUFFICIENT_EVIDENCE"
        report = make_report(meta(tests), tests, [result(), negative])
        metrics = compare_reports(report, report)["comparisons"]["full"]
        self.assertEqual(1, metrics["context.coverage"]["n"])
        self.assertEqual(2, metrics["behaviorCorrect"]["n"])
        self.assertEqual(0, metrics["context.coverage"]["delta"])

    def test_paraphrase_groups_cannot_leak_into_holdout(self):
        first, second = case(), case("q2")
        second["split"] = "holdout"
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "cases.jsonl"
            path.write_text("\n".join(json.dumps(c) for c in [first, second]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "cannot cross"):
                load_cases(path)

    def test_empty_or_malformed_datasets_do_not_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "cases.jsonl"
            for invalid in [[], [dict(case(), answerable=False)], [dict(case(), evidence=[])],
                            [dict(case(), dialogue=["q"] * 6)], [dict(case(), expectedFacts=[])]]:
                path.write_text("\n".join(json.dumps(c) for c in invalid), encoding="utf-8")
                with self.assertRaises(ValueError):
                    load_cases(path)

    def test_snapshot_includes_wal_commits_and_never_mutates_source(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source_dir = root / "live"
            source_dir.mkdir()
            source = source_dir / "app.db"
            with closing(sqlite3.connect(source)) as original:
                original.execute("PRAGMA journal_mode=WAL")
                original.execute("CREATE TABLE notes(id TEXT)")
                original.execute("CREATE TABLE admin_account(password_hash TEXT)")
                original.execute("INSERT INTO notes VALUES('in-wal')")
                original.execute("INSERT INTO admin_account VALUES('fixture-sensitive-unique-sentinel')")
                original.commit()
                workspace = root / "copy"
                workspace.mkdir()
                copied = snapshot(source, workspace)
                with closing(sqlite3.connect(copied)) as sql:
                    self.assertEqual([('in-wal',)], sql.execute("SELECT id FROM notes").fetchall())
                    self.assertEqual(0, sql.execute("SELECT COUNT(*) FROM admin_account").fetchone()[0])
                    sql.execute("DELETE FROM notes")
                self.assertEqual(1, original.execute("SELECT COUNT(*) FROM notes").fetchone()[0])
                self.assertEqual(1, original.execute("SELECT COUNT(*) FROM admin_account").fetchone()[0])
                self.assertNotIn(b"fixture-sensitive-unique-sentinel", copied.read_bytes())
                unsafe = source_dir / "eval"
                unsafe.mkdir()
                with self.assertRaisesRegex(ValueError, "outside"):
                    snapshot(source, unsafe)


if __name__ == "__main__":
    unittest.main()
