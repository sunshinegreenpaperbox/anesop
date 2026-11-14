package com.anesop.m4.api.drug;

/**
 * 药品名称解析命中多个不同药品（M4-M15-01 B-1 / T-4）。
 *
 * <p>名称/通用名/别名交叉命中不同药品时，无法唯一判定麻精属性。服务必须 fail-closed：
 * 由调用方映射 409/422（{@code DRUG_AMBIGUOUS}），禁止静默取第一条。异常不携带药品名称
 * 原文或候选列表，避免把字典内部数据回显到响应/审计。
 */
public class DrugDictionaryAmbiguousException extends RuntimeException {

    private final int candidateCount;

    /**
     * @param candidateCount 命中的不同药品数（仅用于日志/审计计数，不对外回显名称）
     */
    public DrugDictionaryAmbiguousException(int candidateCount) {
        super("药品名称在字典中命中多个药品");
        this.candidateCount = candidateCount;
    }

    public int candidateCount() {
        return candidateCount;
    }
}
