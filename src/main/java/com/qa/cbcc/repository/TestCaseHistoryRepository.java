package com.qa.cbcc.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.qa.cbcc.model.TestCaseHistory;

public interface TestCaseHistoryRepository extends JpaRepository<TestCaseHistory, Long> {

    // ✅ Correct field path for nested entity ID
    List<TestCaseHistory> findByTestCase_IdTCOrderByModifiedOnDesc(Long testCaseId);
    
    @Query("SELECT h FROM TestCaseHistory h WHERE h.testCase.idTC = :testCaseId ORDER BY h.modifiedOn DESC")
    List<TestCaseHistory> findByTestCaseId(@Param("testCaseId") Long testCaseId);
}
