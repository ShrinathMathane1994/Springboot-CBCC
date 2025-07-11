package com.qa.cbcc.repository;

import com.qa.cbcc.model.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, Long> {
    // No need to write the `save()` method — it's inherited from JpaRepository
}
