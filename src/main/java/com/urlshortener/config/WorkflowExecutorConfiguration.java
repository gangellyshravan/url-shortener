package com.urlshortener.config;

import com.urlshortener.orchestration.DeterministicWorkflowTaskExecutor;
import com.urlshortener.orchestration.WorkflowTaskExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class WorkflowExecutorConfiguration {
    @Bean
    @ConditionalOnMissingBean(WorkflowTaskExecutor.class)
    WorkflowTaskExecutor deterministicWorkflowTaskExecutor() {
        return new DeterministicWorkflowTaskExecutor();
    }

    @Bean("workflowRunnerExecutor")
    Executor workflowRunnerExecutor() {
        return executor(2, 2, 32, "workflow-runner-");
    }

    @Bean("workflowTaskExecutor")
    Executor workflowTaskExecutor(@Value("${urlshortener.workflow.max-parallel-tasks:4}") int maxParallelTasks) {
        if (maxParallelTasks < 1 || maxParallelTasks > 16) {
            throw new IllegalArgumentException("urlshortener.workflow.max-parallel-tasks must be between 1 and 16");
        }
        return executor(maxParallelTasks, maxParallelTasks, maxParallelTasks * 4, "workflow-task-");
    }

    private static ThreadPoolTaskExecutor executor(int core, int max, int queue, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
