package com.anesop.m2.api.threeway;

import com.anesop.contracts.anesthesia.model.ApplicationThreeWayCheck;
import com.anesop.contracts.anesthesia.model.ApplicationThreeWayCheckList;
import com.anesop.contracts.anesthesia.model.RecordApplicationThreeWayCheckRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 三方核查服务（M2-M5-01，契约 anesthesia.yaml 0.4.0，手术申请维度）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>append-only：每次录入新增 check_version，记录不可修改/删除；修正必须新增版本；</li>
 *   <li>Time-out 先录，Sign-out 可在出室后以新版本补录；最新版本同时含 timeOutAt 与
 *       signOutAt 即为“核查完成”；新增版本可省略 timeOutAt（继承上一版本），显式改
 *       Time-out 须 confirmTimeOutChange=true，否则 409；</li>
 *   <li>{@link #isThreeWayCheckCompleted(String)} 是 M3 锁单前置校验的集成点：M3 锁单时
 *       必须调用本方法（不直接读 m2 表）；未完成返回 false（fail-safe，宁可阻止锁单）；</li>
 *   <li>数据范围沿 M1 申请可见性：申请不存在/越界 404；审计 THREEWAY_RECORD，
 *       detail 只含标识、版本、核对结果与问题项数量，不含问题项原文。</li>
 * </ul>
 */
public interface ThreeWayCheckService {

    /** 按手术申请查询核查版本（checkVersion 倒序）与完成标记；申请不可见 404。 */
    ApplicationThreeWayCheckList listByApplication(String applicationId, AuthPrincipal caller);

    /**
     * 新增核查版本（append-only）；申请不可见 404；状态不允许、Time-out 变更未确认、
     * 并发版本冲突 409；首版缺少 Time-out 或时间非法 400。
     */
    ApplicationThreeWayCheck record(String applicationId,
            RecordApplicationThreeWayCheckRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * M3 锁单前置集成点：最新核查版本是否同时记录 Time-out 与 Sign-out。
     *
     * @param applicationId 手术申请标识（UUID 字符串）；非法/无记录返回 false
     */
    boolean isThreeWayCheckCompleted(String applicationId);
}
