package com.qa.cbcc.repository;

import com.qa.cbcc.model.ScenarioExampleRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ScenarioExampleRunRepository extends JpaRepository<ScenarioExampleRun, Long> {
    List<ScenarioExampleRun> findByExecutionIdOrderByIdAsc(Long executionId);
    List<ScenarioExampleRun> findByTestCase_IdTCOrderByIdDesc(Long testCaseId);
}


