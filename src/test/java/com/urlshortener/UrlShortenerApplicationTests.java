package com.urlshortener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.urlshortener.orchestration.WorkflowModels.ApprovalRequest;
import com.urlshortener.orchestration.WorkflowModels.Decision;
import com.urlshortener.orchestration.WorkflowModels.RunStatus;
import com.urlshortener.orchestration.WorkflowModels.Scenario;
import com.urlshortener.orchestration.WorkflowModels.TaskStatus;
import com.urlshortener.orchestration.WorkflowModels.TaskDefinition;
import com.urlshortener.orchestration.WorkflowModels.TaskOutput;
import com.urlshortener.orchestration.WorkflowModels.TaskView;
import com.urlshortener.orchestration.WorkflowRepository;
import com.urlshortener.orchestration.WorkflowService;
import com.urlshortener.orchestration.WorkflowPlan;
import com.urlshortener.service.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
		"JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"spring.datasource.url=jdbc:h2:mem:urlshortener-test;DB_CLOSE_DELAY=-1;NON_KEYWORDS=DAY",
		"urlshortener.workflow.retry-delay-ms=0"
})
@AutoConfigureMockMvc
class UrlShortenerApplicationTests {
	@Autowired
	private WorkflowService workflows;

	@Autowired
	private WorkflowRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@Test
	void graphRejectsUnknownDependenciesAndCycles() {
		assertThrows(IllegalArgumentException.class, () -> WorkflowPlan.validate(List.of(
				new TaskDefinition("one", "TEST", List.of("missing"), false))));
		assertThrows(IllegalArgumentException.class, () -> WorkflowPlan.validate(List.of(
				new TaskDefinition("one", "TEST", List.of("two"), false),
				new TaskDefinition("two", "TEST", List.of("one"), false))));
	}

	@Test
	void greenfieldRunWaitsForApprovalAndReplansDownstreamArtifacts() throws Exception {
		var run = workflows.create(Scenario.GREENFIELD, "Add a safe, reviewable feature.");
		awaitTask(run.id(), "release-readiness", TaskStatus.WAITING_APPROVAL, 1);
		var initial = workflows.get(run.id());
		assertEquals(TaskStatus.SUCCEEDED, task(initial, "implementation").status());
		assertEquals(TaskStatus.SUCCEEDED, task(initial, "documentation").status());
		assertEquals(TaskStatus.SUCCEEDED, task(initial, "tests").status());
		assertNotNull(workflows.artifacts(run.id()).stream()
				.filter(artifact -> artifact.taskKey().equals("release-readiness")).findFirst().orElse(null));

		workflows.approve(run.id(), "release-readiness", 1, Decision.APPROVE,
				"Reviewed initial proposal", "test-admin");
		awaitRun(run.id(), RunStatus.SUCCEEDED);

		workflows.replan(run.id(), "implementation", "Revised implementation proposal",
				"Clarified implementation boundary", "test-admin");
		awaitTask(run.id(), "release-readiness", TaskStatus.WAITING_APPROVAL, 2);
		var revised = workflows.get(run.id());
		assertEquals(2, task(revised, "implementation").artifactVersion());
		assertEquals(2, task(revised, "tests").artifactVersion());
		assertEquals(1, task(revised, "documentation").artifactVersion());
		assertTrue(jdbc.queryForObject("""
				SELECT invalidated FROM workflow_approvals
				WHERE run_id = ? AND task_key = 'release-readiness' AND artifact_version = 1
				""", Boolean.class, run.id()));
		ApiException staleApproval = assertThrows(ApiException.class, () ->
				workflows.approve(run.id(), "release-readiness", 1, Decision.APPROVE,
						"Stale review", "test-admin"));
		assertEquals(409, staleApproval.getStatus());

		workflows.approve(run.id(), "release-readiness", 2, Decision.APPROVE,
				"Reviewed revised proposal", "test-admin");
		awaitRun(run.id(), RunStatus.SUCCEEDED);
		assertTrue(workflows.events(run.id()).stream().anyMatch(event -> event.type().equals("REPLAN")));
		assertTrue(workflows.metrics().successRatePercent() > 0);
	}

	@Test
	void ambiguousScenarioRequiresClarificationAndCanBeSafelyRejected() throws Exception {
		var run = workflows.create(Scenario.AMBIGUOUS, "Improve the service.");
		awaitTask(run.id(), "assumptions", TaskStatus.WAITING_APPROVAL, 1);
		assertEquals(TaskStatus.PENDING, task(workflows.get(run.id()), "architecture").status());

		workflows.approve(run.id(), "assumptions", 1, Decision.APPROVE,
				"Confirmed the documented assumptions", "test-admin");
		awaitTask(run.id(), "release-readiness", TaskStatus.WAITING_APPROVAL, 1);
		workflows.approve(run.id(), "release-readiness", 1, Decision.REJECT,
				"Do not proceed without stakeholder confirmation", "test-admin");
		awaitRun(run.id(), RunStatus.REJECTED);
		assertTrue(workflows.artifacts(run.id()).stream()
				.anyMatch(artifact -> artifact.taskKey().equals("release-readiness")
						&& artifact.status().name().equals("REJECTED")));
		assertFalse(workflows.events(run.id()).isEmpty());
	}

	@Test
	void brownfieldCanStopWithoutApplyingItsArtifacts() throws Exception {
		var run = workflows.create(Scenario.BROWNFIELD, "Improve analytics without breaking existing clients.");
		awaitTask(run.id(), "release-readiness", TaskStatus.WAITING_APPROVAL, 1);
		workflows.cancel(run.id(), "test-admin");
		awaitRun(run.id(), RunStatus.CANCELLED);
		assertTrue(workflows.artifacts(run.id()).stream()
				.filter(artifact -> artifact.status().name().equals("WITHDRAWN")).count() > 0);
		assertTrue(workflows.events(run.id()).stream().anyMatch(event -> event.type().equals("CANCELLATION_REQUESTED")));
	}

	@Test
	void healthEndpointIsAvailableWithoutAuthentication() throws Exception {
		mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"));
	}

	@Test
	void orchestrationEndpointsRequireTheAdminRole() throws Exception {
		mockMvc.perform(get("/api/orchestration/metrics"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(header().doesNotExist("WWW-Authenticate"));
		mockMvc.perform(get("/api/orchestration/metrics")
						.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_other"))))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/orchestration/metrics")
						.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_urlshortener.admin"))))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/orchestration/runs")
						.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_urlshortener.admin")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"scenario\":\"GREENFIELD\",\"requirement\":\"Add a feature\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.id").isNotEmpty());
	}

	@Test
	void taskFailureWithdrawsReviewableArtifactsAndRecordsRollback() throws Exception {
		var run = workflows.create(Scenario.GREENFIELD, "Exercise the persisted rollback path.");
		awaitTask(run.id(), "release-readiness", TaskStatus.WAITING_APPROVAL, 1);

		repository.failRun(run.id(), "tests", "Injected validation failure");

		awaitRun(run.id(), RunStatus.FAILED);
		assertTrue(workflows.artifacts(run.id()).stream()
				.filter(artifact -> artifact.status().name().equals("REVIEWABLE")).findAny().isEmpty());
		assertTrue(workflows.artifacts(run.id()).stream()
				.anyMatch(artifact -> artifact.status().name().equals("WITHDRAWN")));
		assertTrue(workflows.events(run.id()).stream().anyMatch(event -> event.type().equals("ARTIFACTS_WITHDRAWN")));
	}

	@Test
	void lateTaskOutputCannotBePublishedAfterCancellationIsRequested() {
		String runId = UUID.randomUUID().toString();
		repository.createRun(runId, Scenario.GREENFIELD, "A bounded test run", "No external changes",
				List.of(new TaskDefinition("task", "TEST", List.of(), false)));
		assertTrue(repository.beginTask(runId, "task"));
		assertTrue(repository.requestCancellation(runId, "test-admin"));
		int artifactCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM workflow_artifacts WHERE run_id = ?", Integer.class, runId);

		assertFalse(repository.saveOutput(runId, "task", new TaskOutput("test", "Late", "Result"),
				TaskStatus.SUCCEEDED));
		assertEquals(artifactCount, jdbc.queryForObject(
				"SELECT COUNT(*) FROM workflow_artifacts WHERE run_id = ?", Integer.class, runId));
		repository.markCancelled(runId);
	}

	private void awaitTask(String runId, String taskKey, TaskStatus status, int minimumVersion) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			var task = task(workflows.get(runId), taskKey);
			if (task.status() == status && task.artifactVersion() >= minimumVersion) {
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("Task " + taskKey + " did not reach " + status);
	}

	private void awaitRun(String runId, RunStatus status) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			if (workflows.get(runId).status() == status) {
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("Run did not reach " + status);
	}

	private static TaskView task(
			com.urlshortener.orchestration.WorkflowModels.RunView run, String taskKey) {
		return run.tasks().stream().filter(task -> task.key().equals(taskKey)).findFirst().orElseThrow();
	}
}
