// --- TestCaseHistoryRepository.java ---
package com.qa.cbcc.repository;

import com.qa.cbcc.model.TestCaseHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestCaseHistoryRepository extends JpaRepository<TestCaseHistory, Long> {
    List<TestCaseHistory> findByTestCaseIdOrderByModifiedOnDesc(Long testCaseId);
}
