# syntax=docker/dockerfile:1.7
# =============================================================================
# ANESOP Web 镜像（Vue 3.5 + Vite 6 构建，Nginx 1.27 运行）
#
# 构建上下文 = 仓库根（不是 deploy/compose/）：
#   docker build -f deploy/compose/web.Dockerfile -t anesop/web:local .
# 在 compose 中由 deploy/compose/docker-compose.yml 的 build.context: ../.. 指定。
#
# 环境受限（EXT-08）：本机 Docker daemon 不可用，本文件未实际构建/运行；
# 首次可运行环境须补基础镜像 digest、实跑构建与镜像扫描（HIGH/CRITICAL 阻断）。
# =============================================================================

# -----------------------------------------------------------------------------
# 构建阶段：pnpm workspace 冻结锁文件安装，仅构建 @anesop/web
# -----------------------------------------------------------------------------
# TODO(digest,EXT-08): 首次可运行环境补 @sha256:
FROM node:22-alpine AS build

# 与宿主/CI 对齐的 pnpm 版本（lockfileVersion 9.0）；升级须连同 lockfile 一起评审
ARG PNPM_VERSION=11.28.5
RUN npm install -g "pnpm@${PNPM_VERSION}" \
    && pnpm --version

WORKDIR /workspace

# 先复制工作区清单以复用依赖层缓存
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json ./apps/web/package.json
# workspace 包清单（packages/*）：保持与 lockfile importers 一致，避免 frozen-lockfile 漂移
COPY packages ./packages

# 冻结锁文件；只装 web 及其依赖（不安装无关 workspace 包）
RUN pnpm install --frozen-lockfile --filter @anesop/web...

# 源码（node_modules/dist 由 *.dockerignore 排除）
COPY apps/web ./apps/web

# package.json 的 build = vue-tsc 类型检查 + vite build；类型错误会阻断镜像构建
RUN pnpm -C apps/web build

# -----------------------------------------------------------------------------
# 运行阶段：Nginx 1.27，配置与静态产物固化进镜像
# -----------------------------------------------------------------------------
# TODO(digest,EXT-08): 首次可运行环境补 @sha256:
FROM nginx:1.27-alpine

# 站点配置（TLS 证书不入镜像，由 compose 只读挂载 /etc/nginx/certs）
COPY deploy/nginx/anesop.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html

# 证书挂载点占位（实际文件由宿主提供；构建期不做 nginx -t，因证书尚未挂载）
RUN mkdir -p /etc/nginx/certs \
    && chown -R nginx:nginx /usr/share/nginx/html

EXPOSE 80 443

# 与 compose healthcheck 一致的入口；nginx 官方镜像默认以前台运行
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD wget -q -O- http://127.0.0.1/healthz | grep -q ok || exit 1

CMD ["nginx", "-g", "daemon off;"]
