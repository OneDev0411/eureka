package com.netflix.discovery;

import com.netflix.appinfo.HealthCheckCallback;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.config.ConfigurationManager;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author Nitesh Kant
 */
public class DiscoveryClientHealthTest extends AbstractDiscoveryClientTester {

    @Override
    protected void setupProperties() {
        super.setupProperties();
        ConfigurationManager.getConfigInstance().setProperty("eureka.registration.enabled", "true");
    }

    @Override
    protected InstanceInfo.Builder newInstanceInfoBuilder(int renewalIntervalInSecs) {
        InstanceInfo.Builder builder = super.newInstanceInfoBuilder(renewalIntervalInSecs);
        builder.setStatus(InstanceInfo.InstanceStatus.STARTING);
        return builder;
    }

    @Test
    public void testCallback() throws Exception {
        MyHealthCheckCallback myCallback = new MyHealthCheckCallback(true);
        client.registerHealthCheckCallback(myCallback);

        Assert.assertTrue(client instanceof DiscoveryClient);
        DiscoveryClient clientImpl = (DiscoveryClient) client;

        DiscoveryClient.InstanceInfoReplicator instanceInfoReplicator = clientImpl.getInstanceInfoReplicator();
        instanceInfoReplicator.run();

        Assert.assertEquals("Instance info status not as expected.", InstanceInfo.InstanceStatus.STARTING,
                clientImpl.getInstanceInfo().getStatus());
        Assert.assertFalse("Healthcheck callback invoked when status is STARTING.", myCallback.isInvoked());

        clientImpl.getInstanceInfo().setStatus(InstanceInfo.InstanceStatus.OUT_OF_SERVICE);
        Assert.assertEquals("Instance info status not as expected.", InstanceInfo.InstanceStatus.OUT_OF_SERVICE,
                clientImpl.getInstanceInfo().getStatus());

        myCallback.reset();
        instanceInfoReplicator.run();
        Assert.assertFalse("Healthcheck callback invoked when status is OOS.", myCallback.isInvoked());

        clientImpl.getInstanceInfo().setStatus(InstanceInfo.InstanceStatus.DOWN);
        Assert.assertEquals("Instance info status not as expected.", InstanceInfo.InstanceStatus.DOWN,
                clientImpl.getInstanceInfo().getStatus());
        myCallback.reset();
        instanceInfoReplicator.run();

        Assert.assertTrue("Healthcheck callback not invoked.", myCallback.isInvoked());
        Assert.assertEquals("Instance info status not as expected.", InstanceInfo.InstanceStatus.UP,
                clientImpl.getInstanceInfo().getStatus());
    }

    @Test
    public void testHandler() throws Exception {
        MyHealthCheckHandler myHealthCheckHandler = new MyHealthCheckHandler(InstanceInfo.InstanceStatus.UP);
        client.registerHealthCheck(myHealthCheckHandler);

        Assert.assertTrue(client instanceof DiscoveryClient);
        DiscoveryClient clientImpl = (DiscoveryClient) client;

        DiscoveryClient.InstanceInfoReplicator instanceInfoReplicator = clientImpl.getInstanceInfoReplicator();

        Assert.assertEquals("Instance info status not as expected.", InstanceInfo.InstanceStatus.STARTING,
                clientImpl.getInstanceInfo().getStatus());
        instanceInfoReplicator.run();

        Assert.assertTrue("Healthcheck callback not invoked when status is STARTING.", myHealthCheckHandler.isInvoked());
        Assert.assertEquals("Instance info status not as expected post healthcheck.", InstanceInfo.InstanceStatus.UP,
                clientImpl.getInstanceInfo().getStatus());

        clientImpl.getInstanceInfo().setStatus(InstanceInfo.InstanceStatus.OUT_OF_SERVICE);
        Assert.assertEquals("Instance info status not as expected.", InstanceInfo.InstanceStatus.OUT_OF_SERVICE,
                clientImpl.getInstanceInfo().getStatus());
        myHealthCheckHandler.reset();
        instanceInfoReplicator.run();

        Assert.assertTrue("Healthcheck callback not invoked when status is OUT_OF_SERVICE.", myHealthCheckHandler.isInvoked());
        Assert.assertEquals("Instance info status not as expected post healthcheck.", InstanceInfo.InstanceStatus.UP,
                clientImpl.getInstanceInfo().getStatus());

        clientImpl.getInstanceInfo().setStatus(InstanceInfo.InstanceStatus.DOWN);
        Assert.assertEquals("Instance info status not as expected.", InstanceInfo.InstanceStatus.DOWN,
                clientImpl.getInstanceInfo().getStatus());
        myHealthCheckHandler.reset();
        instanceInfoReplicator.run();

        Assert.assertTrue("Healthcheck callback not invoked when status is DOWN.", myHealthCheckHandler.isInvoked());
        Assert.assertEquals("Instance info status not as expected post healthcheck.", InstanceInfo.InstanceStatus.UP,
                clientImpl.getInstanceInfo().getStatus());
    }

    private static class MyHealthCheckCallback implements HealthCheckCallback {

        private final boolean health;
        private volatile boolean invoked;

        private MyHealthCheckCallback(boolean health) {
            this.health = health;
        }

        @Override
        public boolean isHealthy() {
            invoked = true;
            return health;
        }

        public boolean isInvoked() {
            return invoked;
        }

        public void reset() {
            invoked = false;
        }
    }

    private static class MyHealthCheckHandler implements HealthCheckHandler {

        private final InstanceInfo.InstanceStatus health;
        private volatile boolean invoked;

        private MyHealthCheckHandler(InstanceInfo.InstanceStatus health) {
            this.health = health;
        }

        public boolean isInvoked() {
            return invoked;
        }

        public void reset() {
            invoked = false;
        }

        @Override
        public InstanceInfo.InstanceStatus getStatus(InstanceInfo.InstanceStatus currentStatus) {
            invoked = true;
            return health;
        }
    }
}
