package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.SavedSql;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.GrantRepository;
import io.github.lexaquila.lyradb.repository.SavedSqlRepository;
import io.github.lexaquila.lyradb.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserLifecycleServiceTest {
    private UserRepository users;
    private SavedSqlRepository scripts;
    private GrantRepository grants;
    private UserService userService;
    private GrantService grantService;
    private AdminUserLifecycleService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        scripts = mock(SavedSqlRepository.class);
        grants = mock(GrantRepository.class);
        userService = mock(UserService.class);
        grantService = mock(GrantService.class);
        service = new AdminUserLifecycleService(users, scripts, grants,
                userService, grantService);
    }

    @Test
    void softDeleteKeepsRecordAndRequiresScriptsToBeTransferred() {
        User user = user("target");
        when(userService.getById("target")).thenReturn(user);
        when(scripts.countByUserId("target")).thenReturn(2L, 0L);

        assertThatThrownBy(() -> service.softDelete("target", "admin"))
                .hasMessageContaining("先在所属工作空间移交脚本");
        assertThat(user.getDeletedAt()).isNull();
        verify(users, never()).save(any());

        service.softDelete("target", "admin");
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getCredentialVersion()).isEqualTo(1);
        verify(users).save(user);
    }

    @Test
    void freezingInvalidatesSessionsAndCannotFreezeSelf() {
        User user = user("target");
        when(userService.getById("target")).thenReturn(user);

        assertThatThrownBy(() -> service.setFrozen("target", "target", true))
                .hasMessageContaining("当前登录账号");
        service.setFrozen("target", "admin", true);
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getCredentialVersion()).isEqualTo(1);
    }

    @Test
    void transferRebindsOnlyToAnEquivalentTargetGrant() {
        User source = user("source");
        User target = user("target");
        when(userService.getById("source")).thenReturn(source);
        when(userService.getById("target")).thenReturn(target);
        when(userService.belongsToWorkspace("source", "ws")).thenReturn(true);
        when(userService.belongsToWorkspace("target", "ws")).thenReturn(true);

        SavedSql script = new SavedSql();
        script.setGrantId("old-grant");
        script.setUserId("source");
        script.setTitle("查询");
        script.setGrantedSourceName("sales");
        script.setEncryptedSql("ciphertext");
        when(scripts.findByWorkspaceIdAndUserIdOrderByUpdatedAtDesc("ws", "source"))
                .thenReturn(List.of(script));
        Grant oldGrant = grant("source", "old-grant", "sales.orders");
        Grant newGrant = grant("target", "new-grant", "sales.orders");
        when(grants.findById("old-grant")).thenReturn(Optional.of(oldGrant));
        when(grantService.resolveForUser("target", "ws", "sales"))
                .thenReturn(newGrant);

        assertThat(service.transferScripts("ws", "source", "target")).isEqualTo(1);
        assertThat(script.getUserId()).isEqualTo("target");
        assertThat(script.getGrantId()).isEqualTo("new-grant");
        assertThat(script.getEncryptedSql()).isEqualTo("ciphertext");
        verify(scripts).saveAllAndFlush(List.of(script));
    }

    @Test
    void transferRejectsWiderOrDifferentGrantBeforeChangingOwner() {
        when(userService.getById("source")).thenReturn(user("source"));
        when(userService.getById("target")).thenReturn(user("target"));
        when(userService.belongsToWorkspace("source", "ws")).thenReturn(true);
        when(userService.belongsToWorkspace("target", "ws")).thenReturn(true);
        SavedSql script = new SavedSql();
        script.setGrantId("old-grant");
        script.setUserId("source");
        script.setTitle("查询");
        script.setGrantedSourceName("sales");
        when(scripts.findByWorkspaceIdAndUserIdOrderByUpdatedAtDesc("ws", "source"))
                .thenReturn(List.of(script));
        when(grants.findById("old-grant"))
                .thenReturn(Optional.of(grant("source", "old-grant", "sales.orders")));
        when(grantService.resolveForUser("target", "ws", "sales"))
                .thenReturn(grant("target", "new-grant", "*.*"));

        assertThatThrownBy(() -> service.transferScripts("ws", "source", "target"))
                .hasMessageContaining("同范围的授权");
        assertThat(script.getUserId()).isEqualTo("source");
        verify(scripts, never()).saveAllAndFlush(any());
    }

    private static User user(String id) {
        User user = new User();
        user.setId(id);
        user.setEnabled(true);
        return user;
    }

    private static Grant grant(String userId, String id, String tables) {
        Grant grant = new Grant();
        grant.setId(id);
        grant.setUserId(userId);
        grant.setWorkspaceId("ws");
        grant.setDataSourceId("data-source");
        grant.setGrantedSourceName("sales");
        grant.setAllowedSchemas("sales");
        grant.setAllowedTables(tables);
        return grant;
    }
}
