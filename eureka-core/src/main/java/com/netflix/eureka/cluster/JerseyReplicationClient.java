package com.netflix.eureka.cluster;

import javax.ws.rs.core.MediaType;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;

import com.netflix.discovery.EurekaIdentityHeaderFilter;
import com.netflix.discovery.shared.EurekaJerseyClient;
import com.netflix.discovery.shared.EurekaJerseyClient.JerseyClient;
import com.netflix.discovery.shared.JerseyEurekaHttpClient;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.EurekaServerIdentity;
import com.netflix.eureka.cluster.protocol.ReplicationList;
import com.netflix.eureka.cluster.protocol.ReplicationListResponse;
import com.netflix.eureka.resources.ASGResource.ASGStatus;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.filter.GZIPContentEncodingFilter;
import com.sun.jersey.client.apache4.ApacheHttpClient4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Tomasz Bak
 */
public class JerseyReplicationClient extends JerseyEurekaHttpClient implements HttpReplicationClient {

    private static final Logger logger = LoggerFactory.getLogger(JerseyReplicationClient.class);

    private final JerseyClient jerseyClient;
    private final ApacheHttpClient4 jerseyApacheClient;

    public JerseyReplicationClient(EurekaServerConfig config, String serviceUrl) {
        super(serviceUrl);
        String name = getClass().getSimpleName() + ": " + serviceUrl + "apps/: ";

        try {
            String hostname;
            try {
                hostname = new URL(serviceUrl).getHost();
            } catch (MalformedURLException e) {
                hostname = serviceUrl;
            }
            String jerseyClientName = "Discovery-PeerNodeClient-" + hostname;
            if (serviceUrl.startsWith("https://") &&
                    "true".equals(System.getProperty("com.netflix.eureka.shouldSSLConnectionsUseSystemSocketFactory"))) {
                jerseyClient = EurekaJerseyClient.createSystemSSLJerseyClient(jerseyClientName,
                        config.getPeerNodeConnectTimeoutMs(),
                        config.getPeerNodeReadTimeoutMs(),
                        config.getPeerNodeTotalConnections(),
                        config.getPeerNodeTotalConnectionsPerHost(),
                        config.getPeerNodeConnectionIdleTimeoutSeconds());
            } else {
                jerseyClient = EurekaJerseyClient.createJerseyClient(jerseyClientName,
                        config.getPeerNodeConnectTimeoutMs(),
                        config.getPeerNodeReadTimeoutMs(),
                        config.getPeerNodeTotalConnections(),
                        config.getPeerNodeTotalConnectionsPerHost(),
                        config.getPeerNodeConnectionIdleTimeoutSeconds());
            }
            jerseyApacheClient = jerseyClient.getClient();
            jerseyApacheClient.addFilter(new GZIPContentEncodingFilter(true));
        } catch (Throwable e) {
            throw new RuntimeException("Cannot Create new Replica Node :" + name, e);
        }

        String ip = null;
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            logger.warn("Cannot find localhost ip", e);
        }
        EurekaServerIdentity identity = new EurekaServerIdentity(ip);
        jerseyApacheClient.addFilter(new EurekaIdentityHeaderFilter(identity));
    }

    @Override
    protected ApacheHttpClient4 getJerseyApacheClient() {
        return jerseyApacheClient;
    }

    @Override
    protected void addExtraHeaders(WebResource webResource) {
        webResource.header(PeerEurekaNode.HEADER_REPLICATION, "true");
    }

    @Override
    public HttpResponse<Void> statusUpdate(String asgName, ASGStatus newStatus) {
        ClientResponse response = null;
        try {
            String urlPath = "asg/" + asgName + "/status";
            response = jerseyApacheClient.resource(serviceUrl)
                    .path(urlPath)
                    .queryParam("value", newStatus.name())
                    .header(PeerEurekaNode.HEADER_REPLICATION, "true")
                    .put(ClientResponse.class);
            return HttpResponse.responseWith(response.getStatus());
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public HttpResponse<ReplicationListResponse> submitBatchUpdates(ReplicationList replicationList) {
        ClientResponse response = null;
        try {
            response = jerseyApacheClient.resource(serviceUrl)
                    .path(PeerEurekaNode.BATCH_URL_PATH)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .post(ClientResponse.class, replicationList);
            if (!isSuccess(response.getStatus())) {
                return HttpResponse.responseWith(response.getStatus());
            }
            ReplicationListResponse batchResponse = response.getEntity(ReplicationListResponse.class);
            return HttpResponse.responseWith(response.getStatus(), batchResponse);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        jerseyClient.destroyResources();
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }
}
