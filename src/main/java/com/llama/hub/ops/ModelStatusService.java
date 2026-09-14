package com.llama.hub.ops;

import com.llama.hub.config.OpsProperties;
import com.llama.hub.mapper.ModelConfigMapper;
import com.llama.hub.model.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型状态机：周期组合探测（SSH PID/端口 + HTTP /health），
 * 维护 STOPPED/STARTING/RUNNING/STOPPING/ERROR/UNKNOWN 状态并广播变化。
 */
@Service
public class ModelStatusService {

    private static final Logger log = LoggerFactory.getLogger(ModelStatusService.class);

    public enum State {
        STOPPED, STARTING, RUNNING, STOPPING, ERROR, UNKNOWN
    }

    private final SshService ssh;
    private final OpsProperties props;
    private final ModelConfigMapper configMapper;
    private final WebClient.Builder webClientBuilder;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "model-status-probe");
                t.setDaemon(true);
                return t;
            });
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private volatile State state = State.UNKNOWN;
    private volatile Integer pid;
    private volatile boolean portListening;
    private volatile boolean healthOk;
    private volatile boolean sshAvailable;
    private volatile String message;
    private volatile String lastCmdline;
    private final AtomicLong lastChangeAt = new AtomicLong(0);
    private final AtomicLong uptimeStartAt = new AtomicLong(0);
    private volatile long transitionDeadline;

    public ModelStatusService(SshService ssh, OpsProperties props, ModelConfigMapper configMapper,
                              WebClient.Builder webClientBuilder) {
        this.ssh = ssh;
        this.props = props;
        this.configMapper = configMapper;
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        scheduler.scheduleWithFixedDelay(this::probeSafe, 1000, props.getProbeIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void probeSafe() {
        try {
            probeOnce();
        } catch (Exception e) {
            log.warn("model status probe failed: {}", e.toString());
        }
    }

    private void probeOnce() {
        boolean sshOk = false;
        Integer newPid = null;
        boolean newPort = false;
        String newCmdline = null;

        try {
            String out = ssh.exec(probeCommand(), 12).output();
            sshOk = true;
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.startsWith("PID_OK ")) {
                    try {
                        newPid = Integer.valueOf(line.substring(7).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (line.equals("PORT_OK")) {
                    newPort = true;
                } else if (line.startsWith("CMDLINE:")) {
                    newCmdline = line.substring("CMDLINE:".length()).trim();
                }
            }
        } catch (Exception e) {
            sshOk = false;
        }

        boolean newHealth = httpHealth();

        State prev = state;
        State target;
        long now = System.currentTimeMillis();
        if (!sshOk) {
            target = State.UNKNOWN;
        } else if (state == State.STARTING) {
            if (newHealth && newPort) {
                target = State.RUNNING;
            } else if (transitionDeadline > 0 && now > transitionDeadline) {
                target = State.ERROR;
            } else {
                target = State.STARTING;
            }
        } else if (state == State.STOPPING) {
            if (!newPort) {
                target = State.STOPPED;
            } else if (transitionDeadline > 0 && now > transitionDeadline) {
                target = State.ERROR;
            } else {
                target = State.STOPPING;
            }
        } else if (state == State.UNKNOWN) {
            target = newHealth ? State.RUNNING : (newPid != null || newPort) ? State.STARTING : State.STOPPED;
        } else {
            // STOPPED / ERROR / RUNNING 稳态探测（ERROR 允许自动恢复）
            target = newHealth ? State.RUNNING : (newPid != null || newPort) ? State.STARTING : State.STOPPED;
        }

        this.pid = newPid;
        this.portListening = newPort;
        this.healthOk = newHealth;
        this.sshAvailable = sshOk;
        this.lastCmdline = newCmdline;

        if (target != prev) {
            this.state = target;
            lastChangeAt.set(System.currentTimeMillis());
            if (target == State.RUNNING && prev != State.RUNNING) {
                uptimeStartAt.set(System.currentTimeMillis());
            }
            if (target == State.STOPPED || target == State.STOPPING) {
                uptimeStartAt.set(0);
            }
            transitionDeadline = 0;
            message = null;
            log.info("model state: {} -> {}", prev, target);
            emitEvent();
        }
    }

    private String probeCommand() {
        StringBuilder sb = new StringBuilder();
        sb.append("pid=\"$(tr -d '[:space:]' < ").append(props.pidFile()).append(" 2>/dev/null || true)\"\n");
        sb.append("if [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null; then echo \"PID_OK $pid\"; else echo PID_NONE; fi\n");
        sb.append("if ss -H -ltn sport = :").append(currentPort()).append(" 2>/dev/null | grep -q .; then echo PORT_OK; else echo PORT_NONE; fi\n");
        sb.append("if [ -n \"$pid\" ] && [ -d \"/proc/$pid\" ]; then echo \"CMDLINE:$(tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null)\"; fi\n");
        return sb.toString();
    }

    /** 探测端口跟随 model_config 参数行的 --port，缺失时回退配置。 */
    private int currentPort() {
        String args = null;
        try {
            ModelConfig row = configMapper.findRow(1L);
            if (row != null) {
                args = ModelConfigService.argsFromJson(row.getConfigJson());
            }
        } catch (Exception e) {
            log.warn("read model_config for probe port failed: {}", e.toString());
        }
        return ModelConfigService.portFromArgs(args, props.getApiPort());
    }

    private boolean httpHealth() {
        try {
            String body = webClientBuilder.clone()
                    .baseUrl("http://" + props.getSshHost() + ":" + currentPort())
                    .build()
                    .get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(2))
                    .block();
            return body != null && (body.contains("ok") || body.contains("\"status\""));
        } catch (Exception e) {
            return false;
        }
    }

    /** 启停操作触发状态迁移并设置超时截止时间。 */
    public void markTransition(State to, long deadlineMillis) {
        State prev = state;
        this.state = to;
        this.transitionDeadline = deadlineMillis;
        lastChangeAt.set(System.currentTimeMillis());
        if (to == State.ERROR) {
            message = "状态迁移超时";
        }
        if (to != prev) {
            log.info("model state: {} -> {} (transition, deadline={})", prev, to, deadlineMillis);
            emitEvent();
        }
    }

    public void failWithMessage(String msg) {
        this.state = State.ERROR;
        this.message = msg;
        lastChangeAt.set(System.currentTimeMillis());
        emitEvent();
    }

    public State getState() {
        return state;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state.name());
        m.put("pid", pid);
        m.put("portListening", portListening);
        m.put("healthOk", healthOk);
        m.put("sshAvailable", sshAvailable);
        long uptime = uptimeStartAt.get();
        m.put("uptimeSec", uptime > 0 && state == State.RUNNING ? (System.currentTimeMillis() - uptime) / 1000 : null);
        m.put("lastChangeAt", lastChangeAt.get() == 0 ? null : lastChangeAt.get());
        m.put("message", message);
        return m;
    }

    public String lastCmdline() {
        return lastCmdline;
    }

    public SseEmitter subscribeEvents() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        try {
            emitter.send(SseEmitter.event().name("state").data(status(), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            emitters.remove(emitter);
        }
        Runnable remove = () -> emitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
        return emitter;
    }

    private void emitEvent() {
        Map<String, Object> payload = status();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("state").data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }
}
