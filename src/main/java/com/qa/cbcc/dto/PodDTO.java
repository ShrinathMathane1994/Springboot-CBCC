package com.qa.cbcc.dto;

public class PodDTO {
    private Long idPod;
    private String podName;
    private Long idRegion;
    private Long idCountry;

    public PodDTO() {}
    public PodDTO(Long idPod, String podName, Long idRegion, Long idCountry) {
        this.idPod = idPod;
        this.podName = podName;
        this.idRegion = idRegion;
        this.idCountry = idCountry;
    }

    public Long getIdPod() { return idPod; }
    public void setIdPod(Long idPod) { this.idPod = idPod; }

    public String getPodName() { return podName; }
    public void setPodName(String podName) { this.podName = podName; }

    public Long getIdRegion() { return idRegion; }
    public void setIdRegion(Long idRegion) { this.idRegion = idRegion; }

    public Long getIdCountry() { return idCountry; }
    public void setIdCountry(Long idCountry) { this.idCountry = idCountry; }
}
