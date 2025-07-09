package com.qa.cbcc.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonComparator {
	
	private static final ObjectMapper mapper = new ObjectMapper();

	public static boolean compareJson(String input, String output) {
		try {
			JsonNode firstNode = mapper.readTree(input);
			JsonNode secNode = mapper.readTree(output);
			return firstNode.equals(secNode);
		} catch (Exception e) {
			System.out.println(e.getMessage());
			return false;
		}          
	}
	
	public static void findJsonDifferences(String input, String output) {
		
	}

}
