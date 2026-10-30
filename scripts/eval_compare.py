"""按题型（用例 id 前缀）拆分离线 RAG 评测报告，并把多份报告并排对比。

用法：python3 scripts/eval_compare.py eval-reports/A.json eval-reports/B.json ...
"""
import json
import sys
from collections import defaultdict

TYPES = ["fact", "para", "multi", "conflict", "rustfs", "resume", "neg"]


def by_type(report):
    groups = defaultdict(list)
    for case in report["cases"]:
        groups[case["caseId"].split("-")[0]].append(case)
    out = {}
    for t in TYPES + ["ALL"]:
        cases = report["cases"] if t == "ALL" else groups.get(t, [])
        if not cases:
            continue
        labeled = [c for c in cases if c["retrievalHit"] is not None]
        judged = [c for c in cases if c["judgeCorrect"] is not None]
        out[t] = {
            "n": len(cases),
            "recall": sum(c["retrievalHit"] for c in labeled) / len(labeled) if labeled else None,
            # 旧报告没有 docHit 字段（只有文件级口径）
            "doc_recall": (sum(bool(c["docHit"]) for c in labeled) / len(labeled)
                           if labeled and all("docHit" in c for c in labeled) else None),
            "mrr": sum(c["reciprocalRank"] or 0 for c in labeled) / len(labeled) if labeled else None,
            "empty": sum(1 for c in cases if not c["retrieved"]),
            "judged": len(judged),
            "acc": sum(c["judgeCorrect"] for c in judged) / len(judged) if judged else None,
            "score": sum(c["judgeScore"] for c in judged) / len(judged) if judged else None,
            "failed": sum(1 for c in cases if c["diagnosis"] in ("JUDGE_FAILED", "GENERATION_FAILED", "RETRIEVAL_FAILED")),
        }
    return out


def fmt(v, pct=True):
    if v is None:
        return "-"
    return f"{v * 100:.1f}%" if pct else f"{v:.2f}"


def main(paths):
    reports = [json.load(open(p, encoding="utf-8")) for p in paths]
    for r in reports:
        s = r["summary"]
        print(f"== {r['label']} (rerank 阈值 {r['config']['rerankScoreThreshold']}, topK {r['topK']}, "
              f"模型 {r['config'].get('llm', '-')}) ==")
        print(f"   Recall@K {fmt(s['recallAtK'])}  文件级 Recall {fmt(s.get('docRecallAtK'))}"
              f"  MRR {fmt(s['mrr'], False)}  评委准确率 {fmt(s['accuracy'])}"
              f"  平均分 {fmt(s['meanJudgeScore'], False)}  失败 {s['failedCases']}  检索模式 {s['retrievalModeCounts']}")
        print(f"   归因 {s['diagnosisCounts']}")
        st = s.get("stages")
        if st:
            # 召回阶段看 RRF 候选（rerank 输入），精排阶段看最终 topK 的 NDCG；旧报告没有这一项
            print(f"   分阶段 召回 Recall@候选 {fmt(st['candidateRecall'])}（BM25 {fmt(st['bm25Recall'])} / KNN {fmt(st['knnRecall'])}）"
                  f"  精排 NDCG@K {fmt(st['meanNdcgAtK'], False)}（{st['ndcgCases']} 题）"
                  f"  前 topK 内被阈值丢掉 {st['thresholdDroppedResults']} 条 / {st['thresholdDroppedCases']} 题")
            if st.get("meanPrecisionAtK") is not None:
                # 噪声：Precision 的分母是实际返回条数，阈值少返回噪声时会上升；无答案题返回的每一条都是噪声
                print(f"   噪声 Precision@K {fmt(st['meanPrecisionAtK'])}  每题不相关 {fmt(st['meanIrrelevantInTopK'], False)} 条"
                      f"  无答案题平均返回 {fmt(st['unlabeledMeanReturned'], False)} 条（{st['unlabeledCases']} 题）")
    print()
    print("题型      | " + " | ".join(f"{r['label']:^52}" for r in reports))
    print("          | " + " | ".join(f"{'Recall':>7} {'文件级':>7} {'MRR':>5} {'空':>3} {'准确率':>7} {'均分':>5} {'失败':>3}"
                                       for _ in reports))
    tables = [by_type(r) for r in reports]
    for t in TYPES + ["ALL"]:
        cells = []
        for tab in tables:
            row = tab.get(t)
            if row is None:
                cells.append(" " * 52)
                continue
            cells.append(f"{fmt(row['recall']):>7} {fmt(row['doc_recall']):>7} {fmt(row['mrr'], False):>5} {row['empty']:>3} "
                         f"{fmt(row['acc']):>7} {fmt(row['score'], False):>5} {row['failed']:>3}")
        n = tables[0].get(t, {}).get("n", 0)
        print(f"{t:<6}({n:>2}) | " + " | ".join(cells))


if __name__ == "__main__":
    main(sys.argv[1:])
