package com.hermesreviewer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermesreviewer.model.PullRequestEvent;
import com.hermesreviewer.service.ReviewOrchestrator;
import com.hermesreviewer.service.WebhookSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    @Autowired
    private final ReviewOrchestrator reviewOrchestrator;
    @Autowired
    private final WebhookSignatureVerifier signatureVerifier;
    @Autowired
    private final ObjectMapper objectMapper;

    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestBody String rawPayload) {

        // Always verify the webhook signature first
        if (!signatureVerifier.verify(rawPayload, signature)) {
            log.warn("Webhook signature verification failed — rejecting request");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }
        System.out.println("Not necessary but just commit");

        if (!"pull_request".equals(eventType)) {
            return ResponseEntity.ok("Event ignored: " + eventType);
        }

        try {
            PullRequestEvent event = objectMapper.readValue(rawPayload, PullRequestEvent.class);

            if (!event.isReviewable()) {
                log.info("PR action '{}' does not require review — skipping", event.getAction());
                return ResponseEntity.ok("Action skipped: " + event.getAction());
            }

            log.info("Queuing review for PR #{} on {}",
                    event.getPullRequest().getNumber(),
                    event.getRepository().getFullName());

            // Fire-and-forget async — GitHub expects a fast 200 response
            reviewOrchestrator.reviewAsync(event);

            return ResponseEntity.ok("Review queued");

        } catch (Exception e) {
            log.error("Failed to parse webhook payload", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Bad payload");
        }


    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Hermes PR Reviewer is running");
    }
}
