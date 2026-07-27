package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.ReportRun;
import io.github.lexaquila.lyradb.model.entity.ReportSchedule;
import io.github.lexaquila.lyradb.service.ReportScheduleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 定时报表订阅控制器（迭代二 PM2）
 *
 * <ul>
 * <li>GET /api/reports - 订阅列表</li>
 * <li>POST /api/reports - 新建/更新订阅（带 id 即更新）</li>
 * <li>DELETE /api/reports/{id} - 删除订阅（连带执行记录）</li>
 * <li>GET /api/reports/{id}/runs - 最近 20 次执行记录</li>
 * <li>POST /api/reports/{id}/trigger - 立即执行一次</li>
 * </ul>
 */
@RestController
@RequestMapping("/reports")
public class ReportScheduleController {

    private final ReportScheduleService reportScheduleService;

    public ReportScheduleController(ReportScheduleService reportScheduleService) {
        this.reportScheduleService = reportScheduleService;
    }

    @GetMapping
    public List<ReportSchedule> list() {
        return reportScheduleService.listAll();
    }

    @PostMapping
    public ReportSchedule save(@RequestBody ReportSchedule schedule) {
        return reportScheduleService.save(schedule);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        reportScheduleService.delete(id);
        return Map.of("success", true);
    }

    @GetMapping("/{id}/runs")
    public List<ReportRun> runs(@PathVariable String id) {
        return reportScheduleService.listRuns(id);
    }

    @PostMapping("/{id}/trigger")
    public ReportRun trigger(@PathVariable String id) {
        return reportScheduleService.triggerNow(id);
    }
}
