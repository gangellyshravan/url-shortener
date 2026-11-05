CREATE TABLE workflow_runs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    scenario VARCHAR(32) NOT NULL,
    requirement CLOB NOT NULL,
    assumptions CLOB NOT NULL,
    status VARCHAR(32) NOT NULL,
    artifact_version INTEGER NOT NULL DEFAULT 1,
    cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    completed_at BIGINT NULL,
    first_replan_at BIGINT NULL,
    failure_reason CLOB NULL
);

CREATE TABLE workflow_tasks (
    run_id VARCHAR(36) NOT NULL,
    task_key VARCHAR(64) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    requires_approval BOOLEAN NOT NULL DEFAULT FALSE,
    artifact_version INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    started_at BIGINT NULL,
    finished_at BIGINT NULL,
    title VARCHAR(256) NULL,
    output CLOB NULL,
    failure_reason CLOB NULL,
    PRIMARY KEY (run_id, task_key),
    CONSTRAINT fk_workflow_tasks_run FOREIGN KEY (run_id) REFERENCES workflow_runs(id)
);

CREATE TABLE workflow_task_dependencies (
    run_id VARCHAR(36) NOT NULL,
    task_key VARCHAR(64) NOT NULL,
    depends_on VARCHAR(64) NOT NULL,
    PRIMARY KEY (run_id, task_key, depends_on),
    CONSTRAINT fk_workflow_dependency_task FOREIGN KEY (run_id, task_key)
        REFERENCES workflow_tasks(run_id, task_key),
    CONSTRAINT fk_workflow_dependency_parent FOREIGN KEY (run_id, depends_on)
        REFERENCES workflow_tasks(run_id, task_key)
);

CREATE TABLE workflow_artifacts (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    task_key VARCHAR(64) NOT NULL,
    artifact_type VARCHAR(64) NOT NULL,
    title VARCHAR(256) NOT NULL,
    content CLOB NOT NULL,
    artifact_version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_workflow_artifact_task FOREIGN KEY (run_id, task_key)
        REFERENCES workflow_tasks(run_id, task_key)
);

CREATE TABLE workflow_approvals (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    task_key VARCHAR(64) NOT NULL,
    artifact_version INTEGER NOT NULL,
    decision VARCHAR(16) NOT NULL,
    rationale CLOB NOT NULL,
    approver VARCHAR(128) NOT NULL,
    invalidated BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_workflow_approval_task FOREIGN KEY (run_id, task_key)
        REFERENCES workflow_tasks(run_id, task_key)
);

CREATE TABLE workflow_events (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    task_key VARCHAR(64) NULL,
    event_type VARCHAR(64) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    details CLOB NOT NULL,
    happened_at BIGINT NOT NULL,
    CONSTRAINT fk_workflow_event_run FOREIGN KEY (run_id) REFERENCES workflow_runs(id)
);

CREATE INDEX idx_workflow_runs_status ON workflow_runs(status);
CREATE INDEX idx_workflow_tasks_run_status ON workflow_tasks(run_id, status);
CREATE INDEX idx_workflow_artifacts_run ON workflow_artifacts(run_id, created_at);
CREATE INDEX idx_workflow_events_run ON workflow_events(run_id, happened_at);
CREATE INDEX idx_workflow_approvals_run ON workflow_approvals(run_id, task_key, artifact_version);
