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
 * 企业版启动引导：edition=enterprise 时，若无用户则创建默认管理员与默认工作空间。
 *
 * <p>默认账号 admin/admin（首次登录后请立即改密）。</p>
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
        // 幂等：确保默认工作空间存在
        Workspace ws = workspaceRepository.findAll().stream().findFirst().orElse(null);
        if (ws == null) {
            ws = new Workspace();
            ws.setName("默认工作空间");
            ws.setDescription("系统引导创建的默认工作空间");
            ws = workspaceRepository.save(ws);
        }

        // 幂等：确保 admin 用户存在
        User admin = userRepository.findByUsername("admin").orElse(null);
        if (admin == null) {
            admin = userService.create(
                    "admin", "admin", "管理员", "admin@lexaquila.local",
                    List.of("PLATFORM_ADMIN", "DS_ADMIN", "STEWARD"));
        }
        // 幂等：确保 admin 属于默认工作空间
        final String wsId = ws.getId();
        final String wsName = ws.getName();
        if (admin.getWorkspaces() == null || admin.getWorkspaces().stream().noneMatch(w -> w.getId().equals(wsId))) {
            userService.assignWorkspace(admin.getUsername(), wsId);
            log.warn("企业版引导：admin/admin（请立即改密），默认工作空间 {}", wsName);
        } else {
            log.info("企业版：admin 与默认工作空间已就绪");
        }
    }
}
