package com.qa.cbcc.service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.cbcc.dto.DashboardRowDTO;
import com.qa.cbcc.dto.RunRowDTO;
import com.qa.cbcc.model.TestCase;
import com.qa.cbcc.model.TestCaseRunHistory;
import com.qa.cbcc.repository.TestCaseRepository;
import com.qa.cbcc.repository.TestCaseRunHistoryRepository;

@Service
public class DashboardService {

	private final TestCaseRepository testCaseRepository;
	private final TestCaseRunHistoryRepository historyRepository;

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss a");

	public DashboardService(TestCaseRepository testCaseRepository, TestCaseRunHistoryRepository historyRepository) {
		this.testCaseRepository = testCaseRepository;
		this.historyRepository = historyRepository;
	}

	public List<DashboardRowDTO> getDashboardRows(String search) {
		List<TestCase> testCases = testCaseRepository.findByIsActiveTrue();

		if (search != null && !search.trim().isEmpty()) {
			final String q = search.toLowerCase();
			testCases = testCases.stream()
					.filter(tc -> tc.getTcName() != null && tc.getTcName().toLowerCase().contains(q))
					.collect(Collectors.toList());
		}

		List<TestCaseRunHistory> latestRuns = historyRepository.findLatestRunsForAllTestCases();
		Map<Long, TestCaseRunHistory> latestByTc = latestRuns.stream()
				.collect(Collectors.toMap(h -> h.getTestCase().getIdTC(), h -> h, (existing, duplicate) -> existing));

		List<Object[]> counts = historyRepository.findRunCountsGroupedByTestCase();
		Map<Long, Integer> runCountByTc = new HashMap<>();
		if (counts != null) {
			for (Object[] row : counts) {
				if (row == null || row.length < 2)
					continue;
				Long tcId = (row[0] instanceof Long) ? (Long) row[0] : ((Number) row[0]).longValue();
				Long cnt = (row[1] instanceof Long) ? (Long) row[1] : ((Number) row[1]).longValue();
				runCountByTc.put(tcId, cnt.intValue());
			}
		}

		List<Long> tcIds = testCases.stream().map(TestCase::getIdTC).collect(Collectors.toList());
		List<TestCaseRunHistory> allRunsForTcs = tcIds.isEmpty() ? Collections.<TestCaseRunHistory>emptyList()
				: historyRepository.findByTestCase_IdTCInOrderByRunTimeDesc(tcIds);

		Map<Long, List<TestCaseRunHistory>> runsByTc = allRunsForTcs.stream()
				.collect(Collectors.groupingBy(h -> h.getTestCase().getIdTC()));

		List<DashboardRowDTO> rows = new ArrayList<>(testCases.size());
		for (TestCase tc : testCases) {
			Long tcId = tc.getIdTC();
			DashboardRowDTO row = new DashboardRowDTO();
			row.setTestCaseId(tcId);
			row.setTestCaseName(tc.getTcName());
			// Extract scenario names from featureScenarioJson
			String scenarioName = "N/A";
			if (tc.getFeatureScenarioJson() != null) {
				try {
					JsonNode root = new ObjectMapper().readTree(tc.getFeatureScenarioJson());
					List<String> names = new ArrayList<>();
					if (root.isArray()) {
						for (JsonNode featureNode : root) {
							// Case 1: selections -> scenarioName
							JsonNode selections = featureNode.get("selections");
							if (selections != null && selections.isArray()) {
								for (JsonNode sel : selections) {
									JsonNode sn = sel.get("scenarioName");
									if (sn != null && !sn.asText().isEmpty()) {
										names.add(sn.asText());
									}
								}
							}
							// Case 2: scenarios -> plain array of strings
							JsonNode scenarios = featureNode.get("scenarios");
							if (scenarios != null && scenarios.isArray()) {
								for (JsonNode sn : scenarios) {
									if (sn != null && !sn.asText().isEmpty()) {
										names.add(sn.asText());
									}
								}
							}
						}
					}
					if (!names.isEmpty()) {
						scenarioName = String.join(", ", names); // ✅ comma-separated list
					}
				} catch (Exception e) {
					scenarioName = "ParseError";
				}
			}
			row.setScenarioName(scenarioName);

			int totalRunsForTC = runCountByTc.getOrDefault(tcId, 0);
			row.setTotalRunsForTC(totalRunsForTC);

			int executedCount = 0;
			int unexecutedCount = 0;

			Map<String, Integer> statusCounts = new LinkedHashMap<>();
			List<TestCaseRunHistory> runsForThisTc = runsByTc.get(tcId);

			if (runsForThisTc != null && !runsForThisTc.isEmpty()) {
				for (TestCaseRunHistory h : runsForThisTc) {
					String status = h.getRunStatus() == null ? "UNKNOWN" : h.getRunStatus().trim();

					// count executed/unexecuted
					if (status.toLowerCase().contains("unexecuted")) {
						unexecutedCount++;
					} else {
						executedCount++;
					}

					// count statuses
					statusCounts.put(status, statusCounts.getOrDefault(status, 0) + 1);
				}
			}

			row.setExecutedCount(executedCount);
			row.setUnexecutedCount(unexecutedCount);

			int denom = Math.max(1, totalRunsForTC);
			row.setExecutedPercent(roundTwoDecimals((executedCount * 100.0) / denom));
			row.setUnexecutedPercent(roundTwoDecimals((unexecutedCount * 100.0) / denom));

			// ✅ Build statusWisePercentageJson
			Map<String, Object> statusJson = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> e : statusCounts.entrySet()) {
				Map<String, Object> inner = new HashMap<>();
				inner.put("count", e.getValue());
				inner.put("percent", roundTwoDecimals((e.getValue() * 100.0) / denom));
				statusJson.put(e.getKey(), inner);
			}
			row.setStatusWisePercentageJson(statusJson);

			TestCaseRunHistory latest = latestByTc.get(tcId);
			if (latest != null) {
				row.setLastStatus(latest.getRunStatus());
				row.setLastRunTime(latest.getRunTime());
				row.setFormattedLastRunTime(latest.getRunTime() != null ? latest.getRunTime().format(FORMATTER) : null);
			} else {
				row.setLastStatus(tc.getLastRunStatus());
				row.setLastRunTime(tc.getLastRunOn());
				row.setFormattedLastRunTime(tc.getLastRunOn() != null ? tc.getLastRunOn().format(FORMATTER) : null);
			}

			rows.add(row);
		}

		rows.sort(
				Comparator.comparing(DashboardRowDTO::getLastRunTime, Comparator.nullsLast(Comparator.reverseOrder())));

		return rows;
	}

	public List<RunRowDTO> getRunsForTestCase(Long testCaseId) {
		// fetch runs for this test case ordered newest first
		List<TestCaseRunHistory> runs = historyRepository.findByTestCase_IdTCOrderByRunTimeDesc(testCaseId);

		final int totalRuns = runs == null ? 0 : runs.size();

		// build status counts across all runs (case-sensitive display preserved,
		// grouping lower-case for 'unexecuted' detection)
		Map<String, Integer> statusCounts = new LinkedHashMap<>();
		if (runs != null) {
			for (TestCaseRunHistory h : runs) {
				String status = h.getRunStatus() == null ? "UNKNOWN" : h.getRunStatus().trim();
				Integer c = statusCounts.get(status);
				statusCounts.put(status, c == null ? 1 : c + 1);
			}
		}

		// produce DTOs
		List<RunRowDTO> dtos = new ArrayList<>();
		if (runs != null) {
			for (TestCaseRunHistory h : runs) {
				RunRowDTO dto = new RunRowDTO();
				dto.setRunId(h.getId());
				dto.setTestCaseId(h.getTestCase().getIdTC());
				dto.setRunTime(h.getRunTime());
				// formatted run time with AM/PM
				dto.setFormattedRunTime(h.getRunTime() != null ? h.getRunTime().format(FORMATTER) : null);

				String status = h.getRunStatus() == null ? "UNKNOWN" : h.getRunStatus().trim();
				dto.setRunStatus(status);

				// executed/unexecuted per run
				boolean isUnexecuted = status.toLowerCase().contains("unexecuted");
				int executed = isUnexecuted ? 0 : 1;
				int unexecuted = isUnexecuted ? 1 : 0;
				dto.setExecutedCount(executed);
				dto.setUnexecutedCount(unexecuted);
				dto.setExecutedPercent(roundTwoDecimals(executed * 100.0));
				dto.setUnexecutedPercent(roundTwoDecimals(unexecuted * 100.0));

				// status percentage across all runs for this test case
				int statusCount = statusCounts.get(status) == null ? 0 : statusCounts.get(status);
				double statusPercent = totalRuns == 0 ? 0.0 : (statusCount * 100.0) / totalRuns;
				dto.setStatusPercent(roundTwoDecimals(statusPercent));

				// keep xmlDiffStatus if you want; included here for completeness
				dto.setXmlDiffStatus(h.getXmlDiffStatus());

				dtos.add(dto);
			}
		}

		return dtos;
	}

	private int parseCount(String s) {
		if (s == null)
			return 0;
		String digits = s.replaceAll("[^0-9]", "");
		if (digits.isEmpty())
			return 0;
		try {
			return Integer.parseInt(digits);
		} catch (Exception e) {
			return 0;
		}
	}

	private double roundTwoDecimals(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
