package com.hermesreviewer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads the optional .hermesreview.yml config from the repo root.
 *
 * Example .hermesreview.yml:
 *
 *   review-persona: "security-focused"
 *   focus-areas:
 *     - SQL injection
 *     - authentication
 *     - input validation
 *   linters: "eslint,ruff"
 *   ignore-paths:
 *     - "*.generated.java"
 *     - "vendor/**"
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RepoConfigLoader {

    private static final String CONFIG_FILE = ".hermesreview.yml";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public RepoConfig load(Path repoDir) {
        Path configPath = repoDir.resolve(CONFIG_FILE);

        if (!Files.exists(configPath)) {
            log.debug("No {} found — using defaults", CONFIG_FILE);
            return new RepoConfig();
        }

        try {
            RepoConfig config = YAML_MAPPER.readValue(configPath.toFile(), RepoConfig.class);
            log.info("Loaded repo config: persona={}, focus={}", config.getReviewPersona(), config.getFocusAreas());
            return config;
        } catch (Exception e) {
            log.warn("Failed to parse {} — using defaults: {}", CONFIG_FILE, e.getMessage());
            return new RepoConfig();
        }
    }
}
