package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.ConnectionDTO;
import io.github.lexaquila.lyradb.service.ConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 连接管理REST控制器
 *
 * <p>
 * 提供数据库连接的完整CRUD和连接管理API。
 * </p>
 *
 * <p>
 * API路径：
 * </p>
 * <ul>
 * <li>GET /api/connections - 列出所有连接</li>
 * <li>GET /api/connections/{id} - 获取单个连接</li>
 * <li>POST /api/connections - 创建连接</li>
 * <li>PUT /api/connections/{id} - 更新连接</li>
 * <li>DELETE /api/connections/{id} - 删除连接</li>
 * <li>POST /api/connections/test - 测试连接</li>
 * <li>POST /api/connections/{id}/connect - 建立连接</li>
 * <li>POST /api/connections/{id}/disconnect - 断开连接</li>
 * </ul>
 */
@RestController
@RequestMapping("/connections")
public class ConnectionController {

    private static final Logger log = LoggerFactory.getLogger(ConnectionController.class);

    private final ConnectionService connectionService;

    public ConnectionController(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    /**
     * 列出所有连接配置
     */
    @GetMapping
    public ResponseEntity<List<ConnectionDTO>> listConnections() {
        return ResponseEntity.ok(connectionService.listConnections());
    }

    /**
     * 获取单个连接配置
     */
    @GetMapping("/{id}")
    public ResponseEntity<ConnectionDTO> getConnection(@PathVariable String id) {
        return ResponseEntity.ok(connectionService.getConnection(id));
    }

    /**
     * 创建连接配置
     */
    @PostMapping
    public ResponseEntity<ConnectionDTO> createConnection(@RequestBody ConnectionDTO dto) {
        log.info("创建连接: {} ({})", dto.getName(), dto.getDbType());
        return ResponseEntity.ok(connectionService.createConnection(dto));
    }

    /**
     * 更新连接配置
     */
    @PutMapping("/{id}")
    public ResponseEntity<ConnectionDTO> updateConnection(@PathVariable String id, @RequestBody ConnectionDTO dto) {
        log.info("更新连接: {}", id);
        return ResponseEntity.ok(connectionService.updateConnection(id, dto));
    }

    /**
     * 删除连接配置
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConnection(@PathVariable String id) {
        log.info("删除连接: {}", id);
        connectionService.deleteConnection(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 测试连接（不保存配置，直接验证参数）
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody Map<String, Object> request) {
        String dbType = (String) request.get("dbType");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        log.info("测试连接: {}", dbType);
        return ResponseEntity.ok(connectionService.testConnection(dbType, params));
    }

    /**
     * 建立数据库连接
     */
    @PostMapping("/{id}/connect")
    public ResponseEntity<Map<String, Object>> connect(@PathVariable String id) {
        log.info("建立连接: {}", id);
        return ResponseEntity.ok(connectionService.connect(id));
    }

    /**
     * 断开数据库连接
     */
    @PostMapping("/{id}/disconnect")
    public ResponseEntity<Void> disconnect(@PathVariable String id) {
        log.info("断开连接: {}", id);
        connectionService.disconnect(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 检查连接状态
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String id) {
        Map<String, Object> status = new java.util.HashMap<>();
        status.put("connected", connectionService.isConnected(id));
        return ResponseEntity.ok(status);
    }

    /**
     * 切换收藏状态
     */
    @PostMapping("/{id}/favorite")
    public ResponseEntity<ConnectionDTO> toggleFavorite(@PathVariable String id) {
        log.info("切换收藏: {}", id);
        return ResponseEntity.ok(connectionService.toggleFavorite(id));
    }

    /**
     * 复制连接配置
     */
    @PostMapping("/{id}/duplicate")
    public ResponseEntity<ConnectionDTO> duplicateConnection(@PathVariable String id) {
        log.info("复制连接: {}", id);
        return ResponseEntity.ok(connectionService.duplicateConnection(id));
    }

    /**
     * 导出所有连接配置（JSON 格式，敏感字段仅返回掩码）
     */
    @PostMapping("/export")
    public ResponseEntity<List<ConnectionDTO>> exportConnections() {
        log.info("导出连接配置");
        return ResponseEntity.ok(connectionService.exportConnections());
    }

    /**
     * 导入连接配置 (JSON格式，凭证将重新加密)
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importConnections(@RequestBody List<ConnectionDTO> dtos) {
        log.info("导入连接配置: {} 条", dtos.size());
        return ResponseEntity.ok(connectionService.importConnections(dtos));
    }
}
