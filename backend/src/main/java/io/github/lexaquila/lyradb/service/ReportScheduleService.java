package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.ReportRun;
import io.github.lexaquila.lyradb.model.entity.ReportSchedule;
import io.github.lexaquila.lyradb.repository.ReportRunRepository;
import io.github.lexaquila.lyradb.repository.ReportScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
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
 * 定时报表订阅服务。
 *
 * <p>订阅绑定所有者与工作空间。Webhook 仅允许解析到公网地址的 HTTPS
 * 目标，发送前再次校验且禁止重定向，以阻断回环、内网、链路本地和云元数据 SSRF。</p>
 */
@Service
public class ReportScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduleService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private static final int MAX_PUSH_ROWS = 100;

    private final ReportScheduleRepository scheduleRepository;
    private final ReportRunRepository runRepository;
    private final QueryService queryService;
    private final AppProperties appProperties;
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ReportScheduleService(ReportScheduleRepository scheduleRepository,
            ReportRunRepository runRepository, QueryService queryService,
            AppProperties appProperties, OutboundUrlPolicy outboundUrlPolicy) {
        this.scheduleRepository = scheduleRepository;
        this.runRepository = runRepository;
        this.queryService = queryService;
        this.appProperties = appProperties;
        this.outboundUrlPolicy = outboundUrlPolicy;
    }

    public List<ReportSchedule> list(String ownerUsername, String workspaceId) {
        return scheduleRepository.findByOwnerUsernameAndWorkspaceIdOrderByCreatedAtDesc(
                ownerUsername, workspaceId);
    }

    public ReportSchedule save(ReportSchedule submitted, String ownerUsername, String workspaceId) {
        validateSchedule(submitted);
        ReportSchedule target;
        if (submitted.getId() == null || submitted.getId().isBlank()) {
            target = new ReportSchedule();
            target.setOwnerUsername(ownerUsername);
            target.setWorkspaceId(workspaceId);
        } else {
            target = requireOwned(submitted.getId(), ownerUsername, workspaceId);
        }
        copyEditableFields(submitted, target);
        return scheduleRepository.save(target);
    }

    @Transactional
    public void delete(String id, String ownerUsername, String workspaceId) {
        ReportSchedule schedule = requireOwned(id, ownerUsername, workspaceId);
        runRepository.deleteByScheduleId(schedule.getId());
        scheduleRepository.delete(schedule);
    }

    public List<ReportRun> listRuns(String scheduleId, String ownerUsername, String workspaceId) {
        requireOwned(scheduleId, ownerUsername, workspaceId);
        return runRepository.findTop20ByScheduleIdOrderByRunAtDesc(scheduleId);
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void scan() {
        // 企业版必须走企业授权、脱敏与审批链，不执行个人版定时报表。
        if ("enterprise".equalsIgnoreCase(appProperties.getEdition())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ReportSchedule schedule : scheduleRepository.findByEnabledTrue()) {
            if (schedule.getOwnerUsername() == null || schedule.getOwnerUsername().isBlank()) {
                log.warn("跳过缺少所有者的历史报表订阅: {}", schedule.getId());
                continue;
            }
            if (isDue(schedule, now)) {
                try {
                    execute(schedule);
                } catch (Exception e) {
                    log.warn("报表订阅执行异常: {} - {}",
                            schedule.getId(), e.getClass().getSimpleName());
                }
            }
        }
    }

    public ReportRun triggerNow(String scheduleId, String ownerUsername, String workspaceId) {
        return execute(requireOwned(scheduleId, ownerUsername, workspaceId));
    }

    private boolean isDue(ReportSchedule schedule, LocalDateTime now) {
        if (schedule.getLastRunAt() != null
                && schedule.getLastRunAt().isAfter(now.minusMinutes(2))) {
            return false;
        }
        return switch (schedule.getScheduleType()) {
            case "HOURLY" -> now.getMinute() == schedule.getRunMinute();
            case "DAILY" -> now.getHour() == schedule.getRunHour()
                    && now.getMinute() == schedule.getRunMinute();
            case "WEEKLY" -> now.getDayOfWeek().getValue() == schedule.getWeekday()
                    && now.getHour() == schedule.getRunHour()
                    && now.getMinute() == schedule.getRunMinute();
            default -> false;
        };
    }

    private ReportRun execute(ReportSchedule schedule) {
        ReportRun run = new ReportRun();
        run.setScheduleId(schedule.getId());
        try {
            QueryService.validateExportSql(schedule.getSql());
            QueryResult result = queryService.executeQuery(schedule.getConnectionId(),
                    schedule.getSql(), schedule.getDefaultDatabase(), true);
            run.setSuccess(true);
            run.setRowCount(result.getTotalRows());
            run.setElapsedMs(result.getElapsedMs());
            run.setPushStatus(push(schedule, result) ? "PUSHED" : "PUSH_FAILED");
        } catch (Exception e) {
            run.setSuccess(false);
            run.setPushStatus("SKIPPED");
            run.setErrorMessage("报表执行失败");
            log.warn("报表订阅查询失败: {} - {}",
                    schedule.getId(), e.getClass().getSimpleName());
        }
        schedule.setLastRunAt(LocalDateTime.now());
        schedule.setLastStatus(run.isSuccess() ? "SUCCESS" : "FAILED");
        scheduleRepository.save(schedule);
        return runRepository.save(run);
    }

    private boolean push(ReportSchedule schedule, QueryResult result) {
        URI target;
        try {
            target = outboundUrlPolicy.validateWebhook(schedule.getWebhookUrl());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("scheduleId", schedule.getId());
            payload.put("name", schedule.getName());
            payload.put("executedAt", LocalDateTime.now().toString());
            payload.put("rowCount", result.getTotalRows());
            payload.put("elapsedMs", result.getElapsedMs());
            payload.put("columns", result.getColumns());
            payload.put("rows", result.getRows().size() > MAX_PUSH_ROWS
                    ? result.getRows().subList(0, MAX_PUSH_ROWS)
                    : result.getRows());

            // 临发送前再次解析，解析失败或任一地址变为私网都拒绝。
            outboundUrlPolicy.validateWebhook(target.toString());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .build();
            HttpResponse<Void> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            if (!ok) {
                log.warn("Webhook 推送非 2xx: host={}, status={}",
                        target.getHost(), response.statusCode());
            }
            return ok;
        } catch (Exception e) {
            log.warn("Webhook 推送被拒绝或失败: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private ReportSchedule requireOwned(String id, String ownerUsername, String workspaceId) {
        ReportSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("订阅不存在"));
        if (!ownerUsername.equals(schedule.getOwnerUsername())
                || !workspaceId.equals(schedule.getWorkspaceId())) {
            throw new AccessDeniedException("无权访问该报表订阅");
        }
        return schedule;
    }

    private void validateSchedule(ReportSchedule schedule) {
        if (schedule == null) {
            throw new IllegalArgumentException("订阅内容不能为空");
        }
        if (schedule.getName() == null || schedule.getName().isBlank()
                || schedule.getName().length() > 100) {
            throw new IllegalArgumentException("name 必填且最长 100 字符");
        }
        QueryService.validateExportSql(schedule.getSql());
        outboundUrlPolicy.validateWebhook(schedule.getWebhookUrl());
        if (!List.of("HOURLY", "DAILY", "WEEKLY").contains(schedule.getScheduleType())) {
            throw new IllegalArgumentException("scheduleType 仅支持 HOURLY/DAILY/WEEKLY");
        }
        if (schedule.getRunMinute() < 0 || schedule.getRunMinute() > 59
                || schedule.getRunHour() < 0 || schedule.getRunHour() > 23
                || schedule.getWeekday() < 1 || schedule.getWeekday() > 7) {
            throw new IllegalArgumentException("报表执行时间参数超出有效范围");
        }
    }

    private void copyEditableFields(ReportSchedule source, ReportSchedule target) {
        target.setName(source.getName().trim());
        target.setConnectionId(source.getConnectionId());
        target.setConnectionName(source.getConnectionName());
        target.setSql(source.getSql());
        target.setDefaultDatabase(source.getDefaultDatabase());
        target.setScheduleType(source.getScheduleType());
        target.setRunMinute(source.getRunMinute());
        target.setRunHour(source.getRunHour());
        target.setWeekday(source.getWeekday());
        target.setWebhookUrl(source.getWebhookUrl().trim());
        target.setEnabled(source.isEnabled());
    }
}
