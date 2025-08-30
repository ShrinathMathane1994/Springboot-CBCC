package com.qa.cbcc.service;

import com.qa.cbcc.dto.CountryDTO;
import com.qa.cbcc.dto.RegionDTO;
import com.qa.cbcc.dto.PodDTO;
import com.qa.cbcc.model.Country;
import com.qa.cbcc.model.Region;
import com.qa.cbcc.model.Pod;
import com.qa.cbcc.repository.CountryRepository;
import com.qa.cbcc.repository.RegionRepository;
import com.qa.cbcc.repository.PodRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MasterServiceImpl implements MasterService {

    @Autowired
    private CountryRepository countryRepo;

    @Autowired
    private RegionRepository regionRepo;

    @Autowired
    private PodRepository podRepo;

    // ---------------- COUNTRY ----------------
    @Override
    public CountryDTO saveCountry(CountryDTO dto) {
        Country country = new Country();
        country.setIdCountry(dto.getIdCountry());
        country.setCountryName(dto.getCountryName());
        country.setCreatedOn(LocalDateTime.now());
        country.setActive(true);
        countryRepo.save(country);
        dto.setIdCountry(country.getIdCountry());
        return dto;
    }

    @Override
    public List<CountryDTO> getAllCountries() {
        return countryRepo.findByIsActiveTrue()
                .stream()
                .map(c -> {
                    CountryDTO dto = new CountryDTO();
                    dto.setIdCountry(c.getIdCountry());
                    dto.setCountryName(c.getCountryName());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void deleteCountry(Long id) {
        countryRepo.findById(id).ifPresent(c -> {
            c.setActive(false);
            c.setUpdatedOn(LocalDateTime.now());
            countryRepo.save(c);
        });
    }

    // ---------------- REGION ----------------
    @Override
    public RegionDTO saveRegion(RegionDTO dto) {
        Region region = new Region();
        region.setIdRegion(dto.getIdRegion());
        region.setRegionName(dto.getRegionName());
        region.setCreatedOn(LocalDateTime.now());
        region.setActive(true);

        // ❌ Java 11+: region.setCountry(countryRepo.findById(dto.getIdCountry()).orElseThrow());
        // ✅ Java 8 replacement:
        region.setCountry(countryRepo.findById(dto.getIdCountry())
                .orElseThrow(() -> new RuntimeException("Country not found with id " + dto.getIdCountry())));

        regionRepo.save(region);
        dto.setIdRegion(region.getIdRegion());
        return dto;
    }

    @Override
    public List<RegionDTO> getAllRegions() {
        return regionRepo.findByIsActiveTrue()
                .stream()
                .map(r -> {
                    RegionDTO dto = new RegionDTO();
                    dto.setIdRegion(r.getIdRegion());
                    dto.setRegionName(r.getRegionName());
                    dto.setIdCountry(r.getCountry().getIdCountry());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<RegionDTO> getRegionsByCountry(Long countryId) {
        return regionRepo.findByCountry_IdCountryAndIsActiveTrue(countryId)
                .stream()
                .map(r -> {
                    RegionDTO dto = new RegionDTO();
                    dto.setIdRegion(r.getIdRegion());
                    dto.setRegionName(r.getRegionName());
                    dto.setIdCountry(r.getCountry().getIdCountry());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void deleteRegion(Long id) {
        regionRepo.findById(id).ifPresent(r -> {
            r.setActive(false);
            r.setUpdatedOn(LocalDateTime.now());
            regionRepo.save(r);
        });
    }

    // ---------------- POD ----------------
    @Override
    public PodDTO savePod(PodDTO dto) {
        Pod pod = new Pod();
        pod.setIdPod(dto.getIdPod());
        pod.setPodName(dto.getPodName());
        pod.setCreatedOn(LocalDateTime.now());
        pod.setActive(true);

        // ❌ Java 11+: pod.setRegion(regionRepo.findById(dto.getIdRegion()).orElseThrow());
        // ✅ Java 8 replacement:
        pod.setRegion(regionRepo.findById(dto.getIdRegion())
                .orElseThrow(() -> new RuntimeException("Region not found with id " + dto.getIdRegion())));

        podRepo.save(pod);
        dto.setIdPod(pod.getIdPod());
        return dto;
    }

    @Override
    public List<PodDTO> getAllPods() {
        return podRepo.findByIsActiveTrue()
                .stream()
                .map(p -> {
                    PodDTO dto = new PodDTO();
                    dto.setIdPod(p.getIdPod());
                    dto.setPodName(p.getPodName());
                    dto.setIdRegion(p.getRegion().getIdRegion());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<PodDTO> getPodsByRegion(Long regionId) {
        return podRepo.findByRegion_IdRegionAndIsActiveTrue(regionId)
                .stream()
                .map(p -> {
                    PodDTO dto = new PodDTO();
                    dto.setIdPod(p.getIdPod());
                    dto.setPodName(p.getPodName());
                    dto.setIdRegion(p.getRegion().getIdRegion());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void deletePod(Long id) {
        podRepo.findById(id).ifPresent(p -> {
            p.setActive(false);
            p.setUpdatedOn(LocalDateTime.now());
            podRepo.save(p);
        });
    }
}
