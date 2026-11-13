package com.anesop.m1.api.schedule;

import com.anesop.contracts.scheduling.model.CancelScheduleRequest;
import com.anesop.contracts.scheduling.model.ConflictCheckRequest;
import com.anesop.contracts.scheduling.model.ConflictCheckResult;
import com.anesop.contracts.scheduling.model.CreateScheduleRequest;
import com.anesop.contracts.scheduling.model.EmergencyAddRequest;
import com.anesop.contracts.scheduling.model.EmergencyAddResult;
import com.anesop.contracts.scheduling.model.IdResponse;
import com.anesop.contracts.scheduling.model.OperatingRoomList;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.contracts.scheduling.model.ScheduleChangeList;
import com.anesop.contracts.scheduling.model.SchedulePage;
import com.anesop.contracts.scheduling.model.StopScheduleRequest;
import com.anesop.contracts.scheduling.model.UpdateScheduleRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 排程工作台服务（M2-M2-01，契约 scheduling.yaml 0.4.0）。
 *
 * <p>数据范围 fail-closed：无范围 403；DEPT/SITE 只可见本科室/院区术间，ROOM 只可见绑定
 * 术间；集合越权 403，单对象越界/不存在 404（不泄露存在性）。
 *
 * <p>并发策略：创建/调整在事务内先锁定目标术间行（{@code SELECT ... FOR UPDATE}）再复检
 * 冲突，保证同一术间同一时段恰有一个台次成功；冲突返回 {@link SchedulingConflictException}
 * （409 + conflicts[]），不静默覆盖。
 */
public interface SchedulingService {

    /**
     * 术间列表（受数据范围约束）：可按院区 {@code siteId} 与运行状态 {@code status} 过滤，
     * 非法参数 400。
     */
    OperatingRoomList listRooms(AuthPrincipal caller, String siteId, String status);

    /** 按日期/术间/状态查询日排程分页（date 必填，按院区时区解释）。 */
    SchedulePage listSchedules(String date, String roomId, String status, int page, int size,
            AuthPrincipal caller);

    /** 排程详情；不存在或超出数据范围 404。 */
    Schedule get(String scheduleId, AuthPrincipal caller);

    /** 创建台次：申请必须存在且状态允许，事务内锁术间 + 复检冲突，冲突 409。 */
    Schedule create(CreateScheduleRequest request, AuthPrincipal caller, AuthRequestContext context);

    /** 调整台次（时间/术间/台次序号）：锁目标术间 + 复检冲突，冲突 409；版本过期 409。 */
    Schedule reschedule(String scheduleId, UpdateScheduleRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * 取消/停台（0.4.0 兼容端点）：与 {@link #stop} 复用同一留痕路径（变更记录 + 审计 +
     * 通知 outbox）；已开始/已结束/已取消 409。
     *
     * @deprecated 0.5.0 起请用 {@link #stop}；计划 1.0.0 收敛。
     */
    @Deprecated
    IdResponse cancel(String scheduleId, CancelScheduleRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * 停台状态机（M2-M2-02）：PLANNED/PUBLISHED/DELAYED 直接停台；IN_PROGRESS 需审批权限。
     * 原因为白名单原因码，写变更记录、审计与通知 outbox；返回 CANCELLED 的台次。
     *
     * @param canApprove 调用方是否持 {@code m1:schedule:approve}（术中停台/急诊加台）
     */
    Schedule stop(String scheduleId, StopScheduleRequest request, AuthPrincipal caller,
            AuthRequestContext context, boolean canApprove);

    /**
     * 急诊加台（M2-M2-02）：需审批权限；可选抢占冲突中的择期台次（置 CANCELLED 并留痕），
     * 插入 priority=EMERGENCY 台次；未指定抢占且冲突时 409 + conflicts[]。
     */
    EmergencyAddResult addEmergency(EmergencyAddRequest request, AuthPrincipal caller,
            AuthRequestContext context, boolean canApprove);

    /**
     * 台次变更历史（停台/改台/急诊审批），受数据范围约束；不存在/越界 404。
     * 分页参数 additive（page 从 1 开始，size 上限 200），按 requestedAt 倒序。
     */
    ScheduleChangeList listChanges(String scheduleId, AuthPrincipal caller, int page, int size);

    /** 冲突即时检测（只读预检，不落库），返回结构化冲突。 */
    ConflictCheckResult checkConflicts(ConflictCheckRequest request, AuthPrincipal caller);
}
