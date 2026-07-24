package io.github.lexaquila.lyradb.service;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * SSH 隧道服务（跳板机 / 端口转发，PRD F9）
 *
 * <p>基于 Apache MINA SSHD：在 SSH 跳板上建立本地端口转发，
 * 将目标数据库 host:port 暴露为本地端口，供 JDBC 连接 127.0.0.1。</p>
 *
 * <p>当前仅支持密码认证；私钥认证可在 {@link #open} 中扩展 addPublicKeyIdentity。
 * 隧道生命周期与活跃连接绑定，断开时一并关闭（tracker.close + session.close + client.stop）。</p>
 */
@Service
public class SshTunnelService {

    private static final Logger log = LoggerFactory.getLogger(SshTunnelService.class);
    private static final int CONNECT_TIMEOUT_SEC = 15;

    /** 隧道句柄 */
    public static class Tunnel {
        private final SshClient client;
        private final ClientSession session;
        private final ExplicitPortForwardingTracker tracker;
        private final int boundLocalPort;

        Tunnel(SshClient client, ClientSession session, ExplicitPortForwardingTracker tracker, int boundLocalPort) {
            this.client = client;
            this.session = session;
            this.tracker = tracker;
            this.boundLocalPort = boundLocalPort;
        }

        public int getBoundLocalPort() {
            return boundLocalPort;
        }

        public void close() {
            try { tracker.close(); } catch (Exception ignored) {}
            try { session.close(); } catch (Exception ignored) {}
            try { client.stop(); } catch (Exception ignored) {}
        }
    }

    /**
     * 建立 SSH 本地端口转发隧道
     */
    public Tunnel open(String sshHost, int sshPort, String sshUser, String sshPassword,
                      String remoteHost, int remotePort) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        try {
            ClientSession session = client.connect(sshUser, sshHost, sshPort)
                    .verify(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .getSession();
            if (sshPassword != null && !sshPassword.isEmpty()) {
                session.addPasswordIdentity(sshPassword);
            }
            session.auth().verify(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!session.isAuthenticated()) {
                throw new RuntimeException("SSH 认证失败");
            }
            // 本地端口转发：localPort=0 自动分配，转发到 remoteHost:remotePort
            ExplicitPortForwardingTracker tracker = session.createLocalPortForwardingTracker(
                    0, new SshdSocketAddress(remoteHost, remotePort));
            int localPort = tracker.getLocalAddress().getPort();
            log.info("SSH 隧道已建立: {}@{}:{} -> 127.0.0.1:{} -> {}:{}",
                    sshUser, sshHost, sshPort, localPort, remoteHost, remotePort);
            return new Tunnel(client, session, tracker, localPort);
        } catch (Exception e) {
            try { client.stop(); } catch (Exception ignored) {}
            throw e;
        }
    }
}
