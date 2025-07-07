package com.example.testscanner.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.testscanner.service.TestMethodService;

@RestController
@RequestMapping("/api/tests")
public class TestMethodController {

    @Autowired
    private TestMethodService testMethodService;

    @GetMapping("/methods")
    public List<String> getTestMethods() {
        return testMethodService.getTestMethods();
    }
    
    @PostMapping("/run")
    public List<Map<String, String>> runTestMethod(@RequestBody Map<String, List<String>> payload) {
        List<String> methodNames = payload.get("methodNames");
        List<Map<String, String>> results = new ArrayList<>();
        for (String methodName : methodNames) {
            results.add(testMethodService.runTestMethod(methodName));
        }
        return results;
    }
    
//  @PostMapping("/run")
//  public String runTestMethod(@RequestBody Map<String, String> payload) {
//      String methodName = payload.get("methodName");
//      return testMethodService.runTestMethod(methodName);
//  }

//    @PostMapping("/run")
//    public Map<String, String> runTestMethod(@RequestBody Map<String, String> payload) {
//        String methodName = payload.get("methodName");
//        return testMethodService.runTestMethod(methodName); // returns Map, and expected Map
//    }

}
