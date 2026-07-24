package io.github.lexaquila.lyradb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器
 *
 * <p>
 * 统一捕获未被控制器自行处理的异常，返回标准 JSON 错误结构：
 * {@code { "success": false, "error": "...", "message": "..." }}，并附带合适的 HTTP 状态码。
 * </p>
 *
 * <p>
 * 注意：现有 {@link io.github.lexaquila.lyradb.controller.QueryController} 等控制器已将查询失败封装为
 * 200 + error 列返回，此处仅兜底未被捕获的异常，不影响既有语义。
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Bad Request", e.getMessage());
    }

    @ExceptionHandler(SQLTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleSqlTimeout(SQLTimeoutException e) {
        log.warn("SQL 执行超时: {}", e.getMessage());
        return buildResponse(HttpStatus.REQUEST_TIMEOUT, "Query Timeout",
                "SQL 执行超时，请优化查询或调整 app.query-timeout-seconds 配置");
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, Object>> handleSqlException(SQLException e) {
        log.error("SQL 异常: {}", e.getMessage(), e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "SQL Error", e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
        log.error("运行时异常: {}", e.getMessage(), e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("未预期异常: {}", e.getMessage(), e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", error);
        body.put("message", message);
        body.put("status", status.value());
        return ResponseEntity.status(status).body(body);
    }
}
