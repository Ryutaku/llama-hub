# 模型运维控制台设计（llama-gateway 扩展）

## 背景

llama-gateway 已实现 API Key 网关（认证/限额/调用日志/仪表盘），上游是 185 的 llama-server。目前模型的启停靠人工 SSH 执行 `/home/llama-cpp/start.sh` / `stop.sh`，参数修改要手改脚本，日志要 SSH 上去 tail。

本设计把「模型运维控制台」扩展进现有项目：启动/停止模型、修改启动参数、实时查看日志，与网关共享同一个上游状态视图。

## 集成方式

合并进同一项目、同一进程，不拆服务：

- 网关转发前必须知道模型存活（决定转发还是 503），面板展示的也是同一份状态；拆两个进程会出现两份 SSH 连接和两份状态缓存
- 现有 `UpstreamHealthService`（30s 探测 `/health` + 内存状态）直接升级为完整状态机，网关 503 判断与面板状态卡共用
- 数据面/管理面隔离：ops 代码独立子包 `com.gateway.ops`，独立线程池执行 SSH 慢操作，不阻塞 WebClient 转发路径

```
 浏览器(管理页)                          业务客户端
   │ /api/admin/model/**                   │ /v1/* (API Key)
   ▼                                       ▼
┌────────────────────────────────────────────────────────┐
│ llama-gateway :18443                                   │
│                                                        │
│  ProxyController/ProxyService (WebClient, SSE 透传)     │
│  ModelOpsController ── ModelOpsService ──┐             │
│  ModelStatusService(状态机, 原上游探活升级) ◄┘           │
│  SshService(sshj 常驻会话, 独立线程池) ──┘              │
│  鉴权: AdminSessionFilter + IpWhitelistFilter (复用)    │
└──────────────────────┬─────────────────────────────────┘
                       │ 数据面: HTTP 直连 18082 (内网)
                       │ 运维面: SSH 免密 :22
                       ▼
   185: llama-server :18082, /home/llama-cpp/logs/llama.log
```

## 模块设计（com.gateway.ops）

- `SshService`：sshj 常驻 `Session`（root@192.168.2.185:22），提供 `exec(cmd, timeout)` 一次性执行与 `openChannel()` 长通道；断线指数退避重连；所有调用走独立 `opsExecutor` 线程池
- `ModelStatusService`：在现有 `UpstreamHealthService` 基础上扩展：
  - 探测组合：SSH（`llama.pid` + `kill -0` + `ss -ltn sport=:18082`）+ 本地 HTTP `/health`
  - 输出 `ModelStatus { state, pid, uptimeSec, healthOk, portListening, checkedAt }`
  - 状态变化发事件（SSE 广播给运维页）
  - 网关侧 `ProxyService` 的 503 判断改读此状态（原内存健康缓存的替代）
- `ModelOpsService`：start / stop / snapshot 编排（见「启停序列」「快照」）
- `ModelConfigService`：`model_config` 表读写 + 参数白名单校验 + diff 计算
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
| GET | `/api/admin/model/config` | 当前参数配置 + source 标记 |
| PUT | `/api/admin/model/config` | 保存参数（白名单校验，返回 diff，重启生效） |
| POST | `/api/admin/model/config/snapshot` | 从运行进程 cmdline 快照参数（返回 diff，确认后落库） |
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

1. 读 `model_config`，生成完整启动脚本（模板 = 现有 start.sh 结构：flock 锁、pid 文件读写、端口占用检测、跨天日志归档、`numactl --cpunodebind=0 --membind=0`、`CUDA_VISIBLE_DEVICES` 注入、gawk 时间戳日志处理；仅参数段替换）
2. SSH 原子写入 `/home/llama-cpp/start-gateway.sh`（tmp + mv）+ `chmod +x`
3. SSH 执行 `bash /home/llama-cpp/start-gateway.sh`，置 STARTING
4. 脚本内 PID 文件与现有机制兼容，stop.sh / 手工 start.sh 均可继续管理同一进程

## 参数管理

### 存储

```sql
CREATE TABLE model_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_json TEXT NOT NULL,          -- params 整体 JSON
    source      VARCHAR(20) NOT NULL,   -- default / snapshot / edited
    updated_at  TIMESTAMP
);
```

单行记录；`source` 标记当前参数来源，UI 展示「来源：服务器快照 2026-09-14」。

### 参数字段（基线 = 2026-09-14 实际运行进程）

```json
{
  "port": 18082, "alias": "qwen3", "ngl": 99,
  "tensorSplit": "36,30", "sm": "layer",
  "ctx": 400000, "ctk": "q8_0", "ctv": "q8_0", "np": 2, "noKvUnified": true,
  "specType": "draft-mtp", "specDraftNMax": 6, "specDraftPMin": 0.3, "specDraftPSplit": 0.1,
  "flashAttention": "on", "threads": 64, "threadsBatch": 64,
  "batchSize": 2048, "ubatchSize": 1024, "cram": 65536, "imageMinTokens": 1024,
  "reasoning": "auto", "reasoningPreserve": true, "jinja": true, "cudaDevice": "0"
}
```

非参数常量（二进制路径、模型路径、mmproj、chat template、185 连接信息）放 `application.yml` 的 `gateway.model.*` 段，不进表单。

### 修改与快照流程

- 修改：表单 → PUT（白名单校验）→ 落库 → UI 明示「已保存，重启后生效」→ 点启动时按新参数生成脚本
- 快照：SSH 读 PID（llama.pid + 端口检测兜底）→ 解析 `/proc/<pid>/cmdline` → 按白名单字段映射回 params → 与库中 diff → 前端展示 diff 确认后覆盖落库（source=snapshot）
- 一致性提示：状态卡比较运行 cmdline 与库内 params 关键字段，不一致时显示「运行参数与配置不一致」徽标，引导用快照

## 日志流

- `GET /api/admin/model/logs` SSE：每订阅者独立 `ChannelExec` 跑 `tail -n 500 -F /home/llama-cpp/logs/llama.log`，stdout 逐行 push（event: `log`）
- 订阅断开（客户端断开/服务重启）强制关闭 channel，防泄漏
- 前端：自动滚动开关、关键字过滤、error/warn 高亮、最大行数截断

## 前端页面

新增 `frontend/src/components/ModelOpsView.vue`，App.vue Tab 栏加「模型运维」：

- 状态卡：state 徽标（配色区分 RUNNING/STARTING/STOPPED/ERROR/UNKNOWN）、PID、uptime、health、端口、「配置不一致」徽标
- 操作区：启动/停止按钮（按状态启用/禁用、防抖），STARTING 时展示「模型加载中」+ 日志尾部
- 参数区：表单按分组（连接 / GPU / 上下文 / 批处理 / 投机解码 / 其他），保存 + 「从服务器快照」（diff 确认弹窗）
- 日志区：终端样式 SSE 流，自动滚动 + 过滤
- 顶栏上游指示灯改绑 `ModelStatusService`（语义不变：绿/红）

沿用 GitHub Light 主题（`@theme` 变量 + utility class），不新增样式体系。

## 审计

`AuditService` 新增动作：`MODEL_START` / `MODEL_STOP` / `MODEL_CONFIG_CHANGE` / `MODEL_SNAPSHOT`，target 记参数摘要，detail 记 diff 摘要（截断 500 字符）。

## 技术决策

- SSH 库选 sshj：纯 Java、维护活跃；JSch original 停更
- 参数存 H2 `model_config` 而非 JSON 文件：与项目现有持久化风格一致（JPA），免额外文件管理
- 启动脚本整体生成推送到 185：脚本在服务器上可见可审计、可手工执行，与现有运维习惯一致；比拼内联长命令可靠
- 不引入 WebSocket：SSE 已满足单向推送，且 nginx 侧已有 `proxy_buffering off` 配置可复用

## 风险与边界

- 双入口漂移：人仍可 SSH 手工改脚本/启停 → 一致性徽标 + 快照按钮兜底
- SSH 断连：状态 UNKNOWN，禁止启停动作；恢复后自动回到真实状态
- 加载超时：5 分钟上限，不自动重试
- 一期不做：多后端、185 资源监控（GPU/磁盘）、模型文件管理（下载/切换权重）、按参数预设多套 profile

## 实施阶段

- 阶段一：`SshService` + `ModelStatusService`（状态机）+ 状态 API + 前端状态卡
- 阶段二：启停（脚本生成 + 异步状态）+ 日志 SSE + 前端操作区/日志区
- 阶段三：参数表单 + 快照 diff + 一致性提示 + 审计动作
