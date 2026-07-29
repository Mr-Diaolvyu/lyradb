package io.github.lexaquila.lyradb.config;

import io.github.lexaquila.lyradb.service.ApprovalRequiredException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * API 异常边界只能返回稳定错误码和关联 ID，不回显 SQL、驱动或连接细节。
 */
class GlobalExceptionHandlerSecurityTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void approvalRequiredReturnsStructuredConflictWithoutSql() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleApprovalRequired(
                        new ApprovalRequiredException("approval-1", "PENDING"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("APPROVAL_REQUIRED", response.getBody().get("error"));
        assertEquals("approval-1", response.getBody().get("approvalRequestId"));
        assertEquals("PENDING", response.getBody().get("approvalStatus"));
        assertFalse(response.getBody().toString().toLowerCase().contains("select "));
        assertFalse(response.getBody().toString().toLowerCase().contains("update "));
    }

    @Test
    void sqlExceptionDoesNotExposeDriverMessageOrConnectionString() {
        SQLException exception = new SQLException(
                "password=top-secret; jdbc:postgresql://10.0.0.8/prod; SELECT * FROM payroll");

        ResponseEntity<Map<String, Object>> response =
                handler.handleSqlException(exception);

        assertEquals(502, response.getStatusCode().value());
        assertEquals("DATABASE_ERROR", response.getBody().get("error"));
        assertNotNull(response.getBody().get("requestId"));
        String body = response.getBody().toString().toLowerCase();
        assertFalse(body.contains("top-secret"));
        assertFalse(body.contains("jdbc:postgresql"));
        assertFalse(body.contains("payroll"));
    }

    @Test
    void illegalStateDoesNotExposeInternalAuditFailure() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleIllegalState(
                        new IllegalStateException("audit database password=secret"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("OPERATION_REJECTED", response.getBody().get("error"));
        assertFalse(response.getBody().toString().contains("password=secret"));
    }
}
