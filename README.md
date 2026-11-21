# TMS 面板

一个集**多协议入站管理**、**转发与中转落地**、**用户流量 / 限速 / 到期控制**于一体的集中式代理管理面板。

---

## 核心特性

| 模块 | 说明 |
|---|---|
| **多协议管理** | 支持 `VLESS-Reality`、`Trojan-Reality`、`VMess`（TCP / WebSocket）、`Hysteria2`、`TUIC`、`AnyTLS`、`Shadowsocks-2022`、`SOCKS5`；支持整机一键搭建、单协议按需添加与单协议删除 |
| **SOCKS5 独立代理** | 强制账号密码认证（拒绝空用户开放代理），支持 TCP + UDP；**不混入任何订阅链接**，在「我的订阅」和「用户管理」中独立卡片展示，一键复制 `IP:端口:账号:密码` 或 `socks5://` 链接 |
| **按需分配协议** | 给用户分配机器或中转线路时支持多选具体协议（支持一键「全选 / 不含 SOCKS5 / 清空」），按线路独立控制限速、流量配额与到期时间 |
| **多格式订阅** | 同时支持通用订阅（v2rayN / Shadowrocket / v2rayNG）、Clash / Mihomo 订阅，以及按用户开关控制的 Quantumult X 订阅 |
| **中转与落地** | 前置机搭建协议 + 落地出口（住宅 SOCKS5 / 外部节点），自带在线连通性测试，直连与各中转线路各自独立计费与限速 |
| **端口 / 隧道转发** | 支持多节点端口转发、两级加密隧道中转与每用户独立限速（TCP + UDP） |
| **集中节点管理** | 单面板统一管理多台转发节点，节点一条命令接入并自动同步配置 |

---

## 项目结构

```text
.
├── springboot-backend/    # Spring Boot 后端（Java 21 + MyBatis-Plus + MySQL）
├── vite-frontend/         # React + TypeScript + HeroUI 前端（含 Nginx SPA 禁缓存配置）
├── go-gost/               # 节点端代理转发核心（基于 go-gost）
├── docker-compose-v4.yml  # IPv4 Docker Compose 部署配置
├── docker-compose-v6.yml  # IPv6 Docker Compose 部署配置
├── docker-compose-hybrid.yml # 源码本地构建部署配置
├── panel_install.sh       # 面板端一键安装/管理脚本
└── install.sh             # 节点端安装脚本
```

---

## 快速部署

部署包含两部分：**面板端**（一台服务器，Docker 运行）和**节点端**（每台转发机，systemd 运行 `gost` + `sing-box`）。

### 1. 部署面板端

#### 方式一：一键脚本安装

```bash
curl -L https://raw.githubusercontent.com/Cat-S-N/TMS/main/panel_install.sh -o panel_install.sh && chmod +x panel_install.sh && ./panel_install.sh
```

#### 方式二：源码构建部署（推荐二次开发使用）

```bash
git clone https://github.com/Cat-S-N/TMS.git
cd TMS
docker compose -f docker-compose-hybrid.yml up -d --build
```

安装完成后默认管理账号与密码为 `admin_user` / `admin_user`，**首次登录后请立即修改密码**。

### 2. 接入节点端

登录面板 → 左侧 **「转发机」** → **「新增」** 填写机器 IP 并保存 → 点击该机器的 **「安装」** → 复制生成的安装命令并在目标机器执行即可（命令已自动包含面板地址与节点密钥）。

若国内机器直连 GitHub 较慢，可加 `-c` 参数并配合镜像加速：

```bash
curl -L https://ghfast.top/https://raw.githubusercontent.com/Cat-S-N/TMS/main/install.sh -o install.sh && chmod +x install.sh && ./install.sh -c -a 面板地址:端口 -s 节点密钥
```

---

## 常用管理命令

使用 `panel_install.sh` 安装后，面板机提供 `tms` 快捷命令：

| 命令 | 说明 |
|---|---|
| `tms` | 打开交互式管理菜单 |
| `tms status` | 查看容器运行状态 |
| `tms info` | 查看访问地址与配置信息 |
| `tms update` | 拉取更新并重启面板 |
| `tms domain <域名>` | 配置面板域名与 HTTPS（Caddy 自动签发证书） |
| `tms domain off` | 关闭域名，恢复 `IP:端口` 访问 |
| `tms export` | 导出数据库备份 |
| `tms purge` | 彻底卸载面板端（容器、镜像、数据卷与命令） |

---

## 本地开发与构建

### 前端（Node.js 18+）

```bash
cd vite-frontend
npm install --legacy-peer-deps
npm run dev     # 本地开发
npm run build   # 生产构建（输出到 dist/）
```

### 后端（Java 21 + Maven 3.9+）

```bash
cd springboot-backend
mvn test                  # 运行单元测试
mvn clean package         # 构建可执行 JAR（输出到 target/）
```

---

## 卸载

- **卸载面板端**：在面板机执行 `tms purge`（或 `docker compose down -v`）。
- **卸载节点端**：在对应转发机执行：

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
