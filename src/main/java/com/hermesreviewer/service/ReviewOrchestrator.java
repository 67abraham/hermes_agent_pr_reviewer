package com.hermesreviewer.service;

import com.hermesreviewer.model.PullRequestEvent;
import com.hermesreviewer.model.ReviewResult;
import com.hermesreviewer.tools.GitTool;
import com.hermesreviewer.tools.LinterTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Orchestrates the full Hermes Agent review pipeline:
 *   1. Clone repo + fetch diff
 *   2. Run linters on changed files
 *   3. Send diff + lint output to Hermes Agent for reasoning
 *   4. Post structured review comments back to GitHub
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewOrchestrator {

    @Autowired
    private final GitTool gitTool;
    @Autowired
    private final LinterTool linterTool;
    @Autowired
    private final HermesAgentService hermesAgentService;
    @Autowired
    private final GitHubCommentService gitHubCommentService;
    @Autowired
    private final RepoConfigLoader repoConfigLoader;

    @Value("${review.cleanup-after-review}")
    private boolean cleanupAfterReview;

    @Async
    public void reviewAsync(PullRequestEvent event) {
        String repoFullName = event.getRepository().getFullName();
        long prNumber = event.getPullRequest().getNumber();
        Path workDir = null;

        try {
            log.info("[{}#{}] Starting review pipeline", repoFullName, prNumber);

            // ── Step 1: Clone repo and fetch the PR diff ──────────────────────
            workDir = gitTool.cloneRepo(
                    event.getRepository().getCloneUrl(),
                    event.getPullRequest().getHead().getSha()
            );

            String diff = gitTool.getDiff(
                    workDir,
                    event.getPullRequest().getBase().getSha(),
                    event.getPullRequest().getHead().getSha()
            );

            if (diff.isBlank()) {
                log.info("[{}#{}] Empty diff — nothing to review", repoFullName, prNumber);
                return;
            }

            // ── Step 2: Load repo-level review config (.hermesreview.yml) ─────
            RepoConfig config = repoConfigLoader.load(workDir);

            // ── Step 3: Run linters on changed files ──────────────────────────
            ReviewResult.LinterOutput linterOutput = linterTool.run(workDir, diff, config);

            // ── Step 4: Send everything to Hermes Agent for reasoning ─────────
            log.info("[{}#{}] Sending diff ({} chars) to Hermes Agent", repoFullName, prNumber, diff.length());
            ReviewResult review = hermesAgentService.review(
                    event.getPullRequest().getTitle(),
                    event.getPullRequest().getBody(),
                    diff,
                    linterOutput,
                    config
            );

            // ── Step 5: Post review comments to GitHub ────────────────────────
            log.info("[{}#{}] Posting review — verdict={}, comments={}",
                    repoFullName, prNumber, review.getVerdict(), review.getComments().size());

            gitHubCommentService.postReview(
                    repoFullName,
                    prNumber,
                    event.getPullRequest().getHead().getSha(),
                    review
            );

            log.info("[{}#{}] Review pipeline complete", repoFullName, prNumber);

        } catch (Exception e) {
            log.error("[{}#{}] Review pipeline failed", repoFullName, prNumber, e);
            // Best-effort: post an error comment so the PR author knows something went wrong
            try {
                gitHubCommentService.postErrorComment(repoFullName, prNumber,
                        "Hermes PR Reviewer encountered an error: " + e.getMessage());
            } catch (Exception inner) {
                log.error("Failed to post error comment", inner);
            }
        } finally {
            if (cleanupAfterReview && workDir != null) {
                gitTool.cleanup(workDir);
            }
        }
    }
}
