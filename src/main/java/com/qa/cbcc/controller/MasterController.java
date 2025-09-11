package com.qa.cbcc.controller;

import com.qa.cbcc.dto.CountryDTO;
import com.qa.cbcc.dto.RegionDTO;
import com.qa.cbcc.dto.PodDTO;
import com.qa.cbcc.service.MasterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/master")
public class MasterController {

    @Autowired
    private MasterService masterService;

    // -------------------- Helpers --------------------
    private <T> ResponseEntity<Map<String,Object>> buildListResponse(List<T> list) {
        Map<String,Object> body = new HashMap<>();
        body.put("data", list);
        body.put("count", list == null ? 0 : list.size());
        body.put("message", (list == null || list.isEmpty()) ? "No records found" : "OK");
        return ResponseEntity.ok(body);
    }

    private <T> ResponseEntity<Map<String,Object>> buildSingleCreatedResponse(T dto) {
        Map<String,Object> body = new HashMap<>();
        body.put("data", dto);
        body.put("message", "Created");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // -------------------- Country --------------------
    @PostMapping("/country")
    public ResponseEntity<Map<String,Object>> saveCountry(@RequestBody CountryDTO dto) {
        CountryDTO created = masterService.saveCountry(dto);
        return buildSingleCreatedResponse(created);
    }

    @GetMapping("/countries")
    public ResponseEntity<Map<String,Object>> getAllCountries(
            @RequestParam(value = "regionId", required = false) Long regionId) {
        // support optional filtering by regionId for convenience
        List<CountryDTO> list = (regionId == null) ? masterService.getAllCountries()
                                                   : masterService.getAllCountries()
                                                                 .stream()
                                                                 .filter(c -> regionId.equals(c.getIdRegion()))
                                                                 .toList();
        return buildListResponse(list);
    }

    @DeleteMapping("/country/{id}")
    public ResponseEntity<Void> deleteCountry(@PathVariable Long id) {
        // Attempt delete; service uses soft-delete; we return 204 if existed, 404 otherwise.
        Optional<Boolean> existed = Optional.ofNullable(masterService.getAllCountries()
                .stream()
                .anyMatch(c -> c.getIdCountry().equals(id)));
        if (existed.orElse(false)) {
            masterService.deleteCountry(id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // -------------------- Region --------------------
    @PostMapping("/region")
    public ResponseEntity<Map<String,Object>> saveRegion(@RequestBody RegionDTO dto) {
        RegionDTO created = masterService.saveRegion(dto);
        return buildSingleCreatedResponse(created);
    }

    @GetMapping("/regions")
    public ResponseEntity<Map<String,Object>> getRegions(@RequestParam(value = "countryId", required = false) Long countryId) {
        List<RegionDTO> list = (countryId == null) ? masterService.getAllRegions()
                                                   : masterService.getRegionsByCountry(countryId);
        return buildListResponse(list);
    }

    @DeleteMapping("/region/{id}")
    public ResponseEntity<Void> deleteRegion(@PathVariable Long id) {
        boolean existed = masterService.getAllRegions().stream().anyMatch(r -> r.getIdRegion().equals(id));
        if (existed) {
            masterService.deleteRegion(id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // -------------------- Pod --------------------
    @PostMapping("/pod")
    public ResponseEntity<Map<String,Object>> savePod(@RequestBody PodDTO dto) {
        PodDTO created = masterService.savePod(dto);
        return buildSingleCreatedResponse(created);
    }

    @GetMapping("/pods")
    public ResponseEntity<Map<String,Object>> getPods(@RequestParam(value = "regionId", required = false) Long regionId,
                                                      @RequestParam(value = "countryId", required = false) Long countryId) {
        List<PodDTO> list = masterService.getPods(regionId, countryId);
        return buildListResponse(list);
    }

    @DeleteMapping("/pod/{id}")
    public ResponseEntity<Void> deletePod(@PathVariable Long id) {
        boolean existed = masterService.getAllPods().stream().anyMatch(p -> p.getIdPod().equals(id));
        if (existed) {
            masterService.deletePod(id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
