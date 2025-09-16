package com.qa.cbcc.runner;

import com.qa.cbcc.dto.GitConfigDTO;
import com.qa.cbcc.service.FeatureService;
import com.qa.cbcc.utils.StepDefCompiler;
import com.qa.cbcc.events.GitConfigChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

@Component
public class TestRunnerPrewarm {

    private static final Logger logger = LoggerFactory.getLogger(TestRunnerPrewarm.class);

    @Autowired
    private FeatureService featureService;

    // keep last-seen config key; atomic for concurrency
    private final AtomicReference<String> lastConfigKey = new AtomicReference<>(null);

    @PostConstruct
    public void warm() {
        // initial parse/sync on background thread
        CompletableFuture.runAsync(() -> {
            try {
                logger.info("Pre-warm: initial sync+parse features");
                featureService.syncGitAndParseFeatures();
            } catch (Exception e) {
                logger.warn("Pre-warm feature sync failed: {}", e.getMessage(), e);
            }
        });

        // initial compile in background (use configured project paths)
        List<String> stepPaths = featureService.getStepDefsProjectPaths();
        StepDefCompiler.compileStepDefsAsync(stepPaths)
                .whenComplete((r,t) -> {
                    if (t == null) logger.info("Pre-warm stepdef compile completed");
                    else logger.warn("Pre-warm stepdef compile failed: {}", t.getMessage(), t);
                });

        // capture baseline config so polling doesn't trigger immediately
        try {
            GitConfigDTO cfg = featureService.getGitConfig();
            lastConfigKey.set(makeConfigKey(cfg));
            logger.debug("Pre-warm baseline config key set: {}", lastConfigKey.get());
        } catch (Exception e) {
            logger.debug("Unable to read baseline git config at startup: {}", e.getMessage());
        }
    }

    @EventListener
    public void onGitConfigChanged(GitConfigChangedEvent event) {
        GitConfigDTO newCfg = event.getNewConfig();
        String newKey = makeConfigKey(newCfg);
        String prev = lastConfigKey.getAndSet(newKey);
        if (!Objects.equals(prev, newKey)) {
            logger.info("Detected GitConfig change via event (prev={}, new={}) -> triggering refresh", prev, newKey);
            triggerRefreshAsync(newCfg);
        } else {
            logger.debug("GitConfig event received but no change detected (key={})", newKey);
        }
    }

    @Scheduled(fixedDelayString = "${features.config.poll.ms:30000}")
    public void pollForConfigChange() {
        try {
            GitConfigDTO cfg = featureService.getGitConfig();
            String key = makeConfigKey(cfg);
            String prev = lastConfigKey.get();
            if (!Objects.equals(prev, key)) {
                lastConfigKey.set(key);
                logger.info("Detected GitConfig change by poll (prev={}, new={}) -> triggering refresh", prev, key);
                triggerRefreshAsync(cfg);
            } else {
                logger.debug("No GitConfig change detected on poll.");
            }
        } catch (Exception e) {
            logger.warn("Error while polling git config: {}", e.getMessage(), e);
        }
    }

    private void triggerRefreshAsync(final GitConfigDTO cfg) {
        CompletableFuture.runAsync(() -> {
            try {
                featureService.syncGitAndParseFeatures();
            } catch (Exception e) {
                logger.warn("Feature sync/parse during config-change refresh failed: {}", e.getMessage(), e);
            }

            // compile step defs for configured project paths (deduped inside compiler)
            try {
                List<String> stepPaths = featureService.getStepDefsProjectPaths();
                StepDefCompiler.compileStepDefsAsync(stepPaths)
                        .whenComplete((r, t) -> {
                            if (t == null) logger.info("StepDef compile completed after config change");
                            else logger.warn("StepDef compile failed after config change: {}", t.getMessage(), t);
                        });
            } catch (Exception e) {
                logger.warn("Failed to start stepdef compile after config change: {}", e.getMessage(), e);
            }
        });
    }

    private String makeConfigKey(GitConfigDTO cfg) {
        if (cfg == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(cfg.getSourceType() == null ? "-" : cfg.getSourceType().toLowerCase()).append("|");
        sb.append(cfg.getCloneDir() == null ? "-" : cfg.getCloneDir()).append("|");
        sb.append(cfg.getGitFeaturePath() == null ? "-" : cfg.getGitFeaturePath()).append("|");
        sb.append(cfg.getLocalFeatherPath() == null ? "-" : cfg.getLocalFeatherPath()).append("|");
        sb.append(cfg.getMavenEnv() == null ? "-" : cfg.getMavenEnv());
        return sb.toString();
    }
}
