package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;

import javax.swing.BorderFactory;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.Component;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 将数据库驱动异常转换为可执行的中文排查建议。
 */
final class ConnectionErrorAdvisor {

    private static final int MAX_TECHNICAL_DETAIL_LENGTH = 800;
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)(password|pwd|accesskeysecret|api[-_]?key|token|secret|passphrase)\\s*[=:]\\s*[^;\\s]+"
    );

    private ConnectionErrorAdvisor() {
    }

    static void show(Component owner, DesktopConnection connection,
            Throwable throwable) {
        JTextArea text = new JTextArea(explain(connection, throwable), 13, 62);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        text.setBackground(NativeTheme.SURFACE);
        text.setForeground(NativeTheme.FOREGROUND);
        text.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = UiKit.scroll(text);
        JOptionPane.showMessageDialog(owner, scroll,
                "数据库连接失败", JOptionPane.ERROR_MESSAGE);
    }

    static String explain(DesktopConnection connection, Throwable throwable) {
        Throwable root = rootCause(throwable);
        String dbType = connection == null ? "" : connection.getDbType();
        String searchable = causeMessages(throwable).toLowerCase(Locale.ROOT);
        Diagnosis diagnosis = diagnose(dbType, searchable);

        StringBuilder message = new StringBuilder();
        message.append("未能连接到 ")
                .append(displayName(connection))
                .append("。");
        String endpoint = endpoint(connection);
        if (!endpoint.isBlank()) {
            message.append("\n目标：").append(endpoint);
        }
        message.append("\n\n判断：").append(diagnosis.summary());
        message.append("\n\n建议：");
        for (int index = 0; index < diagnosis.actions().size(); index++) {
            message.append("\n")
                    .append(index + 1)
                    .append(". ")
                    .append(diagnosis.actions().get(index));
        }
        message.append("\n\n技术详情：")
                .append(technicalDetail(root));
        return message.toString();
    }

    private static Diagnosis diagnose(String dbType, String message) {
        if (containsAny(message, "artifactnotfoundexception",
                "could not find artifact", "artifactresolutionexception")) {
            return new Diagnosis(
                    "所需数据库驱动依赖不存在或版本不可用。",
                    List.of(
                            "这是驱动包解析问题，不是数据库账号或 Endpoint 错误。",
                            "更新 LyraDB 后重试；如仍失败，请检查 Maven 仓库镜像是否完整。"
                    ));
        }
        if (containsAny(message, "transferfailedexception",
                "dependencyresolutionexception", "maven.aliyun.com",
                "repo1.maven.org")) {
            return new Diagnosis(
                    "数据库驱动下载失败。",
                    List.of(
                            "检查当前网络、系统代理以及 Maven 仓库访问。",
                            "网络恢复后重试；无需重新填写数据库密码或 AccessKey。"
                    ));
        }
        if (containsAny(message, "classnotfoundexception",
                "noclassdeffounderror", "no suitable driver")) {
            return new Diagnosis(
                    "数据库驱动包不完整或未能正确加载。",
                    List.of(
                            "关闭 LyraDB 后清理该数据库类型的驱动缓存，再重新连接。",
                            "确认安全软件没有隔离刚下载的 JAR 文件。"
                    ));
        }
        if ("MAXCOMPUTE".equalsIgnoreCase(dbType)
                && containsAny(message, "nosuchobject",
                "database not found", "schema ")
                && containsAny(message, "does not exist", "not found")) {
            return new Diagnosis(
                    "MaxCompute 已响应，但执行 Project 或默认 Schema 不存在或不可访问。",
                    List.of(
                            "执行 Project 必须是账号已加入且具有 CREATE INSTANCE 权限的真实 Project，不能填写 DataWorks 工作空间名。",
                            "Endpoint 必须与执行 Project 所在地域一致；本地客户端应使用该地域的公网 Endpoint。",
                            "MAXCOMPUTE_PUBLIC_DATA/BIGDATA_PUBLIC_DATASET 是跨项目公共数据，不能作为执行 Project；请连接自己的 Project 后使用完整的 Project.Schema.Table 名称查询。"
                    ));
        }
        if (containsAny(message, "expected to read 4 bytes", "read 0 bytes",
                "unexpectedly lost", "before connection was unexpectedly lost")) {
            List<String> actions = new ArrayList<>();
            actions.add("确认主机和端口确实指向目标数据库服务；MySQL 默认端口为 3306。");
            if ("MYSQL".equalsIgnoreCase(dbType)) {
                actions.add("让“SSL 模式”与可正常连接的客户端保持一致；代理未启用 SSL 时选择“禁用 SSL”。");
                actions.add("使用 caching_sha2_password 且未启用 SSL 时，仅在确认服务器可信后启用“允许获取服务器 RSA 公钥”。");
            } else {
                actions.add("确认数据库服务正在运行，且防火墙或云数据库白名单允许当前设备访问。");
            }
            actions.add("若目标经过 SSH、网关或端口转发，请确认转发端口提供的是数据库协议，而不是 HTTP/HTTPS。");
            return new Diagnosis(
                    "服务器在数据库协议握手完成前主动关闭了连接。",
                    actions);
        }
        if (containsAny(message, "unknown host", "name or service not known",
                "nodename nor servname", "no such host")) {
            return new Diagnosis(
                    "主机名无法解析。",
                    List.of(
                            "检查主机地址是否拼写正确。",
                            "若使用内网域名，请先连接对应 VPN 或企业网络。"
                    ));
        }
        if (containsAny(message, "connection refused", "actively refused",
                "no connection could be made")) {
            return new Diagnosis(
                    "目标主机可达，但端口没有接受数据库连接。",
                    List.of(
                            "确认数据库服务已启动并监听填写的端口。",
                            "检查数据库监听地址、防火墙和容器端口映射。"
                    ));
        }
        if (containsAny(message, "timed out", "timeout", "connect timed out")) {
            return new Diagnosis(
                    "在连接超时时间内未收到数据库响应。",
                    List.of(
                            "检查网络、VPN、防火墙和云数据库访问白名单。",
                            "确认主机与端口后，可在连接配置中适当增大连接超时。"
                    ));
        }
        if (containsAny(message, "access denied", "authentication failed",
                "login failed", "invalid authorization", "ora-01017")) {
            return new Diagnosis(
                    "数据库已响应，但身份验证失败。",
                    List.of(
                            "重新核对用户名和密码，注意账号大小写及认证方式。",
                            "确认账号允许从当前主机登录，并具备目标数据库访问权限。"
                    ));
        }
        if (containsAny(message, "certificate", "pkix", "sslhandshake",
                "tls", "encrypt")) {
            List<String> actions = new ArrayList<>();
            actions.add("核对连接配置中的 TLS/SSL 开关与服务器要求是否一致。");
            if ("MSSQL".equalsIgnoreCase(dbType)) {
                actions.add("SQL Server 使用自签名证书时，可在确认服务器可信后启用“信任服务器证书”。");
            } else {
                actions.add("如服务器要求证书校验，请安装正确的 CA/服务器证书，不要长期关闭校验。");
            }
            return new Diagnosis("TLS/SSL 协商或证书校验失败。", actions);
        }
        if (containsAny(message, "public key retrieval is not allowed",
                "allowpublickeyretrieval")) {
            return new Diagnosis(
                    "MySQL 账号认证方式要求获取服务器公钥。",
                    List.of(
                            "优先启用 SSL 并校验服务器身份。",
                            "仅在确认目标服务器可信时，才允许公钥获取或调整账号认证插件。"
                    ));
        }
        if (containsAny(message, "unknown database", "database does not exist",
                "cannot open database", "ora-12514")) {
            return new Diagnosis(
                    "服务器可连接，但目标数据库或服务名不存在或不可访问。",
                    List.of(
                            "核对数据库名、Oracle 服务名或 SQL Server 默认数据库。",
                            "确认当前账号具有连接该数据库的权限。"
                    ));
        }
        if (containsAny(message, "too many connections",
                "remaining connection slots are reserved")) {
            return new Diagnosis(
                    "数据库连接数已达到服务端上限。",
                    List.of(
                            "断开不再使用的会话后重试。",
                            "由数据库管理员检查连接池与服务端最大连接数配置。"
                    ));
        }
        if (message.contains("maven 驱动管理器已关闭")) {
            return new Diagnosis(
                    "应用正在退出，后台连接任务未能完成。",
                    List.of("重新启动 LyraDB 后再连接；新版会等待后台任务结束后再关闭驱动管理器。"));
        }
        return new Diagnosis(
                "驱动未能建立有效的数据库会话。",
                List.of(
                        "先核对主机、端口、数据库名和认证信息。",
                        "确认数据库服务状态与网络访问策略，再重试连接。"
                ));
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String displayName(DesktopConnection connection) {
        if (connection == null) {
            return "数据库";
        }
        String type = "MSSQL".equalsIgnoreCase(connection.getDbType())
                ? "SQL Server" : connection.getDbType();
        return connection.getDisplayName() + "（" + type + "）";
    }

    private static String endpoint(DesktopConnection connection) {
        if (connection == null) {
            return "";
        }
        Map<String, Object> params = connection.getParams();
        Object host = params.get("host");
        Object port = params.get("port");
        if (host == null || host.toString().isBlank()) {
            Object endpoint = params.get("endpoint");
            Object project = params.get("project");
            if (endpoint == null || endpoint.toString().isBlank()) {
                return "";
            }
            return project == null || project.toString().isBlank()
                    ? endpoint.toString() : endpoint + " · " + project;
        }
        return port == null ? host.toString() : host + ":" + port;
    }

    private static String technicalDetail(Throwable root) {
        String detail = root.getClass().getSimpleName();
        if (root.getMessage() != null && !root.getMessage().isBlank()) {
            detail += "：" + root.getMessage();
        }
        if (root instanceof SQLException sqlException
                && sqlException.getSQLState() != null) {
            detail += "（SQLState=" + sqlException.getSQLState()
                    + ", ErrorCode=" + sqlException.getErrorCode() + "）";
        }
        detail = SENSITIVE_VALUE.matcher(detail).replaceAll("$1=***");
        return detail.length() <= MAX_TECHNICAL_DETAIL_LENGTH
                ? detail : detail.substring(0, MAX_TECHNICAL_DETAIL_LENGTH) + "…";
    }

    private static String causeMessages(Throwable throwable) {
        StringBuilder result = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null) {
                result.append(' ').append(current.getMessage());
            }
            current = current.getCause();
        }
        return result.toString();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable == null
                ? new IllegalStateException("未知连接错误") : throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private record Diagnosis(String summary, List<String> actions) {
    }
}
