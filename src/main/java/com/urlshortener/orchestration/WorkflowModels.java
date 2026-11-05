package com.urlshortener.orchestration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class WorkflowModels {
    private WorkflowModels() {
    }

    public enum Scenario {
        GREENFIELD,
        BROWNFIELD,
        AMBIGUOUS
    }

    public enum RunStatus {
        RUNNING,
        WAITING_APPROVAL,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        REJECTED
    }

    public enum TaskStatus {
        PENDING,
        RUNNING,
        WAITING_APPROVAL,
        SUCCEEDED,
        FAILED,
        BLOCKED,
        ROLLED_BACK,
        CANCELLED
    }

    public enum ArtifactStatus {
        REVIEWABLE,
        APPROVED,
        REJECTED,
        SUPERSEDED,
        WITHDRAWN
    }

    public enum Decision {
        APPROVE,
        REJECT
    }

    public record CreateRunRequest(
            Scenario scenario,
            @NotBlank @Size(max = 4000) String requirement) {
    }

    public record ApprovalRequest(
            @NotBlank String taskKey,
            int artifactVersion,
            Decision decision,
            @NotBlank @Size(max = 1000) String rationale) {
    }

    public record ReplanRequest(
            @NotBlank String taskKey,
            @NotBlank @Size(max = 8000) String content,
            @NotBlank @Size(max = 1000) String rationale) {
    }

    public record TaskDefinition(String key, String stage, List<String> dependencies, boolean requiresApproval) {
    }

    public record TaskView(
            String key,
            String stage,
            TaskStatus status,
            List<String> dependencies,
            boolean requiresApproval,
            int artifactVersion,
            int attempts,
            String title,
            String output,
            String failureReason) {
    }

    public record RunView(
            String id,
            Scenario scenario,
            String requirement,
            String assumptions,
            RunStatus status,
            int artifactVersion,
            boolean cancellationRequested,
            long createdAt,
            long updatedAt,
            Long completedAt,
            String failureReason,
            List<TaskView> tasks) {
    }

    public record ArtifactView(
            String id,
            String taskKey,
            String type,
            String title,
            String content,
            int version,
            ArtifactStatus status,
            long createdAt) {
    }

    public record EventView(String id, String taskKey, String type, String actor, String details, long happenedAt) {
    }

    public record MetricsView(
            long totalRuns,
            long terminalRuns,
            double successRatePercent,
            long retryCount,
            long rollbackCount,
            Double meanRecoveryTimeMs,
            Double meanEndToEndLatencyMs) {
    }

    public record TaskOutput(String type, String title, String content) {
    }

    public record TaskContext(
            String runId,
            Scenario scenario,
            String requirement,
            String assumptions,
            TaskDefinition task,
            Map<String, String> dependencyOutputs,
            int attempt) {
    }
}
