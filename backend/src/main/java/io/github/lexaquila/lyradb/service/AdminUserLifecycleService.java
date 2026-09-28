package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.SavedSql;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.GrantRepository;
import io.github.lexaquila.lyradb.repository.SavedSqlRepository;
import io.github.lexaquila.lyradb.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 平台用户冻结、逻辑删除及同工作空间 SQL 脚本移交。 */
@Service
public class AdminUserLifecycleService {
    private final UserRepository users;
    private final SavedSqlRepository scripts;
    private final GrantRepository grants;
    private final UserService userService;
    private final GrantService grantService;

    public AdminUserLifecycleService(UserRepository users, SavedSqlRepository scripts,
                                     GrantRepository grants, UserService userService,
                                     GrantService grantService) {
        this.users = users;
        this.scripts = scripts;
        this.grants = grants;
        this.userService = userService;
        this.grantService = grantService;
    }

    @Transactional
    public void setFrozen(String userId, String actorId, boolean frozen) {
        User user = activeRecord(userId);
        requireOtherUser(userId, actorId);
        if (frozen && user.isEnabled()) requireAnotherPlatformAdmin(user);
        if (user.isEnabled() == !frozen) return;
        user.setEnabled(!frozen);
        user.setCredentialVersion(user.getCredentialVersion() + 1);
        users.save(user);
    }

    @Transactional
    public void softDelete(String userId, String actorId) {
        User user = activeRecord(userId);
        requireOtherUser(userId, actorId);
        if (user.isEnabled()) requireAnotherPlatformAdmin(user);
        long remaining = scripts.countByUserId(userId);
        if (remaining > 0) {
            throw new IllegalStateException("该用户还有 " + remaining
                    + " 个脚本，请先在所属工作空间移交脚本");
        }
        user.setEnabled(false);
        user.setDeletedAt(LocalDateTime.now());
        user.setCredentialVersion(user.getCredentialVersion() + 1);
        users.save(user);
    }

    public List<Map<String, Object>> scripts(String workspaceId, String userId) {
        activeRecord(userId);
        if (!userService.belongsToWorkspace(userId, workspaceId)) {
            throw new IllegalArgumentException("用户不属于当前工作空间");
        }
        return scripts.findByWorkspaceIdAndUserIdOrderByUpdatedAtDesc(workspaceId, userId)
                .stream().map(script -> Map.<String, Object>of(
                        "id", script.getId(), "title", script.getTitle(),
                        "grantedSourceName", script.getGrantedSourceName(),
                        "updatedAt", script.getUpdatedAt())).toList();
    }

    @Transactional
    public int transferScripts(String workspaceId, String sourceId,
                               String targetId) {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("请先选择脚本接收人");
        }
        if (Objects.equals(sourceId, targetId)) {
            throw new IllegalArgumentException("接收人不能是原用户");
        }
        activeRecord(sourceId);
        User target = activeRecord(targetId);
        if (!target.isEnabled()
                || !userService.belongsToWorkspace(sourceId, workspaceId)
                || !userService.belongsToWorkspace(targetId, workspaceId)) {
            throw new IllegalArgumentException("原用户和接收人必须属于当前工作空间，且接收人已启用");
        }
        List<SavedSql> owned = scripts.findByWorkspaceIdAndUserIdOrderByUpdatedAtDesc(
                workspaceId, sourceId);
        if (owned.isEmpty()) return 0;
        if (scripts.countByWorkspaceIdAndUserId(workspaceId, targetId) + owned.size() > 100) {
            throw new IllegalStateException("移交后接收人在此工作空间的脚本会超过 100 个");
        }
        for (SavedSql script : owned) {
            Grant previous = grants.findById(script.getGrantId())
                    .orElseThrow(() -> new IllegalStateException(
                            "脚本原授权已不存在，无法安全移交: " + script.getTitle()));
            if (!workspaceId.equals(previous.getWorkspaceId())
                    || !sourceId.equals(previous.getUserId())) {
                throw new IllegalStateException("脚本原授权与所属用户不一致");
            }
            Grant next = grantService.resolveForUser(targetId, workspaceId,
                    script.getGrantedSourceName());
            if (!sameScope(previous, next)) {
                throw new IllegalStateException("接收人缺少同数据源、同范围的授权: "
                        + script.getGrantedSourceName());
            }
            script.setUserId(targetId);
            script.setGrantId(next.getId());
            script.setUpdatedAt(LocalDateTime.now());
        }
        scripts.saveAllAndFlush(owned);
        return owned.size();
    }

    private User activeRecord(String userId) {
        User user = userService.getById(userId);
        if (user.getDeletedAt() != null) {
            throw new IllegalArgumentException("用户已删除");
        }
        return user;
    }

    private static void requireOtherUser(String userId, String actorId) {
        if (Objects.equals(userId, actorId)) {
            throw new IllegalArgumentException("不能冻结或删除当前登录账号");
        }
    }

    private void requireAnotherPlatformAdmin(User target) {
        if (!target.getRoles().contains("PLATFORM_ADMIN")) return;
        long activeAdmins = users.findAll().stream()
                .filter(user -> user.getDeletedAt() == null && user.isEnabled()
                        && user.getRoles().contains("PLATFORM_ADMIN"))
                .count();
        if (activeAdmins <= 1) {
            throw new IllegalStateException("不能冻结或删除最后一个启用的平台管理员");
        }
    }

    private static boolean sameScope(Grant previous, Grant next) {
        return Objects.equals(previous.getDataSourceId(), next.getDataSourceId())
                && SqlParseUtil.splitCsv(previous.getAllowedSchemas()).equals(
                        SqlParseUtil.splitCsv(next.getAllowedSchemas()))
                && SqlParseUtil.splitCsv(previous.getAllowedTables()).equals(
                        SqlParseUtil.splitCsv(next.getAllowedTables()))
                && SqlParseUtil.splitCsv(previous.getBlockedTables()).equals(
                        SqlParseUtil.splitCsv(next.getBlockedTables()))
                && Objects.equals(previous.getSqlCapability(), next.getSqlCapability())
                && previous.getMaxRowsPerQuery() == next.getMaxRowsPerQuery()
                && previous.isExportApprovedOnly() == next.isExportApprovedOnly()
                && Objects.equals(previous.getExpiresAt(), next.getExpiresAt());
    }
}
