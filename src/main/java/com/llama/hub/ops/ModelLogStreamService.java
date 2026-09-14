package com.llama.hub.ops;

import com.llama.hub.config.OpsProperties;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * llama.log 实时日志流：每个订阅者一个 sshj 长通道（tail -F），
 * 客户端断开必须关闭 channel，防止 185 侧进程/通道泄漏。
 */
@Service
public class ModelLogStreamService {

    private static final Logger log = LoggerFactory.getLogger(ModelLogStreamService.class);

    private final SshService ssh;
    private final OpsProperties props;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "model-log-tail");
        t.setDaemon(true);
        return t;
    });

    public ModelLogStreamService(SshService ssh, OpsProperties props) {
        this.ssh = ssh;
        this.props = props;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<Session> channelRef = new AtomicReference<>();
        Runnable closeChannel = () -> {
            Session channel = channelRef.get();
            if (channel != null) {
                try {
                    channel.close();
                } catch (Exception ignored) {
                }
                channelRef.set(null);
            }
        };
        emitter.onCompletion(closeChannel);
        emitter.onTimeout(closeChannel);
        emitter.onError(t -> closeChannel.run());

        executor.submit(() -> {
            try {
                Session channel = ssh.openExec(
                        "tail -n " + props.getLogTailLines() + " -F " + props.logFile());
                channelRef.set(channel);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(channel.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    emitter.send(SseEmitter.event().name("log").data(line));
                }
            } catch (Exception e) {
                log.debug("log stream ended: {}", e.toString());
            } finally {
                closeChannel.run();
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        });
        return emitter;
    }
}
