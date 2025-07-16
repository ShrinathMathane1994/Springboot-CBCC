package com.qa.cbcc.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.qa.cbcc.model.TestCaseMethodModel;

@Repository
public interface TestCaseMethodRepository extends JpaRepository<TestCaseMethodModel, Long> {
}

