package com.qa.cbcc.repository;

import com.qa.cbcc.model.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RegionRepository extends JpaRepository<Region, Long> {
    List<Region> findByIsActiveTrue();
}
