# 依赖漏洞审计例外（S0-04）

> 规则：`ci/deps-license` 对 `pnpm audit` 中 **high/critical** 逐条要求登记在本文件；
> 未登记或 `expires` 过期即失败。生产依赖树另有
> `pnpm audit --prod --audit-level high` 硬门禁（不受本表影响）。
>
> 维护：S0-04 起草；到期前由责任人升级依赖或重新评审续期。
> 到期：**2026-12-18**（M1 前，与 JaCoCo/白名单 ratchet 同批清理）。

## 一、阻断级例外（high/critical，逐条登记）

| GHSA | 包 | 严重度 | 依赖面 | 说明 | 处置与责任人 | 期限 |
|---|---|---|---|---|---|---|
| GHSA-5j98-mcp5-4vw2 | glob >=10.2.0 <10.5.0 | high | dev（eslint/test-exclude/js-beautify） | 仅 CLI `-c/--cmd` 参数注入，构建脚本不拼接不可信输入 | 升级 vitest/openapi 相关工具链时随 glob 一并升级；前端负责人 | expires=2026-12-18 |
| GHSA-5gmw-xhrv-c9v3 | tinypool <=2.1.0 | critical | dev（vitest worker） | 原型污染链需攻击者控制 worker 选项；仅本地/CI 测试进程 | 升级 vitest ≥4.1.11（当前 3.2.7），由 S0-08 依赖刷新或 Dependabot PR 承载；前端负责人 | expires=2026-12-18 |
| GHSA-85c8-ppgw-ccpr | tinypool <2.1.2 | critical | dev（vitest worker） | 同上，原型污染到 RCE；测试进程不接受外部输入 | 同上；前端负责人 | expires=2026-12-18 |

## 二、记录项（低于阻断线，仅登记）

| GHSA | 包 | 严重度 | 依赖面 | 备注 |
|---|---|---|---|---|
| GHSA-5m5x-9j46-h678 | element-plus <=2.11.0 | moderate | prod | el-link 输入校验不足；当前 2.9.11，S0-08 升级到 ≥2.11.1 时消除 |
| GHSA-82fw-gwwq-j7x9 | vitest / @vitest/mocker | moderate | dev | 随 vitest 升级消除 |
| GHSA-qxg6-89mg-rqv8 | @vitest/mocker | moderate | dev | 随 vitest 升级消除 |
| GHSA-3g3m-2fjr-6pw4 | glob | moderate | dev | AbortSignal 取消绕过；随 glob 升级消除 |
| GHSA-g3hv-75wg-m249 | minimatch | moderate | dev | ReDoS；随工具链升级消除 |

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
