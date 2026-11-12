# ANESOP 备份与恢复手册（单院 Compose 基线）

> 依据：方案书 §3.7.3 备份策略、表 3-16 RPO/RTO；§3.9.1 等保三级「备份恢复」。
> 原则：**恢复不出来的备份等于没有备份**。季度演练一次，记录进交付文档。
> 现状：本仓库只提供流程与命令模板，未部署备份作业（生产由信息科按本手册落地）；
> 所有命令须在具备 Docker 的部署机执行，施工环境未实跑（EXT-08）。

## 1. 对象、目标与责任

| 对象 | 方式 | 频率 | RPO | RTO | 责任人 |
|---|---|---|---|---|---|
| PostgreSQL | 每日物理全备 + WAL 持续归档 | 全备每日 03:00，WAL 实时 | ≤5 min | ≤30 min | DBA/信息科 |
| MinIO 对象 | 版本化 + 每日增量复制/快照 | 每日 | ≤15 min | ≤1 h | 信息科 |
| 配置文件 | Git 版本库 + 每日导出（不含 `.env`） | 每次变更 + 每日 | 0 | ≤10 min | 平台组 |
| `.env`/密钥 | 口令管理系统 + 加密离线副本 | 每次变更 | 0 | ≤10 min | 信息安全负责人 |
| RabbitMQ 定义 | `rabbitmqctl export_definitions` | 每日/变更后 | — | ≤30 min | 信息科 |
| 归档数据（MinIO 冷存储） | 按月归档 | 每月 | — | 按需 | 信息科 |

补充要求：

- 3-2-1：至少 1 份异地（院内另一机房或加密介质），异地副本加密；
- **患者数据不出院（H-01）**：不得把备份传到未审批的外部云；跨院区传输须走院方批准的加密通道；
- 备份文件加密（age/GPG 或 pgBackRest 内置加密），密钥与备份分开保管；
- 备份与生产盘分离，禁止把备份只放在 `anesop-pgdata` 同一块盘；
- 每日校验备份任务成功，失败按 P2 告警（§3.8.3）。

## 2. PostgreSQL：每日全备 + WAL

### 2.1 WAL 归档（生产需增加挂载）

基线 compose 未给 postgres 挂备份目录；生产在 `docker-compose.yml` 的 `postgres` 服务增加：

```yaml
    volumes:
      - pgdata:/var/lib/postgresql/data
      - /data/anesop-backup/wal:/backup/wal   # 宿主独立磁盘/加密卷
```

`postgresql.conf`（或 `command: postgres -c ...`）设置：

```conf
wal_level = replica
archive_mode = on
archive_timeout = 300s
archive_command = 'test ! -f /backup/wal/%f && cp %p /backup/wal/%f'
```

> 生产推荐 pgBackRest（支持全量/增量、保留策略、校验、加密、并行恢复）；
> 若采用，需按 AGENTS 依赖流程登记并单独评审，本文只给 PostgreSQL 原生路径。

### 2.2 每日物理全备（宿主执行，无需容器内落盘）

```bash
STAMP=$(date +%F)
mkdir -p /data/anesop-backup/base/$STAMP

# -Ft -z：tar+gzip 输出到 stdout，重定向到宿主备份盘；-X stream 同时带走所需 WAL
# 容器内需以 postgres 系统用户执行（pg_basebackup 拒绝 root）
docker compose -f deploy/compose/docker-compose.yml exec -T -u postgres postgres \
  pg_basebackup -U anesop -D - -Ft -z -X stream -c fast \
  > /data/anesop-backup/base/$STAMP/base.tar.gz

sha256sum /data/anesop-backup/base/$STAMP/base.tar.gz \
  > /data/anesop-backup/base/$STAMP/base.tar.gz.sha256

# 加密后复制到异地（示例：age）
age -r "<院方备份公钥>" -o /data/anesop-backup/base/$STAMP/base.tar.gz.age \
  /data/anesop-backup/base/$STAMP/base.tar.gz
```

保留策略：每日全备保留 30 天，每月 1 份保留 12 个月；WAL 至少覆盖最旧全备所需区间
（建议 14 天）后才可清理。清理脚本必须先校验最新全备可读。

### 2.3 全量恢复 + 时间点恢复（PITR）

> 由 DBA 在变更单下执行；目标 RTO ≤30 min。先在生产外演练成功，再用于生产。

1. 公告停机，停止写入：`docker compose stop web api`；
2. `docker compose stop postgres`；
3. 保留现场：把原 `anesop-pgdata` 卷改名/快照（不要直接删除）；
4. 新建空卷，解压最近全备到数据目录（compose 中 `PGDATA=/var/lib/postgresql/data/pgdata`，
   即解压到该子目录），属主设为 postgres 容器用户（Debian 镜像通常 UID/GID 999，以实际为准）；
5. 写恢复配置（数据目录下）：

```conf
restore_command = 'cp /backup/wal/%f %p'
recovery_target_time = '2026-11-12 03:20:00+08'
recovery_target_action = 'promote'
```

并在数据目录创建 `recovery.signal`；
6. 启动 postgres，观察 `pg_wal` 回放日志至 `consistent recovery state reached` 与目标时间点；
7. 校验：`pg_isready`、关键表行数、最新审计时间、Flyway `flyway_schema_history` 完整；
8. 启动 api/web，跑登录与关键业务冒烟；确认无重复/丢失后恢复对外服务；
9. 记录：恢复起点、目标时间、实际耗时、数据损失窗口、操作人，归档进演练/事故记录。

### 2.4 单表误删恢复

1. 用最近全备 + WAL 恢复到**临时库**（独立容器/端口，不覆盖生产）；
2. 从临时库导出目标表数据（`COPY ... TO` 或 `pg_dump -t`）；
3. 在生产按主键 `INSERT ... ON CONFLICT DO NOTHING` 回填，或按变更单做数据修复；
4. 校验行数、外键、审计记录；保留临时库至确认后销毁。

## 3. MinIO：版本化与对象恢复

```bash
# 配置别名（凭据从口令管理系统注入，不写历史命令）
mc alias set anesop http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

# 对关键桶开启版本化（文书、波形、导出、归档）
mc version enable anesop/documents
mc version enable anesop/waveforms
mc version enable anesop/exports

# 每日镜像到备份盘/异地 MinIO（保留历史版本，不 --remove；备份卷本身须加密）
mc mirror --preserve anesop/documents /data/anesop-backup/minio/documents

# 恢复单个对象的历史版本
mc ls --versions anesop/documents/<object>
mc cp --version-id <version-id> anesop/documents/<object> /tmp/restore/<object>
```

- 跨节点复制（多节点 MinIO）按 §3.7.2 配置；单节点基线用每日镜像/快照；
- 恢复后核对对象数、总容量、抽样哈希；审计桶不可删（只追加）。

## 4. 配置、RabbitMQ 与密钥

```bash
# 配置导出（排除 .env 与证书）
git -C /opt/anesop rev-parse HEAD > /data/anesop-backup/config/git-sha.txt
tar --exclude='deploy/compose/.env' --exclude='deploy/nginx/certs/*.key' \
    -czf /data/anesop-backup/config/deploy-$(date +%F).tar.gz deploy/

# RabbitMQ 定义（用户/队列/策略/绑定）
docker compose exec -T rabbitmq rabbitmqctl export_definitions /tmp/definitions.json
docker compose cp rabbitmq:/tmp/definitions.json \
  /data/anesop-backup/rabbitmq/definitions-$(date +%F).json
docker compose exec -T rabbitmq rm -f /tmp/definitions.json
```

`.env` 与证书私钥只进口令管理系统/加密介质，不进 Git、不进普通备份目录。

## 5. 季度恢复演练（必做）

每次演练选一个场景，全程计时并留证：

| 场景 | 步骤要点 | 通过标准 |
|---|---|---|
| 主库全量+WAL 恢复 | 第 2.3 节 1–9 | 数据完整、RTO ≤30 min、RPO ≤5 min |
| 单表误删恢复 | 第 2.4 节 | 行数与审计一致、无误覆盖 |
| 对象存储恢复 | 第 3 节版本回滚 | 文件可打开、哈希一致 |
| 配置重建 | Git + 配置包重建 | 10 min 内恢复，`compose-check` 通过 |

演练记录至少包含：日期、参与人、场景、备份时间点、恢复目标时间、实际 RTO、
发现的问题与整改、签字。连续两次演练失败须升级为 P1 并暂停相关发布。

## 6. 监控与告警

- 备份任务失败、WAL 归档延迟 >5 min、备份盘使用率 >80%：P2/P3 告警（邮件/企业微信）；
- 每日抽查：`sha256sum -c` 最新全备、`pg_controldata` 与归档连续性；
- 每季度核对保留策略与异地副本可读性；
- 备份日志保留 ≥6 个月，与审计要求一致。

## 7. 常见错误（禁止）

- 只在生产同一块盘留备份；
- 改了数据库口令但卷内仍是旧口令，导致备份连接失败却无人发现；
- 只做 `pg_dump` 不做 WAL，宣称 RPO ≤5 min；
- 恢复演练只恢复数据库、不验证应用与对象存储；
- 备份文件未加密、密钥与备份同目录；
- 生产直接执行逆向 SQL 回退 schema，而不走备份恢复与变更单。

## 8. 签字

| 项 | 结论 | 证据 | 签字/日期 |
|---|---|---|---|
| 备份作业上线并连续 7 天成功 | 待签字 | 任务日志 | |
| 主库 PITR 演练 | 待签字 | 演练记录 | |
| MinIO 版本恢复演练 | 待签字 | 演练记录 | |
| 异地副本可读性验证 | 待签字 | 校验记录 | |

> 恢复演练与生产恢复操作均须人工确认；自动化施工不执行生产备份/恢复。
