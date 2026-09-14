package com.llama.hub.ops;

import lombok.extern.slf4j.Slf4j;
import com.llama.hub.config.OpsProperties;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 到 185 的常驻 SSH 会话（sshj）。所有模型运维操作走这里；
 * 断线自动重连，慢操作由调用方放独立线程池。
 */
@Service
@Slf4j
public class SshService {


    private final OpsProperties props;
    private final Object lock = new Object();
    private SSHClient client;

    public SshService(OpsProperties props) {
        this.props = props;
    }

    private SSHClient ensureClient() throws Exception {
        synchronized (lock) {
            if (client != null && client.isConnected()) {
                return client;
            }
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
            SSHClient c = new SSHClient();
            c.addHostKeyVerifier(new PromiscuousVerifier());
            c.setTimeout(props.getSshTimeoutSeconds() * 1000);
            try {
                c.connect(props.getSshHost(), props.getSshPort());
                c.authPublickey(props.getSshUser());
            } catch (Exception e) {
                closeQuietly(c);
                throw e;
            }
            log.info("SSH connected {}@{}:{}", props.getSshUser(), props.getSshHost(), props.getSshPort());
            client = c;
            return c;
        }
    }

    /** 远程命令执行结果：stdout + stderr 与进程退出码。 */
    public record ExecResult(String output, int exitCode) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** 一次性执行命令，返回 stdout + stderr 与退出码；失败时使会话失效，下次调用自动重连。 */
    public ExecResult exec(String command, int timeoutSeconds) throws Exception {
        SSHClient c = ensureClient();
        Session channel;
        synchronized (lock) {
            channel = c.startSession();
        }
        try {
            Session.Command cmd = channel.exec(command);
            channel.join(timeoutSeconds, TimeUnit.SECONDS);
            String out;
            try (InputStream in = channel.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String err;
            try (InputStream e = cmd.getErrorStream()) {
                err = new String(e.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exitCode = -1;
            try {
                exitCode = cmd.getExitStatus();
            } catch (Exception ignored) {
            }
            return new ExecResult(out + err, exitCode);
        } catch (Exception e) {
            closeQuietly(c);
            throw e;
        } finally {
            try {
                channel.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 打开长通道（日志 tail 用），调用方负责关闭。stdout 经 getInputStream() 读取。 */
    public Session openExec(String command) throws Exception {
        SSHClient c = ensureClient();
        Session channel;
        synchronized (lock) {
            channel = c.startSession();
        }
        try {
            channel.exec(command);
            return channel;
        } catch (Exception e) {
            closeQuietly(c);
            throw e;
        }
    }

    public SFTPClient openSftp() throws Exception {
        return ensureClient().newSFTPClient();
    }

    public void putRemote(String remotePath, byte[] content) throws Exception {
        Path tmp = Files.createTempFile("llama-hub-upload", ".bin");
        try {
            Files.write(tmp, content);
            try (SFTPClient sftp = openSftp()) {
                sftp.put(tmp.toString(), remotePath);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public void invalidate() {
        SSHClient c;
        synchronized (lock) {
            c = client;
            client = null;
        }
        closeQuietly(c);
    }

    /** 只关指定连接：若它仍是当前 client 则同时清空字段，否则（已被其他线程替换）直接关闭，避免误关新连接或泄漏旧连接。 */
    private void closeQuietly(SSHClient c) {
        if (c == null) {
            return;
        }
        synchronized (lock) {
            if (c == client) {
                client = null;
            }
        }
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    @PreDestroy
    public void close() {
        invalidate();
    }
}
