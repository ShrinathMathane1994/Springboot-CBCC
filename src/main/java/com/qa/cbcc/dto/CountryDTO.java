package com.qa.cbcc.dto;

public class CountryDTO {
    private Long idCountry;
    private String countryName;

    // Getters and Setters
    public Long getIdCountry() {
        return idCountry;
    }

    public void setIdCountry(Long idCountry) {
        this.idCountry = idCountry;
    }

    public String getCountryName() {
        return countryName;
    }

    public void setCountryName(String countryName) {
        this.countryName = countryName;
    }
}
