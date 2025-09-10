package com.qa.cbcc.repository;

import com.qa.cbcc.model.TestCaseRunHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TestCaseRunHistoryRepository extends JpaRepository<TestCaseRunHistory, Long> {

	// 1) latest run per test case (JPQL)
	@Query("SELECT h FROM TestCaseRunHistory h " +
	       "WHERE h.runTime = (" +
	       "  SELECT MAX(h2.runTime) FROM TestCaseRunHistory h2 WHERE h2.testCase.idTC = h.testCase.idTC" +
	       ")")
	List<TestCaseRunHistory> findLatestRunsForAllTestCases();

	// 2) grouped counts
	@Query("SELECT h.testCase.idTC, COUNT(h) FROM TestCaseRunHistory h GROUP BY h.testCase.idTC")
	List<Object[]> findRunCountsGroupedByTestCase();

	// 3) bulk fetch for many testcases
	List<TestCaseRunHistory> findByTestCase_IdTCInOrderByRunTimeDesc(List<Long> testCaseIds);

	// 4) per-test-case runs (expand)
	List<TestCaseRunHistory> findByTestCase_IdTCOrderByRunTimeDesc(Long testCaseId);

}
