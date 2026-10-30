package com.yizhaoqi.smartpai.service.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 倒数排名融合（Reciprocal Rank Fusion）。
 * 只看文档在每一路结果里的名次，不看原始分数：BM25 分数和向量相似度量纲不同，直接加权并不可靠。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * @param k           平滑常数，常用 60
     * @param keyFn       文档去重键（同一文档在两路结果中出现时合并）
     * @param rankedLists 各路召回结果，每一路已按相关性降序排列
     * @return 按 RRF 分数降序排列的融合结果；分数相同按首次出现顺序
     */
    @SafeVarargs
    public static <T> List<Fused<T>> fuse(int k, Function<T, String> keyFn, List<T>... rankedLists) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF k 必须为正数");
        }
        Map<String, Accumulator<T>> accumulators = new LinkedHashMap<>();
        for (int listIndex = 0; listIndex < rankedLists.length; listIndex++) {
            List<T> list = rankedLists[listIndex];
            if (list == null) {
                continue;
            }
            for (int position = 0; position < list.size(); position++) {
                T item = list.get(position);
                String key = keyFn.apply(item);
                if (key == null) {
                    continue;
                }
                Accumulator<T> acc = accumulators.computeIfAbsent(key,
                        ignored -> new Accumulator<>(item, rankedLists.length));
                // 同一路里重复出现时只取最靠前的名次
                if (acc.ranks[listIndex] == 0) {
                    int rank = position + 1;
                    acc.ranks[listIndex] = rank;
                    acc.score += 1.0d / (k + rank);
                }
            }
        }

        List<Fused<T>> fused = new ArrayList<>(accumulators.size());
        accumulators.forEach((key, acc) -> fused.add(new Fused<>(key, acc.item, acc.score, acc.ranks.clone())));
        // List.sort 是稳定排序，分数相同保持首次出现顺序
        fused.sort(Comparator.comparingDouble((Fused<T> f) -> f.score()).reversed());
        return List.copyOf(fused);
    }

    /**
     * @param ranks 在每一路中的名次（从 1 开始），0 表示该路未召回
     */
    public record Fused<T>(String key, T item, double score, int[] ranks) {
        public boolean recalledBy(int listIndex) {
            return listIndex < ranks.length && ranks[listIndex] > 0;
        }
    }

    private static final class Accumulator<T> {
        private final T item;
        private final int[] ranks;
        private double score;

        private Accumulator(T item, int listCount) {
            this.item = item;
            this.ranks = new int[listCount];
        }
    }
}
