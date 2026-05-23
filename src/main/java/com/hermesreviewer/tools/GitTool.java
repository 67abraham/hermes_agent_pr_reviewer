package com.hermesreviewer.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class GitTool {

    @Value("${review.workspace-dir}")
    private String workspaceDir;

    @Value("${review.diff-context-lines}")
    private int diffContextLines;

    /**
     * Shallow-clones the repo and checks out the given SHA.
     * Returns the path to the cloned repo directory.
     */
    public Path cloneRepo(String cloneUrl, String headSha) throws IOException, InterruptedException {
        Path targetDir = Path.of(workspaceDir, UUID.randomUUID().toString());
        Files.createDirectories(targetDir);

        log.info("Cloning {} @ {} into {}", cloneUrl, headSha, targetDir);

        // Shallow clone — we only need history for the diff
        runCommand(List.of(
                "git", "clone",
                "--depth=50",       // enough history for the diff
                "--no-tags",
                cloneUrl,
                targetDir.toString()
        ), Path.of(workspaceDir));

        // Checkout the exact commit
        runCommand(List.of("git", "checkout", headSha), targetDir);

        return targetDir;
    }

    /**
     * Returns the unified diff between baseSha and headSha.
     */
    public String getDiff(Path repoDir, String baseSha, String headSha)
            throws IOException, InterruptedException {

        // Fetch the base ref so git can compute the diff
        runCommand(List.of("git", "fetch", "--depth=50", "origin", baseSha), repoDir);

        ProcessBuilder pb = new ProcessBuilder(
                "git", "diff",
                "--unified=" + diffContextLines,
                "--no-color",
                baseSha + "..." + headSha
        );
        pb.directory(repoDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor(60, TimeUnit.SECONDS);

        return output;
    }

    /**
     * Recursively deletes the cloned repo directory.
     */
    public void cleanup(Path repoDir) {
        try {
            Files.walkFileTree(repoDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("Cleaned up workspace: {}", repoDir);
        } catch (IOException e) {
            log.warn("Failed to clean up workspace {}: {}", repoDir, e.getMessage());
        }
    }

    private void runCommand(List<String> command, Path workingDir)
            throws IOException, InterruptedException {

        log.debug("Running: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        // Exit code 1 from git diff just means there are differences — not an error
        boolean isGitDiff = command.contains("diff");
        if (exitCode != 0 && !(isGitDiff && exitCode == 1)) {
            throw new IOException(
                    "Command failed (exit " + exitCode + "): " +
                    String.join(" ", command) + "\n" + output
            );
        }
    }
}
