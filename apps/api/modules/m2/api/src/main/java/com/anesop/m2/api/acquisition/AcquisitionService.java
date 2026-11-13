package com.anesop.m2.api.acquisition;

import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.time.Instant;
import java.util.UUID;

/**
 * 设备采集服务（M3-M8-01，契约 device.yaml 0.1.0）。
 *
 * <p>职责与约束：
 * <ul>
 *   <li>数据范围：台账/体征查询按可见术间过滤；无数据范围或无可见术间 403，单设备越界 404；
 *       登记/换台目标术间必须可见，未绑定术间仅 ALL 可操作（M3-M8-01 复审 B-1）；</li>
 *   <li>设备登记：唯一 (gatewayId, deviceId)，可绑定术间（逻辑外键，术间主数据在 M1）；
 *       缺省 status 不覆盖已有运维状态（MAINTENANCE 不会被重复登记改回 ONLINE）；</li>
 *   <li>网关上报：内部令牌 {@code X-Gateway-Token} 认证（缺失/不匹配/未配置一律拒绝，
 *       fail-closed）；批次幂等由 {@code device_ingest_log} 唯一键保证，行级幂等由 V4
 *       {@code anesthesia_vital} 唯一键保证；格式非法帧只计数拒绝，不中断整批；</li>
 *   <li>体征查询：按窗口（≤ 配置上限，默认 24 小时）+ 质量码过滤 + 按采样帧分页；</li>
 *   <li>健康摘要：按网关聚合登记设备与近 N 小时上报（默认 24h，可配置 1–168），用于运维看板；</li>
 *   <li>审计 DEVICE_REGISTER / DEVICE_INGEST / DEVICE_QUERY，detail 只含设备/批次标识与计数，
 *       不含 PHI、报文原文与网关令牌。</li>
 * </ul>
 * 设备域只处理设备标识、术间标识与数值体征，不接收也不返回患者标识。
 */
public interface AcquisitionService {

    /**
     * 查询设备登记列表（按推导状态过滤在服务层完成）。
     *
     * <p>数据范围：只返回可见术间内的设备（未绑定术间的设备仅 ALL 可见）；roomId 过滤越权 403。
     *
     * @param gatewayId 网关过滤；null/空表示全部
     * @param status    状态过滤（ONLINE/OFFLINE/MAINTENANCE）；null 表示全部
     * @param roomId    术间过滤（逻辑外键）；null 表示全部
     * @param caller    当前用户（审计用）
     * @param context   请求上下文（审计用）
     */
    DeviceList listDevices(String gatewayId, String status, UUID roomId, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * 登记设备或换台绑定。
     *
     * <p>首次登记返回 created=true（HTTP 201）；同一设备内容相同为幂等（created=false，200）；
     * 换台绑定 version 递增（created=false，200）；并发唯一冲突 409。请求未带 status 时
     * 首次登记默认 ONLINE、已有登记保留当前状态（不覆盖 MAINTENANCE）。目标术间不在调用者
     * 数据范围内（含未绑定术间对非 ALL 用户）返回 403。
     */
    RegisterOutcome register(DeviceRegisterRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * 网关批量上报（幂等）。
     *
     * @param gatewayId    路径网关标识（须与请求体一致）
     * @param deviceId     路径设备标识
     * @param gatewayToken 请求头 X-Gateway-Token 原值（不落日志）
     * @param request      批次与帧
     * @param context      请求上下文（IP/UA/traceId，审计用；不含令牌）
     * @throws GatewayAuthException 令牌缺失、不匹配或服务端未配置（401，fail-closed）
     */
    IngestResult ingest(String gatewayId, String deviceId, String gatewayToken,
            IngestRequest request, AuthRequestContext context);

    /**
     * 按窗口查询标准化体征（按采样帧分页，帧展开为指标行）。
     *
     * <p>设备不存在或超出数据范围统一 404（不泄露存在性）。
     *
     * @param gatewayId 网关标识
     * @param deviceId  设备标识
     * @param from      窗口起点（含）
     * @param to        窗口终点（不含）；跨度不得超过配置上限
     * @param quality   质量码过滤；null 不过滤
     * @param page      页码（从 1 起）
     * @param size      每页采样帧数（1–200）
     */
    VitalPage queryVitals(String gatewayId, String deviceId, Instant from, Instant to,
            Integer quality, Integer page, Integer size, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * 采集健康摘要（运维）：按网关聚合设备状态与近 N 小时上报计数（默认 24h，可配置 1–168）。
     *
     * <p>质量比例分子/分母使用同一窗口（measured_at ∈ [now−窗口, now]）。
     */
    DeviceHealth health();
}
