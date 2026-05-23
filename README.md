# Hermes PR Reviewer 🤖

> Autonomous GitHub pull request reviews powered by [Hermes Agent](https://github.com/NousResearch/hermes-agent).

Hermes PR Reviewer is a GitHub App that listens for pull request events and triggers a full agentic review pipeline — cloning the repo, running linters, reasoning over the diff with Hermes Agent, and posting structured inline comments back to your PR. No human in the loop.

---

## How it works

```
GitHub PR opened/updated
        │
        ▼
  Webhook (Spring Boot)
        │  verify HMAC-SHA256 signature
        ▼
  GitTool — clone repo, fetch diff
        │
        ▼
  LinterTool — run eslint / ruff / checkstyle
        │
        ▼
  HermesAgentService — multi-step reasoning
        │  diff + lint output → Hermes Agent
        │  ← structured JSON review
        ▼
  GitHubCommentService — post inline review
        │  severity-bucketed summary table
        │  line-level inline comments
        ▼
  GitHub PR — review posted ✅
```

## Features

- **Fully agentic** — Hermes Agent reasons across the entire diff + linter output, not just a single prompt
- **Inline comments** — line-level feedback directly in the GitHub review UI
- **Severity buckets** — 🔴 Critical / 🟡 Warning / 💬 Nit with a summary table
- **Per-repo config** — drop a `.hermesreview.yml` in any repo to customise behaviour
- **Linter integration** — eslint, ruff, checkstyle, golangci-lint, rubocop
- **Self-hostable** — runs on a $5 VPS, Docker-first, no vendor lock-in
- **Model-agnostic** — point `HERMES_AGENT_URL` at any Hermes Agent instance

---

## Quick start

### Prerequisites

- Java 21
- Maven 3.9+
- A running [Hermes Agent](https://github.com/NousResearch/hermes-agent) instance
- A GitHub personal access token (or GitHub App credentials)

### 1. Clone and configure

```bash
git clone https://github.com/your-org/hermes-pr-reviewer
cd hermes-pr-reviewer
cp .env.example .env
```

Edit `.env`:

```env
GITHUB_TOKEN=ghp_your_token_here
GITHUB_WEBHOOK_SECRET=your_webhook_secret
HERMES_AGENT_URL=http://localhost:11434
HERMES_MODEL=nous-hermes-2
```

### 2. Run locally with Docker Compose

```bash
docker compose up --build
```

The server starts on `http://localhost:8080`.

### 3. Expose with ngrok (for local GitHub webhook testing)

```bash
ngrok http 8080
```

Copy the `https://xxxx.ngrok.io` URL.

### 4. Configure the GitHub webhook

In your GitHub repo → **Settings → Webhooks → Add webhook**:

| Field | Value |
|-------|-------|
| Payload URL | `https://xxxx.ngrok.io/webhook/github` |
| Content type | `application/json` |
| Secret | Your `GITHUB_WEBHOOK_SECRET` |
| Events | Pull requests |

### 5. Open a PR and watch Hermes work

Open or update any pull request in the repo. Within seconds you'll see Hermes Agent's review appear.

---

## Per-repo configuration

Drop a `.hermesreview.yml` in the root of any repo to customise Hermes' behaviour:

```yaml
review-persona: "security-focused"

focus-areas:
  - SQL injection
  - authentication
  - input validation

linters: "eslint"

ignore-paths:
  - "**/*.generated.java"
  - "vendor/**"
```

See `.hermesreview.yml.example` for all available options.

---

## Deploy to Railway

[![Deploy on Railway](https://railway.app/button.svg)](https://railway.app/new/template)

Set the following environment variables in Railway:

- `GITHUB_TOKEN`
- `GITHUB_WEBHOOK_SECRET`
- `HERMES_AGENT_URL`

---

## Project structure

```
src/main/java/com/hermesreviewer/
├── HermesPrReviewerApplication.java   Entry point
├── config/
│   └── AppConfig.java                 OkHttpClient, ObjectMapper beans
├── controller/
│   └── WebhookController.java         POST /webhook/github
├── model/
│   ├── PullRequestEvent.java          GitHub webhook payload
│   └── ReviewResult.java              Hermes review output
├── service/
│   ├── ReviewOrchestrator.java        Pipeline coordinator (@Async)
│   ├── HermesAgentService.java        Hermes Agent API client
│   ├── GitHubCommentService.java      Posts review to GitHub
│   ├── WebhookSignatureVerifier.java  HMAC-SHA256 validation
│   ├── RepoConfigLoader.java          Loads .hermesreview.yml
│   └── RepoConfig.java                Config POJO
└── tools/
    ├── GitTool.java                   clone, diff, cleanup
    └── LinterTool.java                eslint, ruff, checkstyle, etc.
```

---

## Built for the Hermes Agent Challenge

This project was built for the [DEV Hermes Agent Challenge](https://dev.to/devteam/join-the-hermes-agent-challenge-1000-in-prizes). It demonstrates Hermes Agent's agentic capabilities — multi-step planning, tool use (shell + HTTP), and real-world action — applied to a genuinely useful developer workflow.

---

## License

MIT
