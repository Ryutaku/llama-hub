package com.llama.hub.ops;

import lombok.extern.slf4j.Slf4j;
import com.llama.hub.config.OpsProperties;
import com.llama.hub.service.ApiException;
import com.llama.hub.service.AuditService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模型启停编排：
 * - 停止走 185 现有 stop.sh 语义（SIGTERM → SIGKILL → 校验端口释放）
 * - 启动按 model_config 的完整参数行生成 start-gateway.sh 推送到 185 后执行
 */
@Service
@Slf4j
public class ModelOpsService {

    private final SshService ssh;
    private final OpsProperties props;
    private final ModelStatusService statusService;
    private final ModelConfigService configService;
    private final AuditService auditService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "model-ops");
        t.setDaemon(true);
        return t;
    });

    public ModelOpsService(SshService ssh, OpsProperties props, ModelStatusService statusService,
                           ModelConfigService configService, AuditService auditService) {
        this.ssh = ssh;
        this.props = props;
        this.statusService = statusService;
        this.configService = configService;
        this.auditService = auditService;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public void start(String username, String ip) {
        ModelStatusService.State st = statusService.getState();
        if (st == ModelStatusService.State.RUNNING || st == ModelStatusService.State.STARTING) {
            throw new ApiException(409, "模型已在运行或启动中（" + st + "）");
        }
        executor.submit(() -> doStart(username, ip));
    }

    public void stop(String username, String ip) {
        ModelStatusService.State st = statusService.getState();
        if (st == ModelStatusService.State.STOPPED || st == ModelStatusService.State.STOPPING) {
            throw new ApiException(409, "模型未运行或已在停止中（" + st + "）");
        }
        executor.submit(() -> doStop(username, ip));
    }

    private void doStart(String username, String ip) {
        try {
            String script = generateStartScript();
            String tmp = props.startScript() + ".tmp";
            ssh.putRemote(tmp, script.getBytes(StandardCharsets.UTF_8));
            SshService.ExecResult mv = ssh.exec(
                    "mv -f " + tmp + " " + props.startScript() + " && chmod +x " + props.startScript(), 10);
            if (!mv.ok()) {
                throw new ApiException(500, "启动脚本推送失败: " + lastLine(mv.output()));
            }
            SshService.ExecResult res = ssh.exec("bash " + props.startScript(), 90);
            log.info("start-gateway.sh output: {}", res.output().trim());
            if (!res.ok()) {
                throw new ApiException(500, "启动失败: " + lastLine(res.output()));
            }
            statusService.markTransition(ModelStatusService.State.STARTING,
                    System.currentTimeMillis() + props.getStartTimeoutSeconds() * 1000L);
            auditService.record(username, "MODEL_START", "llama-server", "port=" + props.getApiPort(), ip);
        } catch (Exception e) {
            log.error("model start failed", e);
            statusService.failWithMessage("启动失败: " + e.getMessage());
            auditService.record(username, "MODEL_START", "llama-server", "failed: " + e.getMessage(), ip);
        }
    }

    private void doStop(String username, String ip) {
        try {
            SshService.ExecResult res = ssh.exec("bash " + props.stopScript(), 90);
            log.info("stop.sh output: {}", res.output().trim());
            if (!res.ok()) {
                throw new ApiException(500, "停止失败: " + lastLine(res.output()));
            }
            statusService.markTransition(ModelStatusService.State.STOPPING,
                    System.currentTimeMillis() + props.getStopTimeoutSeconds() * 1000L);
            auditService.record(username, "MODEL_STOP", "llama-server", "port=" + props.getApiPort(), ip);
        } catch (Exception e) {
            log.error("model stop failed", e);
            statusService.failWithMessage("停止失败: " + e.getMessage());
            auditService.record(username, "MODEL_STOP", "llama-server", "failed: " + e.getMessage(), ip);
        }
    }

    /** 取输出最后一个非空行作为错误摘要（截断 200 字符）。 */
    private String lastLine(String output) {
        if (output == null) {
            return "无输出";
        }
        String[] lines = output.trim().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (!l.isEmpty()) {
                return l.length() > 200 ? l.substring(l.length() - 200) : l;
            }
        }
        return "无输出";
    }

    /** 生成参数化启动脚本（模板来自 resources/ops/start-gateway.template，参数行 = model_config 完整参数行）。 */
    public String generateStartScript() {
        String args = configService.currentArgs();
        configService.validateArgs(args);
        String template = loadTemplate();
        String model = ModelConfigService.flagValueFromArgs(args, "-m");
        if (model == null) {
            model = ModelConfigService.flagValueFromArgs(args, "--model");
        }
        String mmproj = ModelConfigService.flagValueFromArgs(args, "-mm");
        int port = ModelConfigService.portFromArgs(args, props.getApiPort());
        String script = template
                .replace("{{BASE}}", props.getBaseDir())
                .replace("{{SERVER}}", props.getServerBinary())
                .replace("{{MODEL}}", model == null ? "" : model)
                .replace("{{MMPROJ}}", mmproj == null ? "" : mmproj)
                .replace("{{PORT}}", String.valueOf(port))
                .replace("{{ENV}}", configService.formatForScriptEnv(configService.currentEnv()))
                .replace("{{CUDA_DEVICE}}", String.valueOf(props.getCudaDevice()))
                .replace("{{ARGS}}", configService.formatForScript(args));
        if (script.contains("{{")) {
            throw new ApiException(500, "启动脚本模板未完全渲染");
        }
        return script;
    }

    private String loadTemplate() {
        ClassPathResource res = new ClassPathResource("ops/start-gateway.template");
        try (InputStream in = res.getInputStream()) {
            // 归一化行尾：模板若在 Windows 下被改成 CRLF，渲染出的 bash 脚本会直接语法错误
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (Exception e) {
            throw new ApiException(500, "启动脚本模板加载失败: " + e.getMessage());
        }
    }

}
