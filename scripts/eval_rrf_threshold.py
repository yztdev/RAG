"""用评测报告里的 RRF 候选，离线模拟 rerank 不可用时按不同 RRF 阈值兜底的检索效果。

报告需由带分阶段指标的版本生成（cases[].stages.candidates 带 rawScore）。按 RRF 顺序取过阈值的前 topK 条，统计：
- 标注用例：分块级 Recall@K、MRR、返回条数、其中不相关的条数；
- 无答案用例（neg）：平均返回条数（越少越好，宁可返回空也不塞噪声）。

用法：python3 scripts/eval_rrf_threshold.py eval-reports/xxx.json [阈值1 阈值2 ...]
"""
import json
import sys

DEFAULT_THRESHOLDS = [0.0, 0.0048, 0.008, 0.0100, 0.0111, 0.0125, 0.0140, 0.0155, 0.0164]


def simulate(report, threshold):
    k = report["topK"]
    labeled = hits = 0
    rr_sum = returned_labeled = noise_labeled = 0.0
    neg_cases = neg_returned = 0
    for case in report["cases"]:
        stages = case.get("stages")
        if not stages:
            continue
        picked = [c for c in stages["candidates"] if (c.get("rawScore") or 0) >= threshold][:k]
        if case["retrievalHit"] is None:
            neg_cases += 1
            neg_returned += len(picked)
            continue
        labeled += 1
        returned_labeled += len(picked)
        noise_labeled += sum(1 for c in picked if not c["relevant"])
        rank = next((i + 1 for i, c in enumerate(picked) if c["relevant"]), 0)
        hits += 1 if rank else 0
        rr_sum += 1 / rank if rank else 0
    return {
        "recall": hits / labeled if labeled else None,
        "mrr": rr_sum / labeled if labeled else None,
        "returned": returned_labeled / labeled if labeled else None,
        "noise": noise_labeled / labeled if labeled else None,
        "neg_returned": neg_returned / neg_cases if neg_cases else None,
        "labeled": labeled,
        "neg_cases": neg_cases,
    }


def main(path, thresholds):
    report = json.load(open(path, encoding="utf-8"))
    print(f"报告 {report['label']}，topK {report['topK']}，RRF k {report['config'].get('rrfK')}")
    print(f"{'阈值':>8} | {'Recall@K':>8} {'MRR':>5} | {'平均返回':>6} {'其中不相关':>8} | {'无答案题平均返回':>12}")
    for t in thresholds:
        r = simulate(report, t)
        print(f"{t:>8.4f} | {r['recall'] * 100:>7.1f}% {r['mrr']:>5.2f} | {r['returned']:>6.2f} {r['noise']:>8.2f} | "
              f"{r['neg_returned']:>12.2f}")
    r = simulate(report, 0.0)
    print(f"（标注用例 {r['labeled']} 题，无答案用例 {r['neg_cases']} 题）")


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(1)
    main(args[0], [float(a) for a in args[1:]] or DEFAULT_THRESHOLDS)
