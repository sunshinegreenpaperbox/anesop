# M1 手术排程管理（模块说明）

> 状态：M2-M1-01「手术申请接收与校验」落地版（2026-11-13）。
> 契约：`packages/contracts/openapi/scheduling.yaml` 0.3.0、`patient.yaml` 0.3.0；
> 方案书：§2.4.2（M1 手术排程）、§2.7.1（HIS 集成）、§3.4.2/§3.4.3（实体与数据库）、
> §3.5.3（I-01a/I-01b）、§3.9.3（加密脱敏）。
> 全部示例、演示账号、患者与申请均为 **SYNTHETIC** 合成数据。

## 1. 职责与边界

M1 负责围术期排程上游的“手术申请接收与校验”：

- 接收 HIS 手术申请（当前为 Mock 适配器，EXT-01 未就绪）与手工/测试创建；
- 以 `his_application_no` 为入站幂等键落库，重复导入不增行、手工重复提交 409；
- 提供申请分页/详情/取消与状态历史，向下游排程（M2-M2-01）、术前访视（M2-M4-01）供数；
- 提供排程/申请所需的患者脱敏摘要（姓名、性别、出生日期/年龄、脱敏住院号、科室/病区/床号），
  **不提供证件号与手机号**（含脱敏值）。

非目标：术间/台次与冲突检测（M2-M2-01）、停改台/急诊加台（M2-M2-02）、排程分析（M2-M6-01）。

## 2. 代码结构

```text
apps/api/modules/m1/
├── api/  com.anesop.m1.api
│   ├── application/  SurgeryApplicationService、查询条件、异常、状态语义
│   ├── his/          HisSurgeryApplicationPort 端口与入站记录（模拟标记）
│   ├── patient/      PatientQueryService（患者脱敏摘要）
│   └── security/     M1Permissions（m1:application:read|write）
└── impl/ com.anesop.m1.impl
    ├── application/  SurgeryApplicationServiceImpl（事务/幂等/状态机/审计）
    ├── patient/      PatientQueryServiceImpl（数据范围过滤）
    ├── his/          MockHisApplicationAdapter（SYNTHETIC 生成器）
    ├── persistence/  实体与 Mapper（m1_sched 三表）
    └── support/      InpatientNoMasker / InpatientNoHasher
```

模块边界：`m1-impl` 只依赖 `m1-api`、`m0-api`（认证主体、数据范围、审计）与基础设施；
不访问其他模块 impl，不跨 schema 读写 M0 表。`api-bootstrap` 是唯一依赖 `m1-impl` 的装配点。

## 3. 接口（scheduling 0.3.0）

| 方法 | 路径 | operationId | 权限 | 说明 |
|---|---|---|---|---|
| GET | `/v1/scheduling/applications` | `listSurgeryApplications` | `m1:application:read` | 分页/状态/紧急度/科室过滤，按数据范围裁剪 |
| POST | `/v1/scheduling/applications` | `submitSurgeryApplication` | `m1:application:write` | 手工/测试创建；重复申请号 409，未知患者 422 |
| GET | `/v1/scheduling/applications/{applicationId}` | `getSchedulingApplication` | `m1:application:read` | 详情 + 完整状态历史；越界/不存在 404 |
| POST | `/v1/scheduling/applications/import` | `importSurgeryApplications` | `m1:application:write` | 模拟 HIS 拉取导入；幂等，响应 `simulated=true` |
| POST | `/v1/scheduling/applications/{applicationId}/cancel` | `cancelSchedulingApplication` | `m1:application:write` | 取消 + 状态历史 + 审计；终态/版本冲突 409 |

患者摘要（patient 0.3.0）：`GET /v1/patients`（支持 `hisPatientNo` 精确过滤）与
`GET /v1/patients/{patientId}`，同样需要 `m1:application:read` 并受数据范围约束。

错误语义：400 字段/枚举/时长校验；401 未认证；403 缺权限或数据范围为空（写 `ACCESS_DENIED` 审计）；
404 单对象不存在或越界（不泄露存在性）；409 重复申请/非法状态流转/乐观锁；422 患者不在快照（创建/导入无路径参数）。

## 4. 状态机

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
  reason/operator_id/occurred_at），同时写 `SURGERY_APPLICATION_*` 审计；
- 取消必须回传 `version`，库层乐观锁失败（并发改状态）返回 409。

## 5. HIS 适配器与 EXT-01

- 端口：`com.anesop.m1.api.his.HisSurgeryApplicationPort#pull(count, seed)`，返回
  `HisSurgeryApplicationBatch(simulated, adapter, applications)`；
- 当前实现：`MockHisApplicationAdapter`，只生成 SYNTHETIC 数据，固定 `simulated=true`、
  `adapter=MOCK_HIS`，条数钳制 3–5，相同 `seed` 生成相同申请号（保证重复导入幂等）；
- 配置：`anesop.m1.his.mock.base-seed`（默认 20260101）、`department-name`、`department-id`、`site-id`
  （测试 profile 指向 V100 演示组织；生产未配置时申请不带科室标识，DEPT 用户不可见）；
- EXT-01（真实 HIS HL7 v2/REST 对接）未就绪：真实适配器接入时只替换端口实现，服务层与契约不变；
- 响应中的 `simulated`/`adapter`/`message` 明确标识“模拟”，禁止在验收/演示中把模拟数据当作真实 HIS 数据。

## 6. 数据与脱敏

- `m1_sched.patient_snapshot`：`his_patient_no` 唯一；只存姓名、性别、出生日期/年龄、
  `inpatient_no_masked`、`inpatient_no_hash`、科室/病区/床号；**不存身份证、手机号、住院号明文**；
- `inpatient_no_hash`：HMAC-SHA-256（pepper 来自 `anesop.m1.patient-hash-pepper` /
  `ANESOP_M1_PATIENT_HASH_PEPPER`），仅用于匹配/去重，唯一索引、不可逆、不出接口；
  未注入 pepper 时退化为 SHA-256 并 WARN，仅允许 SYNTHETIC 演示/测试，生产必须注入；
- 展示：住院号统一 `前 4 + **** + 后 4`（如 `SYNT****0001`）；日志与审计 detail 不含姓名、
  诊断原文、住院号（明文或脱敏值）、证件号、手机号；
- 数据范围：`ALL` 不过滤；`DEPT` 按申请/患者 `dept_id`（逻辑外键 → `m0_platform.sys_org.id`）；
  `SELF` 按申请/患者 `created_by`（M1 暂无患者—医师归属关系）；`ROOM`/无有效绑定 → 403 或不可见，
  fail-closed；单对象越界一律 404。

## 7. 权限与审计

- V7 新增 `m1:application:read`（SYS_ADMIN、SYS_OR_MANAGER、SYS_SURGEON）与
  `m1:application:write`（SYS_ADMIN、SYS_OR_MANAGER）；V2 的 `m1:access` 菜单占位保留；
- 审计动作：`SURGERY_APPLICATION_CREATE`、`SURGERY_APPLICATION_IMPORT`、
  `SURGERY_APPLICATION_CANCEL`；resource_type 固定 `SURGERY_APPLICATION`，resource_id 为申请 UUID；
- 403 由 `ApiExceptionHandler` 写 `ACCESS_DENIED` 审计；审计 detail 只含状态、来源、原因码等非 PHI 字段。

## 8. 测试

- 后端单测（`m1-impl`）：服务状态机/幂等/数据范围/校验、Masker/Hasher、Mock 适配器；
  JaCoCo 行 ≥80%、分支 ≥70%（当前 93%/70%）；
- 后端集成（`api-bootstrap`，嵌入式 PG16）：模拟导入幂等（重复导入 0 新增）、脱敏与无明文、
  创建/列表/筛选/详情、409/404/400/422、取消状态历史 + 审计、403、DEPT 数据范围、患者摘要接口；
- 前端 Vitest：列表渲染/筛选/错误/权限按钮隐藏、详情与 404；
- 契约：`bash scripts/gen-contracts.sh` + `bash scripts/contract-check.sh`（spectral/oasdiff/漂移全绿）。

## 9. 已知限制与后续

- 真实 HIS 适配器（HL7 v2/REST）与回写为 EXT-01，接入前只允许模拟导入；
- `SITE` 数据范围当前只按申请/患者 `site_id` 精确匹配（未展开科室子树），由 M2-M2-01 的排程数据范围统一收敛；
- 申请状态 `ACCEPTED`/`SCHEDULED` 的推进接口由 M2-M2-01 排程工作台提供；本任务只落地创建/取消；
- `patient.yaml` 中 `/v1/surgery-applications*` 为 S0-05 历史端点，已在 0.3.0 标记 `deprecated`，
  由 scheduling 的 `/v1/scheduling/applications*` 取代，M1 契约收敛时统一下线。
