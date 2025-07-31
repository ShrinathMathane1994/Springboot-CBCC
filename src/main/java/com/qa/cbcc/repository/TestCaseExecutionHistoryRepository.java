package com.qa.cbcc.repository;

import com.qa.cbcc.model.TestCaseExecutionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseExecutionHistoryRepository extends JpaRepository<TestCaseExecutionHistory, Long> {
}
