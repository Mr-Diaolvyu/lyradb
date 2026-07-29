package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.ApprovalRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 使用真实 H2、两个线程绑定的 EntityManager 验证审批悲观锁不会复用一级缓存旧状态。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"
})
@Import({
        ApprovalService.class,
        ApprovalServiceJpaConcurrencyTest.JsonConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApprovalServiceJpaConcurrencyTest {

    @Autowired
    private ApprovalRequestRepository repository;

    @Autowired
    private ApprovalService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockBean
    private CredentialService credentialService;

    @MockBean
    private ApprovalSecurityContextService securityContextService;

    @Test
    void staleManagedApprovalCannotBeClaimedAfterAnotherTransactionCommits()
            throws Exception {
        TransactionTemplate transactions =
                new TransactionTemplate(transactionManager);
        String approvalId = transactions.execute(status -> {
            ApprovalRequest approval = new ApprovalRequest();
            approval.setWorkspaceId("workspace-1");
            approval.setApplicantId("user-1");
            approval.setApplicantName("alice");
            approval.setOperationType("EXPORT");
            approval.setDataSourceId("source-1");
            approval.setGrantId("grant-1");
            approval.setGrantedSourceName("sales");
            approval.setSecurityContextHash("CTX");
            approval.setPayloadJson("ENC");
            approval.setPayloadHash("PAYLOAD_HASH");
            approval.setStatus("APPROVED");
            approval.setExpiresAt(LocalDateTime.now().plusHours(1));
            return repository.saveAndFlush(approval).getId();
        });

        String canonicalPayload =
                "{\"sql\":\"select * from orders\","
                        + "\"format\":\"csv\","
                        + "\"defaultDatabase\":\"dw\"}";
        when(credentialService.decryptValue("ENC"))
                .thenReturn(canonicalPayload);
        when(securityContextService.fingerprint(
                org.mockito.ArgumentMatchers.any(Grant.class)))
                .thenReturn("CTX");

        Grant grant = grant();
        User applicant = applicant();
        CountDownLatch staleLoaded = new CountDownLatch(1);
        CountDownLatch firstClaimCommitted = new CountDownLatch(1);
        AtomicReference<EntityManager> stalePersistenceContext =
                new AtomicReference<>();
        AtomicReference<EntityManager> firstClaimPersistenceContext =
                new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> staleTransaction = executor.submit(() ->
                    transactions.execute(status -> {
                        ApprovalRequest stale = repository.findById(approvalId)
                                .orElseThrow();
                        assertEquals("APPROVED", stale.getStatus());
                        stalePersistenceContext.set(
                                transactionalEntityManager());
                        staleLoaded.countDown();
                        await(firstClaimCommitted);

                        RuntimeException rejected = assertThrows(
                                RuntimeException.class,
                                () -> service.claimForExecution(
                                        approvalId, applicant, grant,
                                        "EXPORT", "select * from orders",
                                        "csv", "dw"));
                        assertTrue(rejected.getMessage()
                                .contains("EXECUTING"));
                        // 被调用的 @Transactional 方法按语义已标记回滚；
                        // 显式声明本测试事务回滚，避免提交阶段抛 UnexpectedRollback。
                        status.setRollbackOnly();
                        return true;
                    }));

            await(staleLoaded);
            try {
                ApprovalRequest claimed = transactions.execute(status -> {
                    firstClaimPersistenceContext.set(
                            transactionalEntityManager());
                    return service.claimForExecution(
                            approvalId, applicant, grant,
                            "EXPORT", "select * from orders",
                            "csv", "dw");
                });
                assertEquals("EXECUTING", claimed.getStatus());
            } finally {
                firstClaimCommitted.countDown();
            }

            assertTrue(staleTransaction.get(10, TimeUnit.SECONDS));
            assertNotSame(
                    stalePersistenceContext.get(),
                    firstClaimPersistenceContext.get(),
                    "两个线程必须绑定独立 Persistence Context");
            String finalStatus = transactions.execute(status ->
                    repository.findById(approvalId)
                            .orElseThrow().getStatus());
            assertEquals("EXECUTING", finalStatus);
        } finally {
            firstClaimCommitted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(
                    10, TimeUnit.SECONDS));
        }
    }

    private EntityManager transactionalEntityManager() {
        EntityManager actual =
                EntityManagerFactoryUtils.getTransactionalEntityManager(
                        entityManagerFactory);
        if (actual == null) {
            throw new IllegalStateException(
                    "当前线程未绑定 Persistence Context");
        }
        return actual;
    }

    private static Grant grant() {
        Grant grant = new Grant();
        grant.setId("grant-1");
        grant.setWorkspaceId("workspace-1");
        grant.setUserId("user-1");
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("sales");
        return grant;
    }

    private static User applicant() {
        User user = new User();
        user.setId("user-1");
        user.setUsername("alice");
        return user;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "等待并发事务超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "等待并发事务被中断", exception);
        }
    }

    @TestConfiguration
    static class JsonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
