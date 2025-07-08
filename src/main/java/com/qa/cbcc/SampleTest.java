package com.qa.cbcc;

import org.testng.annotations.Test;

public class SampleTest extends BaseClass {

	@Test
	public void getPageTitle() {
		String title = driver.getTitle();
		logger.info("Title of Page is " + title);
	}

	@Test
	public void getCurrentURL() {
		String currURL = driver.getCurrentUrl();
		logger.info("Current Page URL is " + currURL);
	}

}
