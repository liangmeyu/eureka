package com.netflix.eureka.resources;

import java.io.File;
import java.io.FilenameFilter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.shared.resolver.DefaultEndpoint;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.jersey.JerseyEurekaHttpClientFactory;
import com.netflix.discovery.util.InstanceInfoGenerator;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.cluster.protocol.ReplicationInstance;
import com.netflix.eureka.cluster.protocol.ReplicationInstanceResponse;
import com.netflix.eureka.cluster.protocol.ReplicationList;
import com.netflix.eureka.cluster.protocol.ReplicationListResponse;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl.Action;
import com.netflix.eureka.transport.JerseyReplicationClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test REST layer of client/server communication. This test instantiates fully configured Jersey
 * container,
 * which is essential to verifying content encoding/decoding with different format types (JSON vs
 * XML, compressed vs
 * uncompressed).
 *
 * @author Tomasz Bak
 */
public class EurekaClientServerRestIntegrationTest {

    private static final String[] EUREKA1_WAR_DIRS = {"build/libs", "eureka-server/build/libs"};

    private static final Pattern WAR_PATTERN = Pattern.compile("eureka-server.*.war");

    private static EurekaServerConfig eurekaServerConfig;

    private static Server server;
    private static TransportClientFactory httpClientFactory;

    private static EurekaHttpClient jerseyEurekaClient;
    private static JerseyReplicationClient jerseyReplicationClient;

    /**
     * We do not include ASG data to prevent server from consulting AWS for its status.
     */
    private static final InstanceInfoGenerator infoGenerator =
            InstanceInfoGenerator.newBuilder(10, 2).withAsg(false).build();
    // 服务实例信息 迭代器
    private static final Iterator<InstanceInfo> instanceInfoIt = infoGenerator.serviceIterator();

    private static String eurekaServiceUrl;

    @BeforeClass
    public static void setUp() throws Exception {
        injectEurekaConfiguration(); // 注入 Eureka 配置需要的信息
        startServer(); // 启动服务
        createEurekaServerConfig(); // mock EurekaServerConfig eurekaServerConfig 实例行为

        // 构造 TransportClientFactory 实例
        httpClientFactory = JerseyEurekaHttpClientFactory.newBuilder().withClientName(
                "testEurekaClient").withConnectionTimeout(1000).withReadTimeout(1000).withMaxConnectionsPerHost(1).withMaxTotalConnections(1).withConnectionIdleTimeout(1000).build();

        // 获取 EurekaHttpClient 实例
        jerseyEurekaClient = httpClientFactory.newClient(new DefaultEndpoint(eurekaServiceUrl));

        //
        ServerCodecs serverCodecs = new DefaultServerCodecs(eurekaServerConfig);

        // 构造 JerseyReplicationClient 实例
        jerseyReplicationClient =
                JerseyReplicationClient.createReplicationClient(eurekaServerConfig, serverCodecs,
                        eurekaServiceUrl);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        removeEurekaConfiguration(); // 清理 Eureka 配置需要的信息
        if (jerseyReplicationClient != null) {
            jerseyReplicationClient.shutdown();
        }
        if (server != null) {
            server.stop();
        }
        if (httpClientFactory != null) {
            httpClientFactory.shutdown();
        }
    }

    /**
     * 测试 服务注册
     *
     * @throws Exception
     */
    @Test
    public void testRegistration() throws Exception {
        // 获取 服务实例信息 对象
        InstanceInfo instanceInfo = instanceInfoIt.next();

        // 通过 jerseyEurekaClient 网络请求 服务注册中心，将 instanceInfo 注册到注册中心
        EurekaHttpResponse<Void> httpResponse = jerseyEurekaClient.register(instanceInfo);

        // 断言 http code equal 204（(No Content)表示服务器已成功完成请求，并且在响应有效负载正文中没有要发送的内容。）
        assertThat(httpResponse.getStatusCode(), is(equalTo(204)));
    }

    /**
     * 测试 服务心跳
     *
     * @throws Exception
     */
    @Test
    public void testHeartbeat() throws Exception {
        // 服务注册
        // Register first
        InstanceInfo instanceInfo = instanceInfoIt.next();
        jerseyEurekaClient.register(instanceInfo);

        // 发送 服务心跳
        // Now send heartbeat
        EurekaHttpResponse<InstanceInfo> heartBeatResponse =
                jerseyReplicationClient.sendHeartBeat(instanceInfo.getAppName(),
                        instanceInfo.getId(), instanceInfo, null);

        // 断言 http code equal 200（）
        assertThat(heartBeatResponse.getStatusCode(), is(equalTo(200)));
        // 断言 响应体 为空
        assertThat(heartBeatResponse.getEntity(), is(nullValue()));
    }

    /**
     * 测试 丢失服务心跳
     * 没有注册服务就发送心跳
     *
     * @throws Exception
     */
    @Test
    public void testMissedHeartbeat() throws Exception {

        // 获取 服务实例信息
        InstanceInfo instanceInfo = instanceInfoIt.next();

        // 发送 服务心跳
        // Now send heartbeat
        EurekaHttpResponse<InstanceInfo> heartBeatResponse =
                jerseyReplicationClient.sendHeartBeat(instanceInfo.getAppName(),
                        instanceInfo.getId(), instanceInfo, null);

        // 由于没有注册服务，导致找不到对应服务更新租约信息
        assertThat(heartBeatResponse.getStatusCode(), is(equalTo(404)));
    }

    /**
     * 测试 服务主动下线
     * <p>
     * 服务注册中心有服务信息
     *
     * @throws Exception
     */
    @Test
    public void testCancelForEntryThatExists() throws Exception {
        // Register first
        InstanceInfo instanceInfo = instanceInfoIt.next();
        jerseyEurekaClient.register(instanceInfo);

        // Now cancel
        EurekaHttpResponse<Void> httpResponse =
                jerseyEurekaClient.cancel(instanceInfo.getAppName(), instanceInfo.getId());

        assertThat(httpResponse.getStatusCode(), is(equalTo(200)));
    }

    /**
     * 测试 服务主动下线
     * <p>
     * 服务注册中心无服务信息
     *
     * @throws Exception
     */
    @Test
    public void testCancelForEntryThatDoesNotExist() throws Exception {
        // Now cancel
        InstanceInfo instanceInfo = instanceInfoIt.next();
        EurekaHttpResponse<Void> httpResponse =
                jerseyEurekaClient.cancel(instanceInfo.getAppName(), instanceInfo.getId());

        assertThat(httpResponse.getStatusCode(), is(equalTo(404)));
    }


    /**
     * 测试
     *
     * @throws Exception
     */
    @Test
    public void testStatusOverrideUpdateAndDelete() throws Exception {
        // Register first
        InstanceInfo instanceInfo = instanceInfoIt.next();
        jerseyEurekaClient.register(instanceInfo);

        // Now override status
        EurekaHttpResponse<Void> overrideUpdateResponse =
                jerseyEurekaClient.statusUpdate(instanceInfo.getAppName(), instanceInfo.getId(),
                        InstanceStatus.DOWN, instanceInfo);
        assertThat(overrideUpdateResponse.getStatusCode(), is(equalTo(200)));

        InstanceInfo fetchedInstance = expectInstanceInfoInRegistry(instanceInfo);
        assertThat(fetchedInstance.getStatus(), is(equalTo(InstanceStatus.DOWN)));

        // Now remove override
        EurekaHttpResponse<Void> deleteOverrideResponse =
                jerseyEurekaClient.deleteStatusOverride(instanceInfo.getAppName(),
                        instanceInfo.getId(), instanceInfo);
        assertThat(deleteOverrideResponse.getStatusCode(), is(equalTo(200)));

        fetchedInstance = expectInstanceInfoInRegistry(instanceInfo);
        assertThat(fetchedInstance.getStatus(), is(equalTo(InstanceStatus.UNKNOWN)));
    }

    /**
     * 测试 服务
     *
     * @throws Exception
     */
    @Test
    public void testBatch() throws Exception {
        InstanceInfo instanceInfo = instanceInfoIt.next();
        ReplicationInstance replicationInstance =
                ReplicationInstance.replicationInstance().withAction(Action.Register).withAppName(instanceInfo.getAppName()).withId(instanceInfo.getId()).withInstanceInfo(instanceInfo).withLastDirtyTimestamp(System.currentTimeMillis()).withStatus(instanceInfo.getStatus().name()).build();
        EurekaHttpResponse<ReplicationListResponse> httpResponse =
                jerseyReplicationClient.submitBatchUpdates(new ReplicationList(replicationInstance));

        assertThat(httpResponse.getStatusCode(), is(equalTo(200)));
        List<ReplicationInstanceResponse> replicationListResponse =
                httpResponse.getEntity().getResponseList();
        assertThat(replicationListResponse.size(), is(equalTo(1)));
        assertThat(replicationListResponse.get(0).getStatusCode(), is(equalTo(200)));
    }

    private static InstanceInfo expectInstanceInfoInRegistry(InstanceInfo instanceInfo) {
        EurekaHttpResponse<InstanceInfo> queryResponse =
                jerseyEurekaClient.getInstance(instanceInfo.getAppName(), instanceInfo.getId());
        assertThat(queryResponse.getStatusCode(), is(equalTo(200)));
        assertThat(queryResponse.getEntity(), is(notNullValue()));
        assertThat(queryResponse.getEntity().getId(), is(equalTo(instanceInfo.getId())));
        return queryResponse.getEntity();
    }

    /**
     * This will be read by server internal discovery client. We need to salience it.
     */
    private static void injectEurekaConfiguration() throws UnknownHostException {
        String myHostName = InetAddress.getLocalHost().getHostName();
        String myServiceUrl = "http://" + myHostName + ":8080/v2/";

        System.setProperty("eureka.region", "default");
        System.setProperty("eureka.name", "eureka");
        System.setProperty("eureka.vipAddress", "eureka.mydomain.net");
        System.setProperty("eureka.port", "8080");
        System.setProperty("eureka.preferSameZone", "false");
        System.setProperty("eureka.shouldUseDns", "false");
        System.setProperty("eureka.shouldFetchRegistry", "false");
        System.setProperty("eureka.serviceUrl.defaultZone", myServiceUrl);
        System.setProperty("eureka.serviceUrl.default.defaultZone", myServiceUrl);
        System.setProperty("eureka.awsAccessId", "fake_aws_access_id");
        System.setProperty("eureka.awsSecretKey", "fake_aws_secret_key");
        System.setProperty("eureka.numberRegistrySyncRetries", "0");
    }

    private static void removeEurekaConfiguration() {

    }

    private static void startServer() throws Exception {
        // 使用war包方式启动
        // 查找 eureka-server war包
        File warFile = findWar();

        // 获取 jetty 容器，端口设置为8080
        server = new Server(8080);

        // 获取 web应用 容器
        WebAppContext webapp = new WebAppContext();
        //
        webapp.setContextPath("/");
        //
        webapp.setWar(warFile.getAbsolutePath());

        // 设置 jetty 容器 的处理器，即web应用
        server.setHandler(webapp);

        // 启动 jetty 容器
        server.start();

        // eureka-server 终端地址
        eurekaServiceUrl = "http://localhost:8080/v2";

        // 不使用war包方式
//        server = new Server(8080);
//
//        WebAppContext webAppCtx = new WebAppContext(new File("./eureka-server/src/main/webapp")
//        .getAbsolutePath(), "/");
//        webAppCtx.setDescriptor(new File("./eureka-server/src/main/webapp/WEB-INF/web.xml")
//        .getAbsolutePath());
//        webAppCtx.setResourceBase(new File("./eureka-server/src/main/resources")
//        .getAbsolutePath());
//        webAppCtx.setClassLoader(Thread.currentThread().getContextClassLoader());
//        server.setHandler(webAppCtx);
//        server.start();
//
    }

    private static File findWar() {
        File dir = null;
        for (String candidate : EUREKA1_WAR_DIRS) {
            File candidateFile = new File(candidate);
            if (candidateFile.exists()) {
                dir = candidateFile;
                break;
            }
        }
        if (dir == null) {
            throw new IllegalStateException("No directory found at any in any pre-configured " +
                    "location: " + Arrays.toString(EUREKA1_WAR_DIRS));
        }

        File[] warFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return WAR_PATTERN.matcher(name).matches();
            }
        });
        if (warFiles.length == 0) {
            throw new IllegalStateException("War file not found in directory " + dir);
        }
        if (warFiles.length > 1) {
            throw new IllegalStateException("Multiple war files found in directory " + dir + ": " + Arrays.toString(warFiles));
        }
        return warFiles[0];
    }

    private static void createEurekaServerConfig() {
        eurekaServerConfig = mock(EurekaServerConfig.class);

        // Cluster management related // 集群管理相关
        when(eurekaServerConfig.getPeerEurekaNodesUpdateIntervalMs()).thenReturn(1000);

        // Replication logic related // 集群复制相关
        when(eurekaServerConfig.shouldSyncWhenTimestampDiffers()).thenReturn(true);
        when(eurekaServerConfig.getMaxTimeForReplication()).thenReturn(1000);
        when(eurekaServerConfig.getMaxElementsInPeerReplicationPool()).thenReturn(10);
        when(eurekaServerConfig.getMinThreadsForPeerReplication()).thenReturn(1);
        when(eurekaServerConfig.getMaxThreadsForPeerReplication()).thenReturn(1);
        when(eurekaServerConfig.shouldBatchReplication()).thenReturn(true);

        // Peer node connectivity (used by JerseyReplicationClient)
        when(eurekaServerConfig.getPeerNodeTotalConnections()).thenReturn(1);
        when(eurekaServerConfig.getPeerNodeTotalConnectionsPerHost()).thenReturn(1);
        when(eurekaServerConfig.getPeerNodeConnectionIdleTimeoutSeconds()).thenReturn(1000);
    }
}
