package com.urlshortener.orchestration;

import com.urlshortener.orchestration.WorkflowModels.TaskContext;
import com.urlshortener.orchestration.WorkflowModels.TaskOutput;

public class DeterministicWorkflowTaskExecutor implements WorkflowTaskExecutor {
    @Override
    public TaskOutput execute(TaskContext context) {
        String key = context.task().key();
        String title = switch (key) {
            case "requirements" -> "Normalized requirement";
            case "assumptions" -> "Assumptions requiring human confirmation";
            case "architecture" -> "Architecture proposal";
            case "implementation" -> "Implementation proposal";
            case "documentation" -> "Documentation proposal";
            case "tests" -> "Validation plan";
            case "release-readiness" -> "Release-readiness review";
            default -> throw new IllegalArgumentException("Unsupported workflow task: " + key);
        };
        String content = switch (key) {
            case "requirements" -> "Scenario: " + context.scenario() + "\nRequirement:\n" + context.requirement();
            case "assumptions" -> context.assumptions();
            case "architecture" -> "Design the smallest modular change that satisfies the normalized requirement. "
                    + "Preserve existing behavior, define interfaces and data flow, and identify security and failure boundaries.\n\n"
                    + dependency(context, "requirements") + dependency(context, "assumptions");
            case "implementation" -> "Reviewable implementation plan (no repository files are modified):\n"
                    + implementationGuidance(context) + "\n"
                    + "- Require review before applying any generated changes.\n\n"
                    + dependency(context, "architecture");
            case "documentation" -> "Document the requirement, design decisions, setup/configuration, validation, "
                    + "risks, assumptions, and limitations.\n\n" + dependency(context, "architecture");
            case "tests" -> "Validation checklist:\n"
                    + testingGuidance(context) + "\n"
                    + "- Exercise persistence and failure/recovery paths.\n"
                    + "- Verify no unapproved change is applied.\n\n" + dependency(context, "implementation");
            case "release-readiness" -> "Release readiness is a human review checkpoint. Confirm the proposed "
                    + "implementation, tests, documentation, policy checks, and unresolved risks before approving. "
                    + "Approval records review only; it does not deploy or modify the repository.\n\n"
                    + dependency(context, "tests") + dependency(context, "documentation");
            default -> throw new IllegalArgumentException("Unsupported workflow task: " + key);
        };
        return new TaskOutput(key, title, content);
    }

    private static String dependency(TaskContext context, String key) {
        String output = context.dependencyOutputs().get(key);
        return output == null ? "" : "\n\n--- " + key + " ---\n" + output;
    }

    private static String implementationGuidance(TaskContext context) {
        return switch (context.scenario()) {
            case GREENFIELD -> "- Define a small modular implementation and an explicit API/schema contract.";
            case BROWNFIELD -> "- Inspect existing callers and persistence contracts; preserve compatibility and user changes.";
            case AMBIGUOUS -> "- Implement only within the human-approved assumptions; record unresolved decisions explicitly.";
        };
    }

    private static String testingGuidance(TaskContext context) {
        return switch (context.scenario()) {
            case GREENFIELD -> "- Test the new capability's success cases, validation boundaries, and contract behavior.";
            case BROWNFIELD -> "- Add regression coverage for existing behavior and compatibility before testing the enhancement.";
            case AMBIGUOUS -> "- Test the accepted assumptions and explicitly identify behaviors that remain unverified.";
        };
    }
}
