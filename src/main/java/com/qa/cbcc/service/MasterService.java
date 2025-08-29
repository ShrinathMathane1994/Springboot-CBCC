package com.qa.cbcc.service;

import com.qa.cbcc.dto.CountryDTO;
import com.qa.cbcc.dto.RegionDTO;
import com.qa.cbcc.dto.PodDTO;

import java.util.List;

public interface MasterService {
    // Country
    CountryDTO saveCountry(CountryDTO dto);
    List<CountryDTO> getAllCountries();
    void deleteCountry(Long id);

    // Region
    RegionDTO saveRegion(RegionDTO dto);
    List<RegionDTO> getAllRegions();
    List<RegionDTO> getRegionsByCountry(Long countryId);   // ✅ new
    void deleteRegion(Long id);

    // Pod
    PodDTO savePod(PodDTO dto);
    List<PodDTO> getAllPods();
    List<PodDTO> getPodsByRegion(Long regionId);           // ✅ new
    void deletePod(Long id);
}
