package com.qa.cbcc.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qa.cbcc.model.Region;

public interface RegionRepository extends JpaRepository<Region, Long> {
    List<Region> findByIsActiveTrue();
    List<Region> findByCountry_IdCountryAndIsActiveTrue(Long countryId); // ✅ filter
}

