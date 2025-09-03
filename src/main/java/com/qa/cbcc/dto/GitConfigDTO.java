package com.qa.cbcc.dto;

public class GitConfigDTO {

    private String sourceType;
    private String repoUrl;
    private String cloneDir;
    private String gitFeaturePath;
    private String branch;
    private String username;
    private String password;
    private String localFeatherPath;
    private Long refreshInterval;
    private String stepDefsProjectPathGit;
    private String stepDefsProjectPathLocal;
    private String gluePackage;
    private String mavenEnv;

    // === Getters & Setters ===

    public String getSourceType() {
        return sourceType;
    }
    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getRepoUrl() {
        return repoUrl;
    }
    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    public String getCloneDir() {
        return cloneDir;
    }
    public void setCloneDir(String cloneDir) {
        this.cloneDir = cloneDir;
    }

    public String getGitFeaturePath() {
        return gitFeaturePath;
    }
    public void setGitFeaturePath(String gitFeaturePath) {
        this.gitFeaturePath = gitFeaturePath;
    }

    public String getBranch() {
        return branch;
    }
    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getUsername() {
        return username;
    }
    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }
    public void setPassword(String password) {
        this.password = password;
    }

    public String getLocalFeatherPath() {
        return localFeatherPath;
    }
    public void setLocalFeatherPath(String localFeatherPath) {
        this.localFeatherPath = localFeatherPath;
    }

    public Long getRefreshInterval() {
        return refreshInterval;
    }
    public void setRefreshInterval(Long refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public String getStepDefsProjectPathGit() {
        return stepDefsProjectPathGit;
    }
    public void setStepDefsProjectPathGit(String stepDefsProjectPathGit) {
        this.stepDefsProjectPathGit = stepDefsProjectPathGit;
    }

    public String getStepDefsProjectPathLocal() {
        return stepDefsProjectPathLocal;
    }
    public void setStepDefsProjectPathLocal(String stepDefsProjectPathLocal) {
        this.stepDefsProjectPathLocal = stepDefsProjectPathLocal;
    }

    public String getGluePackage() {
        return gluePackage;
    }
    public void setGluePackage(String gluePackage) {
        this.gluePackage = gluePackage;
    }
    
    public String getMavenEnv() {
        return mavenEnv;
    }

    public void setMavenEnv(String mavenEnv) {
        this.mavenEnv = mavenEnv;
    }
}
