package com.qa.cbcc.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.qa.cbcc.model.TestCase;

import java.util.List;
import java.util.Optional;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    Optional<TestCase> findByIdAndIsActiveTrue(Long id);

    List<TestCase> findByIsActiveTrue();

    List<TestCase> findByIsActiveFalse();
}

