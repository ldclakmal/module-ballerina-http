/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.net.transport.connectionpool;

import io.netty.handler.codec.http.HttpMethod;
import org.ballerinalang.net.transport.contract.HttpWsConnectorFactory;
import org.ballerinalang.net.transport.contract.ServerConnector;
import org.ballerinalang.net.transport.contract.ServerConnectorFuture;
import org.ballerinalang.net.transport.contract.config.ListenerConfiguration;
import org.ballerinalang.net.transport.contract.config.SenderConfiguration;
import org.ballerinalang.net.transport.contract.config.ServerBootstrapConfiguration;
import org.ballerinalang.net.transport.contract.exceptions.ServerConnectorException;
import org.ballerinalang.net.transport.contractimpl.DefaultHttpWsConnectorFactory;
import org.ballerinalang.net.transport.passthrough.PassthroughMessageProcessorListener;
import org.ballerinalang.net.transport.util.TestUtil;
import org.ballerinalang.net.transport.util.server.HttpServer;
import org.ballerinalang.net.transport.util.server.initializers.SendChannelIDServerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.testng.Assert.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;

/**
 * Tests for connection pool implementation.
 */
public class ConnectionPoolProxyTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionPoolProxyTestCase.class);

    private Future<String> requestTwoResponse;
    private ExecutorService executor = Executors.newFixedThreadPool(2);

    private HttpWsConnectorFactory httpWsConnectorFactory;
    private ServerConnector serverConnector;
    private HttpServer httpServer;

    @BeforeClass
    public void setup() {
        httpServer = TestUtil
                .startHTTPServer(TestUtil.HTTP_SERVER_PORT, new SendChannelIDServerInitializer(5000));

        httpWsConnectorFactory = new DefaultHttpWsConnectorFactory();
        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        listenerConfiguration.setPort(TestUtil.SERVER_CONNECTOR_PORT);
        serverConnector = httpWsConnectorFactory
                .createServerConnector(new ServerBootstrapConfiguration(new HashMap<>()), listenerConfiguration);
        ServerConnectorFuture serverConnectorFuture = serverConnector.start();
        serverConnectorFuture.setHttpConnectorListener(
                new PassthroughMessageProcessorListener(new SenderConfiguration(), true));
        try {
            serverConnectorFuture.sync();
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting for server connector to start");
        }
    }

    @Test
    public void testConnectionReuseForProxy() {
        try {
            Future<String> requestOneResponse;
            Future<String> requestThreeResponse;

            ClientWorker clientWorkerOne = new ClientWorker();
            ClientWorker clientWorkerTwo = new ClientWorker();
            ClientWorker clientWorkerThree = new ClientWorker();

            requestOneResponse = executor.submit(clientWorkerOne);

            // While the first request is being processed by the back-end,
            // we send the second request which forces the client connector to
            // create a new connection.
            Thread.sleep(2500);
            requestTwoResponse = executor.submit(clientWorkerTwo);
            assertNotNull(requestOneResponse.get());

            requestThreeResponse = executor.submit(clientWorkerThree);

            assertEquals(requestOneResponse.get(), requestThreeResponse.get());
        } catch (Exception e) {
            TestUtil.handleException("IOException occurred while running testConnectionReuseForProxy", e);
        }
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException {
        try {
            requestTwoResponse.get();
            serverConnector.stop();
            httpServer.shutdown();
            httpWsConnectorFactory.shutdown();
        } catch (Exception e) {
            LOG.warn("Interrupted while waiting for response two", e);
        }
    }

    private class ClientWorker implements Callable<String> {

        private String response;

        @Override
        public String call() throws Exception {
            try {
                URI baseURI = URI.create(String.format("http://%s:%d", "localhost", TestUtil.SERVER_CONNECTOR_PORT));
                HttpURLConnection urlConn = TestUtil
                        .request(baseURI, "/", HttpMethod.POST.name(), true);
                urlConn.getOutputStream().write(TestUtil.smallEntity.getBytes());
                response = TestUtil.getContent(urlConn);
            } catch (IOException e) {
                LOG.error("Couldn't get the response", e);
            }

            return response;
        }
    }
}
