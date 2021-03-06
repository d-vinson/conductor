/*
 * Copyright 2016 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.tests.integration;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.netflix.conductor.bootstrap.BootstrapModule;
import com.netflix.conductor.bootstrap.ModulesProvider;
import com.netflix.conductor.client.exceptions.ConductorClientException;
import com.netflix.conductor.client.http.MetadataClient;
import com.netflix.conductor.client.http.TaskClient;
import com.netflix.conductor.client.http.WorkflowClient;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.Task.Status;
import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import com.netflix.conductor.common.metadata.workflow.TaskType;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowTask;
import com.netflix.conductor.common.run.SearchResult;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.run.Workflow.WorkflowStatus;
import com.netflix.conductor.common.run.WorkflowSummary;
import com.netflix.conductor.elasticsearch.ElasticSearchConfiguration;
import com.netflix.conductor.elasticsearch.EmbeddedElasticSearch;
import com.netflix.conductor.elasticsearch.EmbeddedElasticSearchProvider;
import com.netflix.conductor.jetty.server.JettyServer;
import com.netflix.conductor.tests.utils.TestEnvironment;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Viren
 */

public class End2EndTests extends AbstractEndToEndTest {

    private static TaskClient taskClient;
    private static WorkflowClient workflowClient;
    private static EmbeddedElasticSearch search;
    private static MetadataClient metadataClient;

    private static final int SERVER_PORT = 8080;

    @BeforeClass
    public static void setup() throws Exception {
        TestEnvironment.setup();
        System.setProperty(ElasticSearchConfiguration.EMBEDDED_PORT_PROPERTY_NAME, "9201");
        System.setProperty(ElasticSearchConfiguration.ELASTIC_SEARCH_URL_PROPERTY_NAME, "localhost:9301");

        Injector bootInjector = Guice.createInjector(new BootstrapModule());
        Injector serverInjector = Guice.createInjector(bootInjector.getInstance(ModulesProvider.class).get());

        search = serverInjector.getInstance(EmbeddedElasticSearchProvider.class).get().get();
        search.start();

        JettyServer server = new JettyServer(SERVER_PORT, false);
        server.start();

        taskClient = new TaskClient();
        taskClient.setRootURI("http://localhost:8080/api/");

        workflowClient = new WorkflowClient();
        workflowClient.setRootURI("http://localhost:8080/api/");

        metadataClient = new MetadataClient();
        metadataClient.setRootURI("http://localhost:8080/api/");
    }

    @AfterClass
    public static void teardown() throws Exception {
        TestEnvironment.teardown();
        search.stop();
    }

    @Override
    protected String startWorkflow(String workflowExecutionName, WorkflowDef workflowDefinition) {
        StartWorkflowRequest workflowRequest = new StartWorkflowRequest()
                .withName(workflowExecutionName)
                .withWorkflowDef(workflowDefinition);

        return workflowClient.startWorkflow(workflowRequest);
    }

    @Override
    protected Workflow getWorkflow(String workflowId, boolean includeTasks) {
        return workflowClient.getWorkflow(workflowId, includeTasks);
    }

    @Override
    protected TaskDef getTaskDefinition(String taskName) {
        return metadataClient.getTaskDef(taskName);
    }

    @Override
    protected void registerTaskDefinitions(List<TaskDef> taskDefinitionList) {
        metadataClient.registerTaskDefs(taskDefinitionList);
    }

    @Test
    public void testAll() throws Exception {
        createAndRegisterTaskDefinitions("t", 5);

        WorkflowDef def = new WorkflowDef();
        def.setName("test");
        WorkflowTask t0 = new WorkflowTask();
        t0.setName("t0");
        t0.setWorkflowTaskType(TaskType.SIMPLE);
        t0.setTaskReferenceName("t0");

        WorkflowTask t1 = new WorkflowTask();
        t1.setName("t1");
        t1.setWorkflowTaskType(TaskType.SIMPLE);
        t1.setTaskReferenceName("t1");

        def.getTasks().add(t0);
        def.getTasks().add(t1);

        metadataClient.registerWorkflowDef(def);
        WorkflowDef workflowDefinitionFromSystem = metadataClient.getWorkflowDef(def.getName(), null);
        assertNotNull(workflowDefinitionFromSystem);
        assertEquals(def, workflowDefinitionFromSystem);

        String correlationId = "test_corr_id";
        StartWorkflowRequest startWorkflowRequest = new StartWorkflowRequest()
                .withName(def.getName())
                .withCorrelationId(correlationId)
                .withInput(new HashMap<>());
        String workflowId = workflowClient.startWorkflow(startWorkflowRequest);
        assertNotNull(workflowId);

        Workflow workflow = workflowClient.getWorkflow(workflowId, false);
        assertEquals(0, workflow.getTasks().size());
        assertEquals(workflowId, workflow.getWorkflowId());

        List<Workflow> workflowList = workflowClient.getWorkflows(def.getName(), correlationId, false, false);
        assertEquals(1, workflowList.size());
        assertEquals(workflowId, workflowList.get(0).getWorkflowId());

        workflow = workflowClient.getWorkflow(workflowId, true);
        assertNotNull(workflow);
        assertEquals(WorkflowStatus.RUNNING, workflow.getStatus());
        assertEquals(1, workflow.getTasks().size());
        assertEquals(t0.getTaskReferenceName(), workflow.getTasks().get(0).getReferenceTaskName());
        assertEquals(workflowId, workflow.getWorkflowId());

        int queueSize = taskClient.getQueueSizeForTask(workflow.getTasks().get(0).getTaskType());
        assertEquals(1, queueSize);

        List<String> runningIds = workflowClient.getRunningWorkflow(def.getName(), def.getVersion());
        assertNotNull(runningIds);
        assertEquals(1, runningIds.size());
        assertEquals(workflowId, runningIds.get(0));

        List<Task> polled = taskClient.batchPollTasksByTaskType("non existing task", "test", 1, 100);
        assertNotNull(polled);
        assertEquals(0, polled.size());

        polled = taskClient.batchPollTasksByTaskType(t0.getName(), "test", 1, 100);
        assertNotNull(polled);
        assertEquals(1, polled.size());
        assertEquals(t0.getName(), polled.get(0).getTaskDefName());
        Task task = polled.get(0);

        Boolean acked = taskClient.ack(task.getTaskId(), "test");
        assertNotNull(acked);
        assertTrue(acked);

        task.getOutputData().put("key1", "value1");
        task.setStatus(Status.COMPLETED);
        taskClient.updateTask(new TaskResult(task), task.getTaskType());

        polled = taskClient.batchPollTasksByTaskType(t0.getName(), "test", 1, 100);
        assertNotNull(polled);
        assertTrue(polled.toString(), polled.isEmpty());

        workflow = workflowClient.getWorkflow(workflowId, true);
        assertNotNull(workflow);
        assertEquals(WorkflowStatus.RUNNING, workflow.getStatus());
        assertEquals(2, workflow.getTasks().size());
        assertEquals(t0.getTaskReferenceName(), workflow.getTasks().get(0).getReferenceTaskName());
        assertEquals(t1.getTaskReferenceName(), workflow.getTasks().get(1).getReferenceTaskName());
        assertEquals(Task.Status.COMPLETED, workflow.getTasks().get(0).getStatus());
        assertEquals(Task.Status.SCHEDULED, workflow.getTasks().get(1).getStatus());

        Task taskById = taskClient.getTaskDetails(task.getTaskId());
        assertNotNull(taskById);
        assertEquals(task.getTaskId(), taskById.getTaskId());

        queueSize = taskClient.getQueueSizeForTask(workflow.getTasks().get(1).getTaskType());
        assertEquals(1, queueSize);

        List<Task> getTasks = taskClient.getPendingTasksByType(t0.getName(), null, 1);
        assertNotNull(getTasks);
        assertEquals(0, getTasks.size());        //getTasks only gives pending tasks

        getTasks = taskClient.getPendingTasksByType(t1.getName(), null, 1);
        assertNotNull(getTasks);
        assertEquals(1, getTasks.size());

        Task pending = taskClient.getPendingTaskForWorkflow(workflowId, t1.getTaskReferenceName());
        assertNotNull(pending);
        assertEquals(t1.getTaskReferenceName(), pending.getReferenceTaskName());
        assertEquals(workflowId, pending.getWorkflowInstanceId());

        Thread.sleep(1000);
        SearchResult<WorkflowSummary> searchResult = workflowClient.search("workflowType='" + def.getName() + "'");
        assertNotNull(searchResult);
        assertEquals(1, searchResult.getTotalHits());

        workflowClient.terminateWorkflow(workflowId, "terminate reason");
        workflow = workflowClient.getWorkflow(workflowId, true);
        assertNotNull(workflow);
        assertEquals(WorkflowStatus.TERMINATED, workflow.getStatus());

        workflowClient.restart(workflowId);
        workflow = workflowClient.getWorkflow(workflowId, true);
        assertNotNull(workflow);
        assertEquals(WorkflowStatus.RUNNING, workflow.getStatus());
        assertEquals(1, workflow.getTasks().size());
    }

    @Test
    public void testMetadataWorkflowDefinition() {
        String workflowDefName = "testWorkflowDefMetadata";
        WorkflowDef def = new WorkflowDef();
        def.setName(workflowDefName);
        def.setVersion(1);
        WorkflowTask t0 = new WorkflowTask();
        t0.setName("t0");
        t0.setWorkflowTaskType(TaskType.SIMPLE);
        t0.setTaskReferenceName("t0");
        WorkflowTask t1 = new WorkflowTask();
        t1.setName("t1");
        t1.setWorkflowTaskType(TaskType.SIMPLE);
        t1.setTaskReferenceName("t1");
        def.getTasks().add(t0);
        def.getTasks().add(t1);
        metadataClient.registerWorkflowDef(def);
        try {
            metadataClient.getWorkflowDef(workflowDefName, 1);
        } catch (ConductorClientException e) {
            int statusCode = e.getStatus();
            String errorMessage = e.getMessage();
            boolean retryable = e.isRetryable();
            assertEquals(404, statusCode);
            assertEquals("No such workflow found by name: testWorkflowDefMetadata, version: 1", errorMessage);
            assertFalse(retryable);
        }
        metadataClient.unregisterWorkflowDef(workflowDefName, 1);
    }

    @Test
    public void testInvalidResource() {
        MetadataClient metadataClient = new MetadataClient();
        metadataClient.setRootURI("http://localhost:8080/api/invalid");
        WorkflowDef def = new WorkflowDef();
        def.setName("testWorkflowDel");
        def.setVersion(1);
        try {
            metadataClient.registerWorkflowDef(def);
        } catch (ConductorClientException e) {
            int statusCode = e.getStatus();
            boolean retryable = e.isRetryable();
            assertEquals(404, statusCode);
            assertFalse(retryable);
        }
    }

    @Test
    public void testUpdateWorkflow() {
        WorkflowDef def = new WorkflowDef();
        def.setName("testWorkflowDel");
        def.setVersion(1);
        metadataClient.registerWorkflowDef(def);
        def.setVersion(2);
        List<WorkflowDef> workflowList = new ArrayList<>();
        workflowList.add(def);
        metadataClient.updateWorkflowDefs(workflowList);
        WorkflowDef def1 = metadataClient.getWorkflowDef(def.getName(), 2);
        assertNotNull(def1);
        try {
            metadataClient.getTaskDef("test");
        } catch (ConductorClientException e) {
            int statuCode = e.getStatus();
            assertEquals(404, statuCode);
            assertEquals("No such taskType found by name: test", e.getMessage());
            assertFalse(e.isRetryable());
        }
    }

    @Test
    public void testStartWorkflow() {
        StartWorkflowRequest startWorkflowRequest = new StartWorkflowRequest();
        try {
            workflowClient.startWorkflow(startWorkflowRequest);
        } catch (IllegalArgumentException e) {
            assertEquals("Workflow name cannot be null or empty", e.getMessage());
        }
    }

    @Test
    public void testUpdateTask() {
        TaskResult taskResult = new TaskResult();
        try {
            taskClient.updateTask(taskResult, "taskTest");
        } catch (ConductorClientException e) {
            int statuCode = e.getStatus();
            assertEquals(400, statuCode);
            assertEquals("Workflow Id cannot be null or empty", e.getMessage());
            assertFalse(e.isRetryable());
        }
    }

    @Test
    public void testGetWorfklowNotFound() {
        try {
            workflowClient.getWorkflow("w123", true);
        } catch (ConductorClientException e) {
            assertEquals(404, e.getStatus());
            assertEquals("No such workflow found by id: w123", e.getMessage());
            assertFalse(e.isRetryable());
        }
    }

    @Test
    public void testEmptyCreateWorkflowDef() {
        try {
            WorkflowDef workflowDef = new WorkflowDef();
            metadataClient.registerWorkflowDef(workflowDef);
        } catch (ConductorClientException e) {
            assertEquals(400, e.getStatus());
            assertEquals("Workflow name cannot be null or empty", e.getMessage());
            assertFalse(e.isRetryable());
        }
    }

    @Test
    public void testUpdateWorkflowDef() {
        try {
            WorkflowDef workflowDef = new WorkflowDef();
            List<WorkflowDef> workflowDefList = new ArrayList<>();
            workflowDefList.add(workflowDef);
            metadataClient.updateWorkflowDefs(workflowDefList);
        } catch (ConductorClientException e) {
            assertEquals(400, e.getStatus());
            assertEquals("WorkflowDef name cannot be null", e.getMessage());
            assertFalse(e.isRetryable());
        }
    }

    @Test
    public void testGetTaskInProgress() {
        taskClient.getPendingTaskForWorkflow("test", "t1");
    }

    @Test
    public void testRemoveTaskFromTaskQueue() {
        try {
            taskClient.removeTaskFromQueue("test", "fakeQueue");
        } catch (ConductorClientException e) {
            assertEquals(404, e.getStatus());
        }
    }

    @Test
    public void testTaskByTaskId() {
        try {
            taskClient.getTaskDetails("test123");
        } catch (ConductorClientException e) {
            assertEquals(404, e.getStatus());
            assertEquals("No such task found by taskId: test123", e.getMessage());
        }
    }

    @Test
    public void testListworkflowsByCorrelationId() {
        workflowClient.getWorkflows("test", "test12", false, false);
    }
}
