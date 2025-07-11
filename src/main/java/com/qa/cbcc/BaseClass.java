package com.qa.cbcc;

import java.io.FileInputStream;
import java.time.Duration;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Parameters;

public class BaseClass {
	public WebDriver driver;
	public Properties prop;
    public Logger logger;

	@BeforeMethod
	@Parameters({"json1", "json2"})
	public void setUp(String json1, String json2) {
		try {
			logger = LogManager.getLogger(BaseClass.class);
			if (json1 != null && json2 != null) {
		        logger.info("Skipping browser launch for JSON comparison.");
		        return;
		    }
			FileInputStream file = new FileInputStream(
					System.getProperty("user.dir") + "\\src\\main\\java\\com\\qa\\cbcc\\config\\config.properties");
			prop = new Properties();
			prop.load(file);

			String browserName = System.getProperty("browser") != null ? System.getProperty("browser")
					: prop.getProperty("browser");

			switch (browserName.toLowerCase()) {
			case "chrome":
				ChromeOptions options = new ChromeOptions();
				if (prop.getProperty("isHeadless").toLowerCase().equals("yes")) {
					options.addArguments("--headless");
				}
				driver = new ChromeDriver(options);
				break;
			case "firefox":
				driver = new FirefoxDriver();
				break;
			default:
				System.out.println(browserName + " Provid Valid Browser Details");
				logger.info(browserName + " Provid Valid Browser Details");
			}

			driver.manage().deleteAllCookies();
			driver.manage().window().maximize();

			driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(10));

			driver.get(prop.getProperty("url"));
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
	}

	@AfterMethod
	public void tearDown() {
	    try {
	        if (driver != null) {
	            driver.quit();
	        }
	    } catch (Exception e) {
	        System.out.println(e.getMessage());
	    }
	}

}
