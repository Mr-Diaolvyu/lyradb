package io.github.lexaquila.lyradb.service;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.concurrent.TimeUnit;

/**
 * SSH 隧道服务（跳板机 / 端口转发，PRD F9）
 *
 * <p>
 * 基于 Apache MINA SSHD：在 SSH 跳板上建立本地端口转发，
 * 将目标数据库 host:port 暴露为本地端口，供 JDBC 连接 127.0.0.1。
 * </p>
 *
 * <p>
 * 支持密码认证与私钥认证（PEM 私钥文本 + 可选口令，OpenSSH/PKCS#8 格式）。
 * 隧道生命周期与活跃连接绑定，断开时一并关闭（tracker.close + session.close + client.stop）。
 * </p>
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
            try {
                tracker.close();
            } catch (Exception ignored) {
            }
            try {
                session.close();
            } catch (Exception ignored) {
            }
            try {
                client.stop();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 建立 SSH 本地端口转发隧道（密码认证）
     */
    public Tunnel open(String sshHost, int sshPort, String sshUser, String sshPassword,
            String remoteHost, int remotePort) throws Exception {
        return open(sshHost, sshPort, sshUser, sshPassword, null, null, remoteHost, remotePort);
    }

    /**
     * 建立 SSH 本地端口转发隧道（密码 / 私钥认证）
     *
     * @param sshPrivateKey PEM 私钥文本（OpenSSH/PKCS#8），非空时优先使用私钥认证
     * @param sshPassphrase 私钥口令（可为空）
     */
    public Tunnel open(String sshHost, int sshPort, String sshUser, String sshPassword,
            String sshPrivateKey, String sshPassphrase,
            String remoteHost, int remotePort) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        try {
            ClientSession session = client.connect(sshUser, sshHost, sshPort)
                    .verify(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .getSession();
            if (sshPrivateKey != null && !sshPrivateKey.isBlank()) {
                FilePasswordProvider passwordProvider = (sshPassphrase != null && !sshPassphrase.isEmpty())
                        ? FilePasswordProvider.of(sshPassphrase)
                        : FilePasswordProvider.EMPTY;
                Iterable<KeyPair> keyPairs = SecurityUtils.loadKeyPairIdentities(
                        session, NamedResource.ofName("ssh-private-key"),
                        new ByteArrayInputStream(sshPrivateKey.getBytes(StandardCharsets.UTF_8)),
                        passwordProvider);
                if (keyPairs == null || !keyPairs.iterator().hasNext()) {
                    throw new RuntimeException("SSH 私钥解析失败：不支持的格式或口令错误");
                }
                for (KeyPair kp : keyPairs) {
                    session.addPublicKeyIdentity(kp);
                }
            } else if (sshPassword != null && !sshPassword.isEmpty()) {
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
            try {
                client.stop();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }
}
