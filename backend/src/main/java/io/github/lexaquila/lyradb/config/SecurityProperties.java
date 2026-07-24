package io.github.lexaquila.lyradb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全配置属性（app.security）
 *
 * <p>dev profile 默认 {@code enabled=false}（保持无认证）；prod 启用 HTTP Basic，
 * 用户来自 {@code app.security.users} 列表（用户名/密码/角色）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /** 是否启用认证 */
    private boolean enabled = false;

    /** 用户列表 */
    private List<User> users = new ArrayList<>();

    @Data
    public static class User {
        private String username;
        private String password;
        private String roles = "USER";

        public User() {}

        public User(String username, String password, String roles) {
            this.username = username;
            this.password = password;
            this.roles = roles;
        }
    }
}
