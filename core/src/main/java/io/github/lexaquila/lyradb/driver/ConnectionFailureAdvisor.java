package io.github.lexaquila.lyradb.driver;

import java.util.Locale;

/**
 * 将底层驱动异常归纳为不含凭据的用户可执行提示。
 */
public final class ConnectionFailureAdvisor {

    private ConnectionFailureAdvisor() {
    }

    public static String message(String dbType, Throwable throwable) {
        String detail = causeMessages(throwable).toLowerCase(Locale.ROOT);
        String type = dbType == null ? "" : dbType.trim().toUpperCase(Locale.ROOT);

        if (containsAny(detail, "artifactnotfoundexception",
                "could not find artifact", "artifactresolutionexception")) {
            return "数据库驱动依赖不存在或版本不可用，请更新 LyraDB 后重试";
        }
        if (containsAny(detail, "transferfailedexception",
                "dependencyresolutionexception", "maven.aliyun.com",
                "repo1.maven.org")) {
            return "数据库驱动下载失败，请检查网络、代理和 Maven 仓库访问";
        }
        if (containsAny(detail, "classnotfoundexception",
                "no suitable driver", "noclassdeffounderror")) {
            return "数据库驱动未正确加载，请清理该类型驱动缓存后重试";
        }
        if (containsAny(detail, "expected to read 4 bytes", "read 0 bytes",
                "unexpectedly lost")) {
            return "MYSQL".equals(type)
                    ? "MySQL 握手被远端关闭，请核对端口，并让 SSL 模式与服务端或代理保持一致"
                    : "数据库协议握手被远端关闭，请核对数据库类型、主机和端口";
        }
        if (containsAny(detail, "unknown host", "no such host",
                "name or service not known")) {
            return "主机名无法解析，请检查地址、DNS 或企业网络";
        }
        if (containsAny(detail, "connection refused", "actively refused",
                "no connection could be made")) {
            return "目标端口拒绝连接，请确认数据库服务、监听端口和防火墙";
        }
        if (containsAny(detail, "timed out", "timeout", "connect timed out")) {
            return "连接超时，请检查网络、VPN、防火墙和访问白名单";
        }
        if (containsAny(detail, "access denied", "authentication failed",
                "login failed", "invalid authorization", "ora-01017")) {
            return "身份验证失败，请核对账号、密码和来源主机授权";
        }
        if (containsAny(detail, "certificate", "pkix", "sslhandshake",
                "tls", "encrypt")) {
            return "TLS/SSL 协商失败，请核对加密模式和服务器证书";
        }
        if ("MAXCOMPUTE".equals(type)
                && containsAny(detail, "nosuchobject",
                "database not found", "schema ")
                && containsAny(detail, "does not exist", "not found")) {
            return "MaxCompute 执行 Project 或默认 Schema 不存在。"
                    + "请填写账号已加入且具备 CREATE INSTANCE 权限的执行 Project；"
                    + "公共数据集请通过 BIGDATA_PUBLIC_DATASET.Schema.Table 完整名称访问";
        }
        if (containsAny(detail, "unknown database", "database does not exist",
                "cannot open database", "ora-12514")) {
            return "目标数据库或服务名不存在，或当前账号无权访问";
        }
        if (containsAny(detail, "too many connections",
                "remaining connection slots are reserved")) {
            return "数据库连接数已满，请释放空闲会话或提高服务端连接上限";
        }
        if ("MAXCOMPUTE".equals(type)
                && containsAny(detail, "invalid signature", "signaturedoesnotmatch")) {
            return "MaxCompute 签名校验失败，请核对 AccessKey、Endpoint 和系统时间";
        }
        return "连接失败，请核对地址、端口、数据库名、网络和认证信息";
    }

    private static String causeMessages(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(' ').append(current.getMessage());
            }
            messages.append(' ').append(current.getClass().getSimpleName());
            current = current.getCause();
        }
        return messages.toString();
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
