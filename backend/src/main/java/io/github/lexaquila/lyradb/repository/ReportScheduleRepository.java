package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.ReportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportScheduleRepository extends JpaRepository<ReportSchedule, String> {
    List<ReportSchedule> findAllByOrderByCreatedAtDesc();

    List<ReportSchedule> findByEnabledTrue();
}
