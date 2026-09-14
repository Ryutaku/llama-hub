# llama-gateway 项目说明

## 项目定位

llama-gateway（目录名 llama-gateway，artifact 名 llm-gateway）是 185 模型服务（llama-server，Swift-Qwen3.8-27B）的 OpenAI 兼容 API 网关与运维控制台：

- 网关（数据面）：`/v1/*` 反向代理转发到 `http://192.168.2.185:18082`，含 API Key 认证、用量限额、调用日志统计
- 管理界面：Vue 3 SPA（GitHub Light 风格）：仪表盘、API Keys、调用日志、审计日志
- 模型运维控制台（扩展）：模型启动/停止、启动参数修改、实时日志查看，与本网关合并在同一项目，设计见 `docs/model-ops.md`

## 技术栈

- JDK 21
- Spring Boot 4.1.0：starter-web + starter-webflux（WebClient 做代理转发与 SSE 透传）
- 持久化：Spring Data JPA + H2 文件模式（`./data/llm-gateway.mv.db`，`ddl-auto: update`）；本项目不引入 MyBatis/MySQL
- 密码：spring-security-crypto（BCrypt）；Key 明文副本 AES 加密存储
- SSH（模型运维用）：sshj（纯 Java），不使用 JSch
- 前端：Vue 3 + Vite + Tailwind CSS，GitHub Light 主题，无 vue-router（Tab 切换）
- 构建：Maven；打包前必须先构建前端（vite outDir → `src/main/resources/static`）

## 运行与部署约定

- 服务端口 18443；对外经 nginx 9090 反代（SSE 必须 `proxy_buffering off`）
- 分离打包：jar（仅 class）+ `lib/` + `resources/`（application.yml + static/）平级，PropertiesLauncher，`java -jar llm-gateway.jar` 零参数启动，不加启动参数
- 前端改动必须先 `npm run build` 再 `mvn package`；静态产物不进 jar，在 `resources/static/`
- 日志：`logs/llm-gateway.log`
- 可配置项在 `resources/application.yml` 的 `gateway.*` 段：`upstream`、`key.encryption-key`（建议环境变量覆盖）、`log.retention-days`、`log.body-enabled`、`admin.allowed-ips`

## 包与模块约定

- 统一包名 `com.gateway`：config / controller / filter / model / repository / service / util / web
- 模型运维扩展代码放独立子包 `com.gateway.ops`（service/controller 各入子包），不得与数据面代理代码（ProxyService、ApiKeyAuthFilter）互相缠绕
- 管理端 API 一律 `/api/admin/**` 前缀，复用现有会话认证（AdminSessionFilter）与 IP 白名单（IpWhitelistFilter），不为运维功能另开鉴权通道

## 后端服务器 185

- `192.168.2.185:22`，用户 root，SSH 公钥免密，主机名 ubuntu2404
- llama-server（OpenAI 兼容）监听 `0.0.0.0:18082`；二进制 `/home/llama-cpp/llama.cpp/build-cuda/bin/llama-server`
- 模型 `/home/hf/ukisai/Swift-Qwen3.8-27B-GGUF/`（Q4_K_M + F16 mmproj）
- 日志 `/home/llama-cpp/logs/llama.log`（gawk 逐行加时间戳，跨天自动归档）；PID 文件 `/home/llama-cpp/logs/llama.pid`
- 现有脚本 `/home/llama-cpp/start.sh`、`/home/llama-cpp/stop.sh`（flock 防双开、端口检测、SIGTERM→SIGKILL 两段式停止、日志归档）

## 模型运维约定

- 停止：走 185 现有 `stop.sh` 的语义，不发明新停止方式
- 启动：网关按参数配置生成参数化启动脚本推送到 `/home/llama-cpp/start-gateway.sh`，复用 start.sh 的 flock / PID 文件 / 日志归档机制，只替换参数段；不拼超长内联命令
- 参数：唯一来源是 H2 `model_config` 表；UI 明示「重启后生效」；提供「从服务器快照」（解析运行进程 cmdline，防漂移）
- 已知漂移：2026-09-14 运行进程为 `-ts 36,30 -sm layer --ubatch-size 1024`，与 start.sh（`-sm none` 无 `-ts`、ubatch 512）不一致；快照以运行进程为准
- 状态判定以「PID 文件 + `/proc/<pid>/cmdline` + 端口监听 + `/health`」组合作准，单看 PID 文件不够
- SSH 断连期间状态标记 UNKNOWN，不触发任何启停动作
- 27B 加载耗时长：启停接口异步（STARTING/STOPPING + 轮询 `/health`），超时默认 5 分钟，超时不自动重试

## 审计约定

- 管理端敏感操作一律写 `audit_log`；模型运维新增动作：`MODEL_START` / `MODEL_STOP` / `MODEL_CONFIG_CHANGE` / `MODEL_SNAPSHOT`
- 审计日志保留 180 天，随 `LogCleanerService` 清理

## 安全约定

- 管理端（含模型运维 API）受会话 + `gateway.admin.allowed-ips` 白名单保护
- 不在项目内存放 SSH 口令/私钥，依赖本机公钥免密
- `gateway.key.encryption-key` 建议通过环境变量覆盖，不落默认值

## 验证约定

- 网关：发真实 `/v1/chat/completions`（含流式）验证转发、认证与 usage 统计，不能只探 `/health`
- 模型运维：真实执行一次启动、一次停止、一次参数修改（快照 diff 对比），并用 SSE 确认日志流有输出
