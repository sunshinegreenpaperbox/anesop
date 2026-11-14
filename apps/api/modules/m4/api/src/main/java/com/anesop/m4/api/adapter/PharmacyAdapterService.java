package com.anesop.m4.api.adapter;

import com.anesop.contracts.drug.model.PharmacyAdapterStatus;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 药房系统适配器（EXT-02，SIMULATED）。
 *
 * <p>未接入真实药房系统：每次查询状态会执行一次 **无外部 I/O** 的模拟探测，并把结果
 * （成功/失败/降级）写入 {@code m4_drug.pharmacy_adapter_log}；探测失败时
 * {@code available=false, degraded=true}，本地药品字典继续可用（降级不阻塞临床记录）。
 *
 * <p>安全：不接收也不返回外部系统凭据、处方明细与患者信息；审计动作 ADAPTER_SYNC
 * 只记录 adapterCode/mode/available/degraded 与是否降级。
 */
public interface PharmacyAdapterService {

    /** 探测并返回适配器状态（模拟同步 + 留痕）。 */
    PharmacyAdapterStatus status(AuthPrincipal caller, AuthRequestContext context);
}
