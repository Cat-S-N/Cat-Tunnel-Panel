# Cat Tunnel Panel

<p align="center">
  <b>集多协议入站管理、转发与中转落地、用户流量/限速/到期控制、实时告警与操作审计于一体的集中式代理管理面板</b>
</p>

<p align="center">
  <a href="https://github.com/Cat-S-N/Cat-Tunnel-Panel/actions/workflows/docker-build.yml"><img src="https://github.com/Cat-S-N/Cat-Tunnel-Panel/actions/workflows/docker-build.yml/badge.svg" alt="Build Status" /></a>
  <img src="https://img.shields.io/badge/Docker-amd64%20%7C%20arm64-2496ED?logo=docker&logoColor=white" alt="Docker Multi-Arch" />
  <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/React-TypeScript-61DAFB?logo=react&logoColor=black" alt="React TypeScript" />
  <a href="./LICENSE"><img src="https://img.shields.io/github/license/Cat-S-N/Cat-Tunnel-Panel" alt="License" /></a>
</p>

---

## 目录

- [核心特性](#核心特性)
- [v1.1.0 版本亮点](#v110-版本亮点)
- [环境要求与端口说明](#环境要求与端口说明)
- [快速部署（面板端）](#快速部署面板端)
  - [方式一：一键脚本安装（推荐）](#方式一一键脚本安装推荐)
  - [方式二：Docker Compose 预构建镜像部署](#方式二docker-compose-预构建镜像部署)
  - [方式三：源码本地构建部署（二次开发）](#方式三源码本地构建部署二次开发)
- [首次登录与必做配置](#首次登录与必做配置)
- [接入节点端](#接入节点端)
- [日常运维与版本更新](#日常运维与版本更新)
- [本地开发与测试](#本地开发与测试)
- [常见问题 FAQ](#常见问题-faq)
- [卸载](#卸载)
- [致谢与免责声明](#致谢与免责声明)

---

## 核心特性

| 模块 | 说明 |
|---|---|
| **多协议管理** | 支持 `VLESS-Reality`、`Trojan-Reality`、`VMess`（TCP / WebSocket）、`Hysteria2`、`TUIC`、`AnyTLS`、`Shadowsocks-2022`、`SOCKS5`、`HTTP`、`HTTPS`；支持整机一键搭建、单协议按需增删与改名 |
| **独立代理（SOCKS5 / HTTP / HTTPS）** | 强制账号密码认证（拒绝空用户开放代理），HTTPS 使用节点自签 TLS；**不混入任何订阅链接**，在「我的订阅」和「用户管理」中独立卡片展示，支持一键测速、重置密码与复制 |
| **按需分配与多格式订阅** | 分配节点/中转线路时可多选具体协议，按线路独立控制限速、流量配额与到期时间；支持通用订阅（v2rayN / Shadowrocket / v2rayNG）、Clash / Mihomo 订阅与按用户开关控制的 Quantumult X 订阅 |
| **用户自助注册与默认配额** | 支持后台一键开启/关闭自助注册，配置新账号默认流量、转发数上限、有效期与月流量重置日并提供实时配额预览；支持可选多邀请码校验（加密随机生成，公开接口仅返回布尔状态、零泄露原文） |
| **登录/注册防爆破与令牌安全** | 登录与自助注册内置单 IP 有界滑动窗口防爆破限流（自动回收过期记录与容量上限保护）；修改密码或停用账号后即时吊销旧 JWT 令牌，严守权限边界 |
| **订阅防泄露审计** | 自动记录每次订阅拉取的来源 IP、客户端 UA（自动识别）与订阅类型；支持在用户弹窗/节点弹窗查看 24 小时拉取次数与唯一 IP 统计，异常多 IP 自动标黄并主动推送告警 |
| **节点管理员与操作审计** | 支持为普通账号按节点分配管理权限（仅管理授权节点及其线路用户）；所有关键变更操作（密码、邀请码、密钥全程脱敏）自动写入「操作日志」，支持事件类型快捷筛选、关键词防抖搜索、分页、清空及防公式注入的 CSV / JSON 安全导出 |
| **多维筛选与排序** | 支持节点、隧道、端口转发、中转线路、落地机与限速规则的全方位状态与协议筛选、实时搜索，以及按带宽/流量等维度排序 |
| **监控预警与个人中心** | 监控中心与公开状态页提供高负载（`>=80%`）预警统计；个人仪表盘与个人中心支持流量配额色阶预警及密码强度实时评估 |
| **Telegram / Webhook 实时告警** | 节点离线（防抖确认）/ 恢复上线、线路到期或超额自动停用、订阅多 IP 疑似泄露时实时推送通知；原生兼容 Telegram Bot、飞书、钉钉、企业微信与通用 Webhook，内置「测试告警」按钮 |
| **中转与端口转发** | 支持前置机 + 落地出口（住宅 SOCKS5 / 外部节点）在线测通、多节点端口转发与两级加密隧道中转（TCP + UDP） |

---

## v1.1.0 版本亮点

- **用户自助注册、零泄露邀请码与防爆破限流**：新增后台可控的用户自助注册与默认套餐配额实时预览，支持无模偏差随机生成多邀请码且公开接口零泄露原文；登录与注册全链路内置有界滑动窗口防爆破限流，改密或停用账号即时失效旧令牌。
- **操作审计分类筛选与安全导出增强**：操作审计日志支持按事件类型（登录、自助注册、改密、系统配置、用户管理、节点协议、转发隧道）快捷筛选与防抖即时搜索，支持跨页安全导出 CSV（防公式注入）与 JSON，并补强敏感配置全链路自动脱敏与订阅多 IP 防泄露告警。
- **像素黑白灰默认主题与品牌图标**：全新像素风黑白灰默认主题与黑色像素风小猫 Logo，统一全页面亮/暗色阴影、卡片空状态与移动端窄屏布局，优化无障碍提示及长页面回到顶部交互。
- **全页面筛选排序、监控预警与 `/` 快捷搜索**：节点、隧道、端口转发、中转线路、落地机、用户管理与限速规则全面支持多维筛选、排序及 `/` 键盘快捷搜索；监控告警页、公开状态页与个人中心提供高负载（`>=80%`）及流量配额色阶预警，补齐加载错误重试与防连点保护。
- **全量单测保障与运维脚本升级**：后端累计 425 项零外部依赖单元测试覆盖核心安全与业务链路；`panel_install.sh` 支持更新前 `docker-compose.yml` 自动备份与平滑热更新自检提示；修复 CI Release 工作流 GOST 的 Go 工具链版本配置。

---

## 环境要求与端口说明

### 面板机要求
- **系统**：Linux（Ubuntu 20.04+ / Debian 11+ / Rocky / AlmaLinux / CentOS 8+ 等），支持 `amd64` 与 `arm64`
- **配置**：建议 `1C 1G` 及以上（推荐 `2G` 内存，含 MySQL 5.7 + Spring Boot 后端 + Nginx 前端）
- **依赖**：Docker & Docker Compose v2（使用方式一一键脚本会自动安装）

### 防火墙需放行端口

| 端口（默认） | 用途 | 是否对外开放 |
|---|---|---|
| `6366`（`FRONTEND_PORT`） | 面板 Web 页面（前端 Nginx，如前置反代可只监听 `127.0.0.1`） | 视是否使用域名反代 |
| `6365`（`BACKEND_PORT`） | 面板后端 API / 节点 WebSocket 通信 / 客户端订阅接口 | **必须放行**（或通过反代暴露） |
| `80` / `443` | 启用域名 HTTPS（Caddy / Nginx / 1Panel 反代）时使用 | 按需放行 |

---

## 快速部署（面板端）

部署包含两部分：**面板端**（一台主控机，Docker 运行）和**节点端**（每台转发机，systemd 运行 `gost` + `sing-box`）。

### 方式一：一键脚本安装（推荐）

适合新机器最快开箱即用，自动安装 Docker、生成 `.env`、拉取 GHCR 预构建镜像并注册 `tms` 管理命令：

```bash
curl -L https://raw.githubusercontent.com/Cat-S-N/Cat-Tunnel-Panel/main/panel_install.sh -o panel_install.sh && chmod +x panel_install.sh && ./panel_install.sh
```

国内服务器若直连 GitHub 较慢，可使用镜像加速：

```bash
curl -L https://ghfast.top/https://raw.githubusercontent.com/Cat-S-N/Cat-Tunnel-Panel/main/panel_install.sh -o panel_install.sh && chmod +x panel_install.sh && ./panel_install.sh
```

### 方式二：Docker Compose 预构建镜像部署

适合已安装 Docker、希望直接通过 `docker compose` 自行托管的用户（镜像已发布至 GitHub Container Registry，支持 `linux/amd64` 与 `linux/arm64`）：

```bash
# 1. 创建目录并下载编排文件与初始化 SQL
mkdir -p /opt/cat-tunnel-panel && cd /opt/cat-tunnel-panel
curl -fsSLO https://raw.githubusercontent.com/Cat-S-N/Cat-Tunnel-Panel/main/docker-compose-v4.yml
curl -fsSLO https://raw.githubusercontent.com/Cat-S-N/Cat-Tunnel-Panel/main/gost.sql

# 2. 生成随机密钥与配置文件 .env（可按需修改端口）
cat > .env <<EOF
DB_NAME=cat_tunnel
DB_USER=cat_tunnel
DB_PASSWORD=$(openssl rand -hex 12)
JWT_SECRET=$(openssl rand -hex 24)
BACKEND_PORT=6365
FRONTEND_PORT=6366
EOF

# 3. 启动服务
docker compose -f docker-compose-v4.yml up -d

# 4. 查看健康状态（等待 springboot-backend 显示 healthy 即可访问）
docker compose -f docker-compose-v4.yml ps
```

> 若服务器需要 Docker 内部 IPv6 网络，可将上述 `docker-compose-v4.yml` 替换为 `docker-compose-v6.yml`。

### 方式三：源码本地构建部署（二次开发）

适合修改源码后在本地直接编译前后端镜像：

```bash
git clone https://github.com/Cat-S-N/Cat-Tunnel-Panel.git
cd Cat-Tunnel-Panel

# 生成 .env 配置文件
cat > .env <<EOF
DB_NAME=cat_tunnel
DB_USER=cat_tunnel
DB_PASSWORD=$(openssl rand -hex 12)
JWT_SECRET=$(openssl rand -hex 24)
BACKEND_PORT=6365
FRONTEND_PORT=6366
EOF

# 本地构建并启动（首次编译约需 2~5 分钟）
docker compose -f docker-compose-hybrid.yml --env-file .env up -d --build
```

---

## 首次登录与必做配置

1. 浏览器访问 `http://你的服务器IP:6366`（若已配置域名反代则访问对应域名）。
2. 默认管理员账号 / 密码：`admin_user` / `admin_user`。
3. **强烈建议按以下顺序完成初始化**：
   - **修改默认密码**：右上角修改管理员账号与强密码。
   - **配置面板地址与订阅域名**：进入「网站配置」，填写对外访问地址与订阅域名（可选），避免节点安装命令或订阅链接指向内网地址。
   - **开启实时告警（推荐）**：在「网站配置」开启「实时运维告警」，填入 Telegram Bot Token + Chat ID 或飞书/钉钉/企微 Webhook，点击「🔔 测试告警」确认推送畅通后保存。
   - **按需开放自助注册（可选）**：如需允许新用户自助开通账号，可在「网站配置」开启「开放用户注册」，按需生成注册邀请码并设置新用户默认流量、转发数与有效期配额。

---

## 接入节点端

1. 登录面板 → 左侧 **「转发机」** → **「新增」** 填写机器名称、公网 IP 等信息并保存。
2. 点击该机器对应的 **「安装」** 按钮，复制生成的安装命令并在目标转发机上以 `root` 执行即可（命令已自动包含面板地址与节点密钥）。
3. 若国内节点直连 GitHub 较慢，可加 `-c` 参数并配合镜像加速：

```bash
curl -L https://ghfast.top/https://raw.githubusercontent.com/Cat-S-N/Cat-Tunnel-Panel/main/install.sh -o install.sh && chmod +x install.sh && ./install.sh -c -a 面板地址:6365 -s 节点密钥
```

---

## 日常运维与版本更新

### 使用 Docker Compose 更新（方式二）

```bash
cd /opt/cat-tunnel-panel
docker compose -f docker-compose-v4.yml pull backend frontend
docker compose -f docker-compose-v4.yml up -d backend frontend
```

> **生产环境平滑热更新最佳实践**：若生产环境使用自定义的 `docker-compose.yml`（例如配置了 `mem_limit`、自定义子网或特定端口绑定），建议直接执行 `docker compose pull backend frontend && docker compose up -d backend frontend` 仅拉取并重建前后端服务，完整保留自定义编排配置与数据库容器运行状态。新版本启动时会自动执行增量建表与字段迁移，无需手动跑 SQL。

### 使用 `tms` 快捷命令（方式一安装）

| 命令 | 说明 |
|---|---|
| `tms` | 打开交互式管理菜单 |
| `tms status` | 查看容器运行状态 |
| `tms info` | 查看访问地址与配置信息 |
| `tms update` | 拉取最新镜像并重启面板 |
| `tms domain <域名>` | 配置面板域名与 HTTPS（Caddy 自动签发证书） |
| `tms domain off` | 关闭域名，恢复 `IP:端口` 访问 |
| `tms export` | 导出数据库备份 |
| `tms purge` | 彻底卸载面板端（容器、镜像、数据卷与命令） |

---

## 项目结构

```text
.
├── springboot-backend/       # Spring Boot 后端（Java 21 + MyBatis-Plus + MySQL）
├── vite-frontend/            # React + TypeScript + HeroUI 前端（内置 Nginx SPA 禁缓存）
├── go-gost/                  # 节点端代理转发核心（基于 go-gost + sing-box 控制）
├── docker-compose-v4.yml     # IPv4 预构建镜像 Compose 配置（GHCR）
├── docker-compose-v6.yml     # IPv6 预构建镜像 Compose 配置（GHCR）
├── docker-compose-hybrid.yml # 源码本地构建 Compose 配置
├── gost.sql                  # 数据库完整初始化 SQL
├── panel_install.sh          # 面板端一键安装与管理脚本
└── install.sh                # 节点端一键安装脚本
```

---

## 本地开发与测试

### 前端（Node.js 18+）

```bash
cd vite-frontend
npm install --legacy-peer-deps
npm run dev     # 本地开发
npm run build   # TypeScript 类型检查 + 生产构建（输出到 dist/）
```

### 后端（Java 21 + Maven 3.9+）

```bash
cd springboot-backend
mvn test                  # 运行全量单元测试（无外部 MySQL 依赖）
mvn clean package         # 运行单测并打包可执行 JAR（输出到 target/）
```

---

## 常见问题 FAQ

- **Q：执行 `docker compose up -d` 报错缺少变量或 MySQL 启动失败？**
  - 请确认同目录下已创建 `.env` 且包含 `DB_NAME`、`DB_USER`、`DB_PASSWORD`、`JWT_SECRET`、`BACKEND_PORT`、`FRONTEND_PORT`，且 `gost.sql` 文件在同一目录。
- **Q：节点安装后一直显示离线？**
  - 请确认面板机的 `BACKEND_PORT`（默认 `6365`）已在云服务器安全组和系统防火墙放行，并在节点机查看日志：`journalctl -u gost -n 50 --no-pager`。
- **Q：更新前端镜像后浏览器仍看到旧界面？**
  - 前端 Nginx 已对 `index.html` 配置禁用缓存；若前置有 CDN 或反向代理，请确认未缓存 HTML 文件，并尝试强刷浏览器（`Ctrl + Shift + R`）。
- **Q：如何备份 MySQL 数据库？**
  - 执行 `docker exec gost-mysql sh -c 'mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' > backup_$(date +%F).sql`。

---

## 卸载

- **卸载面板端**：
  - 一键脚本安装：执行 `tms purge`。
  - Docker Compose 部署：在部署目录执行 `docker compose -f docker-compose-v4.yml down -v`（`-v` 会一并清空数据库卷，请先备份）。
- **卸载节点端**（在对应转发机执行）：

```bash
systemctl stop gost sing-box 2>/dev/null
systemctl disable gost sing-box 2>/dev/null
rm -rf /etc/systemd/system/sing-box.service.d /etc/gost
find /etc/systemd /run/systemd \( -name 'gost.service' -o -name 'sing-box.service' \) -delete 2>/dev/null
systemctl daemon-reload
systemctl reset-failed 2>/dev/null
```

> 注意：如果面板机同时也作为转发机使用，请勿在面板机上直接运行节点卸载命令，以免停掉本机转发服务。

---

## 致谢与免责声明

- 底层转发与协议能力基于 [go-gost/gost](https://github.com/go-gost/gost) 与 [sing-box](https://github.com/SagerNet/sing-box) 等开源项目。
- 本项目仅供个人学习、网络测试与合法合规的网络运维研究使用，使用者须自行遵守所在国家及地区的法律法规，自行承担使用本项目所产生的一切风险与责任。
