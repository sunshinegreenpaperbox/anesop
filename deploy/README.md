# deploy — 部署编排与环境配置

单院私有化部署的编排与配置入口：PostgreSQL 16、Redis 7.2、RabbitMQ 3.13、
MinIO、Nginx、API、Web，以及监控（Prometheus + Grafana + Loki）。

## 目录约定

```text
deploy/
├── compose/     # docker compose（S0-10 落地）
└── README.md
```

## 规则

- 镜像按 digest 引用，不用 `latest`；
- 数据卷与配置分离，密钥走环境变量 / secret，不入库；
- 健康检查与启动依赖顺序显式声明；
- 生产发布须 `release.yml` 变更单审批，应用回滚目标 10 分钟。

## 状态

S0-02 仅建目录与说明。本机 Docker 当前不可用，Compose 与一键起由 S0-10 编写并做静态校验（EXT-08）。
