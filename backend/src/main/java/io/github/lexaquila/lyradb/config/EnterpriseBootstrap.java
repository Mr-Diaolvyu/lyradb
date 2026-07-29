
package io.github.lexaquila.lyradb.config;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.model.entity.Workspace;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceRepository;
import io.github.lexaquila.lyradb.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 企业版首次启动引导。
 *
 * <p>系统不再创建任何默认口令。数据库中没有用户时，必须显式提供
 * LYRADB_BOOTSTRAP_ADMIN_USERNAME 与 LYRADB_BOOTSTRAP_ADMIN_PASSWORD。</p>
 */
@Component
public class EnterpriseBootstrap {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseBootstrap.class);

    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserService userService;

    public EnterpriseBootstrap(AppProperties appProperties, UserRepository userRepository,
                               WorkspaceRepository workspaceRepository, UserService userService) {
        this.appProperties = appProperties;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.userService = userService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void bootstrap() {
        if (!"enterprise".equalsIgnoreCase(appProperties.getEdition())) {
            return;
        }

        if (userRepository.count() > 0) {
            int migrated = userService.ensureLegacyMemberships();
            if (migrated > 0) {
                log.warn("已把 {} 条旧用户-工作空间关系迁入作用域角色表，请复核最小权限", migrated);
            } else {
                log.info("企业版用户数据已存在，跳过首次启动管理员创建");
            }
            return;
        }

        AppProperties.Enterprise config = appProperties.getEnterprise();
        String username = config == null ? null : trimToNull(config.getBootstrapAdminUsername());
        String password = config == null ? null : trimToNull(config.getBootstrapAdminPassword());
        if (username == null || password == null) {
            throw new IllegalStateException(
                    "企业版首次启动拒绝使用默认账号：请设置 LYRADB_BOOTSTRAP_ADMIN_USERNAME "
                            + "与 LYRADB_BOOTSTRAP_ADMIN_PASSWORD");
        }
        UserService.validatePassword(username, password);

        Workspace workspace = workspaceRepository.findAll().stream().findFirst().orElseGet(() -> {
            Workspace created = new Workspace();
            created.setName("默认工作空间");
            created.setDescription("系统首次启动创建的默认工作空间");
            return workspaceRepository.save(created);
        });

        User admin = userService.create(
                username,
                password,
                config.getBootstrapAdminDisplayName(),
                config.getBootstrapAdminEmail(),
                List.of("PLATFORM_ADMIN", "DS_ADMIN", "STEWARD"));
        userService.assignWorkspace(admin.getUsername(), workspace.getId(),
                List.of("DS_ADMIN", "STEWARD"));
        if (workspace.getOwnerId() == null) {
            workspace.setOwnerId(admin.getId());
            workspaceRepository.save(workspace);
        }
        log.warn("企业版首次启动管理员 {} 已安全创建，请妥善保管配置凭据并及时轮换", username);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
