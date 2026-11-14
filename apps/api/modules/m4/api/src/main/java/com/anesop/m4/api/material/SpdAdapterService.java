package com.anesop.m4.api.material;

import com.anesop.contracts.material.model.SpdAdapterStatus;
import com.anesop.contracts.material.model.SpdSyncResult;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * SPD 供应链适配器（EXT-03，SIMULATED）。
 *
 * <p>未接入真实 SPD 系统：同步为无外部 I/O 的模拟操作，结果写入
 * {@code m4_material.spd_sync_log}；失败时 {@code success=false, degraded=true}，
 * 本地耗材库存与出入库继续可用（降级不阻塞临床记录）。
 *
 * <p>安全：不接收也不返回外部系统凭据、供应商明细与患者信息；审计动作 SPD_SYNC
 * 只记录 adapterCode/mode/success/degraded。
 */
public interface SpdAdapterService {

    /** 查询最近一次模拟同步结果（只读，不产生新日志）。 */
    SpdAdapterStatus status(AuthPrincipal caller, AuthRequestContext context);

    /** 触发一次模拟同步并留痕；失败降级但返回 200。 */
    SpdSyncResult sync(AuthPrincipal caller, AuthRequestContext context);
}
