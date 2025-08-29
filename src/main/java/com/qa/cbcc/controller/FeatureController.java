package com.qa.cbcc.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.context.request.async.WebAsyncTask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.cbcc.dto.GitConfigDTO;
import com.qa.cbcc.dto.ScenarioDTO;
import com.qa.cbcc.service.FeatureService;

@RestController
@RequestMapping("/api")
public class FeatureController {

	private static final Logger logger = LogManager.getLogger(FeatureController.class);
	private final FeatureService featureService;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public FeatureController(FeatureService featureService) {
		this.featureService = featureService;
	}

	@GetMapping("/scenarios")
	public ResponseEntity<?> getScenarios(@RequestParam List<String> tags) {
		logger.info("Received request to fetch scenarios for tags: {}", tags);

		try {
			List<ScenarioDTO> scenarios = featureService.getScenariosByTags(tags);
			logger.debug("Scenarios found: {}", Integer.valueOf(scenarios.size()));

			if (scenarios.isEmpty()) {
				logger.warn("No scenarios found for tags: {}", tags);
				Map<String, Object> body = new LinkedHashMap<String, Object>();
				body.put("status", Integer.valueOf(404));
				body.put("message", "No matching scenarios found for tags: " + joinTags(tags));
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
			}

			try {
				String jsonOutput = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(scenarios);
				logger.info("Successfully retrieved {} scenarios:\n{}", Integer.valueOf(scenarios.size()), jsonOutput);
			} catch (JsonProcessingException e) {
				logger.warn("Failed to serialize scenarios for logging.");
			}

			return ResponseEntity.ok(scenarios);

		} catch (IOException e) {
			logger.error("IOException while fetching scenarios: {}", e.getMessage(), e);
			Map<String, Object> body = new LinkedHashMap<String, Object>();
			body.put("status", Integer.valueOf(500));
			body.put("message", "Error processing feature files");
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
		} catch (Exception e) {
			logger.error("Unexpected error while fetching scenarios: {}", e.getMessage(), e);
			Map<String, Object> body = new LinkedHashMap<String, Object>();
			body.put("status", Integer.valueOf(500));
			body.put("message", "Internal server error");
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
		}
	}

	@GetMapping("/scenarios/filter")
	public ResponseEntity<?> getScenariosByMultiTags(@RequestParam(required = false) String country,
			@RequestParam(required = false) String region, @RequestParam(required = false) String pod,
			@RequestParam(required = false) String team, @RequestParam(required = false) String env) {
		Map<String, String> tagFilters = new HashMap<String, String>();
		if (country != null)
			tagFilters.put("country", country.toLowerCase());
		if (region != null)
			tagFilters.put("region", region.toLowerCase());
		if (pod != null)
			tagFilters.put("pod", pod.toLowerCase());
		if (env != null)
			tagFilters.put("env", env.toLowerCase());
		if (team != null)
			tagFilters.put("team", team.toLowerCase());

		logger.info("Fetching scenarios with tag filters: {}", tagFilters);
		try {
			List<ScenarioDTO> scenarios = featureService.getScenariosByTags(tagFilters);

			if (scenarios.isEmpty()) {
				Map<String, Object> body = new LinkedHashMap<String, Object>();
				body.put("status", Integer.valueOf(200));
				body.put("message", "No scenarios found for provided filters");
				return ResponseEntity.status(HttpStatus.OK).body(body);
			}

			return ResponseEntity.ok(scenarios);
		} catch (IOException e) {
			logger.error("IO error fetching scenarios: {}", e.getMessage());
			Map<String, Object> body = new LinkedHashMap<String, Object>();
			body.put("status", Integer.valueOf(500));
			body.put("message", "Error processing feature files");
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
		} catch (Exception e) {
			logger.error("Unexpected error: {}", e.getMessage());
			Map<String, Object> body = new LinkedHashMap<String, Object>();
			body.put("status", Integer.valueOf(500));
			body.put("message", "Internal server error");
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
		}
	}

	/**
	 * Kicks off sync+parse. Uses WebAsyncTask so the request can time out without
	 * killing the server thread. Default timeout = 30s, override with
	 * ?timeoutMs=45000. - On time-out: 202 Accepted (sync may still be running in
	 * server thread if you choose to offload it). - On success: 200 OK with totals.
	 */
	@GetMapping("/sync-features")
	public WebAsyncTask<ResponseEntity<Map<String, Object>>> syncFeatures(
			@RequestParam(value = "timeoutMs", required = false) Long timeoutMs) {

		final long timeout = (timeoutMs != null && timeoutMs.longValue() > 0L) ? timeoutMs.longValue() : 30000L;

		Callable<ResponseEntity<Map<String, Object>>> callable = new Callable<ResponseEntity<Map<String, Object>>>() {
			@Override
			public ResponseEntity<Map<String, Object>> call() throws Exception {
				featureService.syncGitAndParseFeatures();

				Map<String, Object> response = new LinkedHashMap<String, Object>();
				response.put("status", Integer.valueOf(200));
				response.put("message", "Features synced and parsed successfully");
				response.put("totalScenarios", Integer.valueOf(featureService.getCachedScenarios().size()));
				response.put("source", featureService.getFeatureSource());
				return ResponseEntity.ok(response);
			}
		};

		WebAsyncTask<ResponseEntity<Map<String, Object>>> task = new WebAsyncTask<ResponseEntity<Map<String, Object>>>(
				timeout, callable);

		task.onTimeout(new Callable<ResponseEntity<Map<String, Object>>>() {
			@Override
			public ResponseEntity<Map<String, Object>> call() throws Exception {
				Map<String, Object> body = new LinkedHashMap<String, Object>();
				body.put("status", Integer.valueOf(202));
				body.put("message", "Sync is taking longer than " + timeout + " ms. Please try again later.");
				return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
			}
		});

		task.onError(new Callable<ResponseEntity<Map<String, Object>>>() {
			@Override
			public ResponseEntity<Map<String, Object>> call() throws Exception {
				Map<String, Object> body = new LinkedHashMap<String, Object>();
				body.put("status", Integer.valueOf(500));
				body.put("message", "Failed to sync features due to an unexpected error.");
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
			}
		});

		return task;
	}

	@GetMapping("/git-config")
	public ResponseEntity<GitConfigDTO> getGitConfig() {
		return ResponseEntity.ok(featureService.getGitConfig());
	}

	@PostMapping("/git-config")
	public ResponseEntity<Map<String, Object>> updateGitConfig(@RequestBody GitConfigDTO configDTO) {
		Map<String, Object> response = featureService.updateGitConfig(configDTO);
		Object statusObj = response.get("status");
		int status = (statusObj instanceof Number) ? ((Number) statusObj).intValue() : 500;
		return ResponseEntity.status(status).body(response);
	}

	// ---------- helpers ----------

	private String joinTags(List<String> tags) {
		if (tags == null || tags.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < tags.size(); i++) {
			if (i > 0)
				sb.append(", ");
			sb.append(tags.get(i));
		}
		return sb.toString();
	}
}
