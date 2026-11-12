# TLS 证书挂载点（占位）

本目录**不存放任何真实证书或私钥**，仓库只保留本说明与 `.gitkeep`。
`deploy/nginx/anesop.conf` 期望容器内 `/etc/nginx/certs/` 存在：

| 文件名 | 内容 | 来源 |
|---|---|---|
| `tls.crt` | 服务器证书（含中间证书链更佳） | 院方 CA / 公网 CA 签发 |
| `tls.key` | 服务器私钥（权限 600，属主为部署账号） | 同上 |
| `ca-chain.crt` | 可选，OCSP stapling 用 | CA 提供 |

## 本地/联调环境生成自签证书（仅测试，禁止用于生产）

```bash
mkdir -p deploy/nginx/certs
openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout deploy/nginx/certs/tls.key \
  -out deploy/nginx/certs/tls.crt \
  -subj "/C=CN/O=ANESOP-TEST/CN=anesop.local" \
  -addext "subjectAltName=DNS:anesop.local,IP:127.0.0.1"
chmod 600 deploy/nginx/certs/tls.key
```

## 生产要求

- 证书由院方信息科按等保要求签发与保管，私钥不得入库、不得进镜像；
- 证书到期纳入巡检（§3.8.3 P3 告警：证书到期），提前 30 天续签；
- 部署后执行 `docker compose exec web nginx -t` 校验配置与证书可读性。

`.gitignore` 已忽略 `*.pem`、`*.key`、`*.p12`；提交前仍须 `git status` 确认无证书文件。
