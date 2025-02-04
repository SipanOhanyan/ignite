/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.common;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteCompute;
import org.apache.ignite.IgniteException;
import org.apache.ignite.Ignition;
import org.apache.ignite.client.ClientCompute;
import org.apache.ignite.client.Config;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobContext;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeJobResultPolicy;
import org.apache.ignite.compute.ComputeTask;
import org.apache.ignite.compute.ComputeTaskMapAsync;
import org.apache.ignite.compute.ComputeTaskTimeoutException;
import org.apache.ignite.configuration.ClientConfiguration;
import org.apache.ignite.configuration.ClientConnectorConfiguration;
import org.apache.ignite.configuration.ConnectorConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.ThinClientConfiguration;
import org.apache.ignite.events.Event;
import org.apache.ignite.events.JobEvent;
import org.apache.ignite.events.TaskEvent;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.client.GridClient;
import org.apache.ignite.internal.client.GridClientCompute;
import org.apache.ignite.internal.client.GridClientConfiguration;
import org.apache.ignite.internal.client.GridClientFactory;
import org.apache.ignite.internal.client.GridClientNode;
import org.apache.ignite.internal.client.thin.ClientServerError;
import org.apache.ignite.internal.processors.rest.GridRestCommand;
import org.apache.ignite.internal.processors.rest.protocols.http.jetty.GridJettyObjectMapper;
import org.apache.ignite.internal.processors.security.AbstractSecurityTest;
import org.apache.ignite.internal.processors.security.AbstractTestSecurityPluginProvider;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.plugin.security.SecurityCredentials;
import org.apache.ignite.plugin.security.SecurityCredentialsBasicProvider;
import org.apache.ignite.plugin.security.SecurityPermissionSet;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.apache.ignite.resources.JobContextResource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.apache.ignite.Ignition.allGrids;
import static org.apache.ignite.events.EventType.EVT_JOB_CANCELLED;
import static org.apache.ignite.events.EventType.EVT_JOB_FAILED;
import static org.apache.ignite.events.EventType.EVT_JOB_FINISHED;
import static org.apache.ignite.events.EventType.EVT_JOB_MAPPED;
import static org.apache.ignite.events.EventType.EVT_JOB_QUEUED;
import static org.apache.ignite.events.EventType.EVT_JOB_RESULTED;
import static org.apache.ignite.events.EventType.EVT_JOB_STARTED;
import static org.apache.ignite.events.EventType.EVT_TASK_FAILED;
import static org.apache.ignite.events.EventType.EVT_TASK_FINISHED;
import static org.apache.ignite.events.EventType.EVT_TASK_REDUCED;
import static org.apache.ignite.events.EventType.EVT_TASK_STARTED;
import static org.apache.ignite.events.EventType.EVT_TASK_TIMEDOUT;
import static org.apache.ignite.internal.IgniteNodeAttributes.ATTR_IGNITE_INSTANCE_NAME;
import static org.apache.ignite.testframework.GridTestUtils.waitForCondition;

/** Tests that compute tasks are executed with the security context of the task initiator. */
@RunWith(Parameterized.class)
public class ComputeTaskRemoteSecurityContextTest extends AbstractSecurityTest {
    /** Port for REST client connection. */
    private static final String DFLT_REST_PORT = "11080";

    /** Task timeout.*/
    private static final long TEST_TASK_TIMEOUT = 500;

    /** Custom object mapper for HTTP REST API.  */
    private static final ObjectMapper OBJECT_MAPPER = new GridJettyObjectMapper();

    /** Events paired with the nodes on which they were listened to. */
    private static final Map<ClusterNode, Collection<Event>> LISTENED_TASK_EVENTS = new HashMap<>();

    /** Indicates whether the test task can proceed with its execution. */
    private static CountDownLatch taskExecutionUnlockedLatch;

    /** All possible task events. */
    private static final int[] TASK_EVENTS = {
        EVT_TASK_STARTED, EVT_TASK_FINISHED, EVT_TASK_REDUCED, EVT_JOB_MAPPED, EVT_JOB_RESULTED, EVT_JOB_STARTED,
        EVT_JOB_FINISHED, EVT_JOB_QUEUED, EVT_TASK_TIMEDOUT, EVT_TASK_FAILED, EVT_JOB_CANCELLED, EVT_JOB_FAILED};

    /** Events that occur on the task reducer if task execution completes successfully. */
    private static final List<Integer> REDUCER_SUCCEEDED_TASK_EVENTS = Arrays.asList(
        EVT_JOB_QUEUED, EVT_JOB_STARTED, EVT_JOB_FINISHED, EVT_TASK_STARTED, EVT_JOB_MAPPED, EVT_TASK_REDUCED,
        EVT_JOB_RESULTED, EVT_TASK_FINISHED);

    /** Events that occur on task map node if task execution fails. */
    private static final List<Integer> REDUCER_FAILED_TASK_EVENTS = Arrays.asList(
        EVT_JOB_QUEUED, EVT_JOB_STARTED, EVT_JOB_CANCELLED, EVT_JOB_FAILED, EVT_TASK_STARTED, EVT_JOB_MAPPED,
        EVT_TASK_TIMEDOUT, EVT_TASK_FAILED);

    /** Events that occur on task reducer if task execution completes successfully. */
    private static final List<Integer> MAP_NODE_SUCCEEDED_TASK_EVENTS = Arrays.asList(
        EVT_JOB_QUEUED, EVT_JOB_STARTED, EVT_JOB_FINISHED);

    /** Events that occur on task map node if task execution fails. */
    private static final List<Integer> MAP_NODE_FAILED_TASK_EVENTS = Arrays.asList(
        EVT_JOB_QUEUED, EVT_JOB_STARTED, EVT_JOB_CANCELLED, EVT_JOB_FAILED);

    /** */
    @Parameterized.Parameters(name = "async={0} failWithTimeout={1} mapAsync={2}")
    public static Iterable<Boolean[]> data() {
        List<Boolean[]> res = new ArrayList<>();

        for (Boolean async : Arrays.asList(false, true)) {
            for (Boolean failWithTimeout : Arrays.asList(false, true)) {
                for (Boolean mapAsync : Arrays.asList(false, true))
                    res.add(new Boolean[] {async, failWithTimeout, mapAsync});
            }
        }

        return res;
    }

    /** Whether task is executed asynchronously. */
    @Parameterized.Parameter()
    public boolean async;

    /** Whether task fails with timeout exception. */
    @Parameterized.Parameter(1)
    public boolean failWithTimeout;

    /** Whether task mapping is processed asynchronously. */
    @Parameterized.Parameter(2)
    public boolean mapAsync;


    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        LISTENED_TASK_EVENTS.put(startGridAllowAll("crd").localNode(), ConcurrentHashMap.newKeySet());
        LISTENED_TASK_EVENTS.put(startGridAllowAll("srv").localNode(), ConcurrentHashMap.newKeySet());
        LISTENED_TASK_EVENTS.put(startGridAllowAll("cli").localNode(), ConcurrentHashMap.newKeySet());
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        if (taskExecutionUnlockedLatch != null)
            taskExecutionUnlockedLatch.countDown();

        super.afterTest();
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        LISTENED_TASK_EVENTS.values().forEach(Collection::clear);

        taskExecutionUnlockedLatch = null;

        if (failWithTimeout)
            taskExecutionUnlockedLatch = new CountDownLatch(1);
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(
        String instanceName,
        AbstractTestSecurityPluginProvider pluginProv
    ) throws Exception {
        return super.getConfiguration(instanceName, pluginProv)
            .setLocalHost("127.0.0.1")
            .setIncludeEventTypes(TASK_EVENTS)
            .setConnectorConfiguration(new ConnectorConfiguration()
                .setJettyPath("modules/clients/src/test/resources/jetty/rest-jetty.xml"))
            .setClientConnectorConfiguration(new ClientConnectorConfiguration()
                .setThinClientConfiguration(new ThinClientConfiguration()
                    .setMaxActiveComputeTasksPerConnection(1)));
    }

    /** {@inheritDoc} */
    @Override protected IgniteEx startGrid(String login, SecurityPermissionSet prmSet, boolean isClient) throws Exception {
        IgniteEx ignite = startGrid(login, prmSet, null, isClient);

        ignite.events().localListen(evt -> {
            LISTENED_TASK_EVENTS.get(ignite.localNode()).add(evt);

            return true;
        }, TASK_EVENTS);

        return ignite;
    }

    /** Tests task execution security context in case task was initiated from the {@link GridClient}. */
    @Test
    public void testGridClient() throws Exception {
        Assume.assumeFalse(failWithTimeout);

        String login = "grid_client";

        GridClientConfiguration cfg = new GridClientConfiguration()
            .setServers(singletonList("127.0.0.1:11211"))
            .setSecurityCredentialsProvider(new SecurityCredentialsBasicProvider(new SecurityCredentials(login, "")));

        try (GridClient cli = GridClientFactory.start(cfg)) {
            GridClientNode taskReqRecipient = cli.compute().nodes().stream()
                .filter(n -> "crd".equals(n.attribute(ATTR_IGNITE_INSTANCE_NAME)))
                .findFirst().orElseThrow(NoSuchElementException::new);

            GridClientCompute comp = cli.compute().projection(taskReqRecipient);

            String taskName = mapAsync ? MapAsyncTestTask.class.getName() : TestTask.class.getName();

            if (async)
                comp.executeAsync(taskName, login).get();
            else
                comp.execute(taskName, login);

            checkTaskEvents("crd", login, REDUCER_SUCCEEDED_TASK_EVENTS, MAP_NODE_SUCCEEDED_TASK_EVENTS);
        }
    }

    /** Tests task execution security context in case task was initiated from the {@link IgniteClient}. */
    @Test
    public void testIgniteClient() throws Exception {
        String login = "thin_client";

        ClientConfiguration cfg = new ClientConfiguration()
            .setAddresses(Config.SERVER)
            .setUserName(login)
            .setUserPassword("");

        try (IgniteClient cli = Ignition.startClient(cfg)) {
            ClientCompute comp = cli.compute(cli.cluster().forNodes(cli.cluster().nodes()));

            if (failWithTimeout)
                comp = comp.withTimeout(TEST_TASK_TIMEOUT);

            String taskName = mapAsync ? MapAsyncTestTask.class.getName() : TestTask.class.getName();

            Throwable timeoutE = null;

            try {
                if (async)
                    comp.executeAsync2(taskName, login).get();
                else
                    comp.execute(taskName, login);

                checkTaskEvents("crd", login, REDUCER_SUCCEEDED_TASK_EVENTS, MAP_NODE_SUCCEEDED_TASK_EVENTS);
            }
            catch (Throwable e) {
                if (!failWithTimeout)
                    throw e;

                timeoutE = e;
            }

            if (failWithTimeout) {
                assertNotNull(timeoutE);
                assertTrue(X.hasCause(timeoutE, "Task timed out", ClientServerError.class));

                checkTaskEvents("crd", login, REDUCER_FAILED_TASK_EVENTS, MAP_NODE_FAILED_TASK_EVENTS);
            }
        }
    }

    /** Tests task execution security context in case task was initiated from the REST client. */
    @Test
    public void testRestClient() throws Exception {
        String login = "rest_client";

        String taskName = mapAsync ? MapAsyncTestTask.class.getName() : TestTask.class.getName();

        JsonNode resp = sendRestRequest("http://127.0.0.1:" + DFLT_REST_PORT + "/ignite" +
            "?ignite.login=" + login +
            "&ignite.password=" +
            "&cmd=" + GridRestCommand.EXE.key() +
            "&name=" + taskName +
            "&p1=" + login +
            "&async=" + async +
            "&timeout=" + (failWithTimeout ? TEST_TASK_TIMEOUT : 0));

        if (async) {
            String taskId = resp.get("response").get("id").textValue();

            U.sleep(2 * TEST_TASK_TIMEOUT);

            resp = sendRestRequest("http://127.0.0.1:" + DFLT_REST_PORT + "/ignite" +
                "?ignite.login=" + login +
                "&ignite.password=" +
                "&id=" + taskId +
                "&cmd=" + GridRestCommand.RESULT.key());
        }

        if (failWithTimeout) {
            assertEquals(1, resp.get("successStatus").intValue());
            assertTrue(resp.get("error").textValue().contains("Task timed out"));

            checkTaskEvents("crd", login, REDUCER_FAILED_TASK_EVENTS, MAP_NODE_FAILED_TASK_EVENTS);
        }
        else {
            assertEquals(0, resp.get("successStatus").intValue());
            assertNull(resp.get("error").textValue());

            JsonNode taskRes = resp.get("response");

            assertTrue(taskRes.get("finished").asBoolean());
            assertNull(taskRes.get("error").textValue());

            checkTaskEvents("crd", login, REDUCER_SUCCEEDED_TASK_EVENTS, MAP_NODE_SUCCEEDED_TASK_EVENTS);
        }
    }

    /** Tests task execution security context in case task was initiated from the {@link Ignite} server node. */
    @Test
    public void testServerNode() throws Exception {
        doNodeTest(false);
    }


    /** Tests task execution security context in case task was initiated from the {@link Ignite} client node. */
    @Test
    public void testClientNode() throws Exception {
        doNodeTest(true);
    }

    /** */
    public void doNodeTest(boolean isClient) throws Exception {
        String login = isClient ? "cli" : "srv";

        IgniteEx ignite = grid(login);

        IgniteCompute compute = ignite.compute(ignite.cluster().forNodes(ignite.cluster().nodes()));

        if (failWithTimeout)
            compute.withTimeout(TEST_TASK_TIMEOUT);

        String taskName = mapAsync ? MapAsyncTestTask.class.getName() : TestTask.class.getName();

        ComputeTaskTimeoutException timeoutE = null;

        try {
            if (async)
                compute.executeAsync(taskName, login).get();
            else
                compute.execute(taskName, login);

            checkTaskEvents(login, login, REDUCER_SUCCEEDED_TASK_EVENTS, MAP_NODE_SUCCEEDED_TASK_EVENTS);
        }
        catch (ComputeTaskTimeoutException e) {
            if (!failWithTimeout)
                throw e;

            timeoutE = e;
        }

        if (failWithTimeout) {
            assertNotNull(timeoutE);

            checkTaskEvents(login, login, REDUCER_FAILED_TASK_EVENTS, MAP_NODE_FAILED_TASK_EVENTS);
        }
    }

    /** */
    private void checkTaskEvents(
        String taskReducerNode,
        String taskInitiatorLogin,
        List<Integer> expReducerTaskEvts,
        List<Integer> expMapNodeTaskEvts
    ) throws Exception {
        for (Ignite ignite : allGrids()) {
            checkEvents(((IgniteEx)ignite).localNode(), taskInitiatorLogin, ignite.name().equals(taskReducerNode)
                ? expReducerTaskEvts : expMapNodeTaskEvts);
        }
    }

    /** */
    private void checkEvents(ClusterNode node, String expLogin, List<Integer> expEvtTypes) throws Exception {
        Collection<Event> nodeTaskEvents = LISTENED_TASK_EVENTS.get(node);

        assertTrue(waitForCondition(() ->
            nodeTaskEvents.stream()
                .map(Event::type)
                .collect(Collectors.toList())
                .containsAll(expEvtTypes), getTestTimeout()));

        assertTrue(nodeTaskEvents.stream()
            .map(evt -> evt instanceof TaskEvent ? ((TaskEvent)evt).subjectId() : ((JobEvent)evt).taskSubjectId())
            .map(subjId -> {
                try {
                    return grid("crd").context().security().authenticatedSubject(subjId).login();
                }
                catch (IgniteCheckedException e) {
                    throw new SecurityException(e);
                }
            })
            .allMatch(expLogin::equals));
    }

    /** */
    static JsonNode sendRestRequest(String url) throws IOException {
        URLConnection conn = new URL(url).openConnection();

        StringBuilder buf = new StringBuilder(256);

        try (LineNumberReader rdr = new LineNumberReader(new InputStreamReader(conn.getInputStream(), UTF_8))) {
            for (String line = rdr.readLine(); line != null; line = rdr.readLine())
                buf.append(line);
        }

        return OBJECT_MAPPER.readTree(buf.toString());
    }

    /** */
    @ComputeTaskMapAsync
    public static class MapAsyncTestTask extends TestTask {
        // No-op.
    }

    /** Test compute task that checks security context of the task initiator. */
    public static class TestTask implements ComputeTask<String, Void> {
        /** {@inheritDoc} */
        @Override public @NotNull Map<? extends ComputeJob, ClusterNode> map(
            List<ClusterNode> subgrid,
            @Nullable String taskInitiatorLogin
        ) throws IgniteException {
            Map<ComputeJob, ClusterNode> res = new HashMap<>();

            for (ClusterNode node : subgrid) {
                res.put(new ComputeJob() {
                    /** */
                    @IgniteInstanceResource
                    private IgniteEx ignite;

                    /** */
                    @JobContextResource
                    private ComputeJobContext jobCtx;

                    /** */
                    private boolean suspended;

                    @Override public void cancel() {
                        // No-op.
                    }

                    @Override public Object execute() throws IgniteException {
                        assertEquals(taskInitiatorLogin, ignite.context().security().securityContext().subject().login());

                        if (taskExecutionUnlockedLatch != null) {
                            try {
                                taskExecutionUnlockedLatch.await();
                            }
                            catch (InterruptedException e) {
                                throw new IgniteException();
                            }

                            return null;
                        }

                        if (!suspended) {
                            jobCtx.holdcc();

                            suspended = true;

                            new Thread(() -> jobCtx.callcc()).start();

                            return null;
                        }

                        return null;
                    }
                }, node);
            }

            return res;
        }

        /** {@inheritDoc} */
        @Override public ComputeJobResultPolicy result(ComputeJobResult res, List<ComputeJobResult> rcvd) {
            if (res.getException() != null)
                throw new IgniteException(res.getException());

            return ComputeJobResultPolicy.WAIT;
        }

        /** {@inheritDoc} */
        @Override public @Nullable Void reduce(List<ComputeJobResult> results) throws IgniteException {
            assertEquals(allGrids().size(), results.size());

            return null;
        }
    }
}
