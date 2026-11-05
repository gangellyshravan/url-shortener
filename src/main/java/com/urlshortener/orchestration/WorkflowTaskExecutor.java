package com.urlshortener.orchestration;

import com.urlshortener.orchestration.WorkflowModels.TaskContext;
import com.urlshortener.orchestration.WorkflowModels.TaskOutput;

public interface WorkflowTaskExecutor {
    TaskOutput execute(TaskContext context);
}
