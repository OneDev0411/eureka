/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.shared.transport.decorator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.netflix.discovery.shared.resolver.ClusterResolver;
import com.netflix.discovery.shared.resolver.EurekaEndpoint;
import com.netflix.discovery.shared.resolver.SampleCluster;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpClientFactory;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.TransportException;
import com.netflix.discovery.shared.transport.decorator.EurekaHttpClientDecorator.RequestExecutor;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Tomasz Bak
 */
public class RetryableEurekaHttpClientTest {

    private static final int NUMBER_OF_RETRIES = 2;
    private static final int CLUSTER_SIZE = 3;

    private static final List<EurekaEndpoint> CLUSTER_ENDPOINTS = SampleCluster.UsEast1a.builder().withServerPool(CLUSTER_SIZE).build();

    private final ClusterResolver clusterResolver = mock(ClusterResolver.class);
    private final EurekaHttpClientFactory clientFactory = mock(EurekaHttpClientFactory.class);
    private final ServerStatusEvaluator serverStatusEvaluator = mock(ServerStatusEvaluator.class);
    private final RequestExecutor<Void> requestExecutor = mock(RequestExecutor.class);

    private RetryableEurekaHttpClient retryableClient;

    private List<EurekaHttpClient> clusterDelegates;

    @Before
    public void setUp() throws Exception {
        retryableClient = new RetryableEurekaHttpClient(clusterResolver, clientFactory, serverStatusEvaluator, NUMBER_OF_RETRIES);

        clusterDelegates = new ArrayList<>(CLUSTER_SIZE);
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            clusterDelegates.add(mock(EurekaHttpClient.class));
        }

        when(clusterResolver.getClusterEndpoints()).thenReturn(CLUSTER_ENDPOINTS);
    }

    @Test
    public void testRequestsReuseSameConnectionIfThereIsNoError() throws Exception {
        when(clientFactory.create(Matchers.<String>anyVararg())).thenReturn(clusterDelegates.get(0));
        when(requestExecutor.execute(clusterDelegates.get(0))).thenReturn(EurekaHttpResponse.<Void>responseWith(200));

        // First request creates delegate, second reuses it
        for (int i = 0; i < 3; i++) {
            EurekaHttpResponse<Void> httpResponse = retryableClient.execute(requestExecutor);
            assertThat(httpResponse.getStatusCode(), is(equalTo(200)));
        }

        verify(clientFactory, times(1)).create(Matchers.<String>anyVararg());
        verify(requestExecutor, times(3)).execute(clusterDelegates.get(0));
    }

    @Test
    public void testRequestIsRetriedOnConnectionError() throws Exception {
        when(clientFactory.create(Matchers.<String>anyVararg())).thenReturn(clusterDelegates.get(0), clusterDelegates.get(1));
        when(requestExecutor.execute(clusterDelegates.get(0))).thenThrow(new TransportException("simulated network error"));
        when(requestExecutor.execute(clusterDelegates.get(1))).thenReturn(EurekaHttpResponse.<Void>responseWith(200));

        EurekaHttpResponse<Void> httpResponse = retryableClient.execute(requestExecutor);
        assertThat(httpResponse.getStatusCode(), is(equalTo(200)));

        verify(clientFactory, times(2)).create(Matchers.<String>anyVararg());
        verify(requestExecutor, times(1)).execute(clusterDelegates.get(0));
        verify(requestExecutor, times(1)).execute(clusterDelegates.get(1));
    }

    @Test(expected = TransportException.class)
    public void testErrorResponseIsReturnedIfRetryLimitIsReached() throws Exception {
        simulateTransportError(0, NUMBER_OF_RETRIES + 1);
        retryableClient.execute(requestExecutor);
    }

    @Test
    public void testQuarantineListIsResetWhenNoMoreServerAreAvailable() throws Exception {
        // First two call fail
        simulateTransportError(0, CLUSTER_SIZE);
        for (int i = 0; i < 2; i++) {
            executeWithTransportErrorExpectation();
        }

        // Second call, should reset cluster quarantine list, and hit health node 0
        when(clientFactory.create(Matchers.<String>anyVararg())).thenReturn(clusterDelegates.get(0));
        reset(requestExecutor);
        when(requestExecutor.execute(clusterDelegates.get(0))).thenReturn(EurekaHttpResponse.<Void>responseWith(200));

        retryableClient.execute(requestExecutor);
    }

    @Test
    public void test5xxWithAbandonEvaluationIsRespected() throws Exception {
        when(serverStatusEvaluator.abandon(500)).thenReturn(true);
        when(clientFactory.create(Matchers.<String>anyVararg())).thenReturn(clusterDelegates.get(0), clusterDelegates.get(1));
        when(requestExecutor.execute(clusterDelegates.get(0))).thenReturn(EurekaHttpResponse.<Void>responseWith(500));
        when(requestExecutor.execute(clusterDelegates.get(1))).thenReturn(EurekaHttpResponse.<Void>responseWith(200));

        EurekaHttpResponse<Void> httpResponse = retryableClient.execute(requestExecutor);
        assertThat(httpResponse.getStatusCode(), is(equalTo(200)));

        verify(requestExecutor, times(1)).execute(clusterDelegates.get(0));
        verify(requestExecutor, times(1)).execute(clusterDelegates.get(1));
    }

    @Test
    public void test5xxWithNotAbandonEvaluationIsRespected() throws Exception {
        when(serverStatusEvaluator.abandon(500)).thenReturn(false);
        when(clientFactory.create(Matchers.<String>anyVararg())).thenReturn(clusterDelegates.get(0), clusterDelegates.get(1));
        when(requestExecutor.execute(clusterDelegates.get(0))).thenReturn(EurekaHttpResponse.<Void>responseWith(500));
        when(requestExecutor.execute(clusterDelegates.get(1))).thenReturn(EurekaHttpResponse.<Void>responseWith(200));

        EurekaHttpResponse<Void> httpResponse = retryableClient.execute(requestExecutor);
        assertThat(httpResponse.getStatusCode(), is(equalTo(500)));

        verify(requestExecutor, times(1)).execute(clusterDelegates.get(0));
        verify(requestExecutor, times(0)).execute(clusterDelegates.get(1));
    }

//    @Test(timeout = 10000)
    @Test
    public void testConcurrentRequestsLeaveLastSuccessfulDelegate() throws Exception {
        when(clientFactory.create(Matchers.<String>anyVararg())).thenReturn(clusterDelegates.get(0), clusterDelegates.get(1));

        BlockingRequestExecutor executor0 = new BlockingRequestExecutor();
        BlockingRequestExecutor executor1 = new BlockingRequestExecutor();

        Thread thread0 = new Thread(new RequestExecutorRunner(executor0));
        Thread thread1 = new Thread(new RequestExecutorRunner(executor1));

        // Run parallel requests
        thread0.start();
        executor0.awaitReady();

        thread1.start();
        executor1.awaitReady();

        // Complete request, first thread first, second afterwards
        executor0.complete();
        thread0.join();
        executor1.complete();
        thread1.join();

        // Verify subsequent request done on delegate1
        when(requestExecutor.execute(clusterDelegates.get(1))).thenReturn(EurekaHttpResponse.<Void>responseWith(200));

        EurekaHttpResponse<Void> httpResponse = retryableClient.execute(requestExecutor);
        assertThat(httpResponse.getStatusCode(), is(equalTo(200)));

        verify(clientFactory, times(2)).create(Matchers.<String>anyVararg());
        verify(requestExecutor, times(0)).execute(clusterDelegates.get(0));
        verify(requestExecutor, times(1)).execute(clusterDelegates.get(1));
    }

    private void simulateTransportError(int delegateFrom, int count) {
        for (int i = 0; i < count; i++) {
            int delegateId = delegateFrom + i;
            when(clientFactory.create(Matchers.<String>anyVararg())).thenReturn(clusterDelegates.get(delegateId));
            when(requestExecutor.execute(clusterDelegates.get(delegateId))).thenThrow(new TransportException("simulated network error"));
        }
    }

    private void executeWithTransportErrorExpectation() {
        try {
            retryableClient.execute(requestExecutor);
            fail("TransportException expected");
        } catch (TransportException ignore) {
        }
    }

    class RequestExecutorRunner implements Runnable {

        private final RequestExecutor<Void> requestExecutor;

        RequestExecutorRunner(RequestExecutor<Void> requestExecutor) {
            this.requestExecutor = requestExecutor;
        }

        @Override
        public void run() {
            retryableClient.execute(requestExecutor);
        }
    }

    static class BlockingRequestExecutor implements RequestExecutor<Void> {

        private final CountDownLatch readyLatch = new CountDownLatch(1);
        private final CountDownLatch completeLatch = new CountDownLatch(1);

        @Override
        public EurekaHttpResponse<Void> execute(EurekaHttpClient delegate) {
            readyLatch.countDown();
            try {
                completeLatch.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException("never released");
            }
            return EurekaHttpResponse.responseWith(200);
        }

        void awaitReady() {
            try {
                readyLatch.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException("never released");
            }
        }

        void complete() {
            completeLatch.countDown();
        }
    }
}