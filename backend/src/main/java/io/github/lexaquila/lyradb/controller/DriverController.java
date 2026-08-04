
package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.driver.DriverDownloadWebSocketHandler;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.driver.MavenDriverManager;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PreDestroy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 驱动管理REST控制器
 *
 * <p>
 * 提供驱动配置查询、下载状态检查、驱动预下载等API。
 * 驱动下载为异步执行，进度通过 {@code /ws/drivers} WebSocket 实时推送。
 * </p>
 *
 * <p>
 * API路径：
 * </p>
 * <ul>
 * <li>GET /api/drivers - 获取所有驱动配置</li>
 * <li>GET /api/drivers/{dbType} - 获取指定驱动配置</li>
 * <li>GET /api/drivers/{dbType}/status - 检查驱动下载状态</li>
 * <li>POST /api/drivers/{dbType}/download - 预下载驱动（异步，进度走 WebSocket）</li>
 * <li>GET /api/drivers/types - 获取支持的数据库类型列表</li>
 * </ul>
 */
@RestController
@RequestMapping("/drivers")
public class DriverController {

    private static final Logger log = LoggerFactory.getLogger(DriverController.class);

    private final DriverRegistry driverRegistry;
    private final DriverFactory driverFactory;
    private final MavenDriverManager mavenDriverManager;
    private final DriverDownloadWebSocketHandler progressHandler;
    private final SecurityUtil securityUtil;
    private final AppProperties appProperties;

    /** 单线程有界队列，支持的驱动类型数量有限，不接受无限堆积。 */
    private final ThreadPoolExecutor downloadExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10), r -> {
                Thread t = new Thread(r, "driver-download");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    /** 同一驱动下载请求去重（含排队与执行中）。 */
    private final Set<String> inFlightDownloads = ConcurrentHashMap.newKeySet();

    public DriverController(DriverRegistry driverRegistry, DriverFactory driverFactory,
                           MavenDriverManager mavenDriverManager,
                           DriverDownloadWebSocketHandler progressHandler,
                           SecurityUtil securityUtil,
                           AppProperties appProperties) {
        this.driverRegistry = driverRegistry;
        this.driverFactory = driverFactory;
        this.mavenDriverManager = mavenDriverManager;
        this.progressHandler = progressHandler;
        this.securityUtil = securityUtil;
        this.appProperties = appProperties;
    }

    /**
     * 获取所有驱动配置
     */
    @GetMapping
    public ResponseEntity<List<DriverInfo>> getAllDrivers() {
        List<DriverInfo> drivers = driverRegistry.getAllDriverInfos();
        return ResponseEntity.ok(drivers);
    }

    /**
     * 获取指定驱动配置
     */
    @GetMapping("/{dbType}")
    public ResponseEntity<DriverInfo> getDriver(@PathVariable String dbType) {
        DriverInfo driverInfo = driverRegistry.getDriverInfo(dbType);
        return ResponseEntity.ok(driverInfo);
    }

    /**
     * 检查驱动下载状态
     */
    @GetMapping("/{dbType}/status")
    public ResponseEntity<Map<String, Object>> getDriverStatus(@PathVariable String dbType) {
        Map<String, Object> status = new HashMap<>();
        status.put("dbType", dbType);
        status.put("displayName", driverRegistry.getDriverInfo(dbType).getDisplayName());
        status.put("downloaded", driverFactory.isDriverDownloaded(dbType));
        status.put("downloading", inFlightDownloads.contains(
                dbType.toUpperCase(java.util.Locale.ROOT)));
        return ResponseEntity.ok(status);
    }

    /**
     * 预下载驱动（异步）
     *
     * <p>POST 立即返回 {@code {async:true}}，实际下载进度通过 {@code /ws/drivers} 推送：
     * {@code {dbType, percent, message, status:"progress|done|error"}}。</p>
     */
    @PostMapping("/{dbType}/download")
    public ResponseEntity<Map<String, Object>> downloadDriver(@PathVariable String dbType) {
        if ("enterprise".equalsIgnoreCase(appProperties.getEdition())) {
            securityUtil.requireRole("DS_ADMIN");
        }
        Map<String, Object> result = new HashMap<>();
        DriverInfo driverInfo = driverRegistry.getDriverInfo(dbType);
        result.put("dbType", dbType);
        result.put("displayName", driverInfo.getDisplayName());

        // 如果已下载，直接返回成功
        if (driverFactory.isDriverDownloaded(dbType)) {
            result.put("success", true);
            result.put("message", driverInfo.getDisplayName() + " 驱动已就绪");
            result.put("alreadyExists", true);
            progressHandler.sendProgress(dbType, 100, "驱动已就绪", "done");
            return ResponseEntity.ok(result);
        }

        String downloadKey = dbType.toUpperCase(java.util.Locale.ROOT);
        if (!inFlightDownloads.add(downloadKey)) {
            result.put("success", true);
            result.put("async", true);
            result.put("message", "该驱动正在下载或等待下载");
            return ResponseEntity.accepted().body(result);
        }

        try {
            downloadExecutor.execute(() -> {
                try {
                    mavenDriverManager.downloadDriverWithProgress(driverInfo, (dt, pct, msg) ->
                            progressHandler.sendProgress(dt, pct, msg, "progress"));
                    progressHandler.sendProgress(dbType, 100, "驱动加载完成", "done");
                    log.info("驱动下载成功: {}", dbType);
                } catch (Exception e) {
                    log.error("驱动下载失败: {} - {}", dbType,
                            e.getClass().getSimpleName(), e);
                    progressHandler.sendProgress(dbType, -1,
                            "驱动下载失败，请稍后重试", "error");
                } finally {
                    inFlightDownloads.remove(downloadKey);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlightDownloads.remove(downloadKey);
            result.put("success", false);
            result.put("message", "驱动下载队列已满，请稍后重试");
            return ResponseEntity.status(429).body(result);
        }

        result.put("success", true);
        result.put("async", true);
        result.put("message", driverInfo.getDisplayName() + " 驱动开始下载，请关注进度面板");
        return ResponseEntity.ok(result);
    }

    @PreDestroy
    public void shutdown() {
        downloadExecutor.shutdownNow();
        inFlightDownloads.clear();
    }

    /**
     * 获取支持的数据库类型列表（用于前端数据库类型选择器）
     */
    @GetMapping("/types")
    public ResponseEntity<List<Map<String, String>>> getSupportedTypes() {
        List<Map<String, String>> types = driverRegistry.getAllDriverInfos().stream()
                .map(info -> {
                    Map<String, String> type = new HashMap<>();
                    type.put("dbType", info.getDbType());
                    type.put("displayName", info.getDisplayName());
                    type.put("driverType", info.getDriverType());
                    type.put("defaultPort", String.valueOf(info.getDefaultPort()));
                    return type;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(types);
    }
}
