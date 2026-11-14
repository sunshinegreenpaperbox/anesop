package com.anesop.m2.api.record;

import com.anesop.contracts.anesthesia.model.AdministerDrugRequest;
import com.anesop.contracts.anesthesia.model.AnesthesiaRecord;
import com.anesop.contracts.anesthesia.model.AnesthesiaRecordList;
import com.anesop.contracts.anesthesia.model.CreateAnesthesiaRecordRequest;
import com.anesop.contracts.anesthesia.model.CreateRecordEventRequest;
import com.anesop.contracts.anesthesia.model.DrugAdministration;
import com.anesop.contracts.anesthesia.model.DrugAdministrationList;
import com.anesop.contracts.anesthesia.model.FluidRecord;
import com.anesop.contracts.anesthesia.model.FluidRecordList;
import com.anesop.contracts.anesthesia.model.IntakeOutputSummary;
import com.anesop.contracts.anesthesia.model.RecordEvent;
import com.anesop.contracts.anesthesia.model.RecordEventList;
import com.anesop.contracts.anesthesia.model.RecordFluidRequest;
import com.anesop.contracts.anesthesia.model.UpdateAnesthesiaRecordRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 麻醉记录单服务（M3-M2-01，契约 anesthesia.yaml 0.5.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>一个台次（scheduleId）唯一记录单：重复创建 409，数据库唯一索引兜底；</li>
 *   <li>时间轴 append-only：事件只追加不修改；追加前锁定记录单行再分配 seq（并发串行化）；
 *       occurredAt 不得早于记录开始、不得晚于服务端时间 + 容差（400），ANESTHESIA_END 之后
 *       追加 409；DRAFT 下术中事件必须先记录 ANESTHESIA_START；补录（早于最新事件或早于
 *       now−容差）必须填写 entryReason（400）；ANESTHESIA_START 置记录为进行中、
 *       ANESTHESIA_END 记录结束时间；</li>
 *   <li>用药：麻精属性由服务端按 m4-api 药品字典判定（M4-M15-01 收口 T-4）；字典未收录 400，
 *       客户端声明与字典不一致 400（以字典为准）。麻精类必须双人核对
 *       （secondCheckerId/secondCheckerName），缺失 400；核对人不得与给药人相同，且必须存在、
 *       在职并持有 {@code m2:record:write}（400）；失败路径写 result=0 审计；</li>
 *   <li>输液/出入量：明细 append-only，出入量汇总由服务端按明细计算（入量-出量为平衡）；</li>
 *   <li>记录单未锁定时可改（PUT 乐观锁 version，过期 409）；锁单/修订由 M3-M2-03 实现，
 *       本期无锁单状态；</li>
 *   <li>数据范围复用台次/申请可见性（只调 m1-api，不访问 m1 表）：按台次列表为集合语义
 *       （越权 403），单记录查询/变更为单对象语义（越界 404），创建时台次不可见 422；</li>
 *   <li>审计 RECORD_CREATE/RECORD_UPDATE/EVENT_RECORD/DRUG_ADMIN/FLUID_RECORD，
 *       RECORD_UPDATE 记录 before/after 摘要；detail 只含标识、事件类型/序号、药品名与剂量、
 *       出入量数值，不含患者姓名/院内患者号/核对人姓名与补录原因原文。</li>
 * </ul>
 */
public interface AnesthesiaRecordService {

    /** 按台次查询记录单（0 或 1 条）；台次不可见 403（集合语义）。 */
    AnesthesiaRecordList listBySchedule(String scheduleId, AuthPrincipal caller);

    /**
     * 按台次创建记录单（DRAFT）：重复创建 409；台次不可见 422；台次已取消/已完成 409。
     */
    AnesthesiaRecord create(CreateAnesthesiaRecordRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 记录单详情（含兼容事件列表、体征占位与三方核查摘要）；不存在或越界 404。 */
    AnesthesiaRecord get(String recordId, AuthPrincipal caller);

    /**
     * 保存记录单（未锁定可改）：必须回传 version，过期 409；非空 events 返回 400
     * （事件为 append-only，请走 {@link #appendEvent}）。
     */
    AnesthesiaRecord update(String recordId, UpdateAnesthesiaRecordRequest request,
            AuthPrincipal caller, AuthRequestContext context);

    /** 时间轴事件（occurredAt 升序，同刻按 seq 升序）。 */
    RecordEventList listEvents(String recordId, AuthPrincipal caller);

    /**
     * 追加时间轴事件（append-only）。
     *
     * <p>occurredAt 必填；早于记录开始或晚于服务端时间 + 容差 400；ANESTHESIA_END 之后追加
     * 409；DRAFT 下术中事件须先记录 ANESTHESIA_START（409）；补录（早于最新事件或早于
     * now−容差）缺少 entryReason 400；重复开始/结束或记录锁定 409。
     */
    RecordEvent appendEvent(String recordId, CreateRecordEventRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 用药记录（按给药时间倒序）。 */
    DrugAdministrationList listDrugs(String recordId, AuthPrincipal caller);

    /**
     * 记录用药：麻精类缺双人核对、同人核对、核对人不存在/停用/无 {@code m2:record:write}
     * 均 400（失败写 result=0 审计）；记录锁定 409。
     */
    DrugAdministration recordDrug(String recordId, AdministerDrugRequest request,
            AuthPrincipal caller, AuthRequestContext context);

    /** 输液/出入量明细（按记录时间倒序）。 */
    FluidRecordList listFluids(String recordId, AuthPrincipal caller);

    /** 记录入量/出量：amountMl 必须大于 0；记录锁定 409。 */
    FluidRecord recordFluid(String recordId, RecordFluidRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 出入量汇总（服务端按明细计算，balanceMl = intakeMl - outputMl）。 */
    IntakeOutputSummary ioSummary(String recordId, AuthPrincipal caller);
}
