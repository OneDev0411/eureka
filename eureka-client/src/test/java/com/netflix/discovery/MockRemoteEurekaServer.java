package com.netflix.discovery;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;

import org.apache.commons.lang.StringUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.mortbay.jetty.Request;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Nitesh Kant
 */
public class MockRemoteEurekaServer {

    public static final String EUREKA_API_BASE_PATH = "/eureka/v2/";

    private final int port;
    private final Map<String, Application> applicationMap;
    private final Map<String, Application> remoteRegionApps;
    private final Map<String, Application> remoteRegionAppsDelta;
    private final Map<String, Application> applicationDeltaMap;
    private final Server server;
    private AtomicBoolean sentDelta = new AtomicBoolean();
    private AtomicBoolean sentRegistry = new AtomicBoolean();

    public MockRemoteEurekaServer(int port, Map<String, Application> localRegionApps,
                                  Map<String, Application> localRegionAppsDelta,
                                  Map<String, Application> remoteRegionApps,
                                  Map<String, Application> remoteRegionAppsDelta) {
        this.port = port;
        this.applicationMap = localRegionApps;
        this.applicationDeltaMap = localRegionAppsDelta;
        this.remoteRegionApps = remoteRegionApps;
        this.remoteRegionAppsDelta = remoteRegionAppsDelta;
        server = new Server(port);
        server.setHandler(new AppsResourceHandler());
    }

    public void start() throws Exception {
        server.start();
    }

    public void stop() throws Exception {
        server.stop();
    }

    public boolean isSentDelta() {
        return sentDelta.get();
    }

    public boolean isSentRegistry() {
        return sentRegistry.get();
    }

    private class AppsResourceHandler extends AbstractHandler {

        @Override
        public void handle(String target, HttpServletRequest request, HttpServletResponse response, int dispatch)
                throws IOException, ServletException {
            String pathInfo = request.getPathInfo();
            System.out.println("Eureka port: " + port + ". " + System.currentTimeMillis() +
                               ". Eureka resource mock, received request on path: " + pathInfo + ". HTTP method: |"
                               + request.getMethod() + "|" + ", query string: " + request.getQueryString());
            boolean handled = false;
            if (null != pathInfo && pathInfo.startsWith("")) {
                pathInfo = pathInfo.substring(EUREKA_API_BASE_PATH.length());
                boolean includeRemote = isRemoteRequest(request);

                if (pathInfo.startsWith("apps/delta")) {
                    Applications apps = new Applications();
                    apps.setVersion(100l);
                    if (sentDelta.compareAndSet(false, true)) {
                        addDeltaApps(includeRemote, apps);
                    } else {
                        System.out.println("Eureka port: " +  port + ". " + System.currentTimeMillis() +". Not including delta as it has already been sent.");
                    }
                    apps.setAppsHashCode(getDeltaAppsHashCode(includeRemote));
                    sendOkResponseWithContent((Request) request, response, apps);
                    handled = true;
                } else if(pathInfo.startsWith("apps")) {
                    if (request.getMethod().equals("GET")) {
                        Applications apps = new Applications();
                        apps.setVersion(100l);
                        for (Application application : applicationMap.values()) {
                            apps.addApplication(application);
                        }
                        if (includeRemote) {
                            for (Application application : remoteRegionApps.values()) {
                                apps.addApplication(application);
                            }
                        }
    
                        if (sentDelta.get()) {
                            addDeltaApps(includeRemote, apps);
                        } else {
                            System.out.println("Eureka port: " + port + ". " + System.currentTimeMillis() +". Not including delta apps in /apps response, as delta has not been sent.");
                        }
                        apps.setAppsHashCode(apps.getReconcileHashCode());
                        sendOkResponseWithContent((Request) request, response, apps);
                        sentRegistry.set(true);
                        handled = true;
                    }
                    else if (request.getMethod().equals("DELETE")) {
                        if (pathInfo != null) {
                            String[] parts = StringUtils.split(pathInfo, "/");
                            if (parts.length == 3) {
                                String appName = parts[1];
                                String id = parts[2];
                                Application app = applicationMap.get(appName);
                                InstanceInfo ii = app.getByInstanceId(id);
                                ii.setStatus(InstanceStatus.OUT_OF_SERVICE);
                                handled = true;
                            }
                        }
                    }
                    else if (request.getMethod().equals("POST")) {
                        if (pathInfo != null) {
                            String[] parts = StringUtils.split(pathInfo, "/");
                            if (parts.length == 2) {
                                String appName = parts[1];
                                Application app = applicationMap.get(appName);
                                
                                ObjectMapper mapper = new ObjectMapper();
                                JsonNode node = mapper.readTree(request.getInputStream());
                                String id = node.get("instance").get("hostName").asText();
                                InstanceInfo ii = app.getByInstanceId(id);
                                ii.setStatus(InstanceStatus.UP);
                                handled = true;
                            }
                        }
                    }
                }
            }

            if(!handled) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND,
                                   "Request path: " + pathInfo + " not supported by eureka resource mock.");
            }
        }

        private void addDeltaApps(boolean includeRemote, Applications apps) {
            for (Application application : applicationDeltaMap.values()) {
                apps.addApplication(application);
            }
            if (includeRemote) {
                for (Application application : remoteRegionAppsDelta.values()) {
                    apps.addApplication(application);
                }
            }
        }

        private String getDeltaAppsHashCode(boolean includeRemote) {
            Applications allApps = new Applications();
            for (Application application : applicationMap.values()) {
                allApps.addApplication(application);
            }

            if (includeRemote) {
                for (Application application : remoteRegionApps.values()) {
                    allApps.addApplication(application);
                }
            }
            addDeltaApps(includeRemote, allApps);
            return allApps.getReconcileHashCode();
        }

        private boolean isRemoteRequest(HttpServletRequest request) {
            String queryString = request.getQueryString();
            if (queryString == null)
                return false;
            return queryString.contains("regions=");
        }

        private void sendOkResponseWithContent(Request request, HttpServletResponse response, Applications apps)
                throws IOException {
            String content = XmlXStream.getInstance().toXML(apps);
            response.setContentType("application/xml");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println(content);
            response.getWriter().flush();
            request.setHandled(true);
            System.out.println("Eureka port: " + port + ". " + System.currentTimeMillis() +
                               ". Eureka resource mock, sent response for request path: " + request.getPathInfo() +
                               ", apps count: " + apps.getRegisteredApplications().size());
        }
    }

}
