package com.yizhaoqi.smartpai.service.eval;

import java.time.Instant;

/**
 * 人工对单条评测用例的判定。correct 必填，score（1-5，与评委同一量表）和 note 可选。
 */
public record HumanVerdict(
        String caseId,
        Boolean correct,
        Integer score,
        String note,
        String reviewer,
        Instant reviewedAt
) {
}
