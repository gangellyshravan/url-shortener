package com.urlshortener.orchestration;

import static com.urlshortener.orchestration.WorkflowModels.*;

import com.urlshortener.orchestration.WorkflowRepository.TaskRecord;
import com.urlshortener.service.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WorkflowService {
    private static final int MAX_ARTIFACT_CHARACTERS = 16_000;

    private final WorkflowRepository repository;
    private final WorkflowTaskExecutor taskExecutor;
    private final Executor runnerExecutor;
    private final Executor taskPool;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final long maxRetryDelayMillis;
    private final long taskTimeoutMillis;

    public WorkflowService(
            WorkflowRepository repository,
            WorkflowTaskExecutor taskExecutor,
            @Qualifier("workflowRunnerExecutor") Executor runnerExecutor,
            @Qualifier("workflowTaskExecutor") Executor taskPool,
            @Value("${urlshortener.workflow.max-attempts:3}") int maxAttempts,
            @Value("${urlshortener.workflow.retry-delay-ms:100}") long retryDelayMillis,
            @Value("${urlshortener.workflow.max-retry-delay-ms:1000}") long maxRetryDelayMillis,
            @Value("${urlshortener.workflow.task-timeout-ms:30000}") long taskTimeoutMillis) {
        if (maxAttempts < 1 || maxAttempts > 5 || retryDelayMillis < 0
                || maxRetryDelayMillis < retryDelayMillis || maxRetryDelayMillis > 30_000
                || taskTimeoutMillis < 1 || taskTimeoutMillis > 300_000) {
            throw new IllegalArgumentException("Invalid workflow execution configuration");
        }
        this.repository = repository;
        this.taskExecutor = taskExecutor;
        this.runnerExecutor = runnerExecutor;
        this.taskPool = taskPool;
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.maxRetryDelayMillis = maxRetryDelayMillis;
        this.taskTimeoutMillis = taskTimeoutMillis;
    }

    public RunView create(Scenario scenario, String requirement) {
        if (scenario == null || requirement == null || requirement.isBlank() || requirement.length() > 4000) {
            throw new ApiException(400, "A scenario and a requirement of 1-4000 characters are required");
        }
        String id = UUID.randomUUID().toString();
        repository.createRun(id, scenario, requirement, assumptionsFor(scenario), WorkflowPlan.forScenario(scenario));
        schedule(id);
        return get(id);
    }

    public RunView get(String id) {
        return repository.findRun(id).orElseThrow(() -> new ApiException(404, "Workflow run not found"));
    }

    public List<ArtifactView> artifacts(String id) {
        get(id);
        return repository.findArtifacts(id);
    }

    public List<EventView> events(String id) {
        get(id);
        return repository.findEvents(id);
    }

    public MetricsView metrics() {
        return repository.metrics();
    }

    public RunView approve(String id, String taskKey, int artifactVersion, Decision decision,
                           String rationale, String actor) {
        get(id);
        if (taskKey == null || taskKey.isBlank() || artifactVersion < 1 || decision == null
                || rationale == null || rationale.isBlank() || rationale.length() > 1000) {
            throw new ApiException(400, "A task, current artifact version, decision, and rationale are required");
        }
        try {
            repository.approve(id, taskKey, artifactVersion, decision == Decision.APPROVE, rationale, actor);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(404, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ApiException(409, exception.getMessage());
        }
        if (decision == Decision.APPROVE) {
            schedule(id);
        }
        return get(id);
    }

    public RunView replan(String id, String taskKey, String content, String rationale, String actor) {
        get(id);
        if (taskKey == null || taskKey.isBlank() || content == null || content.isBlank()
                || content.length() > MAX_ARTIFACT_CHARACTERS || rationale == null
                || rationale.isBlank() || rationale.length() > 1000) {
            throw new ApiException(400, "A task, revised content, and rationale within the configured limits are required");
        }
        try {
            repository.replan(id, taskKey, content, rationale, actor);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(404, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ApiException(409, exception.getMessage());
        }
        schedule(id);
        return get(id);
    }

    public RunView cancel(String id, String actor) {
        get(id);
        if (repository.requestCancellation(id, actor)) {
            schedule(id);
        }
        return get(id);
    }

    private void schedule(String runId) {
        try {
            CompletableFuture.runAsync(() -> execute(runId), runnerExecutor);
        } catch (RejectedExecutionException exception) {
            repository.recordExecutorFailure(runId, "Workflow runner capacity is exhausted");
        }
    }

    private void execute(String runId) {
        try {
            while (true) {
                RunStatus currentStatus = repository.findStatus(runId).orElse(null);
                if (currentStatus == null || terminal(currentStatus)) {
                    return;
                }
                if (repository.cancellationRequested(runId)) {
                    repository.markCancelled(runId);
                    return;
                }
                List<TaskRecord> tasks = repository.findTasks(runId);
                if (tasks.isEmpty()) {
                    repository.recordExecutorFailure(runId, "Workflow run has no tasks");
                    return;
                }
                TaskRecord waiting = tasks.stream()
                        .filter(task -> task.status() == TaskStatus.WAITING_APPROVAL).findFirst().orElse(null);
                if (waiting != null) {
                    repository.waitForApproval(runId, waiting.definition().key());
                    return;
                }
                if (tasks.stream().allMatch(task -> task.status() == TaskStatus.SUCCEEDED)) {
                    repository.completeRun(runId);
                    return;
                }
                Map<String, TaskRecord> byKey = new LinkedHashMap<>();
                tasks.forEach(task -> byKey.put(task.definition().key(), task));
                List<TaskRecord> ready = tasks.stream()
                        .filter(task -> task.status() == TaskStatus.PENDING)
                        .filter(task -> task.definition().dependencies().stream()
                                .allMatch(dependency -> byKey.get(dependency).status() == TaskStatus.SUCCEEDED))
                        .toList();
                if (ready.isEmpty()) {
                    boolean terminal = tasks.stream().allMatch(task ->
                            task.status() == TaskStatus.SUCCEEDED || task.status() == TaskStatus.BLOCKED
                                    || task.status() == TaskStatus.FAILED || task.status() == TaskStatus.CANCELLED
                                    || task.status() == TaskStatus.ROLLED_BACK);
                    if (terminal) {
                        repository.recordExecutorFailure(runId, "Workflow stopped before reaching a terminal run state");
                        return;
                    }
                    repository.failRun(runId, null, "Workflow graph has no runnable task");
                    return;
                }
                List<FutureTask<TaskResult>> futures = new ArrayList<>();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(taskTimeoutMillis);
                for (TaskRecord task : ready) {
                    FutureTask<TaskResult> future = new FutureTask<>(() -> executeTask(runId, task));
                    try {
                        taskPool.execute(future);
                    } catch (RejectedExecutionException exception) {
                        cancelAll(futures);
                        repository.failRun(runId, task.definition().key(), "Workflow task capacity is exhausted");
                        return;
                    }
                    futures.add(future);
                }
                for (int index = 0; index < futures.size(); index++) {
                    FutureTask<TaskResult> future = futures.get(index);
                    TaskResult result;
                    try {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            throw new TimeoutException();
                        }
                        result = future.get(remaining, TimeUnit.NANOSECONDS);
                    } catch (TimeoutException exception) {
                        cancelAll(futures);
                        repository.failRun(runId, null, "Workflow task wave exceeded its configured timeout");
                        return;
                    } catch (InterruptedException exception) {
                        cancelAll(futures);
                        Thread.currentThread().interrupt();
                        repository.failRun(runId, ready.get(index).definition().key(),
                                "Workflow coordinator was interrupted");
                        return;
                    } catch (ExecutionException exception) {
                        cancelAll(futures);
                        Throwable cause = exception.getCause();
                        repository.failRun(runId, ready.get(index).definition().key(),
                                "Workflow task failed unexpectedly: "
                                        + (cause == null ? "unknown error" : cause.getClass().getSimpleName()));
                        return;
                    }
                    if (result.failure() != null) {
                        cancelAll(futures);
                        repository.failRun(runId, result.taskKey(), result.failure());
                        return;
                    }
                }
            }
        } catch (RuntimeException exception) {
            repository.recordExecutorFailure(runId, "Workflow coordinator failed: " + exception.getClass().getSimpleName());
        }
    }

    private TaskResult executeTask(String runId, TaskRecord task) {
        String taskKey = task.definition().key();
        if (!repository.beginTask(runId, taskKey)) {
            return new TaskResult(taskKey, null);
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (repository.cancellationRequested(runId)) {
                    return new TaskResult(taskKey, "Cancellation requested before task completion");
                }
                RunView run = repository.findRun(runId).orElseThrow();
                Map<String, String> dependencies = new LinkedHashMap<>();
                for (String dependency : task.definition().dependencies()) {
                    TaskRecord parent = repository.findTask(runId, dependency).orElseThrow();
                    dependencies.put(dependency, parent.output());
                }
                TaskOutput output = taskExecutor.execute(new TaskContext(runId, run.scenario(), run.requirement(),
                        run.assumptions(), task.definition(), Map.copyOf(dependencies), attempt));
                validateOutput(output);
                TaskStatus status = task.definition().requiresApproval()
                        ? TaskStatus.WAITING_APPROVAL : TaskStatus.SUCCEEDED;
                if (!repository.saveOutput(runId, taskKey, output, status)) {
                    return new TaskResult(taskKey, "Run stopped before the task artifact could be published");
                }
                return new TaskResult(taskKey, null);
            } catch (RuntimeException exception) {
                String reason = "Task executor failed: " + exception.getClass().getSimpleName();
                if (attempt == maxAttempts) {
                    return new TaskResult(taskKey, reason);
                }
                repository.recordRetry(runId, taskKey, attempt, reason);
                try {
                    if (!waitBeforeRetry(runId, retryDelay(attempt))) {
                        return new TaskResult(taskKey, "Cancellation requested during retry backoff");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new TaskResult(taskKey, "Task interrupted during bounded retry backoff");
                }
            }
        }
        return new TaskResult(taskKey, "Task exhausted its bounded retry policy");
    }

    private long retryDelay(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 20);
        long delay;
        try {
            delay = Math.multiplyExact(retryDelayMillis, multiplier);
        } catch (ArithmeticException exception) {
            delay = maxRetryDelayMillis;
        }
        return Math.min(delay, maxRetryDelayMillis);
    }

    private boolean waitBeforeRetry(String runId, long delayMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
        while (true) {
            if (repository.cancellationRequested(runId)) {
                return false;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return true;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
        }
    }

    private static void cancelAll(List<FutureTask<TaskResult>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    private static void validateOutput(TaskOutput output) {
        if (output == null || output.type() == null || output.type().isBlank()
                || output.title() == null || output.title().isBlank() || output.title().length() > 256
                || output.content() == null || output.content().isBlank()
                || output.content().length() > MAX_ARTIFACT_CHARACTERS) {
            throw new IllegalArgumentException("Agent output violates artifact policy");
        }
    }

    private static String assumptionsFor(Scenario scenario) {
        return switch (scenario) {
            case GREENFIELD -> "Assumptions: The normalized requirement is sufficiently clear; the local deterministic "
                    + "executor produces review artifacts only and does not change repository files or deploy.";
            case BROWNFIELD -> "Assumptions: Existing behavior and user changes must be preserved; only explicitly "
                    + "reviewed proposals are in scope; repository inspection and changes are not performed by this demo.";
            case AMBIGUOUS -> "Clarification required: success criteria, affected users, compatibility requirements, "
                    + "and deployment constraints are unspecified. A human must confirm these assumptions before design work.";
        };
    }

    private static boolean terminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED || status == RunStatus.REJECTED;
    }

    private record TaskResult(String taskKey, String failure) {
    }
}
