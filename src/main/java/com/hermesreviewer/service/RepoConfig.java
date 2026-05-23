package com.hermesreviewer.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RepoConfig {

    @JsonProperty("review-persona")
    private String reviewPersona;

    @JsonProperty("focus-areas")
    private List<String> focusAreas;

    @JsonProperty("linters")
    private String linters;

    @JsonProperty("ignore-paths")
    private List<String> ignorePaths;
}
