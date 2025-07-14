package com.qa.cbcc.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.qa.cbcc.model.TestCase;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {
}
