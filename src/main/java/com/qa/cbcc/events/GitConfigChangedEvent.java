package com.qa.cbcc.events;

import com.qa.cbcc.dto.GitConfigDTO;
import org.springframework.context.ApplicationEvent;

public class GitConfigChangedEvent extends ApplicationEvent {
    private final GitConfigDTO newConfig;

    public GitConfigChangedEvent(Object source, GitConfigDTO newConfig) {
        super(source);
        this.newConfig = newConfig;
    }

    public GitConfigDTO getNewConfig() {
        return newConfig;
    }
}
