package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.ReportRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportRunRepository extends JpaRepository<ReportRun, String> {
    List<ReportRun> findTop20ByScheduleIdOrderByRunAtDesc(String scheduleId);

    List<ReportRun> findByScheduleIdOrderByRunAtDesc(String scheduleId);

    void deleteByScheduleId(String scheduleId);
}
