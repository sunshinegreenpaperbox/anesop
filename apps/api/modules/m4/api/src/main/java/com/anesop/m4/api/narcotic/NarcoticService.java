package com.anesop.m4.api.narcotic;

import com.anesop.contracts.drug.model.CabinetHandover;
import com.anesop.contracts.drug.model.CabinetHandoverList;
import com.anesop.contracts.drug.model.CreateCabinetHandoverRequest;
import com.anesop.contracts.drug.model.CreateDailyCloseRequest;
import com.anesop.contracts.drug.model.CreateNarcoticRegisterRequest;
import com.anesop.contracts.drug.model.DailyClose;
import com.anesop.contracts.drug.model.DepartmentList;
import com.anesop.contracts.drug.model.DrugBaselineList;
import com.anesop.contracts.drug.model.NarcoticRegister;
import com.anesop.contracts.drug.model.NarcoticRegisterList;
import com.anesop.contracts.drug.model.UpdateDrugBaselinesRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.time.OffsetDateTime;

/**
 * 麻精五专服务（契约 drug.yaml 0.1.0）：专册登记、专柜交接、基数、日结/班结。
 *
 * <p>关键约束：
 * <ul>
 *   <li>权限 {@code m4:narcotic:manage}；数据范围按科室（ALL 全院；SITE 院区子树；DEPT 授权
 *       科室；SELF 按本人所属科室推导；ROOM/无数据范围 fail-closed 403）；</li>
 *   <li>专册/交接/日结 append-only：只新增不修改，更正另起记录（数据库触发器兜底 55000）；</li>
 *   <li>双人签字：两名签字人必须不同（400），专柜交接另要求交班人 ≠ 接班人；</li>
 *   <li>日结平账：期初 + 入库 − 使用 − 退回 = 期末；不平 409，details 给出期望值与实盘值；
 *       同一柜 + 业务日 + 班次重复日结 409；</li>
 *   <li>基数按 品种 + 柜 唯一，更新必须回传 version（过期 409），数量 ≥ 0；</li>
 *   <li>审计 NARCOTIC_REGISTER/NARCOTIC_HANDOVER/NARCOTIC_BASELINE/NARCOTIC_DAILY_CLOSE：
 *       detail 只含标识、数量与科室，不含处方号原文、人员姓名与患者信息。</li>
 * </ul>
 */
public interface NarcoticService {

    /** 调用者可访问的启用科室（五专页面显式选择科室；SELF 返回本人科室）。 */
    DepartmentList listDepartments(AuthPrincipal caller);

    /** 专册登记列表（按使用时间倒序）。 */
    NarcoticRegisterList listRegisters(String departmentId, String drugId, OffsetDateTime from,
            OffsetDateTime to, int limit, AuthPrincipal caller);

    /** 专册登记（药品必须已收录；双人签字不同人；科室须在数据范围内）。 */
    NarcoticRegister createRegister(CreateNarcoticRegisterRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 专柜交接列表（按交接时间倒序）。 */
    CabinetHandoverList listHandovers(String cabinetCode, String departmentId, int limit,
            AuthPrincipal caller);

    /** 专柜交接登记（双人签字不同人、交班 ≠ 接班；品种必须已收录）。 */
    CabinetHandover createHandover(CreateCabinetHandoverRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 基数列表（按柜号 + 药品名排序）。 */
    DrugBaselineList listBaselines(String cabinetCode, String departmentId, AuthPrincipal caller);

    /** 基数批量维护（品种 + 柜 upsert；更新必须回传 version）。 */
    DrugBaselineList updateBaselines(UpdateDrugBaselinesRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 日结/班结（平账校验；不平 409；重复日结 409）。 */
    DailyClose createDailyClose(CreateDailyCloseRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 日结详情；不存在或超出科室数据范围 404。 */
    DailyClose getDailyClose(String closeId, AuthPrincipal caller);
}
