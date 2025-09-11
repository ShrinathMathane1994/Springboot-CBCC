package com.qa.cbcc.model;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "country")
public class Country {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_country")
    private Long idCountry;

    @Column(name = "country_name")
    private String countryName;

    @Column(name = "created_on")
    private LocalDateTime createdOn;

    @Column(name = "updated_on")
    private LocalDateTime updatedOn;

    @Column(name = "is_active")
    private boolean isActive = true;

    // Country belongs to a Region (use LAZY to avoid eager loads)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_region", nullable = false)
    private Region region;

    @OneToMany(mappedBy = "country", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Pod> pods;

    // Getters / Setters
    public Long getIdCountry() { return idCountry; }
    public void setIdCountry(Long idCountry) { this.idCountry = idCountry; }

    public String getCountryName() { return countryName; }
    public void setCountryName(String countryName) { this.countryName = countryName; }

    public LocalDateTime getCreatedOn() { return createdOn; }
    public void setCreatedOn(LocalDateTime createdOn) { this.createdOn = createdOn; }

    public LocalDateTime getUpdatedOn() { return updatedOn; }
    public void setUpdatedOn(LocalDateTime updatedOn) { this.updatedOn = updatedOn; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public Region getRegion() { return region; }
    public void setRegion(Region region) { this.region = region; }

    public List<Pod> getPods() { return pods; }
    public void setPods(List<Pod> pods) { this.pods = pods; }
}
