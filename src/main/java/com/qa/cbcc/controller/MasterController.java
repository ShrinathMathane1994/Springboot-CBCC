package com.qa.cbcc.controller;

import com.qa.cbcc.dto.CountryDTO;
import com.qa.cbcc.dto.RegionDTO;
import com.qa.cbcc.dto.PodDTO;
import com.qa.cbcc.service.MasterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/master")
public class MasterController {

    @Autowired
    private MasterService masterService;

    // Country
    @PostMapping("/country")
    public CountryDTO saveCountry(@RequestBody CountryDTO dto) {
        return masterService.saveCountry(dto);
    }

    @GetMapping("/countries")
    public List<CountryDTO> getAllCountries() {
        return masterService.getAllCountries();
    }

    @DeleteMapping("/country/{id}")
    public void deleteCountry(@PathVariable Long id) {
        masterService.deleteCountry(id);
    }

    // Region
    @PostMapping("/region")
    public RegionDTO saveRegion(@RequestBody RegionDTO dto) {
        return masterService.saveRegion(dto);
    }

    @GetMapping("/regions")
    public List<RegionDTO> getAllRegions() {
        return masterService.getAllRegions();
    }

    @DeleteMapping("/region/{id}")
    public void deleteRegion(@PathVariable Long id) {
        masterService.deleteRegion(id);
    }

    // Pod
    @PostMapping("/pod")
    public PodDTO savePod(@RequestBody PodDTO dto) {
        return masterService.savePod(dto);
    }

    @GetMapping("/pods")
    public List<PodDTO> getAllPods() {
        return masterService.getAllPods();
    }

    @DeleteMapping("/pod/{id}")
    public void deletePod(@PathVariable Long id) {
        masterService.deletePod(id);
    }
}
