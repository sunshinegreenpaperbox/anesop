# syntax=docker/dockerfile:1.7
# =============================================================================
# ANESOP API 镜像（Spring Boot 3.4 / Java 21）
#
# 构建上下文 = 仓库根（不是 deploy/compose/）：
#   docker build -f deploy/compose/api.Dockerfile -t anesop/api:local .
# 在 compose 中由 deploy/compose/docker-compose.yml 的 build.context: ../.. 指定。
#
# 环境受限（EXT-08）：本机 Docker daemon 不可用，本文件未实际构建/运行；
# 首次可运行环境须补基础镜像 digest、实跑构建与镜像扫描（HIGH/CRITICAL 阻断）。
# =============================================================================

# -----------------------------------------------------------------------------
# 构建阶段：仓库根为 /workspace；Maven 本地仓库用 BuildKit 缓存挂载，避免写入镜像层
# -----------------------------------------------------------------------------
# TODO(digest,EXT-08): 首次可运行环境补 @sha256:
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# 先复制 POM 以复用依赖层缓存；仓库无 mvnw，使用镜像内 Maven（AGENTS 允许构建期例外）
COPY pom.xml ./
COPY apps/api/pom.xml ./apps/api/pom.xml
COPY apps/api/api-bootstrap/pom.xml ./apps/api/api-bootstrap/pom.xml
COPY apps/api/modules ./apps/api/modules

# Checkstyle 在 validate 阶段执行，规则文件必须就位（${anesop.root}/config）
COPY config ./config

# 源码与迁移脚本（Flyway 以 filesystem 方式读取 /opt/anesop/db/migration）
COPY apps/api ./apps/api
COPY db ./db

# -Danesop.root=/workspace 与 scripts/mvn.sh 注入的仓库根语义一致
# -DskipTests：镜像构建不跑测试（测试在 CI/本地门禁执行，AGENTS H-03 不允许在本流程跳过测试门禁）
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests -Danesop.root=/workspace package

# -----------------------------------------------------------------------------
# 运行阶段：JRE 21，非 root，仅保留运行时所需
# -----------------------------------------------------------------------------
# TODO(digest,EXT-08): 首次可运行环境补 @sha256:
FROM eclipse-temurin:21-jre

# curl 供 HEALTHCHECK 与运维排障使用；安装后清理 apt 索引
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行（UID/GID 固定，便于宿主卷权限与审计对齐）
ARG APP_UID=10001
ARG APP_GID=10001
RUN groupadd --system --gid "${APP_GID}" anesop \
    && useradd --system --uid "${APP_UID}" --gid anesop \
       --home-dir /opt/anesop --shell /usr/sbin/nologin anesop \
    && mkdir -p /opt/anesop/db/migration \
    && chown -R anesop:anesop /opt/anesop

WORKDIR /opt/anesop

# 可执行 JAR（spring-boot-maven-plugin repackage 产物；.jar.original 不匹配）
COPY --from=build /workspace/apps/api/api-bootstrap/target/api-bootstrap-*.jar /opt/anesop/app.jar

# Flyway 迁移脚本（compose 通过 SPRING_FLYWAY_LOCATIONS=filesystem:/opt/anesop/db/migration 读取）
COPY --from=build --chown=anesop:anesop /workspace/db/migration /opt/anesop/db/migration

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

USER anesop

EXPOSE 8080

# 健康检查只查进程与依赖连通性（§3.8.4）；就绪检查由 /actuator/health/readiness 提供
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# 用 sh -c 让 JAVA_OPTS 可被运维覆盖；exec 保证 java 为 1 号进程、信号可透传
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/anesop/app.jar"]
