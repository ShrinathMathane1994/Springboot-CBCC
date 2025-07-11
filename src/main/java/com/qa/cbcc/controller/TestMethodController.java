package com.qa.cbcc.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qa.cbcc.service.TestMethodService;

@RestController
@RequestMapping("/api/tests")
public class TestMethodController {

    @Autowired
    private TestMethodService testMethodService;

    @GetMapping("/methods")
    public List<Map<String, String>> getTestMethods() {
        return testMethodService.getTestMethods();
    }
    
    @PostMapping("/run")
    public List<Map<String, String>> runTestMethod(@RequestBody Map<String, List<String>> payload) {
        List<String> methodNames = payload.get("methodNames");
        List<Map<String, String>> results = new ArrayList<>();
        for (String methodName : methodNames) {
            results.add(testMethodService.runTestMethod2(methodName));
        }
        return results;
    }

}
