/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package io.ballerina.stdlib.http.transport.http2.clienttimeout;

import io.ballerina.stdlib.http.transport.contract.Constants;
import io.ballerina.stdlib.http.transport.contract.HttpClientConnector;
import io.ballerina.stdlib.http.transport.contract.HttpResponseFuture;
import io.ballerina.stdlib.http.transport.contract.HttpWsConnectorFactory;
import io.ballerina.stdlib.http.transport.contract.ServerConnector;
import io.ballerina.stdlib.http.transport.contract.ServerConnectorFuture;
import io.ballerina.stdlib.http.transport.contract.config.ListenerConfiguration;
import io.ballerina.stdlib.http.transport.contract.exceptions.EndpointTimeOutException;
import io.ballerina.stdlib.http.transport.contractimpl.DefaultHttpWsConnectorFactory;
import io.ballerina.stdlib.http.transport.http2.listeners.Http2NoResponseListener;
import io.ballerina.stdlib.http.transport.message.HttpCarbonMessage;
import io.ballerina.stdlib.http.transport.util.DefaultHttpConnectorListener;
import io.ballerina.stdlib.http.transport.util.TestUtil;
import io.ballerina.stdlib.http.transport.util.client.http2.MessageGenerator;
import io.netty.handler.codec.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.AssertJUnit;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.ballerina.stdlib.http.transport.util.Http2Util.getHttp2Client;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * {@code TimeoutAfterRequestWrite} contains test cases for HTTP/2 client timeout after request write state.
 */
public class TimeoutAfterRequestWrite {

    private static final Logger LOG = LoggerFactory.getLogger(TimeoutAfterRequestWrite.class);

    private HttpClientConnector h2PriorOffClient;
    private HttpClientConnector h2PriorOnClient;
    private ServerConnector serverConnector;
    private HttpWsConnectorFactory connectorFactory;

    @BeforeClass
    public void setup() throws InterruptedException {
        connectorFactory = new DefaultHttpWsConnectorFactory();
        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        listenerConfiguration.setPort(TestUtil.HTTP_SERVER_PORT);
        listenerConfiguration.setScheme(Constants.HTTP_SCHEME);
        listenerConfiguration.setVersion(Constants.HTTP_2_0);
        //Set this to a value larger than client socket timeout value, to make sure that the client times out first
        listenerConfiguration.setSocketIdleTimeout(500000);
        serverConnector = connectorFactory
                .createServerConnector(TestUtil.getDefaultServerBootstrapConfig(), listenerConfiguration);
        ServerConnectorFuture future = serverConnector.start();
        Http2NoResponseListener http2ServerConnectorListener = new Http2NoResponseListener();
        future.setHttpConnectorListener(http2ServerConnectorListener);
        future.sync();

        h2PriorOnClient = getHttp2Client(connectorFactory, true, 3000);
        h2PriorOffClient = getHttp2Client(connectorFactory, false, 3000);
    }

    @Test
    public void testHttp2ClientTimeoutWithPriorOff() {
        testH2ClientTimeout(h2PriorOffClient);
    }

    @Test
    public void testHttp2ClientTimeoutWithPriorOn() {
        testH2ClientTimeout(h2PriorOnClient);
    }

    private void testH2ClientTimeout(HttpClientConnector h2Client) {
        HttpCarbonMessage request = MessageGenerator.generateRequest(HttpMethod.POST, "test");
        try {
            CountDownLatch latch = new CountDownLatch(1);
            DefaultHttpConnectorListener listener = new DefaultHttpConnectorListener(latch);
            HttpResponseFuture responseFuture = h2Client.send(request);
            responseFuture.setHttpConnectorListener(listener);
            latch.await(TestUtil.HTTP2_RESPONSE_TIME_OUT, TimeUnit.SECONDS);
            Throwable error = listener.getHttpErrorMessage();
            AssertJUnit.assertNotNull(error);
            assertTrue(error instanceof EndpointTimeOutException,
                       "Exception is not an instance of EndpointTimeOutException");
            String result = error.getMessage();
            assertEquals(result, Constants.IDLE_TIMEOUT_TRIGGERED_BEFORE_INITIATING_INBOUND_RESPONSE,
                         "Expected error message not received");
        } catch (Exception e) {
            TestUtil.handleException("Exception occurred while running testHttp2ClientTimeout test case", e);
        }
    }

    @AfterClass
    public void cleanUp() {
        h2PriorOffClient.close();
        h2PriorOnClient.close();
        serverConnector.stop();
        try {
            connectorFactory.shutdown();
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting for HttpWsFactory to close");
        }
    }
}
