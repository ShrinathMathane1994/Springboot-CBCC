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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class MasterServiceImpl implements MasterService {

    private final CountryRepository countryRepo;
    private final RegionRepository regionRepo;
    private final PodRepository podRepo;

    @Autowired
    public MasterServiceImpl(CountryRepository countryRepo,
                             RegionRepository regionRepo,
                             PodRepository podRepo) {
        this.countryRepo = countryRepo;
        this.regionRepo = regionRepo;
        this.podRepo = podRepo;
    }

    // ---------------- COUNTRY ----------------
    @Override
    public CountryDTO saveCountry(CountryDTO dto) {
        Country country = new Country();
        country.setIdCountry(dto.getIdCountry());
        country.setCountryName(dto.getCountryName());
        country.setCreatedOn(LocalDateTime.now());
        country.setActive(true);

        if (dto.getIdRegion() == null) {
            throw new RuntimeException("Region id (idRegion) is required to create/update a Country");
        }
        Region region = regionRepo.findById(dto.getIdRegion())
                .orElseThrow(() -> new RuntimeException("Region not found with id " + dto.getIdRegion()));
        country.setRegion(region);

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
                    dto.setIdRegion(c.getRegion() != null ? c.getRegion().getIdRegion() : null);
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
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<RegionDTO> getRegionsByCountry(Long countryId) {
        return regionRepo.findByCountries_IdCountryAndIsActiveTrue(countryId)
                .stream()
                .map(r -> {
                    RegionDTO dto = new RegionDTO();
                    dto.setIdRegion(r.getIdRegion());
                    dto.setRegionName(r.getRegionName());
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

        // region required
        if (dto.getIdRegion() == null) {
            throw new RuntimeException("Region id (idRegion) is required for Pod");
        }
        Region region = regionRepo.findById(dto.getIdRegion())
                .orElseThrow(() -> new RuntimeException("Region not found with id " + dto.getIdRegion()));
        pod.setRegion(region);

        // country optional; if provided, validate belongs-to
        if (dto.getIdCountry() != null) {
            Country country = countryRepo.findById(dto.getIdCountry())
                    .orElseThrow(() -> new RuntimeException("Country not found with id " + dto.getIdCountry()));
            if (country.getRegion() == null || !country.getRegion().getIdRegion().equals(region.getIdRegion())) {
                throw new RuntimeException("Country (id " + country.getIdCountry() + ") does not belong to Region id " + region.getIdRegion());
            }
            pod.setCountry(country);
        } else {
            pod.setCountry(null);
        }

        podRepo.save(pod);

        dto.setIdPod(pod.getIdPod());
        dto.setIdRegion(pod.getRegion() != null ? pod.getRegion().getIdRegion() : null);
        dto.setIdCountry(pod.getCountry() != null ? pod.getCountry().getIdCountry() : null);
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
                    dto.setIdRegion(p.getRegion() != null ? p.getRegion().getIdRegion() : null);
                    dto.setIdCountry(p.getCountry() != null ? p.getCountry().getIdCountry() : null);
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<PodDTO> getPods(Long regionId, Long countryId) {
        return podRepo.findActiveByRegionAndCountryOptional(regionId, countryId)
                .stream()
                .map(p -> {
                    PodDTO dto = new PodDTO();
                    dto.setIdPod(p.getIdPod());
                    dto.setPodName(p.getPodName());
                    dto.setIdRegion(p.getRegion() != null ? p.getRegion().getIdRegion() : null);
                    dto.setIdCountry(p.getCountry() != null ? p.getCountry().getIdCountry() : null);
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
