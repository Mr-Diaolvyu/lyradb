package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.ReportRun;
import io.github.lexaquila.lyradb.model.entity.ReportSchedule;
import io.github.lexaquila.lyradb.repository.ReportRunRepository;
import io.github.lexaquila.lyradb.repository.ReportScheduleRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时报表订阅服务（迭代二 PM2）
 *
 * <p>
 * 每分钟扫描启用中的订阅，到点执行 SQL 并将结果推送到 Webhook：
 * HOURLY 按分钟对齐，DAILY 按时分对齐，WEEKLY 按星期+时分对齐；
 * lastRunAt 去重防止同一分钟内重复触发。仅允许 SELECT/WITH 语句。
 * Webhook 载荷：{@code {scheduleId,name,rowCount,elapsedMs,columns,rows(前100行)}}。
 * </p>
 */
@Service
public class ReportScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduleService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    /** Webhook 载荷最多携带的行数 */
    private static final int MAX_PUSH_ROWS = 100;

    private final ReportScheduleRepository scheduleRepository;
    private final ReportRunRepository runRepository;
    private final QueryService queryService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ReportScheduleService(ReportScheduleRepository scheduleRepository,
            ReportRunRepository runRepository, QueryService queryService) {
        this.scheduleRepository = scheduleRepository;
        this.runRepository = runRepository;
        this.queryService = queryService;
    }

    // ==================== 订阅 CRUD ====================

    public List<ReportSchedule> listAll() {
        return scheduleRepository.findAllByOrderByCreatedAtDesc();
    }

    public ReportSchedule save(ReportSchedule schedule) {
        if (schedule.getSql() == null || schedule.getSql().isBlank()) {
            throw new RuntimeException("sql 必填");
        }
        String first = SqlParseUtil.firstWord(schedule.getSql().trim().toUpperCase());
        if (!"SELECT".equals(first) && !"WITH".equals(first)) {
            throw new RuntimeException("报表订阅仅支持 SELECT/WITH 查询");
        }
        if (schedule.getWebhookUrl() == null
                || !schedule.getWebhookUrl().matches("^https?://.+")) {
            throw new RuntimeException("webhookUrl 必须为 http/https 地址");
        }
        if (!List.of("HOURLY", "DAILY", "WEEKLY").contains(schedule.getScheduleType())) {
            throw new RuntimeException("scheduleType 仅支持 HOURLY/DAILY/WEEKLY");
        }
        return scheduleRepository.save(schedule);
    }

    @Transactional
    public void delete(String id) {
        runRepository.deleteByScheduleId(id);
        scheduleRepository.deleteById(id);
    }

    public List<ReportRun> listRuns(String scheduleId) {
        return runRepository.findTop20ByScheduleIdOrderByRunAtDesc(scheduleId);
    }

    // ==================== 调度与执行 ====================

    /** 每分钟扫描一次到点订阅 */
    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        for (ReportSchedule s : scheduleRepository.findByEnabledTrue()) {
            if (isDue(s, now)) {
                try {
                    execute(s);
                } catch (Exception e) {
                    log.warn("报表订阅执行异常: {} - {}", s.getName(), e.getMessage());
                }
            }
        }
    }

    /** 立即执行一次（手动触发） */
    public ReportRun triggerNow(String scheduleId) {
        ReportSchedule s = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new RuntimeException("订阅不存在: " + scheduleId));
        return execute(s);
    }

    private boolean isDue(ReportSchedule s, LocalDateTime now) {
        // 同一分钟去重：最近 2 分钟内跑过则跳过
        if (s.getLastRunAt() != null && s.getLastRunAt().isAfter(now.minusMinutes(2))) {
            return false;
        }
        return switch (s.getScheduleType()) {
            case "HOURLY" -> now.getMinute() == s.getRunMinute();
            case "DAILY" -> now.getHour() == s.getRunHour() && now.getMinute() == s.getRunMinute();
            case "WEEKLY" -> now.getDayOfWeek().getValue() == s.getWeekday()
                    && now.getHour() == s.getRunHour() && now.getMinute() == s.getRunMinute();
            default -> false;
        };
    }

    private ReportRun execute(ReportSchedule s) {
        ReportRun run = new ReportRun();
        run.setScheduleId(s.getId());
        try {
            // force=true：订阅 SQL 已在保存时限定为 SELECT，LOW 提醒不应阻断定时任务
            QueryResult result = queryService.executeQuery(
                    s.getConnectionId(), s.getSql(), s.getDefaultDatabase(), true);
            run.setSuccess(true);
            run.setRowCount(result.getTotalRows());
            run.setElapsedMs(result.getElapsedMs());
            run.setPushStatus(push(s, result) ? "PUSHED" : "PUSH_FAILED");
        } catch (Exception e) {
            run.setSuccess(false);
            run.setPushStatus("SKIPPED");
            run.setErrorMessage(e.getMessage());
            log.warn("报表订阅查询失败: {} - {}", s.getName(), e.getMessage());
        }
        s.setLastRunAt(LocalDateTime.now());
        s.setLastStatus(run.isSuccess() ? "SUCCESS" : "FAILED");
        scheduleRepository.save(s);
        return runRepository.save(run);
    }

    private boolean push(ReportSchedule s, QueryResult result) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("scheduleId", s.getId());
            payload.put("name", s.getName());
            payload.put("executedAt", LocalDateTime.now().toString());
            payload.put("rowCount", result.getTotalRows());
            payload.put("elapsedMs", result.getElapsedMs());
            payload.put("columns", result.getColumns());
            payload.put("rows", result.getRows().size() > MAX_PUSH_ROWS
                    ? result.getRows().subList(0, MAX_PUSH_ROWS)
                    : result.getRows());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(s.getWebhookUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            if (!ok) {
                log.warn("Webhook 推送非 2xx: {} -> {}", s.getWebhookUrl(), response.statusCode());
            }
            return ok;
        } catch (Exception e) {
            log.warn("Webhook 推送失败: {} - {}", s.getWebhookUrl(), e.getMessage());
            return false;
        }
    }
}
