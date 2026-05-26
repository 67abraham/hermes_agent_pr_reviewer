package com.hermesreviewer.model;

import com.hermesreviewer.uiliity.ReviewVerdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReviewResult {

    private String summary;
    private ReviewVerdict verdict;
    private List<ReviewComment> comments;
    private LinterOutput linterOutput;



    @Data
    @Builder
    public static class ReviewComment {
        // File path relative to repo root
        private String path;
        // Line number in the diff (required by GitHub API)
        private int line;
        // One of: critical, warning, nit
        private String severity;
        private String body;
    }

    @Data
    @Builder
    public static class LinterOutput {
        private boolean ran;
        private int errorCount;
        private int warningCount;
        private String rawOutput;
    }
}
