package com.qa.cbcc.dto;

public class CountryDTO {
    private Long idCountry;
    private String countryName;
    private Long idRegion;

    public CountryDTO() {}
    public CountryDTO(Long idCountry, String countryName, Long idRegion) {
        this.idCountry = idCountry;
        this.countryName = countryName;
        this.idRegion = idRegion;
    }

    public Long getIdCountry() { return idCountry; }
    public void setIdCountry(Long idCountry) { this.idCountry = idCountry; }

    public String getCountryName() { return countryName; }
    public void setCountryName(String countryName) { this.countryName = countryName; }

    public Long getIdRegion() { return idRegion; }
    public void setIdRegion(Long idRegion) { this.idRegion = idRegion; }
}
