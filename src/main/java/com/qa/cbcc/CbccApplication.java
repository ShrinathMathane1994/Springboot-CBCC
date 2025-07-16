package com.qa.cbcc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
@EntityScan(basePackages = "com.qa.cbcc.model")
public class CbccApplication {
    public static void main(String[] args) {
        SpringApplication.run(CbccApplication.class, args);
    }
}

