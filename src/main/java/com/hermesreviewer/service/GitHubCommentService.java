package com.hermesreviewer.service;

import com.hermesreviewer.model.ReviewResult;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Posts the structured review back to GitHub via the GitHub API.
 * Uses the github-api SDK (Kohsuke) for clean type-safe interaction.
 */
@Service
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class GitHubCommentService {

    @Autowired
    private GitHub github;

    public GitHubCommentService(@Value("${github.token}") String token) throws IOException {
        this.github = new GitHubBuilder().withOAuthToken(token).build();
    }

    public void postReview(String repoFullName, long prNumber,
                           String headSha, ReviewResult review) throws IOException {

        GHRepository repo = github.getRepository(repoFullName);
        GHPullRequest pr   = repo.getPullRequest((int) prNumber);

        // Build inline comments for the GitHub review
        List<GHPullRequestReviewBuilder> draftComments = review.getComments()
                .stream()
                .filter(c -> c.getPath() != null && !c.getPath().isBlank() && c.getLine() > 0)
                .map(c -> {
                    try {
                        return pr.createReview()
                                // We use the builder pattern below — this is just for mapping
                                .comment(c.getBody(), c.getPath(), c.getLine());
                    } catch (Exception e) {
                        log.warn("Could not map comment for {}: {}", c.getPath(), e.getMessage());
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        // Map verdict to GitHub review state
        GHPullRequestReviewEvent event = switch (review.getVerdict()) {
            case APPROVE         -> GHPullRequestReviewEvent.APPROVE;
            case REQUEST_CHANGES -> GHPullRequestReviewEvent.REQUEST_CHANGES;
            default              -> GHPullRequestReviewEvent.COMMENT;
        };

        String fullSummary = buildSummaryBody(review);

        // Post the review — single API call with inline comments + summary
        GHPullRequestReviewBuilder builder = pr.createReview()
                .commitId(headSha)
                .body(fullSummary)
                .event(event);

        log.info("Full Response Build");

        // Add inline comments
        review.getComments().forEach(c -> {
            if (c.getPath() != null && !c.getPath().isBlank() && c.getLine() > 0) {
                builder.comment(c.getBody(), c.getPath(), c.getLine());
            }
        });

        builder.create();

        log.info("Posted {} review on {}/{} with {} inline comments",
                event, repoFullName, prNumber, review.getComments().size());
    }

    public void postErrorComment(String repoFullName, long prNumber, String message) throws IOException {
        GHRepository repo = github.getRepository(repoFullName);
        GHPullRequest pr   = repo.getPullRequest((int) prNumber);
        pr.comment("⚠️ **Hermes PR Reviewer:** " + message);
    }

    private String buildSummaryBody(ReviewResult review) {
        long criticalCount = review.getComments().stream()
                .filter(c -> "critical".equals(c.getSeverity())).count();
        long warningCount  = review.getComments().stream()
                .filter(c -> "warning".equals(c.getSeverity())).count();
        long nitCount      = review.getComments().stream()
                .filter(c -> "nit".equals(c.getSeverity())).count();

        return String.format("""
                ## 🤖 Hermes Agent Review
                
                %s
                
                ---
                | 🔴 Critical | 🟡 Warning | 💬 Nit |
                |:-----------:|:----------:|:------:|
                | %d | %d | %d |
                
                *Reviewed by [Hermes PR Reviewer](https://github.com/your-org/hermes-pr-reviewer)*
                """,
                review.getSummary(),
                criticalCount, warningCount, nitCount);
    }
}
