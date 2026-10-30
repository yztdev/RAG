package com.yizhaoqi.smartpai.service.eval;

/**
 * 把检索结果和回答结果放在一起看，定位问题出在哪一环
 */
public enum EvalDiagnosis {

    OK("检索命中，回答正确"),
    PROMPT_OR_MODEL("检索命中但回答错误：资料已经给到模型，优先检查提示词或模型"),
    RETRIEVAL_MISS("检索未命中：先别动模型，优先检查分块和检索"),
    MISS_BUT_CORRECT("检索未命中但回答正确：检查期望文档的标注是否完整，或模型是否用了其他文档"),
    NOT_LABELED("没有标注期望文档，只评估回答"),
    RETRIEVAL_FAILED("检索调用失败"),
    GENERATION_FAILED("生成回答失败"),
    JUDGE_FAILED("评委调用失败或输出无法解析，需要人工判断");

    private final String description;

    EvalDiagnosis(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /**
     * @param retrievalHit 前 K 条是否命中期望文档；没有标注期望文档时为 null
     * @param answered     是否成功生成了回答
     * @param verdict      评委结论；没有调用评委时为 null
     */
    public static EvalDiagnosis classify(Boolean retrievalHit, boolean answered, JudgeVerdict verdict) {
        boolean judged = verdict != null && !verdict.parseFailed();
        if (Boolean.FALSE.equals(retrievalHit)) {
            return judged && Boolean.TRUE.equals(verdict.correct()) ? MISS_BUT_CORRECT : RETRIEVAL_MISS;
        }
        if (!answered) {
            return GENERATION_FAILED;
        }
        if (!judged) {
            return JUDGE_FAILED;
        }
        if (retrievalHit == null) {
            return NOT_LABELED;
        }
        return Boolean.TRUE.equals(verdict.correct()) ? OK : PROMPT_OR_MODEL;
    }
}
