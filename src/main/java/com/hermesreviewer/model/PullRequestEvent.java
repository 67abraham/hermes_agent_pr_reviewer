package com.hermesreviewer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@AllArgsConstructor
@NoArgsConstructor
public class PullRequestEvent {

    private String action;

    @JsonProperty("pull_request")
    private PullRequest pullRequest;

    private Repository repository;

    private Installation installation;

    public boolean isReviewable() {
        return "opened".equals(action) || "synchronize".equals(action) || "reopened".equals(action);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PullRequest {
        private long number;
        private String title;
        private String body;
        private String state;

        @JsonProperty("html_url")
        private String htmlUrl;

        @JsonProperty("diff_url")
        private String diffUrl;

        @JsonProperty("head")
        private Ref head;

        @JsonProperty("base")
        private Ref base;

        @JsonProperty("changed_files")
        private int changedFiles;

        @JsonProperty("additions")
        private int additions;

        @JsonProperty("deletions")
        private int deletions;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ref {
        private String sha;
        private String ref;
        private RepoRef repo;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class RepoRef {
            @JsonProperty("clone_url")
            private String cloneUrl;

            @JsonProperty("ssh_url")
            private String sshUrl;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repository {
        private long id;

        @JsonProperty("full_name")
        private String fullName;

        @JsonProperty("clone_url")
        private String cloneUrl;

        @JsonProperty("default_branch")
        private String defaultBranch;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Installation {
        private long id;
    }
}
