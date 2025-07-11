package com.qa.cbcc;

import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import com.qa.cbcc.utils.SimpleJsonComparator;

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
	
	@Test
	@Parameters({"json1", "json2"})
	public void jsonCompareTest2(String json1Path, String json2Path) {
	    logger.info("Comparing: {} & {}", json1Path, json2Path);
	    String result = SimpleJsonComparator.compareJsonFiles(json1Path, json2Path);
	    logger.info(result);

	    // Fail test if not equal
	    if (result.contains("NOT equal")) {
	        Assert.fail("JSONs do not match:\n" + result);
	    }
	}

	@Test
	@Parameters({"json1", "json2"})
	public void jsonCompareTest(String json1Path, String json2Path) {
	    System.out.println("✅ inputJson = " + json1Path);
	    System.out.println("✅ outputJson = " + json2Path);

	    if (json1Path == null || json2Path == null) {
	        System.out.println("❌ One or both parameters are null. Check parameter passing.");
	        Assert.fail("Parameters json1/json2 not injected.");
	    }

	    logger.info("Comparing: {} & {}", json1Path, json2Path);
	    String result = SimpleJsonComparator.compareJsonFiles(json1Path, json2Path);
	    logger.info(result);

	    if (result.contains("NOT equal")) {
	        Assert.fail("JSONs do not match:\n" + result);
	    }
	}



}
