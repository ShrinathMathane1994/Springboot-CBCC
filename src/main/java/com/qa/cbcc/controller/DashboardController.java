package com.qa.cbcc.controller;

import com.qa.cbcc.dto.DashboardRowDTO;
import com.qa.cbcc.dto.RunRowDTO;
import com.qa.cbcc.service.DashboardService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

	private final DashboardService dashboardService;

	public DashboardController(DashboardService dashboardService) {
		this.dashboardService = dashboardService;
	}

	/**
	 * Get parent rows for dashboard
	 */
	@GetMapping("/rows")
	public List<DashboardRowDTO> getDashboardRows(@RequestParam(name = "search", required = false) String search) {
		return dashboardService.getDashboardRows(search);
	}

	/**
	 * Get all runs for a specific test case (expand view)
	 */
	@GetMapping("/rows/{testCaseId}/runs")
	public List<RunRowDTO> getRunsForTestCase(@PathVariable Long testCaseId) {
		return dashboardService.getRunsForTestCase(testCaseId);
	}
}
