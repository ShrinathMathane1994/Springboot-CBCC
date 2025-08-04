package com.qa.cbcc.repository;

import com.qa.cbcc.model.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    Optional<TestCase> findByIdTCAndIsActiveTrue(Long idTC);

    Optional<TestCase> findByIdTC(Long idTC); // ✅ gets both active and inactive

    List<TestCase> findByIsActiveTrue();

    List<TestCase> findByIsActiveFalse();

    List<TestCase> findByIdTCIn(List<Long> idTCs);

    // ✅ Java 11-compatible dynamic filtering by country, region, pod
    @Query("SELECT t FROM TestCase t " +
           "WHERE t.isActive = true " +
           "AND (:country IS NULL OR t.country = :country) " +
           "AND (:region IS NULL OR t.region = :region) " +
           "AND (:pod IS NULL OR t.pod = :pod)")
    List<TestCase> findFiltered(
        @Param("country") String country,
        @Param("region") String region,
        @Param("pod") String pod
    );
}
