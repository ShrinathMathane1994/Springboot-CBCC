package com.qa.cbcc.repository;

import com.qa.cbcc.model.Pod;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PodRepository extends JpaRepository<Pod, Long> {
    List<Pod> findByIsActiveTrue();
    List<Pod> findByRegion_IdRegionAndIsActiveTrue(Long regionId);       // ✅ filter
}
