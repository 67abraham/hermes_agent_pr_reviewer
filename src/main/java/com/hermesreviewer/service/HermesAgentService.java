package com.hermesreviewer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermesreviewer.model.ReviewResult;
import com.hermesreviewer.uiliity.ReviewVerdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the Hermes Agent API with the PR diff and linter output,
 * then parses the structured JSON review response.
 *
 * Hermes Agent performs multi-step reasoning:
 *   - Reads the diff hunk by hunk
 *   - Cross-references linter warnings with diff lines
 *   - Reasons about logic, security, and style issues
 *   - Returns a structured JSON review
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HermesAgentService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    // Truncate very large diffs to stay within model context
    private static final int MAX_DIFF_CHARS = 24_000;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${hermes.agent-url}")
    private String hermesAgentUrl;

    @Value("${hermes.model}")
    private String model;

    @Value("${hermes.max-tokens}")
    private int maxTokens;

    public ReviewResult review(
            String prTitle,
            String prBody,
            String diff,
            ReviewResult.LinterOutput linterOutput,
            RepoConfig config) throws IOException {

        String prompt = buildPrompt(prTitle, prBody, diff, linterOutput, config);
        String agentResponse = callHermesAgent(prompt);
        return parseReviewResponse(agentResponse);
    }

    private String buildPrompt(String prTitle, String prBody, String diff,
                                ReviewResult.LinterOutput linterOutput, RepoConfig config) {

        // Truncate diff if needed, keeping the beginning (most relevant files first)
        String truncatedDiff = diff.length() > MAX_DIFF_CHARS
                ? diff.substring(0, MAX_DIFF_CHARS) + "\n\n... [diff truncated]"
                : diff;

        String linterSection = linterOutput.isRan()
                ? String.format("""
                ## Linter output (%d errors, %d warnings)
                ```
                %s
                ```
                """, linterOutput.getErrorCount(), linterOutput.getWarningCount(), linterOutput.getRawOutput())
                : "## Linter: not configured for this repo\n";

        String personaSection = config.getReviewPersona() != null
                ? "Review persona: " + config.getReviewPersona() + "\n"
                : "";

        String focusSection = config.getFocusAreas() != null && !config.getFocusAreas().isEmpty()
                ? "Focus especially on: " + String.join(", ", config.getFocusAreas()) + "\n"
                : "";

        return String.format("""
                You are an expert code reviewer powered by Hermes Agent. Your job is to review
                the following pull request thoroughly and return a structured JSON review.
                
                %s%s
                
                ## PR title
                %s
                
                ## PR description
                %s
                
                %s
                
                ## Diff
                ```diff
                %s
                ```
                
                ## Instructions
                Analyse the diff carefully. Consider:
                - Logic errors or bugs
                - Security vulnerabilities (injection, auth, secrets in code, etc.)
                - Code style and readability
                - Test coverage gaps
                - Performance concerns
                - Anything the linter flagged
                
                Respond ONLY with a valid JSON object in this exact structure:
                {
                  "verdict": "APPROVE" | "REQUEST_CHANGES" | "COMMENT",
                  "summary": "2-4 sentence overall assessment of the PR",
                  "comments": [
                    {
                      "path": "relative/file/path.java",
                      "line": <line number in the file>,
                      "severity": "critical" | "warning" | "nit",
                      "body": "Specific, actionable feedback for this line"
                    }
                  ]
                }
                
                Rules:
                - verdict must be REQUEST_CHANGES if there are any critical issues
                - verdict must be APPROVE only if the code is genuinely ready to merge
                - Keep comment bodies concise and actionable (1-3 sentences max)
                - Only comment on lines that actually appear in the diff
                - Return an empty comments array if there is nothing specific to flag
                """,
                personaSection, focusSection, prTitle,
                prBody != null ? prBody : "(no description)",
                linterSection, truncatedDiff);
    }

    private String callHermesAgent(String prompt) throws IOException {
        // Hermes Agent exposes an OpenAI-compatible /v1/chat/completions endpoint
        String requestBody = objectMapper.writeValueAsString(new ChatRequest(model, maxTokens, prompt));

        Map<String, String> seheader = new HashMap();
        seheader.put("Authorization","Bearer 7b75c35040a6d0be8a5208b3e7d48130d4222c27");
        seheader.put("Content-Type", "application/json");

        Request request = new Request.Builder()
                .url(hermesAgentUrl + "/v1/chat/completions")
                .post(RequestBody.create(requestBody, JSON))
                .headers(Headers.of(seheader))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Hermes Agent returned HTTP " + response.code());
            }
            String body = response.body().string();
            JsonNode root = objectMapper.readTree(body);
            return root.path("choices").get(0).path("message").path("content").asText();
        }
    }
    private ReviewResult parseReviewResponse(String rawResponse) {
        try {
            // Strip any markdown fences the model may have added
            String json = rawResponse
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("```", "")
                    .trim();

            JsonNode root = objectMapper.readTree(json);
            String verdictStr = root.path("verdict").asText("COMMENT");
            ReviewVerdict verdict = ReviewVerdict.valueOf(verdictStr);

            String summary = root.path("summary").asText("");

            List<ReviewResult.ReviewComment> comments = new ArrayList<>();
            for (JsonNode c : root.path("comments")) {
                comments.add(ReviewResult.ReviewComment.builder()
                        .path(c.path("path").asText())
                        .line(c.path("line").asInt())
                        .severity(c.path("severity").asText("nit"))
                        .body(formatCommentBody(c.path("severity").asText("nit"), c.path("body").asText()))
                        .build());
            }

            return ReviewResult.builder()
                    .verdict(verdict)
                    .summary(summary)
                    .comments(comments)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse Hermes Agent response — falling back to COMMENT verdict", e);
            return ReviewResult.builder()
                    .verdict(ReviewVerdict.COMMENT)
                    .summary("Hermes Agent completed the review but the response could not be parsed.")
                    .comments(List.of())
                    .build();
        }
    }

    private String formatCommentBody(String severity, String body) {
        return switch (severity) {
            case "critical" -> "🔴 **Critical:** " + body;
            case "warning"  -> "🟡 **Warning:** " + body;
            default         -> "💬 **Nit:** " + body;
        };
    }

    // Minimal request POJO for Hermes Agent's OpenAI-compatible API
    record ChatRequest(String model, int max_tokens, String content) {
        // Jackson will serialise fields automatically
        public record Message(String role, String content) {}

        // Custom serialisation shape expected by Hermes Agent
        @com.fasterxml.jackson.annotation.JsonProperty("model")
        public String getModel() { return model; }

        @com.fasterxml.jackson.annotation.JsonProperty("max_tokens")
        public int getMaxTokens() { return max_tokens; }

        @com.fasterxml.jackson.annotation.JsonProperty("messages")
        public java.util.List<Message> getMessages() {
            return List.of(new Message("user", content));
        }
    }
}
