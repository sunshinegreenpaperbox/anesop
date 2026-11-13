# M1 手术排程管理（模块说明）

> 状态：M2-M1-01「手术申请接收与校验」+ M2-M2-01「排程工作台与冲突检测」落地版（2026-11-13）。
> 契约：`packages/contracts/openapi/scheduling.yaml` 0.4.0、`patient.yaml` 0.3.0；
> 方案书：§2.4.2（M1 手术排程）、§2.7.1（HIS 集成）、§3.4.2/§3.4.3（实体与数据库）、
> §3.5.3（I-01a/I-01b）、§3.9.2（RBAC/数据权限）、§3.9.3（加密脱敏）。
> 全部示例、演示账号、患者、术间与申请均为 **SYNTHETIC** 合成数据。

## 1. 职责与边界

M1 负责围术期排程上游的“手术申请接收与校验”和“术间/台次排程”：

- 接收 HIS 手术申请（当前为 Mock 适配器，EXT-01 未就绪）与手工/测试创建；
- 以 `his_application_no` 为入站幂等键落库，重复导入不增行、手工重复提交 409；
- 提供申请分页/详情/取消与状态历史，向下游排程、术前访视（M2-M4-01）供数；
- 提供排程/申请所需的患者脱敏摘要（姓名、性别、出生日期/年龄、脱敏住院号、科室/病区/床号），
  **不提供证件号与手机号**（含脱敏值）；
- 维护术间主数据与日排程台次，提供规则引擎冲突检测（创建/调整事务内复检，409 + conflicts[]）。

非目标：停改台/急诊加台（M2-M2-02）、排程分析（M2-M6-01）、设备依赖与人员资质主数据（M3）。

## 2. 代码结构

```text
apps/api/modules/m1/
├── api/  com.anesop.m1.api
│   ├── application/  SurgeryApplicationService、查询条件、异常、状态语义
│   ├── schedule/     SchedulingService、SchedulingConflictException、校验/语义异常
│   ├── his/          HisSurgeryApplicationPort 端口与入站记录（模拟标记）
│   ├── patient/      PatientQueryService（患者脱敏摘要）
│   └── security/     M1Permissions（m1:application:* / m1:patient:read / m1:schedule:*）
└── impl/ com.anesop.m1.impl
    ├── application/  SurgeryApplicationServiceImpl（事务/幂等/状态机/审计）
    ├── schedule/     SchedulingServiceImpl（事务/锁/规则引擎调用/审计）
    │   └── rule/     SchedulingRule、注册表与 5 条规则（重叠/术者/患者/类型/时间窗）
    ├── patient/      PatientQueryServiceImpl（数据范围过滤）
    ├── his/          MockHisApplicationAdapter（SYNTHETIC 生成器）
    ├── persistence/  实体与 Mapper（m1_sched 五表；术间行 FOR UPDATE）
    └── support/      InpatientNoMasker / InpatientNoHasher
```

模块边界：`m1-impl` 只依赖 `m1-api`、`m0-api`（认证主体、数据范围、审计）与基础设施；
不访问其他模块 impl，不跨 schema 读写 M0 表。`api-bootstrap` 是唯一依赖 `m1-impl` 的装配点。

## 3. 接口（scheduling 0.4.0 与 patient 0.3.0）

### 3.1 手术申请与患者摘要

| 方法 | 路径 | operationId | 权限 | 说明 |
|---|---|---|---|---|
| GET | `/v1/scheduling/applications` | `listSurgeryApplications` | `m1:application:read` | 分页/状态/紧急度/科室过滤，按数据范围裁剪 |
| POST | `/v1/scheduling/applications` | `submitSurgeryApplication` | `m1:application:write` | 手工/测试创建；重复申请号 409，未知患者 422 |
| GET | `/v1/scheduling/applications/{applicationId}` | `getSchedulingApplication` | `m1:application:read` | 详情 + 完整状态历史；越界/不存在 404 |
| POST | `/v1/scheduling/applications/import` | `importSurgeryApplications` | `m1:application:write` | 模拟 HIS 拉取导入；幂等，响应 `simulated=true` |
| POST | `/v1/scheduling/applications/{applicationId}/cancel` | `cancelSchedulingApplication` | `m1:application:write` | 取消 + 状态历史 + 审计；终态/版本冲突 409 |

患者摘要（patient 0.3.0）：`POST /v1/patients/search`（检索条件在 JSON 请求体，避免 PHI 进入
URL/访问日志）与 `GET /v1/patients/{patientId}`，需要 `m1:patient:read`（Q-3 最小权限：
SYS_ADMIN/SYS_SURGEON/SYS_OR_MANAGER）并受数据范围约束；旧 `GET /v1/patients` 标记 deprecated，
生产禁用、仅兼容。

### 3.2 术间与排程工作台

| 方法 | 路径 | operationId | 权限 | 说明 |
|---|---|---|---|---|
| GET | `/v1/scheduling/rooms?siteId=&status=` | `listOperatingRooms` | `m1:schedule:read` | 术间编号/名称/类型/状态；按 `siteId`/`status` 过滤（非法 400），受 DEPT/ROOM 数据范围约束 |
| GET | `/v1/scheduling/schedules?date=` | `listSchedules` | `m1:schedule:read` | 按日（`Asia/Shanghai`）分页查询，可按 `roomId`/`status` 过滤 |
| GET | `/v1/scheduling/schedules/{scheduleId}` | `getSchedule` | `m1:schedule:read` | 台次详情；越界/不存在 404 |
| POST | `/v1/scheduling/schedules` | `createSchedule` | `m1:schedule:write` | 创建台次；冲突 409 + `conflicts[]` |
| PUT | `/v1/scheduling/schedules/{scheduleId}` | `rescheduleSchedule` | `m1:schedule:write` | 调整时间/术间/台次序号；必须回传 `version`，冲突/过期 409 |
| POST | `/v1/scheduling/schedules/{scheduleId}/cancel` | `cancelSchedule` | `m1:schedule:write` | 停台/取消；原因码 + 原因 + 版本 |
| POST | `/v1/scheduling/schedules/conflicts` | `detectScheduleConflicts` | `m1:schedule:read` | 只读冲突预检（不落库），供工作台即时提示 |

旧 `POST /v1/scheduling/conflicts/check`（`checkScheduleConflict`）标记 `deprecated`，计划 1.0.0
删除；**0.4.0 仍映射到同一 `checkConflicts` 服务方法**（响应与新端点逐字节一致），不得删除
（ADR-0011 弃用窗口）。新代码使用 `schedules/conflicts`。冲突响应结构：
`conflicts[].conflictType/severity/message/conflictingScheduleId`；`conflictType` 使用
`x-extensible-enum`（开放枚举，客户端必须容忍未知值）。契约 0.4.0 由 `enum` 放宽为
`x-extensible-enum` 属**非严格 additive**（JSON 不变、TS 由字面量联合变 `string`），已在
ADR-0011 增补与 `docs/api/README.md` §3.2 登记；强类型诉求记为 Q，待契约 1.0.0 评估。

错误语义：400 字段/枚举/日期格式/时长/原因码格式（`reasonCode` 须匹配 `^[A-Z_]{2,32}$`）；
401 未认证；403 缺权限或数据范围为空（写 `ACCESS_DENIED` 审计）；404 单对象不存在或越界
（不泄露存在性）；409 排程冲突/重复申请/非法状态流转/乐观锁（`conflictType` 分别为
`APPLICATION_ALREADY_SCHEDULED`/`STATE_CONFLICT`/`VERSION_CONFLICT` 等）；422 申请不存在或
状态不允许排程。时间窗非法（结束不晚于开始或时长不在 1–1440 分钟）统一 400，不再以 409
`TIME_WINDOW_INVALID` 返回（该枚举值保留给内部规则防御）。

## 4. 状态机

### 4.1 手术申请

```text
                 ┌──────────── cancel ────────────┐
                 ▼                                │
(创建/导入) ─→ SUBMITTED ─→ ACCEPTED ─→ SCHEDULED ─┴─→ CANCELLED（终态）
                    │            │
                    └────────────┴──────────────→ REJECTED（终态）
```

- 初始状态固定 `SUBMITTED`，来源 `HIS`（模拟导入）或 `MANUAL`（手工）；
- `CANCELLED`/`REJECTED` 为终态，再次取消返回 409；
- 每次创建/状态变更写 `m1_sched.application_status_history`（append-only：from_status/to_status/
  reason_code/reason/operator_id/occurred_at），同时写 `SURGERY_APPLICATION_*` 审计；
- 取消必须回传 `version`，库层乐观锁失败（并发改状态）返回 409。

### 4.2 排程台次

```text
PLANNED ─→ PUBLISHED ─→ IN_PROGRESS ─→ COMPLETED
   │            │
   └────────────┴──→ DELAYED ─→ (可继续/取消)
   └──────────────────────────→ CANCELLED（终态）
```

- 仅 `SUBMITTED`/`ACCEPTED` 申请可排程；`CANCELLED`/`REJECTED` 申请返回 422，`SCHEDULED`
  申请再次排程返回 409 `APPLICATION_ALREADY_SCHEDULED`（避免重复建台）；本任务不自动改写申请
  状态，申请状态由后续流程联动；
- 创建时 `CreateScheduleRequest.priority` 落库为 `schedule.priority`（0=ELECTIVE/1=URGENT/
  2=EMERGENCY），响应按库值回显；`staffIds`/`equipmentTags` 在契约标 `x-deferred: true`
  （M2-M2-02/M3 实现，当前服务端忽略）；
- `IN_PROGRESS`/`COMPLETED`/`CANCELLED` 台次不允许调整或取消（409）；
- 每个申请同一时刻只允许一个未取消台次（V8 部分唯一索引 `uk_schedule_application_active`）；
- 创建/调整/取消分别写 `SCHEDULE_CREATE/SCHEDULE_UPDATE/SCHEDULE_CANCEL` 审计。

## 5. HIS 适配器与 EXT-01

- 端口：`com.anesop.m1.api.his.HisSurgeryApplicationPort#pull(count, seed)`，返回
  `HisSurgeryApplicationBatch(simulated, adapter, applications)`；
- 当前实现：`MockHisApplicationAdapter`，只生成 SYNTHETIC 数据，固定 `simulated=true`、
  `adapter=MOCK_HIS`，条数严格 1–5（请求多少返回多少，越界 400），相同 `seed` 生成相同申请号
  （保证重复导入幂等）；
- 配置：`anesop.m1.his.mock.base-seed`（默认 20260101）、`department-name`、`department-id`、`site-id`
  （测试 profile 指向 V100 演示组织；生产未配置时申请不带科室标识，DEPT 用户不可见）；
- EXT-01（真实 HIS HL7 v2/REST 对接）未就绪：真实适配器接入时只替换端口实现，服务层与契约不变；
- 响应中的 `simulated`/`adapter`/`message` 明确标识“模拟”，禁止在验收/演示中把模拟数据当作真实 HIS 数据。

## 6. 数据与脱敏

- `m1_sched.patient_snapshot`：`his_patient_no` 唯一；只存姓名、性别、出生日期/年龄、
  `inpatient_no_masked`、`inpatient_no_hash`、科室/病区/床号；**不存身份证、手机号、住院号明文**；
- `inpatient_no_hash`：HMAC-SHA-256（pepper 来自 `anesop.m1.inpatient-pepper.value` /
  `ANESOP_INPATIENT_PEPPER`，Base64 且解码后 ≥32 字节），仅用于匹配/去重，唯一索引、不可逆、
  不出接口；缺失/非法 Base64/长度不足默认拒绝启动（fail-closed），只有 dev/test 显式
  `anesop.m1.inpatient-pepper.allow-dev-generated=true` 才生成进程内随机 pepper 并 WARN；
- HIS 导入患者 upsert 使用单条 `INSERT ... ON CONFLICT (his_patient_no) DO UPDATE ... RETURNING id`，
  并发导入不在已中止事务内回查；`birthDate` 缺失拒绝导入/创建（400），年龄不用 now() 伪造；
- 状态历史 `reason_code varchar(64)` 与 `reason varchar(500)` 分列；表级触发器拒绝
  UPDATE/DELETE/TRUNCATE（append-only，SQLSTATE 55000）；
- 排程表 `m1_sched.operating_room` / `schedule`（V8）：时间统一 `timestamptz`，`end_at > start_at`
  由 `ck_schedule_time_window` 约束；取消保留 `reason` 与审计，不物理删除；
- 展示：住院号统一 `前 4 + **** + 后 4`（如 `SYNT****0001`）；日志与审计 detail 不含姓名、
  诊断原文、住院号（明文或脱敏值）、证件号、手机号；冲突文案只含术间编号/台次序号/时段；
- 数据范围：创建/调整/冲突预检在术间范围外**再校验申请与患者可见性**，不可见统一 404 且响应
  不含 `patientName/hisPatientNo`；ROOM 范围的文档例外——ROOM 用户无组织绑定，申请可见性按其
  绑定术间的 `dept_id/site_id` 推导（仅用于申请可见性，不扩大术间范围）。
  `ALL` 不过滤；`DEPT/SITE` 按申请/患者/术间的 `dept_id`/`site_id`（逻辑外键 →
  `m0_platform.sys_org.id`）；`ROOM` 按 `sys_user_room` 绑定术间；`SELF` 按申请创建人；
  `ROOM`/无有效绑定/`SELF` 看术间 → 403 或不可见，fail-closed；单对象越界一律 404。

## 7. 权限与审计

- V7 新增 `m1:application:read`（SYS_ADMIN、SYS_OR_MANAGER、SYS_SURGEON）、
  `m1:application:write`（SYS_ADMIN、SYS_OR_MANAGER）与 `m1:patient:read`
  （SYS_ADMIN、SYS_SURGEON、SYS_OR_MANAGER，患者摘要最小权限）；V2 的 `m1:access` 菜单占位保留；
- V8 新增 `m1:schedule:read`/`m1:schedule:write`，**仅授 SYS_ADMIN、SYS_OR_MANAGER**（最小权限：
  外科/麻醉医师不直接改排程）；
- 审计动作：`SURGERY_APPLICATION_CREATE/IMPORT/CANCEL`（resource_type=`SURGERY_APPLICATION`）
  与 `SCHEDULE_CREATE/UPDATE/CANCEL`（resource_type=`SCHEDULE`，detail 只含 applicationId/roomId/
  时段/序号/reasonCode）；
- 403 由 `ApiExceptionHandler` 写 `ACCESS_DENIED` 审计；审计 detail 只含状态、来源、原因码等非 PHI 字段。

## 8. 排程规则引擎（`SchedulingRule` + 注册表）

| 规则 | code / conflictType | 阻断 | 判定 |
|---|---|---|---|
| 术间时段重叠 | `ROOM_OVERLAP` | 是 | 同术间、半开区间 `[start,end)` 真重叠；**相邻相接（end=start）不算冲突** |
| 术者冲突 | `STAFF_CONFLICT` | 是 | 申请术者姓名相同且窗口重叠（跨术间）；术者缺失不评估 |
| 患者冲突 | `PATIENT_CONFLICT` | 是 | 同一 patientId 窗口重叠（跨术间）；患者未知不评估 |
| 术间类型匹配 | `ROOM_TYPE_MISMATCH` | 是 | 申请 `requiredRoomType` 非空且与术间 `roomType` 不一致 |
| 时间窗/时长 | `TIME_WINDOW_INVALID` | 是 | 保留为内部防御；API 边界在进入规则前统一 400，正常请求不会命中 |

- 规则实现为 Spring Bean（`@Order` 排序），`SchedulingRuleRegistry` 汇总；新增规则只新增 Bean，
  服务/控制器不感知具体规则（可配置扩展点）；注册表亦支持显式注入固定规则列表用于测试。
- 服务级状态冲突使用专用类型，避免误用规则类型：`APPLICATION_ALREADY_SCHEDULED`（申请已有
  生效台次）、`VERSION_CONFLICT`（乐观锁版本过期）、`STATE_CONFLICT`（台次状态不允许操作）。
- 设备/隔离/资质（`EQUIPMENT_CONFLICT`/`ISOLATION_CONFLICT`/`QUALIFICATION_MISMATCH`）为后续
  规则预留枚举值，当前不产生。
- 冲突检测在创建/调整事务内与只读预检共用同一注册表，避免“预检通过、保存失败”口径漂移。

## 9. 并发策略（高风险：事务与并发）

1. 创建/调整显式 `@Transactional(isolation = READ_COMMITTED)`（PostgreSQL 默认隔离级别）；
2. **全局锁顺序（避免死锁）**：先人员/患者维度 advisory 锁，再术间行锁。
   - 人员/患者：`pg_advisory_xact_lock(hashtext('schedule:surgeon:<name>'))`、
     `pg_advisory_xact_lock(hashtext('schedule:patient:<id>'))`；多个 key 去重后按**字典序**
     升序获取（`TreeSet`），任意两事务的加锁顺序一致，不会交叉等待；锁随事务提交/回滚释放。
   - 术间：`SELECT ... FROM m1_sched.operating_room WHERE id=? FOR UPDATE`，只锁目标术间。
3. 锁内查询重叠台次并执行规则引擎；后到事务在前一事务提交后读到已提交台次，复检到冲突；
   冲突抛 `SchedulingConflictException`，不写库，Web 层返回 409 + `conflicts[]`；
4. 数据库第二道防线：`ck_schedule_time_window`（end>start）、`ck_schedule_priority` 与部分唯一
   索引（申请唯一活动台次）。哈希碰撞只会导致无关排程短暂串行，不影响正确性。
- 并发测试（`CyclicBarrier` 制造真实竞争，两线程同时进入 `create`）：
  同室同窗、跨术间同术者、跨术间同患者三个用例 → **恰一个成功、一个 409**，输出
  `success=1 ... rows=1`。

## 10. 测试

- 后端单测（`m1-impl`）：服务状态机/幂等/数据范围/校验、Masker/Hasher、Mock 适配器、
  排程 5 条规则（含边界相接）与排程服务（冲突/版本/状态/审计/数据范围/原因码/时间窗）；
  m1-impl 共 100 用例，JaCoCo 行 94.31%、分支 71.58%（阈值 80/70）；
- 后端集成（`api-bootstrap`，嵌入式 PG16）：模拟导入幂等、脱敏与无明文、创建/列表/筛选/详情、
  取消状态历史 + 审计、403、DEPT 数据范围、患者摘要接口；排程 14 用例：术间列表/`siteId`+
  `status` 过滤与非法参数 400、创建→日查询→详情→调整→取消 + 3 条审计、409 + conflicts[]
  （重叠/术者/患者/类型）、冲突预检与新/旧端点响应一致、时间窗 400/申请状态（409/422）、
  403/DEPT/ROOM 越界与跨科室申请 404（无 PHI 泄露）、并发同室/同术者/同患者恰一成功、
  1000 条日排程查询 <2s 本地基准；后端合计 347 用例（api-bootstrap 98 / m0 144 / m1 100 /
  contracts 5）；
- 前端 Vitest：申请列表渲染/筛选/错误/权限按钮隐藏、详情与 404；排程工作台加载/冲突预检/
  409 提示（优先解析 `conflicts[]` 并保留 `conflictingScheduleId`，缺失时回退 `details`）/
  冲突解决/仅 PLANNED/PUBLISHED 显示调整取消/本地时区日期/只读权限；
- Playwright E2E：`m1-applications.spec.ts`（4 条）+ `m1-schedule-conflict.spec.ts`
  （E2E-01：制造冲突→提示→改术间解决；只读权限）；
- 迁移/安全：V7/V8 结构断言（`uk_schedule_application_active`/`ck_schedule_time_window`/
  `ck_schedule_priority`/权限授予/5 个 SYNTHETIC 术间）、状态历史 append-only 负向用例、
  并发导入仅建一行、缺 pepper 启动失败；
- 契约：`bash scripts/gen-contracts.sh` + `bash scripts/contract-check.sh`（spectral/oasdiff/HEAD
  baseline/漂移全绿）。

## 11. 已知限制与后续

- 真实 HIS 适配器（HL7 v2/REST）与回写为 EXT-01，接入前只允许模拟导入；
- `SITE` 数据范围当前按 `site_id` 精确匹配（DEPT 按 `dept_id`），未展开科室子树；
- 申请状态 `ACCEPTED`/`SCHEDULED` 的推进接口由 M2-M2-02 承接；本任务只落地排程台次，不改写申请状态；
- 设备依赖、隔离要求与医师资质规则待 M3 人员资质主数据与 M2-M2-02 评审后接入（枚举已预留；
  `ConflictCheckRequest.staffIds/equipmentTags` 契约标 `x-deferred: true`，服务端当前忽略）；
- 1000 条日排程查询本地基准 <2s（`SchedulingApiIntegrationTest#thousandScheduleQueryStaysUnderLocalBaseline`，
  嵌入式 PG16 + MockMvc，含 JSON 序列化；仅作本机回归基线，非生产容量承诺）；
- `patient.yaml` 中 `/v1/surgery-applications*` 为 S0-05 历史端点，已在 0.3.0 标记 `deprecated`，
  由 scheduling 的 `/v1/scheduling/applications*` 取代，M1 契约收敛时统一下线。
