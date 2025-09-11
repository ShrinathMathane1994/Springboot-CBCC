package com.qa.cbcc.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "pod")
public class Pod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_pod")
    private Long idPod;

    @Column(name = "pod_name")
    private String podName;

    @Column(name = "created_on")
    private LocalDateTime createdOn;

    @Column(name = "updated_on")
    private LocalDateTime updatedOn;

    @Column(name = "is_active")
    private boolean isActive = true;

    // Pod references the Region (so region-only queries can find pods)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_region", nullable = false)
    private Region region;

    // Pod also references the Country (so country-only queries can find pods)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_country", nullable = true)
    private Country country;

    // Getters / Setters
    public Long getIdPod() { return idPod; }
    public void setIdPod(Long idPod) { this.idPod = idPod; }

    public String getPodName() { return podName; }
    public void setPodName(String podName) { this.podName = podName; }

    public LocalDateTime getCreatedOn() { return createdOn; }
    public void setCreatedOn(LocalDateTime createdOn) { this.createdOn = createdOn; }

    public LocalDateTime getUpdatedOn() { return updatedOn; }
    public void setUpdatedOn(LocalDateTime updatedOn) { this.updatedOn = updatedOn; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public Region getRegion() { return region; }
    public void setRegion(Region region) { this.region = region; }

    public Country getCountry() { return country; }
    public void setCountry(Country country) { this.country = country; }
}
