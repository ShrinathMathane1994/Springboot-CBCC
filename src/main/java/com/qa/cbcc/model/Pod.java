package com.qa.cbcc.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "pod")
public class Pod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long idPod;

    private String podName;
    private LocalDateTime createdOn;
    private LocalDateTime updatedOn;
    private boolean isActive = true;

    @ManyToOne
    @JoinColumn(name = "idRegion")
    private Region region;

    // ----- Getters and Setters -----
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

    public LocalDateTime getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(LocalDateTime createdOn) {
        this.createdOn = createdOn;
    }

    public LocalDateTime getUpdatedOn() {
        return updatedOn;
    }

    public void setUpdatedOn(LocalDateTime updatedOn) {
        this.updatedOn = updatedOn;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public Region getRegion() {
        return region;
    }

    public void setRegion(Region region) {
        this.region = region;
    }
}
