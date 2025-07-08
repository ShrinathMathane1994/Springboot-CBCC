package com.qa.cbcc;

import java.time.Duration;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;

import io.github.bonigarcia.wdm.WebDriverManager;

public class BaseClass {
    protected WebDriver driver;

    @BeforeTest
    public void setUp() {
        WebDriverManager.chromedriver().setup();
        driver = new ChromeDriver();
        
        driver.manage().deleteAllCookies();
        driver.manage().window().maximize();
        
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(10));
        
        driver.get("https://www.google.co.in/");
    }
    

	@AfterTest
	public void tearDown() {
		driver.quit();
	}
}

