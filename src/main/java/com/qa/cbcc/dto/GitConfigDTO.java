package com.qa.cbcc.dto;

public class GitConfigDTO {

	private String sourceType;
    private String repoUrl;
    private String cloneDir;
    private String featurePath;
    private String branch;
    private String username;
    private String password;
    private String localPath;
    private Long refreshInterval;

    
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
	public String getFeaturePath() {
		return featurePath;
	}
	public void setFeaturePath(String featurePath) {
		this.featurePath = featurePath;
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
	public String getLocalPath() {
		return localPath;
	}
	public void setLocalPath(String localPath) {
		this.localPath = localPath;
	}
	public Long getRefreshInterval() {
		return refreshInterval;
	}
	public void setRefreshInterval(Long refreshInterval) {
		this.refreshInterval = refreshInterval;
	}
    
}
