# 信息科管理员培训（R9 / SYS_ADMIN）

> 目标：能管理账号与会话、按最小权限授权、查审计、配合发布/备份/应急。
> 前置：管理员账号、`must_change_password` 已处理；演示库或已初始化生产库。
> 注意：当前 Web 端**没有独立用户维护页**，账号/角色维护通过管理员接口或实施变更单执行
> （生产 springdoc 关闭，接口调用须经受控脚本/堡垒机，禁止在浏览器控制台留 token）。

## 1. 登录、改密与会话

1. 打开 `https://<院方域名>/`，输入用户名与口令；
2. 首次登录进入"修改口令"（12–128 位，至少三类字符）；
3. 改密成功后重新登录，确认菜单出现；
4. 左侧"会话管理"（`/m0/sessions`）查看当前账号会话，异常会话可撤销；
5. 离岗锁屏；共享账号禁止。

> 截图占位 1：登录页与修改口令页（演示账号，禁止真实姓名）。

## 2. 账号、角色与数据范围

权限模型：用户 → 角色 → 权限点；数据范围 `ALL/SITE/DEPT/ROOM/SELF` 由角色 + 绑定表决定。

| 动作 | 方式 | 要点 |
|---|---|---|
| 新建用户 | `POST /v1/auth/users`（管理员）或变更单 | 初始口令一次性；`must_change_password=true`；工号唯一 |
| 停用/重置口令 | 管理员接口/变更单 | 停用不删除，保留审计；重置后强制改密 |
| 角色分配 | 变更单 | 按岗位最小权限；SYS_ADMIN 只给信息科少数人 |
| 数据范围 | `sys_user_org` / `sys_user_room`（变更单） | ROOM 无绑定 fail-closed 403；不要先给 ALL 再收 |
| 分级授权 | `/m4/staff/authorizations` | 双人审批、不得自授；审批人不同人 |

创建用户示例（在受控终端执行；`TOKEN` 不写历史/日志）：

```bash
BASE=https://<院方域名>/api
read -r -s -p "管理员 access token: " TOKEN; echo
curl -sS -X POST "$BASE/v1/auth/users" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"anes_doc_01","displayName":"<姓名>","employeeNo":"<工号>","roleCodes":["SYS_ANESTHESIOLOGIST"],"initialPassword":"<一次性口令>"}' \
  | python3 -m json.tool
unset TOKEN
```

> 截图占位 2：用户创建成功响应（隐去 token/口令）与角色分配变更单。

## 3. 审计查询（只读）

审计表 `m0_platform.sys_audit_log` 只追加（UPDATE/DELETE 被触发器拒绝），保留 ≥6 个月。
经堡垒机/只读账号查询，**不得导出含 PHI 的明细**：

```sql
-- 最近 50 条登录/越权/业务动作（只取必要列）
select occurred_at, actor_name, action, resource_type, result, trace_id
from m0_platform.sys_audit_log
order by occurred_at desc
limit 50;

-- 某对象操作历史
select occurred_at, actor_name, action, result
from m0_platform.sys_audit_log
where resource_type = '<类型>' and resource_id = '<id>'
order by occurred_at desc;
```

- 登录失败/越权（`ACCESS_DENIED`）/令牌异常均应有记录；
- 审计 detail 不应出现口令、令牌、患者标识；发现异常按安全事件上报。

> 截图占位 3：审计查询结果（脱敏，仅演示数据）。

## 4. 组织、术间与字典

- 组织/术间/人员是院方主数据，按 [`../deploy/DATA-INIT.md`](../deploy/DATA-INIT.md) §4 变更单导入；
- 字典（麻醉方式、ASA、术间类型等）影响下拉与校验，变更前评估历史数据兼容；
- 停用而非删除：组织/术间/用户软删除保留历史指向；
- 每次变更前备份、变更后抽查：`sys_org`、`m1_sched.operating_room`、字典项。

## 5. 日常运维与发布配合

1. 每日：`curl -fsS https://<域名>/healthz`、API `/actuator/health`、磁盘、备份成功、证书天数；
2. 每周：队列积压、慢查询、审计增长、账号异动；
3. 发布：按 [`../deploy/DEPLOYMENT.md`](../deploy/DEPLOYMENT.md)，窗口周二/周四 20:00–22:00，
   `release.yml` 审批；发布前备份、发布后冒烟；
4. 回滚：按 [`../deploy/ROLLBACK.md`](../deploy/ROLLBACK.md)，10 分钟内切回上一版本；
5. 应急：按 [`../runbook/EMERGENCY-PLAYBOOK.md`](../runbook/EMERGENCY-PLAYBOOK.md)，
   联系人见 [`../runbook/CONTACTS.md`](../runbook/CONTACTS.md)。

## 6. 常见错误与处理

| 现象 | 可能原因 | 处理 |
|---|---|---|
| 管理员登录 429 | 触发登录限流（IP+用户名） | 等 `Retry-After` 秒；检查是否脚本重试；不要狂点 |
| 新建用户 409 | 用户名/工号重复 | 查用户列表；停用旧号而非复用 |
| 用户登录后 403 | 角色未授予/范围为空 | 补角色与绑定；ROOM/SELF 必绑 |
| 无法查看某科室数据 | 数据范围 DEPT 不含该科室 | 调整绑定或换授权；不要临时给 ALL |
| 审计查不到某操作 | 动作未落审计/查错时间 | 按 traceId/对象查；仍无则报缺陷，不删改 |
| 备份任务失败 | 口令变更/磁盘满/权限 | 按备份手册检查；连续失败升级 P2 |
| 证书剩余 <30 天 | 未续签 | 提前续签并 `nginx -t`；避免 HSTS 下证书过期 |
| 发布后大面积 502 | API 未健康/端口/配置 | 先回滚，再按 runbook 排查 |

## 7. 考核（3 项）

1. 在演示库创建一个麻醉医师账号并授予正确角色与数据范围；
2. 查出一条越权审计记录并说明处置；
3. 口述发布前 5 项阻断检查与回滚 10 分钟时间盒。
