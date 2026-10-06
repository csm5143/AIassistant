package com.aiproject.aiassitant.module.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Slf4j
@Service
public class DocumentParser {

    private final Tika tika = new Tika();

    public String parse(InputStream inputStream, String filename) {
        try {
            return tika.parseToString(inputStream);
        } catch (Exception e) {
            log.error("Failed to parse document: {}", filename, e);
            return "";
        }
    }

    public String detectMimeType(InputStream inputStream, String filename) {
        try {
            return tika.detect(inputStream, filename);
        } catch (Exception e) {
            log.warn("Failed to detect mime type for: {}", filename);
            return "application/octet-stream";
        }
    }
}
