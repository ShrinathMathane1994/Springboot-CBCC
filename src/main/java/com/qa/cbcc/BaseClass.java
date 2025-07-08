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

public class BaseClass {
	public WebDriver driver;
	public Properties prop;
    public Logger logger;

	@BeforeMethod
	public void setUp() {
		try {
			logger = LogManager.getLogger(BaseClass.class);
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
			driver.quit();
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
	}
}
