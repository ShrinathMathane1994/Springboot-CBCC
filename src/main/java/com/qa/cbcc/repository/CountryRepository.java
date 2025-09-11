package com.qa.cbcc.repository;

import com.qa.cbcc.model.Country;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CountryRepository extends JpaRepository<Country, Long> {
    List<Country> findByIsActiveTrue();
    List<Country> findByRegion_IdRegionAndIsActiveTrue(Long regionId);
}
