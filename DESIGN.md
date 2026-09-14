# llama-hub 设计文档

## 概述

在 llama-server 前加一层 Spring Boot 网关服务，提供 API Key 认证、管理界面和反向代理。

```
客户端 ──(API Key)──> nginx :9090 ──> llama-hub:18443 ──> llama-server:192.168.2.185:18082
                                          │
                                     管理界面 (Web UI)
```

## 技术选型

| 项 | 选择 | 理由 |
|----|------|------|
| 框架 | Spring Boot 4.1.0 | 最新正式版（Spring Framework 7，Java 17+ stack） |
| Java | 21（LTS） | 服务器已部署 /usr/local/jdk-21.0.8，启动快、虚拟线程/MVC async 表现佳 |
| 数据库 | H2 内嵌（文件模式） | 零配置，单文件 `./data/llama-hub.mv.db` |
| 前端 | Vue 3 + Vite + Tailwind CSS | 组件化开发 + 热更新；构建产物外置 `resources/static/`（不进 jar），前端更新只传静态目录 |
| 反向代理 | Spring WebClient | 支持 SSE 流式转发（llama-server 的 streaming）；body 在 exchangeToMono 回调内消费，避免连接释放时丢弃响应 |
| 构建 | Maven | 分离打包（纯 class jar + lib/ + resources/，PropertiesLauncher，`java -jar` 零参数启动） |

## 核心功能

### 反向代理

- 路径前缀 `/v1/*` 转发到 `http://127.0.0.1:18082/v1/*`
- 支持 SSE（Server-Sent Events）流式响应透传
- 请求头 `Authorization: Bearer <api_key>` 认证

### API Key 管理

- 生成：`sk-gw-` 前缀 + 32 位随机十六进制
- 存储：SHA-256 哈希（不可逆），列表只展示前 12 位
- 有效期：创建时设置，支持按 **分钟 / 小时 / 天 / 月 / 年 / 永久** 设置
- 状态：active / disabled / expired / exceeded（自动判定）
- 记录：`last_used_at`、`hit_count`
- **用量限额**：创建/编辑时可设置，二者均可选，NULL = 不限
  - `token_quota`：总 tokens 上限（按 `usage.total_tokens` 累计）
  - `request_quota`：总请求次数上限（按成功响应累计）
  - 超限后 Key 状态置 `exceeded`，代理返回 `429`；提升限额或删除重建可恢复
  - Key 列表显示用量进度条（已用/限额）

#### 有效期单位

创建/编辑 Key 时，有效期由两个字段组成：

| 字段 | 说明 |
|------|------|
| 数量（number） | 正整数 |
| 单位（unit） | 下拉选择：`分钟` / `小时` / `天` / `月` / `年` / `永久` |

后端换算为绝对时间戳存入 `expires_at`：

```
分钟 → now + N minutes
小时 → now + N hours
天   → now + N days
月   → now + N months
年   → now + N years
永久 → NULL
```

页面上展示为相对描述（如"30 天"）+ 绝对到期时间（如 `2026-09-23 14:30`）。

### LLM 调用日志

每个 API Key 每次调用均记录：

| 字段 | 来源 | 说明 |
|------|------|------|
| 时间 | 请求进入时间 | `started_at` |
| Key 名称 | 关联 api_key | |
| 端点 | 请求路径 | 如 `/v1/chat/completions` |
| 模型 | 请求 body 中的 model | |
| 输入 tokens | `usage.prompt_tokens` | |
| 输出 tokens | `usage.completion_tokens` | |
| 总 tokens | `usage.total_tokens` | |
| 缓存 tokens | `usage.prompt_tokens_details.cached_tokens` | KV cache 复用 |
| 缓存命中率 | `cached_tokens / prompt_tokens` | 保留 4 位小数，分母为 0 记 NULL |
| 生成速度 | `completion_tokens / (duration_ms / 1000)` | 输出 tokens/s，保留 2 位小数；duration 为 0 记 NULL |
| 耗时 | 请求进入 → 响应完整返回 | 毫秒 |
| 状态码 | HTTP 响应码 | 200 / 4xx / 5xx |
| 错误信息 | 异常时 | 截断 500 字符 |
| 请求体 | 开关开启时 | 截断 2000 字符 |
| 响应体 | 开关开启时 | 截断 2000 字符（流式响应不记录响应体） |

> llama.cpp 流式响应不含 OpenAI 标准 `usage` 字段，以 `timings`（`cache_n`+`prompt_n`=输入、`predicted_n`=输出）兜底统计。

**采集方式**：

- 非流式：直接解析响应 JSON 的 `usage` 字段
- 流式（SSE）：缓冲最后一个 SSE chunk（`data: {"choices":[],"usage":{...}}`），解析 usage
- 缓存命中率：`usage.prompt_tokens_details.cached_tokens / prompt_tokens`
- 生成速度：`usage.completion_tokens / (duration_ms / 1000)`，tokens/s

**日志清理**：

- 保留天数可配置（`gateway.log.retention-days`，默认 90 天）
- 定时任务每天凌晨 3 点清理超期记录

**请求/响应体记录**：

- 开关：`gateway.log.body-enabled`，默认 `false`
- 开启后非流式请求记录截断后的请求体/响应体（各 2000 字符），用于排障
- 流式（SSE）响应不记录响应体，只记录 usage 统计

**日志导出**：

- `GET /api/admin/logs/export`：按当前筛选条件导出 CSV（UTF-8 BOM，Excel 友好）
- 导出上限 10000 条，超出提示缩小筛选范围

### 用量统计仪表盘

管理界面首页 Tab，数据来自 `call_log` 聚合：

- **统计卡片**：今日请求数、今日 tokens 消耗、今日缓存命中率、活跃 Key 数
- **趋势图**：近 7 天请求量 / tokens 消耗折线图（每日聚合）
- **Top 5 活跃 Key**：按今日请求数排序
- **上游状态**：llama-server 存活指示（绿/红）

### 上游健康检查

- 定时任务每 30 秒探测上游 `/health`（2 秒超时）
- 状态缓存在内存（最近一次探测结果 + 时间），管理页顶栏常驻显示
- 探测失败在调用日志中体现为 5xx + `upstream unreachable`

### 审计日志

记录管理端敏感操作，`audit_log` 表：

| 字段 | 说明 |
|------|------|
| 时间 | 操作时间 |
| 用户 | 操作者（admin） |
| 动作 | `LOGIN_OK` / `LOGIN_FAIL` / `LOGOUT` / `PASSWORD_CHANGE` / `KEY_CREATE` / `KEY_UPDATE` / `KEY_DELETE` / `KEY_REVEAL` / `CONFIG_CHANGE` |
| 目标 | 操作对象（如 Key 名称） |
| 详情 | 附加信息，截断 500 字符 |
| 来源 IP | 操作来源地址 |

- 管理界面"审计日志" Tab 可查看，保留 180 天（随日志清理任务一起清理）

### 管理界面（GitHub 风格，Vue 3 SPA）

- **登录页**：输入用户名 + 密码
- **主页面**（Tab 切换）：
  - **仪表盘**：统计卡片 + 近 7 天趋势图 + Top 5 活跃 Key + 上游状态
  - **API Keys**：Key 列表表格
    - 列：名称、Key（前缀…）、有效期、用量（进度条）、状态、创建时间、最后使用
    - 操作：修改有效期/限额、禁用/启用、删除
  - **调用日志**：日志列表
    - 列：时间、Key 名称、端点、输入 tokens、输出 tokens、缓存 tokens、缓存命中率、生成速度、耗时、状态
    - 支持按 Key 筛选、时间范围筛选
    - 默认按时间倒序，分页 50 条
    - 行展开：查看请求/响应体（开关开启时）与错误信息
    - 导出 CSV 按钮（按当前筛选条件）
  - **审计日志**：操作记录列表，分页
- **新建 Key 弹窗**：名称 + 有效期（数量 + 单位）+ 限额（tokens / 次数，可留空不限）
- **修改密码弹窗**（右上角用户菜单）：旧密码 + 新密码 + 确认新密码
- **顶栏**：上游健康状态指示灯（绿 = 正常 / 红 = 不可达）

## 数据模型

```sql
CREATE TABLE api_key (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    key_hash       VARCHAR(64)  NOT NULL UNIQUE,   -- SHA-256
    key_prefix     VARCHAR(16)  NOT NULL,           -- 前 12 位明文，用于展示
    key_plain      TEXT NULL,                       -- 密钥明文（AES 加密），用于列表"复制"再次查看
    expires_at     TIMESTAMP NULL,                  -- NULL = 永久
    token_quota    BIGINT NULL,                     -- 总 tokens 上限，NULL = 不限
    request_quota  BIGINT NULL,                     -- 总请求次数上限，NULL = 不限
    tokens_used    BIGINT DEFAULT 0,                -- 已消耗 tokens
    requests_used  BIGINT DEFAULT 0,                -- 已消耗请求数
    is_active      BOOLEAN DEFAULT TRUE,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at   TIMESTAMP NULL,
    hit_count      BIGINT DEFAULT 0
);

CREATE TABLE call_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_id            BIGINT NOT NULL,
    request_id        VARCHAR(64) NOT NULL,       -- 请求唯一 ID（UUID）
    endpoint          VARCHAR(200),               -- 请求路径
    model             VARCHAR(100),
    prompt_tokens     INT,
    completion_tokens INT,
    total_tokens      INT,
    cached_tokens     INT,
    cache_hit_rate    DECIMAL(5,4),               -- cached_tokens / prompt_tokens
    tokens_per_sec    DECIMAL(8,2),               -- 输出 tokens/s
    started_at        TIMESTAMP NOT NULL,
    duration_ms       BIGINT,
    status_code       INT,
    error_msg         VARCHAR(500),
    request_body      TEXT NULL,                  -- 开关开启时记录，截断 2000 字符
    response_body     TEXT NULL                   -- 开关开启时记录，截断 2000 字符
);

CREATE TABLE admin_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,    -- 默认 admin
    password_hash VARCHAR(100) NOT NULL,           -- BCrypt
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(50)  NOT NULL,
    action     VARCHAR(30)  NOT NULL,               -- LOGIN_OK / KEY_CREATE / ...
    target     VARCHAR(200) NULL,                   -- 操作对象
    detail     VARCHAR(500) NULL,
    ip         VARCHAR(45)  NULL,                   -- IPv6 最长 45
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_call_log_key_id ON call_log(key_id, started_at DESC);
CREATE INDEX idx_call_log_time ON call_log(started_at DESC);
CREATE INDEX idx_audit_log_time ON audit_log(created_at DESC);
```

## API 设计

### 管理接口（需 Session 认证，账号密码登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 前端 SPA（Vite 构建产物，静态资源） |
| POST | `/api/login` | 登录 `{username, password}` |
| POST | `/api/logout` | 登出 |
| PUT | `/api/admin/password` | 修改密码 `{oldPassword, newPassword}`（成功后 Session 失效，需重新登录） |
| GET | `/api/admin/dashboard` | 仪表盘数据（今日统计 + 近 7 天趋势 + Top 5 Key + 上游状态） |
| GET | `/api/admin/stats/usage` | 按 Key + 日期区间统计 token 用量 `{keyId?, startAt?, endAt?}` |
| GET | `/api/admin/upstream/status` | 上游健康状态（缓存值，前端轮询 30s） |
| GET | `/api/admin/keys` | Key 列表（含用量 `tokensUsed/requestsUsed/quota`） |
| POST | `/api/admin/keys` | 创建 Key `{name, number, unit, tokenQuota?, requestQuota?}` |
| POST | `/api/admin/keys/{id}/reveal` | 获取密钥明文副本（列表"复制"按钮，旧密钥无副本则 400） |
| PUT | `/api/admin/keys/{id}` | 更新 `{number?, unit?, isActive?, tokenQuota?, requestQuota?}` |
| DELETE | `/api/admin/keys/{id}` | 删除 |
| GET | `/api/admin/logs` | 调用日志（分页 + 筛选，含请求/响应体） |
| GET | `/api/admin/logs/export` | 调用日志导出 CSV（同筛选条件，上限 10000 条） |
| GET | `/api/admin/audit-logs` | 审计日志（分页 + 筛选） |

### 代理接口（需 API Key）

| 方法 | 路径 | 说明 |
|------|------|------|
| * | `/v1/**` | 透传到 llama-server |
| GET | `/health` | 网关自身健康检查 |

## 安全设计

- **API Key**：SHA-256 存储，响应中不返回完整 key（仅创建时展示一次）；明文副本以 AES（密钥 `gateway.key.encryption-key`，可用环境变量覆盖）加密存入 `key_plain`，支持管理端列表"复制"再次查看，此操作记 `KEY_REVEAL` 审计日志
- **用量限额**：超限请求返回 `429 Too Many Requests`，响应体带 `{"error": {"message": "token quota exceeded", "type": "quota_exceeded"}}`（OpenAI 风格错误）
- **后台账号**：默认用户名 `admin`，初始密码首次启动写入 `admin_user` 表（BCrypt 哈希，明文默认 `admin123`，可用 `GATEWAY_ADMIN_INITIAL_PASSWORD` 覆盖）；之后以数据库为准，环境变量不再生效
- **密码存储**：BCrypt（自适应慢哈希，抵御彩虹表/暴力破解）；修改密码需验证旧密码，成功后销毁当前 Session 强制重新登录；新密码最少 8 位
- **登录保护**：连续失败 5 次锁定 5 分钟（内存计数），失败也记审计日志
- **审计日志**：登录、改密、Key 增删改等敏感操作全部落库，含来源 IP
- **IP 白名单**：`gateway.admin.allowed-ips`（逗号分隔，支持 CIDR），空 = 不限制；仅作用于管理页面与 `/api/admin/**`
- **Session**：30 分钟空闲超时，HttpOnly + SameSite=Strict Cookie
- **llama-server**：`--host` 改为 `127.0.0.1`，仅本机可达
- **CORS**：默认不允许跨域

## 部署

```
# 构建（前端 → 后端）
cd frontend && npm install && npm run build   # 产物输出到 src/main/resources/static/
mvn package
# 分离打包产物（token-dashboard 模式：jar 里只有 class，无任何资源）：
#   target/llama-hub.jar        可执行 jar（纯 class，manifest 为 PropertiesLauncher +
#                                 Class-Path ./resources/ + Loader-Path resources/,lib/，~340KB，更新只传它）
#   target/lib/*.jar              运行期依赖（maven-dependency-plugin 拷贝，116 个，不含 lombok）
#   target/resources/             外置资源（application.yml + static/ 前端产物）

# 开发模式
cd frontend && npm run dev                    # Vite 热更新，/llama-hub 代理到 :18443
mvn spring-boot:run                           # 注意 static/ 不进 target/classes，开发期前端由 Vite 提供

# 服务器目录（分离部署：主 jar + lib + resources）
/home/monitor/deployments/llama-hub/
├── llama-hub.jar           # 可执行 jar（纯 class，java -jar 零参数启动，后端更新只传这个，~340KB）
├── lib/                      # 运行期依赖（116 个 jar，不含 lombok，基本不变，装一次）
├── resources/
│   ├── application.yml       # 外置配置（唯一配置来源，jar 内不打包配置）
│   └── static/               # 前端构建产物（前端更新只传这个目录，不用重启）
├── data/llama-hub.mv.db    # H2 数据文件
├── logs/llama-hub.log
└── start.sh / stop.sh

# 启动方式（start.sh）：零参数
# java -jar llama-hub.jar
# 原理：spring-boot-maven-plugin 用 <layout>ZIP</layout> + null 依赖技巧重打包，入口为
# PropertiesLauncher，按 manifest 的 Loader-Path 把外置 lib/（依赖）、resources/（配置+静态页面）
# 挂进类路径，Spring 经 classpath 天然加载 application.yml 和 static/，与启动目录无关

# 常用可覆盖参数（写入 resources/application.yml 或用 --xxx 追加）
--server.port=18443
--GATEWAY_ADMIN_INITIAL_PASSWORD=xxxxx   # 可选，仅首次初始化生效，之后以库里为准
--gateway.log.retention-days=90
--gateway.log.body-enabled=false         # 请求/响应体记录，排障时开启
--gateway.admin.allowed-ips=             # 管理端 IP 白名单（CIDR），空 = 不限制

# nginx 反向代理（对外端口 9090）
/etc/nginx/conf.d/llama-hub.conf:
server {
    listen 9090;
    client_max_body_size 50m;

    location / {
        proxy_pass http://127.0.0.1:18443;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_buffering off;              # SSE 流式透传
        proxy_read_timeout 600s;
    }
}
```

## 页面风格（GitHub Light，Tailwind 主题）

色值配置为 Tailwind 自定义主题变量（`@theme`），页面全部使用 utility class：

- 背景：`#ffffff`
- 卡片/面板：`#f6f8fa`
- 边框：`#d0d7de`
- 文字主色：`#1f2328`
- 文字次色：`#59636e`
- 强调/按钮：`#1f883d`（绿）、`#0969da`（蓝）
- 标签：圆角 pill，背景 `#eaeef2`
- 字体：`-apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif`
- 代码字体：`ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace`

## 项目结构

```
llama-hub/
├── pom.xml                                # 打包前需先构建前端
├── frontend/                              # Vue 3 + Vite + Tailwind
│   ├── package.json
│   ├── vite.config.js                     # outDir → ../src/main/resources/static
│   ├── index.html
│   └── src/
│       ├── main.js
│       ├── App.vue                        # 登录/主界面状态切换（无 vue-router）
│       ├── api.js                         # fetch 封装（401 自动跳回登录）
│       ├── assets/main.css                # Tailwind 入口 + GitHub Dark 主题变量
│       └── components/
│           ├── LoginView.vue
│           ├── DashboardView.vue          # 统计卡片 + 趋势图 + Top Key + 上游状态
│           ├── ApiKeysView.vue            # Key 列表 + 新建/编辑弹窗（含限额、单位下拉、复制）
│           ├── LogsView.vue               # 调用日志（筛选 + 分页 + CSV 导出 + 行展开）
│           ├── UsageStatsView.vue         # 按 Key + 日期区间统计 token 用量
│           ├── AuditLogsView.vue          # 审计日志
│           └── ChangePasswordModal.vue
├── src/main/java/com/llama/hub/
│   ├── GatewayApplication.java
│   ├── config/
│   │   ├── WebClientConfig.java
│   │   ├── SecurityConfig.java
│   │   └── ScheduleConfig.java
│   ├── controller/
│   │   ├── AuthController.java          # login / logout / password
│   │   ├── AdminController.java         # /api/admin/** REST API
│   │   ├── ProxyController.java         # 反向代理
│   │   └── HealthController.java
│   ├── model/
│   │   ├── ApiKey.java
│   │   ├── AdminUser.java
│   │   ├── CallLog.java
│   │   └── AuditLog.java
│   ├── repository/
│   │   ├── ApiKeyRepository.java
│   │   ├── AdminUserRepository.java
│   │   ├── CallLogRepository.java
│   │   └── AuditLogRepository.java
│   ├── service/
│   │   ├── KeyService.java
│   │   ├── AdminUserService.java       # 登录校验、修改密码、初始化默认账号
│   │   ├── ProxyService.java          # 含 SSE 流式转发 + usage 采集 + 配额扣减
│   │   ├── StatsService.java          # 仪表盘聚合查询
│   │   ├── UpstreamHealthService.java # 上游探活（30s 定时）
│   │   ├── AuditService.java          # 审计日志写入
│   │   └── LogCleanerService.java     # 调用日志/审计日志清理
│   └── filter/
│       └── ApiKeyAuthFilter.java
├── src/main/resources/
│   ├── application.yml
│   └── static/                          # Vite 构建产物（git 忽略）
└── README.md
```
