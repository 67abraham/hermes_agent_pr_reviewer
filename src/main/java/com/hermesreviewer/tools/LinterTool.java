package com.hermesreviewer.tools;

import com.hermesreviewer.model.ReviewResult;
import com.hermesreviewer.service.RepoConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Linter tool — detects the repo's language/tooling and runs the appropriate linter.
 * Output is fed into Hermes Agent alongside the diff so it can correlate warnings
 * with specific diff lines for more precise inline comments.
 */
@Component
@Slf4j
public class LinterTool {

    @Value("${review.linters}")
    private String configuredLinters;

    private static final int MAX_OUTPUT_CHARS = 4000;
    private static final Pattern ERROR_PATTERN   = Pattern.compile("(?i)\\berror\\b");
    private static final Pattern WARNING_PATTERN = Pattern.compile("(?i)\\bwarning\\b");

    public ReviewResult.LinterOutput run(Path repoDir, String diff, RepoConfig config) {
        String linters = config.getLinters() != null ? config.getLinters() : configuredLinters;

        if ("none".equalsIgnoreCase(linters) || linters.isBlank()) {
            return ReviewResult.LinterOutput.builder().ran(false).build();
        }

        List<String> changedFiles = extractChangedFiles(diff);
        if (changedFiles.isEmpty()) {
            return ReviewResult.LinterOutput.builder().ran(false).build();
        }

        StringBuilder combinedOutput = new StringBuilder();
        int totalErrors = 0;
        int totalWarnings = 0;

        for (String linter : linters.split(",")) {
            try {
                LinterResult result = runLinter(linter.trim(), repoDir, changedFiles);
                combinedOutput.append("=== ").append(linter.trim()).append(" ===\n");
                combinedOutput.append(result.output()).append("\n");
                totalErrors   += result.errors();
                totalWarnings += result.warnings();
            } catch (Exception e) {
                log.warn("Linter '{}' failed: {}", linter.trim(), e.getMessage());
                combinedOutput.append("=== ").append(linter.trim()).append(" (failed) ===\n");
            }
        }

        String rawOutput = combinedOutput.toString();
        if (rawOutput.length() > MAX_OUTPUT_CHARS) {
            rawOutput = rawOutput.substring(0, MAX_OUTPUT_CHARS) + "\n... [truncated]";
        }

        return ReviewResult.LinterOutput.builder()
                .ran(true)
                .errorCount(totalErrors)
                .warningCount(totalWarnings)
                .rawOutput(rawOutput)
                .build();
    }

    private LinterResult runLinter(String linter, Path repoDir, List<String> changedFiles)
            throws IOException, InterruptedException {

        List<String> command = buildCommand(linter, changedFiles);
        if (command.isEmpty()) {
            log.warn("Unknown linter: {} — skipping", linter);
            return new LinterResult("", 0, 0);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor(120, TimeUnit.SECONDS);

        return new LinterResult(output, countMatches(output, ERROR_PATTERN), countMatches(output, WARNING_PATTERN));
    }

    private List<String> buildCommand(String linter, List<String> files) {
        return switch (linter.toLowerCase()) {
            case "eslint"      -> buildCommand("npx", "eslint", "--format=compact", files);
            case "ruff"        -> buildCommand("ruff", "check", files);
            case "checkstyle"  -> buildCommand("checkstyle", "-c", "/google_checks.xml", files);
            case "golangci"    -> List.of("golangci-lint", "run", "--out-format=line-number");
            case "rubocop"     -> buildCommand("rubocop", "--format=json", files);
            default            -> List.of();
        };
    }

    private List<String> buildCommand(String... prefixArgs) {
        return List.of(prefixArgs);
    }

    private List<String> buildCommand(String cmd, String flag, List<String> files) {
        List<String> command = new ArrayList<>();
        command.add(cmd);
        command.add(flag);
        command.addAll(files);
        return command;
    }

    private List<String> buildCommand(String cmd1, String cmd2, String flag, List<String> files) {
        List<String> command = new ArrayList<>();
        command.add(cmd1);
        command.add(cmd2);
        command.add(flag);
        command.addAll(files);
        return command;
    }

    /**
     * Extracts file paths from the git diff header lines (e.g. "+++ b/src/Foo.java").
     */
    private List<String> extractChangedFiles(String diff) {
        List<String> files = new ArrayList<>();
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++ b/")) {
                files.add(line.substring("+++ b/".length()));
            }
        }
        return files;
    }

    private int countMatches(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private record LinterResult(String output, int errors, int warnings) {}
}
