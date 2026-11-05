package com.urlshortener.orchestration;

import static com.urlshortener.orchestration.WorkflowModels.Scenario.AMBIGUOUS;
import static com.urlshortener.orchestration.WorkflowModels.TaskDefinition;

import com.urlshortener.orchestration.WorkflowModels.Scenario;
import java.util.ArrayList;
import java.util.List;

public final class WorkflowPlan {
    private WorkflowPlan() {
    }

    static List<TaskDefinition> forScenario(Scenario scenario) {
        List<TaskDefinition> plan = new ArrayList<>();
        plan.add(new TaskDefinition("requirements", "REQUIREMENTS", List.of(), false));
        if (scenario == AMBIGUOUS) {
            plan.add(new TaskDefinition("assumptions", "REQUIREMENTS", List.of("requirements"), true));
        }
        List<String> designDependencies = scenario == AMBIGUOUS
                ? List.of("requirements", "assumptions")
                : List.of("requirements");
        plan.add(new TaskDefinition("architecture", "DESIGN", designDependencies, false));
        plan.add(new TaskDefinition("implementation", "IMPLEMENTATION", List.of("architecture"), false));
        plan.add(new TaskDefinition("documentation", "DOCUMENTATION", List.of("architecture"), false));
        plan.add(new TaskDefinition("tests", "TESTING", List.of("implementation"), false));
        plan.add(new TaskDefinition("release-readiness", "RELEASE", List.of("tests", "documentation"), true));
        validate(plan);
        return List.copyOf(plan);
    }

    public static void validate(List<TaskDefinition> definitions) {
        var keys = definitions.stream().map(TaskDefinition::key).collect(java.util.stream.Collectors.toSet());
        if (keys.size() != definitions.size()) {
            throw new IllegalArgumentException("Workflow task keys must be unique");
        }
        for (TaskDefinition definition : definitions) {
            for (String dependency : definition.dependencies()) {
                if (!keys.contains(dependency)) {
                    throw new IllegalArgumentException("Unknown workflow dependency: " + dependency);
                }
            }
        }
        var remaining = new java.util.HashSet<>(keys);
        while (!remaining.isEmpty()) {
            int previousSize = remaining.size();
            for (TaskDefinition definition : definitions) {
                if (remaining.contains(definition.key()) && definition.dependencies().stream().noneMatch(remaining::contains)) {
                    remaining.remove(definition.key());
                }
            }
            if (remaining.size() == previousSize) {
                throw new IllegalArgumentException("Workflow graph contains a cycle");
            }
        }
    }
}
