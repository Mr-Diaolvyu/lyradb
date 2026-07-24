package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.MigrationRequest;
import io.github.lexaquila.lyradb.service.MigrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 跨库数据迁移 REST 控制器（PRD F8）
 *
 * <p>POST /api/migration - 执行一次源→目标迁移（同步，返回行数/错误）。</p>
 */
@RestController
@RequestMapping("/migration")
public class MigrationController {

    private final MigrationService migrationService;

    public MigrationController(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> migrate(@RequestBody MigrationRequest request) {
        return ResponseEntity.ok(migrationService.migrate(request));
    }
}
