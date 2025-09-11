package com.qa.cbcc.repository;

import com.qa.cbcc.model.Pod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PodRepository extends JpaRepository<Pod, Long> {
    List<Pod> findByIsActiveTrue();
    List<Pod> findByRegion_IdRegionAndIsActiveTrue(Long regionId);
    List<Pod> findByCountry_IdCountryAndIsActiveTrue(Long countryId);
    List<Pod> findByRegion_IdRegionAndCountry_IdCountryAndIsActiveTrue(Long regionId, Long countryId);

    @Query("SELECT p FROM Pod p " +
           "WHERE p.isActive = true " +
           "  AND (:regionId IS NULL OR p.region.idRegion = :regionId) " +
           "  AND (:countryId IS NULL OR p.country.idCountry = :countryId)")
    List<Pod> findActiveByRegionAndCountryOptional(@Param("regionId") Long regionId,
                                                   @Param("countryId") Long countryId);
}
