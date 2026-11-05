package com.urlshortener.orchestration;

import static com.urlshortener.orchestration.WorkflowModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.urlshortener.orchestration.WorkflowRepository.TaskRecord;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkflowServiceTests {
    private static final TaskDefinition GATE = new TaskDefinition("release-readiness", "RELEASE", List.of(), true);
    private static final RunView RUN = new RunView("run-id", Scenario.GREENFIELD, "requirement", "assumptions",
            RunStatus.WAITING_APPROVAL, 1, false, 1, 1, null, null, List.of());

    @Test
    void retriesAreBoundedAndSuccessfulRetryPausesAtTheApprovalGate() {
        WorkflowRepository repository = mock(WorkflowRepository.class);
        when(repository.findStatus(anyString())).thenReturn(
                Optional.of(RunStatus.RUNNING), Optional.of(RunStatus.RUNNING));
        TaskRecord pending = new TaskRecord(GATE, TaskStatus.PENDING, 0, 0, null, null, null);
        TaskRecord waiting = new TaskRecord(GATE, TaskStatus.WAITING_APPROVAL, 1, 3, "Ready", "Review", null);
        when(repository.findTasks(anyString())).thenReturn(List.of(pending), List.of(waiting));
        when(repository.findRun(anyString())).thenReturn(Optional.of(RUN));
        when(repository.beginTask(anyString(), eq(GATE.key()))).thenReturn(true);
        when(repository.saveOutput(anyString(), eq(GATE.key()), any(), eq(TaskStatus.WAITING_APPROVAL)))
                .thenReturn(true);
        AtomicInteger attempts = new AtomicInteger();
        WorkflowTaskExecutor executor = context -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("Injected transient failure");
            }
            return new TaskOutput(GATE.key(), "Ready", "Review");
        };
        WorkflowService service = service(repository, executor);

        service.create(Scenario.GREENFIELD, "A requirement");

        assertEquals(3, attempts.get());
        verify(repository, times(2)).recordRetry(anyString(), eq(GATE.key()), any(Integer.class), anyString());
        verify(repository).saveOutput(anyString(), eq(GATE.key()),
                eq(new TaskOutput(GATE.key(), "Ready", "Review")), eq(TaskStatus.WAITING_APPROVAL));
        verify(repository).waitForApproval(anyString(), eq(GATE.key()));
    }

    @Test
    void exhaustedRetriesFailTheRunWithoutPublishingAnArtifact() {
        WorkflowRepository repository = mock(WorkflowRepository.class);
        when(repository.findStatus(anyString())).thenReturn(Optional.of(RunStatus.RUNNING));
        TaskRecord pending = new TaskRecord(GATE, TaskStatus.PENDING, 0, 0, null, null, null);
        when(repository.findTasks(anyString())).thenReturn(List.of(pending));
        when(repository.findRun(anyString())).thenReturn(Optional.of(RUN));
        when(repository.beginTask(anyString(), eq(GATE.key()))).thenReturn(true);
        AtomicInteger attempts = new AtomicInteger();
        WorkflowTaskExecutor executor = context -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Injected persistent failure");
        };
        WorkflowService service = service(repository, executor);

        service.create(Scenario.GREENFIELD, "A requirement");

        assertEquals(3, attempts.get());
        verify(repository, times(2)).recordRetry(anyString(), eq(GATE.key()), any(Integer.class), anyString());
        verify(repository).failRun(anyString(), eq(GATE.key()), anyString());
        verify(repository, times(0)).saveOutput(anyString(), eq(GATE.key()), any(), any());
    }

    @Test
    void taskTimeoutCancelsWorkerAndPreventsLateArtifactPublication() throws Exception {
        WorkflowRepository repository = mock(WorkflowRepository.class);
        when(repository.findStatus(anyString())).thenReturn(Optional.of(RunStatus.RUNNING));
        when(repository.findTasks(anyString())).thenReturn(List.of(
                new TaskRecord(GATE, TaskStatus.PENDING, 0, 0, null, null, null)));
        when(repository.findRun(anyString())).thenReturn(Optional.of(RUN));
        when(repository.beginTask(anyString(), eq(GATE.key()))).thenReturn(true);
        when(repository.cancellationRequested(anyString())).thenReturn(false);
        when(repository.saveOutput(anyString(), eq(GATE.key()), any(), any())).thenReturn(false);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        WorkflowTaskExecutor executor = context -> {
            entered.countDown();
            try {
                boolean waiting = true;
                while (waiting) {
                    try {
                        release.await();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                        // Simulate an adapter that does not stop immediately on interruption.
                    }
                }
                return new TaskOutput(GATE.key(), "Ready", "Late result");
            } finally {
                returned.countDown();
            }
        };
        var taskPool = Executors.newSingleThreadExecutor();
        WorkflowService service = new WorkflowService(repository, executor, Runnable::run, taskPool,
                3, 0, 0, 50);

        try {
            service.create(Scenario.GREENFIELD, "A requirement");

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            verify(repository).failRun(anyString(), isNull(), anyString());
            release.countDown();
            assertTrue(returned.await(1, TimeUnit.SECONDS));
            verify(repository, timeout(1000)).saveOutput(anyString(), eq(GATE.key()), any(), any());
        } finally {
            release.countDown();
            taskPool.shutdownNow();
        }
    }

    private static WorkflowService service(WorkflowRepository repository, WorkflowTaskExecutor taskExecutor) {
        Executor direct = Runnable::run;
        when(repository.beginTask(anyString(), eq(GATE.key()))).thenReturn(true);
        when(repository.saveOutput(anyString(), eq(GATE.key()), any(), any())).thenReturn(true);
        return new WorkflowService(repository, taskExecutor, direct, direct, 3, 0, 0, 1000);
    }
}
