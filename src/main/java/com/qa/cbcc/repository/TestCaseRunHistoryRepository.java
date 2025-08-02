package com.qa.cbcc.repository;

import com.qa.cbcc.model.TestCaseRunHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRunHistoryRepository extends JpaRepository<TestCaseRunHistory, Long> {
}
