package com.qa.cbcc.dto;

public class RegionDTO {
    private Long idRegion;
    private String regionName;

    public RegionDTO() {}
    public RegionDTO(Long idRegion, String regionName) {
        this.idRegion = idRegion;
        this.regionName = regionName;
    }

    public Long getIdRegion() { return idRegion; }
    public void setIdRegion(Long idRegion) { this.idRegion = idRegion; }

    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }
}
