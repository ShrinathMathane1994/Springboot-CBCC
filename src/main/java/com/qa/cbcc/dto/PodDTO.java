package com.qa.cbcc.dto;

public class PodDTO {
    private Long idPod;
    private String podName;
    private Long idRegion; // To link Pod with Region

    // Getters and Setters
    public Long getIdPod() {
        return idPod;
    }

    public void setIdPod(Long idPod) {
        this.idPod = idPod;
    }

    public String getPodName() {
        return podName;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public Long getIdRegion() {
        return idRegion;
    }

    public void setIdRegion(Long idRegion) {
        this.idRegion = idRegion;
    }
}
