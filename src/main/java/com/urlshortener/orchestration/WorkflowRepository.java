package com.urlshortener.orchestration;

import static com.urlshortener.orchestration.WorkflowModels.ArtifactStatus;
import static com.urlshortener.orchestration.WorkflowModels.EventView;
import static com.urlshortener.orchestration.WorkflowModels.MetricsView;
import static com.urlshortener.orchestration.WorkflowModels.RunStatus;
import static com.urlshortener.orchestration.WorkflowModels.RunView;
import static com.urlshortener.orchestration.WorkflowModels.Scenario;
import static com.urlshortener.orchestration.WorkflowModels.TaskDefinition;
import static com.urlshortener.orchestration.WorkflowModels.TaskOutput;
import static com.urlshortener.orchestration.WorkflowModels.TaskStatus;
import static com.urlshortener.orchestration.WorkflowModels.TaskView;
import static com.urlshortener.orchestration.WorkflowModels.ArtifactView;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WorkflowRepository {
    private static final String SYSTEM_ACTOR = "system";
    private final JdbcTemplate jdbc;

    public WorkflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record RunControl(RunStatus status, boolean cancellationRequested) {
    }

    @Transactional
    public void createRun(String id, Scenario scenario, String requirement, String assumptions,
                          List<TaskDefinition> definitions) {
        long now = now();
        jdbc.update("""
                INSERT INTO workflow_runs
                    (id, scenario, requirement, assumptions, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, scenario.name(), requirement, assumptions, RunStatus.RUNNING.name(), now, now);
        for (TaskDefinition definition : definitions) {
            jdbc.update("""
                    INSERT INTO workflow_tasks (run_id, task_key, stage, status, requires_approval)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, definition.key(), definition.stage(), TaskStatus.PENDING.name(),
                    definition.requiresApproval());
        }
        for (TaskDefinition definition : definitions) {
            for (String dependency : definition.dependencies()) {
                jdbc.update("""
                        INSERT INTO workflow_task_dependencies (run_id, task_key, depends_on)
                        VALUES (?, ?, ?)
                        """, id, definition.key(), dependency);
            }
        }
        event(id, null, "RUN_CREATED", SYSTEM_ACTOR, "Scenario " + scenario + " submitted");
    }

    public Optional<RunView> findRun(String id) {
        List<RunView> runs = jdbc.query("""
                SELECT id, scenario, requirement, assumptions, status, artifact_version,
                       cancellation_requested, created_at, updated_at, completed_at, failure_reason
                FROM workflow_runs WHERE id = ?
                """, (rs, row) -> new RunView(
                rs.getString("id"),
                Scenario.valueOf(rs.getString("scenario")),
                rs.getString("requirement"),
                rs.getString("assumptions"),
                RunStatus.valueOf(rs.getString("status")),
                rs.getInt("artifact_version"),
                rs.getBoolean("cancellation_requested"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                nullableLong(rs, "completed_at"),
                rs.getString("failure_reason"),
                findTasks(rs.getString("id")).stream().map(TaskRecord::view).toList()), id);
        return runs.stream().findFirst();
    }

    public Optional<RunStatus> findStatus(String runId) {
        return runStatus(runId);
    }

    public List<TaskRecord> findTasks(String runId) {
        List<TaskRecord> tasks = jdbc.query("""
                SELECT task_key, stage, status, requires_approval, artifact_version, attempts,
                       title, output, failure_reason
                FROM workflow_tasks WHERE run_id = ? ORDER BY task_key
                """, (rs, row) -> {
            String key = rs.getString("task_key");
            List<String> dependencies = jdbc.queryForList("""
                    SELECT depends_on FROM workflow_task_dependencies
                    WHERE run_id = ? AND task_key = ? ORDER BY depends_on
                    """, String.class, runId, key);
            return new TaskRecord(
                    new TaskDefinition(key, rs.getString("stage"), dependencies,
                            rs.getBoolean("requires_approval")),
                    TaskStatus.valueOf(rs.getString("status")),
                    rs.getInt("artifact_version"),
                    rs.getInt("attempts"),
                    rs.getString("title"),
                    rs.getString("output"),
                    rs.getString("failure_reason"));
        }, runId);
        return List.copyOf(tasks);
    }

    public Optional<TaskRecord> findTask(String runId, String taskKey) {
        return findTasks(runId).stream().filter(task -> task.definition().key().equals(taskKey)).findFirst();
    }

    @Transactional
    public boolean beginTask(String runId, String taskKey) {
        long now = now();
        int updated = jdbc.update("""
                UPDATE workflow_tasks
                SET status = ?, attempts = attempts + 1, started_at = COALESCE(started_at, ?),
                    failure_reason = NULL
                WHERE run_id = ? AND task_key = ? AND status = ?
                    AND EXISTS (SELECT 1 FROM workflow_runs
                        WHERE id = ? AND status = ? AND cancellation_requested = FALSE)
                """, TaskStatus.RUNNING.name(), now, runId, taskKey, TaskStatus.PENDING.name(),
                runId, RunStatus.RUNNING.name());
        if (updated != 1) {
            return false;
        }
        event(runId, taskKey, "TASK_STARTED", SYSTEM_ACTOR, "Task execution started");
        return true;
    }

    @Transactional
    public boolean saveOutput(String runId, String taskKey, TaskOutput output, TaskStatus status) {
        TaskRecord task = findTask(runId, taskKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow task: " + taskKey));
        int version = task.artifactVersion() + 1;
        long now = now();
        int updated = jdbc.update("""
                UPDATE workflow_tasks
                SET status = ?, artifact_version = ?, title = ?, output = ?, failure_reason = NULL,
                    finished_at = ?
                WHERE run_id = ? AND task_key = ? AND status = ?
                    AND EXISTS (SELECT 1 FROM workflow_runs
                        WHERE id = ? AND status = ? AND cancellation_requested = FALSE)
                """, status.name(), version, output.title(), output.content(), now, runId, taskKey,
                TaskStatus.RUNNING.name(), runId, RunStatus.RUNNING.name());
        if (updated != 1) {
            return false;
        }
        jdbc.update("""
                INSERT INTO workflow_artifacts
                    (id, run_id, task_key, artifact_type, title, content, artifact_version, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), runId, taskKey, output.type(), output.title(),
                output.content(), version, ArtifactStatus.REVIEWABLE.name(), now);
        event(runId, taskKey, status == TaskStatus.WAITING_APPROVAL
                ? "APPROVAL_REQUIRED" : "TASK_SUCCEEDED", SYSTEM_ACTOR,
                "Artifact version " + version + " created");
        return true;
    }

    @Transactional
    public void recordRetry(String runId, String taskKey, int attempt, String reason) {
        jdbc.update("""
                UPDATE workflow_tasks SET attempts = attempts + 1
                WHERE run_id = ? AND task_key = ? AND status = ?
                """, runId, taskKey, TaskStatus.RUNNING.name());
        event(runId, taskKey, "TASK_RETRY", SYSTEM_ACTOR, "Attempt " + attempt + " failed: " + reason);
    }

    @Transactional
    public void failRun(String runId, String taskKey, String reason) {
        RunControl currentRun = jdbc.query("""
                SELECT status, cancellation_requested FROM workflow_runs WHERE id = ? FOR UPDATE
                """, (rs, row) -> new RunControl(
                RunStatus.valueOf(rs.getString("status")), rs.getBoolean("cancellation_requested")), runId)
                .stream().findFirst().orElse(null);
        if (currentRun == null || terminal(currentRun.status())) {
            return;
        }
        if (currentRun.cancellationRequested()) {
            markCancelled(runId);
            return;
        }
        long now = now();
        jdbc.update("""
                UPDATE workflow_tasks SET status = ?, failure_reason = ?, finished_at = ?
                WHERE run_id = ? AND task_key = ?
                """, TaskStatus.FAILED.name(), reason, now, runId, taskKey);
        jdbc.update("""
                UPDATE workflow_tasks SET status = ?, finished_at = ?
                WHERE run_id = ? AND status IN (?, ?, ?)
                """, TaskStatus.BLOCKED.name(), now, runId,
                TaskStatus.PENDING.name(), TaskStatus.WAITING_APPROVAL.name(), TaskStatus.RUNNING.name());
        int withdrawn = jdbc.update("""
                UPDATE workflow_artifacts SET status = ?
                WHERE run_id = ? AND status = ?
                """, ArtifactStatus.WITHDRAWN.name(), runId, ArtifactStatus.REVIEWABLE.name());
        jdbc.update("""
                UPDATE workflow_tasks SET status = ?, finished_at = ?
                WHERE run_id = ? AND status = ?
                """, TaskStatus.ROLLED_BACK.name(), now, runId, TaskStatus.SUCCEEDED.name());
        updateRun(runId, RunStatus.FAILED, reason);
        event(runId, taskKey, "RUN_FAILED", SYSTEM_ACTOR, reason);
        if (withdrawn > 0) {
            event(runId, taskKey, "ARTIFACTS_WITHDRAWN", SYSTEM_ACTOR,
                    withdrawn + " reviewable artifact(s) withdrawn; no workspace changes were applied");
        }
    }

    @Transactional
    public boolean requestCancellation(String runId, String actor) {
        int updated = jdbc.update("""
                UPDATE workflow_runs SET cancellation_requested = TRUE, updated_at = ?
                WHERE id = ? AND cancellation_requested = FALSE
                    AND status NOT IN (?, ?, ?, ?)
                """, now(), runId, RunStatus.SUCCEEDED.name(), RunStatus.FAILED.name(),
                RunStatus.CANCELLED.name(), RunStatus.REJECTED.name());
        if (updated == 0) {
            return false;
        }
        event(runId, null, "CANCELLATION_REQUESTED", actor, "Safe stop requested");
        return true;
    }

    public boolean cancellationRequested(String runId) {
        Boolean requested = jdbc.queryForObject(
                "SELECT cancellation_requested FROM workflow_runs WHERE id = ?", Boolean.class, runId);
        return Boolean.TRUE.equals(requested);
    }

    @Transactional
    public void waitForApproval(String runId, String taskKey) {
        long now = now();
        int updated = jdbc.update("""
                UPDATE workflow_runs SET status = ?, updated_at = ?, completed_at = NULL
                WHERE id = ? AND status = ? AND cancellation_requested = FALSE
                """, RunStatus.WAITING_APPROVAL.name(), now, runId, RunStatus.RUNNING.name());
        if (updated == 1) {
            event(runId, taskKey, "RUN_WAITING_APPROVAL", SYSTEM_ACTOR, "Run paused at an approval gate");
        }
    }

    @Transactional
    public void completeRun(String runId) {
        long now = now();
        int updated = jdbc.update("""
                UPDATE workflow_runs SET status = ?, updated_at = ?, completed_at = ?, failure_reason = NULL
                WHERE id = ? AND cancellation_requested = FALSE AND status = ?
                """, RunStatus.SUCCEEDED.name(), now, now, runId, RunStatus.RUNNING.name());
        if (updated == 1) {
            event(runId, null, "RUN_SUCCEEDED", SYSTEM_ACTOR, "All tasks and approval gates completed");
        } else if (cancellationRequested(runId)) {
            markCancelled(runId);
        } else {
            recordExecutorFailure(runId, "Run could not transition to successful completion");
        }
    }

    @Transactional
    public void recordExecutorFailure(String runId, String reason) {
        failRun(runId, null, reason);
    }

    @Transactional
    public void markCancelled(String runId) {
        long now = now();
        jdbc.update("""
                UPDATE workflow_tasks SET status = ?, finished_at = ?
                WHERE run_id = ? AND status IN (?, ?, ?)
                """, TaskStatus.CANCELLED.name(), now, runId,
                TaskStatus.PENDING.name(), TaskStatus.WAITING_APPROVAL.name(), TaskStatus.RUNNING.name());
        int withdrawn = jdbc.update("""
                UPDATE workflow_artifacts SET status = ?
                WHERE run_id = ? AND status = ?
                """, ArtifactStatus.WITHDRAWN.name(), runId, ArtifactStatus.REVIEWABLE.name());
        jdbc.update("""
                UPDATE workflow_tasks SET status = ?, finished_at = ?
                WHERE run_id = ? AND status = ?
                """, TaskStatus.ROLLED_BACK.name(), now, runId, TaskStatus.SUCCEEDED.name());
        updateRun(runId, RunStatus.CANCELLED, "Cancelled at a safe-stop boundary");
        event(runId, null, "RUN_CANCELLED", SYSTEM_ACTOR, "Run stopped; unapproved artifacts withdrawn");
        if (withdrawn > 0) {
            event(runId, null, "ARTIFACTS_WITHDRAWN", SYSTEM_ACTOR,
                    withdrawn + " reviewable artifact(s) withdrawn; no workspace changes were applied");
        }
    }

    @Transactional
    public void approve(String runId, String taskKey, int artifactVersion, boolean approved,
                        String rationale, String actor) {
        TaskRecord task = findTask(runId, taskKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow task: " + taskKey));
        if (task.status() != TaskStatus.WAITING_APPROVAL || !task.definition().requiresApproval()) {
            throw new IllegalStateException("Task is not waiting for approval");
        }
        if (artifactVersion != task.artifactVersion()) {
            throw new IllegalStateException("Approval must reference the current artifact version");
        }
        TaskStatus nextStatus = approved ? TaskStatus.SUCCEEDED : TaskStatus.BLOCKED;
        long now = now();
        int updated = jdbc.update("""
                UPDATE workflow_tasks SET status = ?, finished_at = ?
                WHERE run_id = ? AND task_key = ? AND status = ? AND artifact_version = ?
                    AND EXISTS (SELECT 1 FROM workflow_runs
                        WHERE id = ? AND status = ? AND cancellation_requested = FALSE)
                """, nextStatus.name(), now, runId, taskKey, TaskStatus.WAITING_APPROVAL.name(), artifactVersion,
                runId, RunStatus.WAITING_APPROVAL.name());
        if (updated != 1) {
            throw new IllegalStateException("Approval gate has already changed");
        }
        String decision = approved ? "APPROVE" : "REJECT";
        jdbc.update("""
                INSERT INTO workflow_approvals
                    (id, run_id, task_key, artifact_version, decision, rationale, approver, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), runId, taskKey, artifactVersion, decision, rationale, actor, now);
        if (approved) {
            jdbc.update("""
                    UPDATE workflow_artifacts SET status = ?
                    WHERE run_id = ? AND task_key = ? AND artifact_version = ? AND status = ?
                    """, ArtifactStatus.APPROVED.name(), runId, taskKey, artifactVersion,
                    ArtifactStatus.REVIEWABLE.name());
            updateRun(runId, RunStatus.RUNNING, null);
        } else {
            jdbc.update("""
                    UPDATE workflow_tasks SET status = ?, finished_at = ?
                    WHERE run_id = ? AND status = ?
                    """, TaskStatus.BLOCKED.name(), now, runId, TaskStatus.PENDING.name());
            jdbc.update("""
                    UPDATE workflow_artifacts SET status = ?
                    WHERE run_id = ? AND task_key = ? AND artifact_version = ? AND status = ?
                    """, ArtifactStatus.REJECTED.name(), runId, taskKey, artifactVersion,
                    ArtifactStatus.REVIEWABLE.name());
            int withdrawn = jdbc.update("""
                    UPDATE workflow_artifacts SET status = ?
                    WHERE run_id = ? AND status = ? AND task_key <> ?
                    """, ArtifactStatus.WITHDRAWN.name(), runId, ArtifactStatus.REVIEWABLE.name(), taskKey);
            jdbc.update("""
                    UPDATE workflow_tasks SET status = ?, finished_at = ?
                    WHERE run_id = ? AND status = ?
                    """, TaskStatus.ROLLED_BACK.name(), now, runId, TaskStatus.SUCCEEDED.name());
            updateRun(runId, RunStatus.REJECTED, "Human approval rejected at task " + taskKey);
            if (withdrawn > 0) {
                event(runId, taskKey, "ARTIFACTS_WITHDRAWN", SYSTEM_ACTOR,
                        withdrawn + " reviewable artifact(s) withdrawn; no workspace changes were applied");
            }
        }
        event(runId, taskKey, approved ? "APPROVAL_GRANTED" : "APPROVAL_REJECTED", actor,
                "Artifact version " + artifactVersion + ": " + rationale);
    }

    @Transactional
    public void replan(String runId, String taskKey, String content, String rationale, String actor) {
        RunControl currentRun = jdbc.query("""
                SELECT status, cancellation_requested FROM workflow_runs WHERE id = ? FOR UPDATE
                """, (rs, row) -> new RunControl(
                RunStatus.valueOf(rs.getString("status")), rs.getBoolean("cancellation_requested")), runId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Workflow run not found"));
        if (currentRun.cancellationRequested()
                || (currentRun.status() != RunStatus.SUCCEEDED && currentRun.status() != RunStatus.WAITING_APPROVAL)) {
            throw new IllegalStateException("A run can only be replanned after completion or at an approval gate");
        }
        TaskRecord task = findTask(runId, taskKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow task: " + taskKey));
        if (task.status() != TaskStatus.SUCCEEDED || task.definition().requiresApproval()) {
            throw new IllegalStateException("Only a completed, non-release task can be revised");
        }
        int version = task.artifactVersion() + 1;
        long now = now();
        jdbc.update("""
                UPDATE workflow_artifacts SET status = ?
                WHERE run_id = ? AND task_key = ? AND status IN (?, ?)
                """, ArtifactStatus.SUPERSEDED.name(), runId, taskKey,
                ArtifactStatus.REVIEWABLE.name(), ArtifactStatus.APPROVED.name());
        jdbc.update("""
                UPDATE workflow_tasks SET artifact_version = ?, output = ?, finished_at = ?
                WHERE run_id = ? AND task_key = ?
                """, version, content, now, runId, taskKey);
        jdbc.update("""
                INSERT INTO workflow_artifacts
                    (id, run_id, task_key, artifact_type, title, content, artifact_version, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), runId, taskKey, taskKey, task.title(),
                content, version, ArtifactStatus.REVIEWABLE.name(), now);
        List<String> descendants = descendants(runId, taskKey);
        for (String descendant : descendants) {
            jdbc.update("""
                    UPDATE workflow_tasks SET status = ?, attempts = 0,
                        started_at = NULL, finished_at = NULL, title = NULL, output = NULL, failure_reason = NULL
                    WHERE run_id = ? AND task_key = ?
                    """, TaskStatus.PENDING.name(), runId, descendant);
            jdbc.update("""
                    UPDATE workflow_artifacts SET status = ?
                    WHERE run_id = ? AND task_key = ? AND status IN (?, ?)
                    """, ArtifactStatus.SUPERSEDED.name(), runId, descendant,
                    ArtifactStatus.REVIEWABLE.name(), ArtifactStatus.APPROVED.name());
            jdbc.update("""
                    UPDATE workflow_approvals SET invalidated = TRUE
                    WHERE run_id = ? AND task_key = ? AND invalidated = FALSE
                    """, runId, descendant);
        }
        jdbc.update("""
                UPDATE workflow_runs SET status = ?, cancellation_requested = FALSE,
                    updated_at = ?, completed_at = NULL, failure_reason = NULL,
                    first_replan_at = COALESCE(first_replan_at, ?),
                    artifact_version = artifact_version + 1
                WHERE id = ?
                """, RunStatus.RUNNING.name(), now, now, runId);
        event(runId, taskKey, "REPLAN", actor, "Created artifact version " + version
                + "; reset downstream tasks " + descendants + ". Rationale: " + rationale);
    }

    public List<ArtifactView> findArtifacts(String runId) {
        return jdbc.query("""
                SELECT id, task_key, artifact_type, title, content, artifact_version, status, created_at
                FROM workflow_artifacts WHERE run_id = ? ORDER BY created_at, task_key, artifact_version
                """, ARTIFACT_MAPPER, runId);
    }

    public List<EventView> findEvents(String runId) {
        return jdbc.query("""
                SELECT id, task_key, event_type, actor, details, happened_at
                FROM workflow_events WHERE run_id = ? ORDER BY happened_at, id
                """, EVENT_MAPPER, runId);
    }

    public MetricsView metrics() {
        long total = value("SELECT COUNT(*) FROM workflow_runs");
        long terminal = value("""
                SELECT COUNT(*) FROM workflow_runs
                WHERE status IN (?, ?, ?, ?)
                """, RunStatus.SUCCEEDED.name(), RunStatus.FAILED.name(),
                RunStatus.CANCELLED.name(), RunStatus.REJECTED.name());
        long succeeded = value("SELECT COUNT(*) FROM workflow_runs WHERE status = ?", RunStatus.SUCCEEDED.name());
        long retries = value("SELECT COUNT(*) FROM workflow_events WHERE event_type = 'TASK_RETRY'");
        long rollbacks = value("SELECT COUNT(*) FROM workflow_events WHERE event_type = 'ARTIFACTS_WITHDRAWN'");
        Double recovery = jdbc.queryForObject("""
                SELECT AVG(completed_at - first_replan_at) FROM workflow_runs
                WHERE status = ? AND first_replan_at IS NOT NULL AND completed_at IS NOT NULL
                """, Double.class, RunStatus.SUCCEEDED.name());
        Double latency = jdbc.queryForObject("""
                SELECT AVG(completed_at - created_at) FROM workflow_runs
                WHERE completed_at IS NOT NULL
                """, Double.class);
        return new MetricsView(total, terminal, terminal == 0 ? 0.0 : succeeded * 100.0 / terminal,
                retries, rollbacks, recovery, latency);
    }

    private List<String> descendants(String runId, String taskKey) {
        List<String> result = new ArrayList<>();
        List<String> frontier = new ArrayList<>(List.of(taskKey));
        while (!frontier.isEmpty()) {
            String parent = frontier.remove(0);
            List<String> children = jdbc.queryForList("""
                    SELECT task_key FROM workflow_task_dependencies
                    WHERE run_id = ? AND depends_on = ? ORDER BY task_key
                    """, String.class, runId, parent);
            for (String child : children) {
                if (!result.contains(child)) {
                    result.add(child);
                    frontier.add(child);
                }
            }
        }
        return result;
    }

    private Optional<RunStatus> runStatus(String runId) {
        List<String> statuses = jdbc.queryForList("SELECT status FROM workflow_runs WHERE id = ?", String.class, runId);
        return statuses.stream().findFirst().map(RunStatus::valueOf);
    }

    private void updateRun(String runId, RunStatus status, String reason) {
        long now = now();
        jdbc.update("""
                UPDATE workflow_runs SET status = ?, updated_at = ?,
                    completed_at = CASE WHEN ? IN (?, ?, ?, ?) THEN ? ELSE NULL END,
                    failure_reason = ?
                WHERE id = ?
                """, status.name(), now, status.name(), RunStatus.SUCCEEDED.name(), RunStatus.FAILED.name(),
                RunStatus.CANCELLED.name(), RunStatus.REJECTED.name(), now, reason, runId);
    }

    private void event(String runId, String taskKey, String type, String actor, String details) {
        jdbc.update("""
                INSERT INTO workflow_events (id, run_id, task_key, event_type, actor, details, happened_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), runId, taskKey, type, actor, details, now());
    }

    private long value(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        return result == null ? 0L : result;
    }

    private static boolean terminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED || status == RunStatus.REJECTED;
    }

    private static long now() {
        return Instant.now().toEpochMilli();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static final RowMapper<ArtifactView> ARTIFACT_MAPPER = (rs, row) -> new ArtifactView(
            rs.getString("id"), rs.getString("task_key"), rs.getString("artifact_type"),
            rs.getString("title"), rs.getString("content"), rs.getInt("artifact_version"),
            ArtifactStatus.valueOf(rs.getString("status")), rs.getLong("created_at"));

    private static final RowMapper<EventView> EVENT_MAPPER = (rs, row) -> new EventView(
            rs.getString("id"), rs.getString("task_key"), rs.getString("event_type"),
            rs.getString("actor"), rs.getString("details"), rs.getLong("happened_at"));

    public record TaskRecord(TaskDefinition definition, TaskStatus status, int artifactVersion,
                             int attempts, String title, String output, String failureReason) {
        TaskView view() {
            return new TaskView(definition.key(), definition.stage(), status, definition.dependencies(),
                    definition.requiresApproval(), artifactVersion, attempts, title, output, failureReason);
        }
    }
}
