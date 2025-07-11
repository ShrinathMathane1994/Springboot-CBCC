package com.qa.cbcc.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final String BASE_PATH = "src/main/resources/testData";

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            Path path = Paths.get(BASE_PATH, file.getOriginalFilename());
            Files.createDirectories(path.getParent());
            Files.write(path, file.getBytes());
            return ResponseEntity.ok("✅ File uploaded successfully: " + file.getOriginalFilename());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("❌ Failed to upload file: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    public ResponseEntity<List<String>> listFiles() {
        try {
            return ResponseEntity.ok(
                Files.list(Paths.get(BASE_PATH))
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.endsWith(".json"))
                        .collect(Collectors.toList())
            );
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/read/{filename}")
    public ResponseEntity<String> readFile(@PathVariable String filename) {
        try {
            Path path = Paths.get(BASE_PATH, filename);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return ResponseEntity.ok(content);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("File not found: " + filename);
        }
    }
}
