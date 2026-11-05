package com.urlshortener.controller;

import static com.urlshortener.orchestration.WorkflowModels.*;

import com.urlshortener.orchestration.WorkflowService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orchestration")
public class WorkflowController {
    private final WorkflowService workflows;

    public WorkflowController(WorkflowService workflows) {
        this.workflows = workflows;
    }

    @PostMapping("/runs")
    public ResponseEntity<RunView> create(@Valid @RequestBody CreateRunRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(workflows.create(request.scenario(), request.requirement()));
    }

    @GetMapping("/runs/{id}")
    public RunView get(@PathVariable String id) {
        return workflows.get(id);
    }

    @GetMapping("/runs/{id}/artifacts")
    public List<ArtifactView> artifacts(@PathVariable String id) {
        return workflows.artifacts(id);
    }

    @GetMapping("/runs/{id}/events")
    public List<EventView> events(@PathVariable String id) {
        return workflows.events(id);
    }

    @PostMapping("/runs/{id}/approvals")
    public RunView approve(
            @PathVariable String id,
            @Valid @RequestBody ApprovalRequest request,
            Authentication authentication) {
        return workflows.approve(id, request.taskKey(), request.artifactVersion(),
                request.decision(), request.rationale(), authentication.getName());
    }

    @PostMapping("/runs/{id}/cancel")
    public RunView cancel(@PathVariable String id, Authentication authentication) {
        return workflows.cancel(id, authentication.getName());
    }

    @PostMapping("/runs/{id}/replan")
    public RunView replan(
            @PathVariable String id,
            @Valid @RequestBody ReplanRequest request,
            Authentication authentication) {
        return workflows.replan(id, request.taskKey(), request.content(), request.rationale(), authentication.getName());
    }

    @GetMapping("/metrics")
    public MetricsView metrics() {
        return workflows.metrics();
    }

    @GetMapping("/scenarios")
    public List<Map<String, String>> scenarios() {
        return List.of(
                Map.of("name", "GREENFIELD", "description",
                        "Well-defined new capability; inspect requirement, propose architecture, implementation, tests, and docs."),
                Map.of("name", "BROWNFIELD", "description",
                        "Enhance an existing system while preserving behavior and user changes."),
                Map.of("name", "AMBIGUOUS", "description",
                        "Require an explicit human assumptions/clarification approval before design."));
    }
}
