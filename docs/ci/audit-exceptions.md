# 依赖漏洞审计例外（S0-04）

> 规则：`ci/deps-license` 对 `pnpm audit` 中 **high/critical** 逐条要求登记在本文件；
> 未登记或 `expires` 过期即失败。生产依赖树另有
> `pnpm audit --prod --audit-level high` 硬门禁（不受本表影响）。
>
> 维护：S0-04 起草；到期前由责任人升级依赖或重新评审续期。
> 复核：**2026-11-13（M2-M0-03）**。vitest 3.2.7 → 4.1.12、新增 `glob@14.0.6` /
> `minimatch@11.0.8` override 后，`pnpm audit --dev` 为 **0 漏洞**；glob/tinypool 三类
> high/critical 例外全部失效并删除，阻断级例外清零。

## 一、阻断级例外（high/critical，逐条登记）

| GHSA | 包 | 严重度 | 依赖面 | 说明 | 处置与责任人 | 期限 |
|---|---|---|---|---|---|---|
| （无） | — | — | — | 2026-11-13 复核：glob（GHSA-5j98-mcp5-4vw2）、tinypool（GHSA-5gmw-xhrv-c9v3、GHSA-85c8-ppgw-ccpr）已分别随 `glob@14.0.6` override 与 `vitest@4.1.12` 消除 | 前端负责人 | 已关闭 |

## 二、记录项（低于阻断线，仅登记）

| GHSA | 包 | 严重度 | 依赖面 | 备注 |
|---|---|---|---|---|
| GHSA-5m5x-9j46-h678 | element-plus <=2.11.0 | moderate | prod | el-link 输入校验不足；当前 2.9.11，S0-08 升级到 ≥2.11.1 时消除 |
| — | vitest / @vitest/mocker / glob / minimatch | — | dev | 2026-11-13 已随 vitest 4.1.12 + glob 14.0.6 + minimatch 11.0.8 全部消除，仅保留本行作为复核记录 |

## 三、新增例外的流程

1. 先判断是否可升级修复；可修复的一律升级，不登记例外。
2. 不可立即修复时，在本表登记 GHSA、影响面（是否 prod）、补偿措施、责任人、`expires`（≤90 天）。
3. 例外到期前必须修复或经架构师/安全负责人重新评审续期；过期后 CI 自动阻断。

## 四、非依赖类安全例外（记录，不计入漏洞门禁）

| 发现 | 文件 | 规则 | 处置 | 责任人 | 关闭条件 |
|---|---|---|---|---|---|
| nginx master 以 root 启动（worker 为 nginx 用户） | `deploy/compose/web.Dockerfile` | Semgrep `dockerfile.security.missing-user` | 官方 `nginx:1.27-alpine` 绑定 80/443 需要 root master；只读静态资源、无写挂载，接受为部署评审例外 | 平台/部署负责人 | M6 上线准备前切换 `nginxinc/nginx-unprivileged` 或自建非 root 镜像，并实测通过；关闭前不得公网暴露 |

> 说明：该发现不在 `ci/sast` 扫描范围（`deploy/` 由 `ci/image-scan` + 部署评审覆盖），
> 记录于 `docs/deploy/README.md` §13 与本表，便于上线前追踪。依赖类 high/critical 例外见 §一。
