package com.anesop.m2.api.rescue;

import com.anesop.contracts.anesthesia.model.CreateResuscitationRequest;
import com.anesop.contracts.anesthesia.model.ResuscitationRecord;
import com.anesop.contracts.anesthesia.model.ResuscitationRecordList;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 抢救记录服务（M3-M2-02，契约 anesthesia.yaml 0.7.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>append-only：写入后不可修改/删除（数据库触发器拒绝 UPDATE/DELETE/TRUNCATE，
 *       SQLSTATE 55000）；更正只能新增记录并在事件摘要说明；</li>
 *   <li>时间窗：startedAt 不早于记录单开始时间、endedAt 不早于 startedAt、endedAt 不晚于
 *       记录结束时间（已结束时）或服务端当前时间 + 容差；违反 400（字段级）；</li>
 *   <li>结构化留痕：eventSummary 为 {summary, interventions[]} 对象、drugs 为用药数组
 *       （≤50 条，药品名/剂量/单位/途径/时间），表内不存患者直接标识；</li>
 *   <li>数据范围：记录单不存在/超出数据范围 404；读取复用 {@code m2:record:read}，
 *       写入需要 {@code m2:rescue:write}；</li>
 *   <li>审计 RESCUE_RECORD 只含记录单/抢救记录标识、类型、时间窗与用药数量，
 *       不含事件摘要与药品明细原文。</li>
 * </ul>
 */
public interface ResuscitationService {

    /** 抢救记录列表（按 startedAt 倒序）。 */
    ResuscitationRecordList list(String recordId, AuthPrincipal caller);

    /**
     * 新增抢救记录（append-only）。
     *
     * @throws com.anesop.m2.api.error.M2ValidationException 字段非法或时间窗越界（400）
     * @throws java.util.NoSuchElementException              记录单不存在或超出数据范围（404）
     */
    ResuscitationRecord create(String recordId, CreateResuscitationRequest request,
            AuthPrincipal caller, AuthRequestContext context);
}
