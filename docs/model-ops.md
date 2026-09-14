# 模型运维控制台设计（llama-hub 扩展）

## 背景

llama-hub 已实现 API Key 网关（认证/限额/调用日志/仪表盘），上游是 185 的 llama-server。目前模型的启停靠人工 SSH 执行 `/home/llama-cpp/start.sh` / `stop.sh`，参数修改要手改脚本，日志要 SSH 上去 tail。

本设计把「模型运维控制台」扩展进现有项目：启动/停止模型、修改启动参数、实时查看日志，与网关共享同一个上游状态视图。

## 集成方式

合并进同一项目、同一进程，不拆服务：

- 网关转发前必须知道模型存活（决定转发还是 503），面板展示的也是同一份状态；拆两个进程会出现两份 SSH 连接和两份状态缓存
- 现有 `UpstreamHealthService`（30s 探测 `/health` + 内存状态）直接升级为完整状态机，网关 503 判断与面板状态卡共用
- 数据面/管理面隔离：ops 代码独立子包 `com.llama.hub.ops`，独立线程池执行 SSH 慢操作，不阻塞 WebClient 转发路径

```
 浏览器(管理页)                          业务客户端
   │ /api/admin/model/**                   │ /v1/* (API Key)
 ▼                                       ▼
┌────────────────────────────────────────────────────────┐
│ llama-hub :18443                                   │
│                                                        │
│  ProxyController/ProxyService (WebClient, SSE 透传)     │
│  ModelOpsController ── ModelOpsService ──┐             │
│  ModelStatusService(状态机, 原上游探活升级) ◄┘           │
│  SshService(sshj 常驻会话, 独立线程池) ──┘              │
│  鉴权: AdminSessionFilter + IpWhitelistFilter (复用)    │
└──────────────────────────────────┬─────────────────────┘
                       │ 数据面: HTTP 直连 18082 (内网)
                       │ 运维面: SSH 免密 :22
                       ▼
   185: llama-server :18082, /home/llama-cpp/logs/llama.log
```

## 模块设计（com.llama.hub.ops）

- `SshService`：sshj 常驻 `Session`（root@192.168.2.185:22），提供 `exec(cmd, timeout)` 一次性执行与 `openChannel()` 长通道；断线指数退避重连；所有调用走独立 `opsExecutor` 线程池
- `ModelStatusService`：在现有 `UpstreamHealthService` 基础上扩展：
  - 探测组合：SSH（`llama.pid` + `kill -0` + `ss -ltn sport=:18082`）+ 本地 HTTP `/health`
  - 输出 `ModelStatus { state, pid, uptimeSec, healthOk, portListening, checkedAt }`
  - 状态变化发事件（SSE 广播给运维页）
  - 网关侧 `ProxyService` 的 503 判断改读此状态（原内存健康缓存的替代）
- `ModelOpsService`：start / stop / snapshot 编排（见「启停序列」「快照」）
- `ModelConfigService`：`model_config` 表读写 + 完整参数行词法校验 + flag 级 diff + 从参数行解析 `--port/-m/-mm`（供探测与脚本模板）
- `ModelLogStreamService`：SSE 日志订阅管理，每个订阅一个 sshj `ChannelExec`（`tail -n 500 -F llama.log`），客户端断开必须关 channel
- `ModelOpsController`：`/api/admin/model/**`（复用现有管理端鉴权，不新开通道）
- `model/ModelConfig.java` + `repository/ModelConfigRepository.java`

## API 设计

全部挂在现有管理端前缀下，鉴权与 IP 白名单自动生效：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/model/status` | 状态快照（state/pid/uptime/healthOk/portListening） |
| POST | `/api/admin/model/start` | 启动（异步），202 + STARTING |
| POST | `/api/admin/model/stop` | 停止（异步），202 + STOPPING |
| GET | `/api/admin/model/config` | 当前完整参数行 + 来源 + 运行参数行（drift 标记） |
| PUT | `/api/admin/model/config` | 保存完整参数行 + 环境变量行（词法校验，返回 diff，重启生效） |
| POST | `/api/admin/model/config/snapshot` | 从运行进程 cmdline/environ 快照参数行与白名单环境变量（返回 diff + 运行事实，确认后落库） |
| GET | `/api/admin/model/logs` | SSE：llama.log 实时流（event: log） |
| GET | `/api/admin/model/events` | SSE：状态机变化事件 |

## 状态机

```
STOPPED ──start──▶ STARTING ──/health ok──▶ RUNNING
   ▲                  │ 超时(5min)/失败       │
   │                  ▼                     │ stop
   └──stop完成── STOPPING ◀──────────────────┘
任意 ──探测失败──▶ ERROR / UNKNOWN(SSH 断连)
```

- 27B 加载需数十秒，start 返回后状态 STARTING，状态机轮询 `/health`（复用现有 30s 周期，STARTING 期间加密到 3s）
- 超时置 ERROR，不自动重试，避免反复加载冲击磁盘/GPU
- 状态变化写 `audit_log` 之外的 SSE 事件，前端顶栏指示与运维页同步刷新

## 启停序列

### 停止

SSH 执行 `bash /home/llama-cpp/stop.sh`（现有脚本：SIGTERM → 30s → SIGKILL → 校验端口释放），命令超时 60s；完成后确认端口无监听才置 STOPPED。

### 启动

1. 读 `model_config` 的完整参数行 + 环境变量行，生成完整启动脚本（模板 = 现有 start.sh 结构：flock 锁、pid 文件读写、端口占用检测、跨天日志归档、`numactl --cpunodebind=0 --membind=0`、`CUDA_VISIBLE_DEVICES` 注入、gawk 时间戳日志处理；exec 的参数部分整段由参数行原样渲染，环境变量行渲染为 `export K=V` 置于 `CUDA_DEVICE` 解析之前，模板不再注入任何 flag）
2. SSH 原子写入 `/home/llama-cpp/start-gateway.sh`（tmp + mv）+ `chmod +x`
3. SSH 执行 `bash /home/llama-cpp/start-gateway.sh`，置 STARTING
4. 脚本内 PID 文件与现有机制兼容，stop.sh / 手工 start.sh 均可继续管理同一进程

## 参数管理

### 存储

参数直接以「llama-server 完整参数行」形式存储（命令行中二进制路径之后的全部参数，含 `-m/-mm/--host/--port` 等，任意参数可自由增删改）；环境变量以 `K=V` 行存储（限白名单前缀，见快照流程），UI 同样以代码块直接编辑（每行一个 K=V）。两者启动时分别渲染进 start-gateway.sh 的参数行与 export 段——比结构化表单灵活：

```sql
CREATE TABLE model_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_json TEXT NOT NULL,          -- {"args": "<完整参数行>", "env": "<K=V K=V ...>"}
    source      VARCHAR(20) NOT NULL,   -- default / snapshot / edited
    updated_at  TIMESTAMP
);
```

单行记录；`source` 标记当前参数来源，UI 展示「来源：服务器快照 2026-09-14」。旧版 params JSON 首次读取时惰性迁移为完整参数行（补 `-m/-mm` 与 `--host/--port/--no-log-timestamps`）。

### 基线参数行（= 2026-09-14 实际运行进程完整参数）

```
-m <model> -mm <mmproj> --chat-template-file <path> -a qwen3 -ngl 99 -ts 36,30 -sm layer -c 400000 -ctk q8_0 -ctv q8_0 -np 2 --no-kv-unified --spec-type draft-mtp --spec-draft-n-max 6 --spec-draft-p-min 0.3 --spec-draft-p-split 0.1 -fa on -t 64 -tb 64 --batch-size 2048 --ubatch-size 1024 -cram 65536 --image-min-tokens 1024 --reasoning auto --reasoning-preserve --jinja --host 0.0.0.0 --port 18082 --no-log-timestamps
```

非参数常量（二进制路径、185 连接信息、CUDA 卡号）放 `application.yml` 的 `gateway.model.*` 段；模型路径/mmproj/chat template 属于参数行本身（基线从 yml 生成）。参数行保存前做词法校验：只允许 `-flag [value]` 结构（flag 形如 `-[a-z]+` / `--kebab-case`，value 限 `[A-Za-z0-9._,+-/]`，含路径斜杠），防脚本注入。

### 修改与快照流程

- 修改：代码块编辑完整参数行（默认每行一个参数）→ PUT（词法校验）→ 落库 → UI 明示「已保存，重启后生效」→ 点启动时把参数行原样渲染进脚本
- 快照：取运行进程 `/proc/<pid>/cmdline` → 去掉二进制路径得到完整参数行；同时取 `/proc/<pid>/environ` 白名单前缀变量（`CUDA_VISIBLE_DEVICES` / `NVIDIA_*` / `GGML_*` / `LLAMA_*` / `OMP_*` / `MKL_*`，非白名单静默丢弃）→ 与库内做 flag 级 + env 键级 diff（diff 项以 `kind: arg/env` 区分）→ 前端展示 diff，覆盖落库（source=snapshot）。快照响应附带 `runtimeFacts`：llama.log 中最近一次 `llama-server starting:` 之后匹配 `flash attention|graph|main_gpu|tensor split|offloaded|spec` 的日志行，只读展示（graph/flash-attn 实际生效状态以日志为准）
- 一致性提示：config API 返回 `runningArgs` 与 `drift`（运行参数行与库内参数行 token 级比较），不一致时显示「运行参数与配置不一致」徽标，引导用快照
- 探测跟随：状态探测（端口检查 + `/health`）与启动脚本的端口/文件检查从参数行解析 `--port` / `-m` / `-mm`，缺失时回退 `gateway.model.*` 配置

## 日志流

- `GET /api/admin/model/logs` SSE：每订阅者独立 `ChannelExec` 跑 `tail -n 500 -F /home/llama-cpp/logs/llama.log`，stdout 逐行 push（event: `log`）
- 订阅断开（客户端断开/服务重启）强制关闭 channel，防泄漏
- 前端：自动滚动开关、关键字过滤、error/warn 高亮、最大行数截断

## 前端页面

新增 `frontend/src/components/ModelOpsView.vue`，App.vue Tab 栏加「模型运维」：

- 状态卡：state 徽标（配色区分 RUNNING/STARTING/STOPPED/ERROR/UNKNOWN）、PID、uptime、health、端口、「配置不一致」徽标
- 操作区：启动/停止按钮（按状态启用/禁用、防抖），STARTING 时展示「模型加载中」+ 日志尾部
- 参数区：代码块直接编辑完整参数行 + 环境变量行（来源徽标 + 更新时间 + 「运行参数/环境变量与配置不一致」徽标 + 当前运行参数/环境对照 + 运行事实只读块），保存 + 「从服务器快照」（参数/env 混合 diff 弹窗）；参数区与日志区 Tab 切换
- 日志区：终端样式 SSE 流，自动滚动 + 过滤
- 顶栏上游指示灯改绑 `ModelStatusService`（语义不变：绿/红）

沿用 GitHub Light 主题（`@theme` 变量 + utility class），不新增样式体系。

## 审计

`AuditService` 新增动作：`MODEL_START` / `MODEL_STOP` / `MODEL_CONFIG_CHANGE` / `MODEL_SNAPSHOT`，target 记参数摘要，detail 记 diff 摘要（截断 500 字符）。

## 技术决策

- SSH 库选 sshj：纯 Java、维护活跃；JSch original 停更
- 参数存 H2 `model_config` 而非 JSON 文件：与项目现有持久化风格一致（JPA），免额外文件管理
- 参数以完整参数行存储而非结构化表单：直接对应命令行（含 `-m/-mm/--host/--port`），任意参数可自由增删改，快照/一致性比较即参数行 token 比较，无结构映射漂移；探测与脚本模板的端口/路径从参数行解析
- 启动脚本整体生成推送到 185：脚本在服务器上可见可审计、可手工执行，与现有运维习惯一致；比拼内联长命令可靠
- 不引入 WebSocket：SSE 已满足单向推送，且 nginx 侧已有 `proxy_buffering off` 配置可复用

## 风险与边界

- 双入口漂移：人仍可 SSH 手工改脚本/启停 → 一致性徽标 + 快照按钮兜底
- SSH 断连：状态 UNKNOWN，禁止启停动作；恢复后自动回到真实状态
- 加载超时：5 分钟上限，不自动重试
- 参数行误编辑：词法校验拦截非法 token 与危险 value（防脚本注入）；diff 展示变更，重启前可回滚。注意 `--port`/`-m` 改动会直接影响状态探测与启动文件检查
- 一期不做：多后端、185 资源监控（GPU/磁盘）、模型文件管理（下载/切换权重）、按参数预设多套 profile

## 实施阶段

- 阶段一：`SshService` + `ModelStatusService`（状态机）+ 状态 API + 前端状态卡
- 阶段二：启停（脚本生成 + 异步状态）+ 日志 SSE + 前端操作区/日志区
- 阶段三：完整参数行代码块编辑 + 快照 diff + 一致性提示 + 审计动作
