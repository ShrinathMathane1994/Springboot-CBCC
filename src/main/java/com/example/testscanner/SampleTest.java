package com.example.testscanner;

import org.testng.annotations.Test;

public class SampleTest extends BaseClass {

	@Test
	public void getPageTitle() {
		String title = driver.getTitle();
		System.out.println("Title of Page is "+title);
	}

	@Test
	public void getCurrentURL() {
		String currURL = driver.getCurrentUrl();
		System.out.println("Current Page URL is "+currURL);
	}
	
}
