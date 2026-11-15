# deploy — 部署编排与环境配置

单院私有化部署的编排与配置入口：PostgreSQL 16、Redis 7.2、RabbitMQ 3.13、
MinIO、Nginx、API、Web，以及监控（Prometheus + Grafana + Loki）。

## 目录约定

```text
deploy/
├── compose/
│   ├── docker-compose.yml        # 9 服务 / 2 网络 / 8 卷（S0-10；M0 文件卷 m0filedata）
│   ├── .env.example              # 变量模板，仅 CHANGE_ME 占位
│   ├── api.Dockerfile            # Java 21 多阶段，运行阶段非 root
│   ├── web.Dockerfile            # pnpm 构建 + Nginx 1.27 运行
│   └── *.Dockerfile.dockerignore # 构建上下文裁剪（BuildKit）
├── nginx/
│   ├── anesop.conf               # TLS/安全头/SPA fallback//api 反代
│   └── certs/                    # 证书挂载点（仓库不含证书）
├── prometheus/prometheus.yml
├── grafana/provisioning/         # 数据源 + 仪表盘 provider
├── loki/loki-config.yml
└── README.md
```

## 规则

- 镜像按 digest 引用，不用 `latest`（当前精确 tag + `# TODO(digest,EXT-08)`）；
- 数据卷与配置分离，密钥走环境变量 / secret，不入库；
- M0 附件存储：`api` 注入 `ANESOP_M0_FILE_ROOT_DIR=/var/lib/anesop/files` 并挂载持久卷
  `m0filedata`；容器重建不丢附件。MinIO 服务为未来切换预留（EXT-06），当前实现不读写 MinIO。
- 健康检查与启动依赖顺序显式声明；
- 生产发布须 `release.yml` 变更单审批，应用回滚目标 10 分钟。

## 快速入口

```bash
bash scripts/compose-check.sh                 # 静态校验（Docker 不可用时跳过运行时项）
cp deploy/compose/.env.example deploy/compose/.env
docker compose -f deploy/compose/docker-compose.yml up -d
```

完整步骤、端口/卷/网络、升级回滚、EXT-08 与 digest 补全见
`docs/deploy/README.md`；备份恢复见 `docs/deploy/backup-restore.md`。

## 状态

S0-10 已完成清单、镜像构建文件、监控配置、部署/备份文档与纯静态校验；M8-M0-04 补
`m0filedata` 文件卷与 `ANESOP_M0_FILE_ROOT_DIR`。本机 Docker 不可用（EXT-08），未执行
pull/build/up/compose config；`scripts/compose-check.sh` 静态断言 9 服务/卷/文件存储配置，
首次可运行环境须补镜像 digest、实跑验收与镜像扫描。生产部署仍须人工签字，自动化施工不执行生产发布。
