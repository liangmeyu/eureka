/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.Date;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.CloudInstanceConfig;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.JsonXStream;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.eureka.aws.AwsBinder;
import com.netflix.eureka.aws.AwsBinderDelegate;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.AwsInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.EurekaMonitors;
import com.thoughtworks.xstream.XStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that kick starts the eureka server.
 *
 * <p>
 * The eureka server is configured by using the configuration
 * {@link EurekaServerConfig} specified by <em>eureka.server.props</em> in the
 * classpath.  The eureka client component is also initialized by using the
 * configuration {@link EurekaInstanceConfig} specified by
 * <em>eureka.client.props</em>. If the server runs in the AWS cloud, the eureka
 * server binds it to the elastic ip as specified.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim, David Liu
 */

/**
 * 本质是个监听器
 * <p>
 * contextInitialized(ServletContextEvent sce) ：当Servlet 容器启动Web 应用时调用该方法。在调用完该方法之后，容器再对Filter
 * 初始化，并且对那些在Web 应用启动时就需要被初始化的Servlet 进行初始化。
 * <p>
 * contextDestroyed(ServletContextEvent sce) ：当Servlet 容器终止Web 应用时调用该方法。在调用该方法之前，容器会先销毁所有的Servlet
 * 和Filter 过滤器。
 */
public class EurekaBootStrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(EurekaBootStrap.class);

    private static final String TEST = "test";

    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";

    private static final String EUREKA_ENVIRONMENT = "eureka.environment";

    private static final String CLOUD = "cloud";
    private static final String DEFAULT = "default";

    private static final String ARCHAIUS_DEPLOYMENT_DATACENTER = "archaius.deployment.datacenter";

    private static final String EUREKA_DATACENTER = "eureka.datacenter";

    protected volatile EurekaServerContext serverContext;
    protected volatile AwsBinder awsBinder;

    private EurekaClient eurekaClient;

    /**
     * Construct a default instance of Eureka boostrap
     */
    public EurekaBootStrap() {
        this(null);
    }

    /**
     * Construct an instance of eureka bootstrap with the supplied eureka client
     *
     * @param eurekaClient the eureka client to bootstrap
     */
    public EurekaBootStrap(EurekaClient eurekaClient) {
        this.eurekaClient = eurekaClient;
    }

    /**
     * Initializes Eureka, including syncing up with other Eureka peers and publishing the registry.
     *
     * @see javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)
     */
    /**
     * 当Servlet 容器启动Web 应用时调用该方法。
     * 在调用完该方法之后，容器再对Filter初始化，并且对那些在Web 应用启动时就需要被初始化的Servlet 进行初始化。
     *
     * @param event
     */
    @Override
    public void contextInitialized(ServletContextEvent event) {
        try {
            // 初始化 eureka-server 环境（数据中心、运行环境）
            initEurekaEnvironment();
            // 初始化 eureka-server 容器（
            initEurekaServerContext();

            // 获取 servletContext
            ServletContext sc = event.getServletContext();

            // 设置 servletContext 属性
            // com.netflix.eureka.EurekaServerContext = EurekaServerContext 实例
            sc.setAttribute(EurekaServerContext.class.getName(), serverContext);
        } catch (Throwable e) {
            logger.error("Cannot bootstrap eureka server :", e);
            throw new RuntimeException("Cannot bootstrap eureka server :", e);
        }
    }

    /**
     * Users can override to initialize the environment themselves.
     */
    protected void initEurekaEnvironment() throws Exception {
        logger.info("Setting the eureka configuration..");

        // 单例模式 获取 配置管理器 ConfigurationManager 实例
        // 创建一个 （AbstractConfiguration)ConcurrentCompositeConfiguration 实例，加入其他
        // (AbstractConfiguration)config
        // 初始化数据中心(eureka.datacenter)的配置，如果没有配置的话，就是DEFAULT(default)
        String dataCenter = ConfigurationManager.getConfigInstance().getString(EUREKA_DATACENTER);
        if (dataCenter == null) {
            logger.info("Eureka data center value eureka.datacenter is not set, defaulting to " + "default");
            // 数据中心如果为空 设置  archaius.deployment.datacenter = default
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER,
                    DEFAULT);
        } else {
            // 数据中心如果不为空，设置 archaius.deployment.datacenter = dataCenter值
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER,
                    dataCenter);
        }
        // 初始化eureka运行的环境(eureka.environment)，如果你没有配置的话，默认设置为test环境
        String environment = ConfigurationManager.getConfigInstance().getString(EUREKA_ENVIRONMENT);
        if (environment == null) {
            // 如果 eureka环境为空，设置 archaius.deployment.environment = test
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT,
                    TEST);
            logger.info("Eureka environment value eureka.environment is not set, defaulting to " + "test");
        }
    }

    /**
     * init hook for server context. Override for custom logic.
     */
    protected void initEurekaServerContext() throws Exception {
        // 第一步，加载 eureka-server.properties 文件中的配置
        // 获取 eureka-server 配置
        // 面向接口的配置项读取
        // DefaultEurekaServerConfig 创建实例时，执行init()方法，完成eureka-server.properties配置项的加载
        EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig();

        // For backward compatibility
        JsonXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(),
                XStream.PRIORITY_VERY_HIGH);
        XmlXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(),
                XStream.PRIORITY_VERY_HIGH);

        logger.info("Initializing the eureka client...");
        logger.info(eurekaServerConfig.getJsonCodecName());

        // 获取 服务编码 实例，用于网络数据的编码与解码
        ServerCodecs serverCodecs = new DefaultServerCodecs(eurekaServerConfig);

        // 第二步，初始化 ApplicationInfoManager
        ApplicationInfoManager applicationInfoManager = null;

        // 第三步，初始化 eureka-server 内部的一个 eureka-client（用来与其他 eureka-server 节点进行 注册和通信）
        // 获取应用管理器及Eureka客户端
        if (eurekaClient == null) {
            // EurekaClient 如果为 null
            // 获取 eureka-client 实例配置 实例
            EurekaInstanceConfig instanceConfig =
                    // 判断是否是 云服务。默认环境是test
                    isCloud(ConfigurationManager.getDeploymentContext()) ?
                            // 实例化 云实例配置
                            new CloudInstanceConfig() :
                            // 实例化 自定义数据中心实例配置
                            new MyDataCenterInstanceConfig();

            // 获取 应用信息管理器
            applicationInfoManager = new ApplicationInfoManager(
                    // eureka-client 实例配置 实例
                    instanceConfig,
                    // 基于 eureka-client 实例配置 实例 获取 InstanceInfo 实例信息 实例
                    new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());

            // 获取 eureka-client默认配置 实例
            EurekaClientConfig eurekaClientConfig = new DefaultEurekaClientConfig();
            // 获取 (EurekaClient)DiscoveryClient eureka-client 实例
            eurekaClient = new DiscoveryClient(applicationInfoManager, eurekaClientConfig);
        } else {
            // EurekaClient 不为空
            // 获取 应用信息管理器
            applicationInfoManager = eurekaClient.getApplicationInfoManager();
        }

        // 第四步，处理注册相关的事情
        // 可以发现集群中节点的服务实例注册表
        PeerAwareInstanceRegistry registry;
        if (isAws(applicationInfoManager.getInfo())) { // 判断 实例信息 是不是 aws类型实例
            // 如果是 aws类型实例
            registry = new AwsInstanceRegistry(eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(), serverCodecs, eurekaClient);
            awsBinder = new AwsBinderDelegate(eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(), registry, applicationInfoManager);
            awsBinder.start();
        } else {
            // 如果不是 aws类型实例
            // 获取 PeerAwareInstanceRegistry 注册表
            // 服务配置信息、客户端配置信息、服务编码组件、客户端组件
            registry = new PeerAwareInstanceRegistryImpl(eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(), serverCodecs, eurekaClient);
        }

        // 根据 注册表、eureka-server配置信息、eureka-client 配置信息、服务编码组件、应用信息管理器等获取 Eureka单个服务节点集群 实例
        // 构造集群信息
        PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(registry, eurekaServerConfig,
                eurekaClient.getEurekaClientConfig(), serverCodecs, applicationInfoManager);

        // 获取 eureka服务上下文
        serverContext = new DefaultEurekaServerContext(eurekaServerConfig, serverCodecs, registry
                , peerEurekaNodes, applicationInfoManager);

        // 构建 EurekaServerContextHolder 持有 EurekaServerContext serverContext
        EurekaServerContextHolder.initialize(serverContext);

        // EurekaServerContext serverContext eureka服务上下文初始化（集群、注册表）
        serverContext.initialize();
        logger.info("Initialized server context");

        // 从相邻的一个eureka server节点拷贝注册表的信息，如果拷贝失败，就找下一个
        // Copy registry from neighboring eureka node
        // 此处的相邻节点说的是eurekaClient.getApplications()。获取 eurekaClient.getApplications()
        // 时，是从EurekaServerConfig服务端地址配置项中取的第一个地址。相邻说的是这个地址。
        int registryCount = registry.syncUp();
        // 初始化服务自动感知及服务自动摘除调度器
        registry.openForTraffic(applicationInfoManager, registryCount);

        // 注册所有跟踪监控统计数据
        // Register all monitoring statistics.
        EurekaMonitors.registerAllStats();
    }

    protected PeerEurekaNodes getPeerEurekaNodes(PeerAwareInstanceRegistry registry,
                                                 EurekaServerConfig eurekaServerConfig,
                                                 EurekaClientConfig eurekaClientConfig,
                                                 ServerCodecs serverCodecs,
                                                 ApplicationInfoManager applicationInfoManager) {
        PeerEurekaNodes peerEurekaNodes = new PeerEurekaNodes(registry, eurekaServerConfig,
                eurekaClientConfig, serverCodecs, applicationInfoManager);

        return peerEurekaNodes;
    }

    /**
     * Handles Eureka cleanup, including shutting down all monitors and yielding all EIPs.
     *
     * @see javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        try {
            logger.info("{} Shutting down Eureka Server..", new Date().toString());
            ServletContext sc = event.getServletContext();
            sc.removeAttribute(EurekaServerContext.class.getName());

            destroyEurekaServerContext();
            destroyEurekaEnvironment();

        } catch (Throwable e) {
            logger.error("Error shutting down eureka", e);
        }
        logger.info("{} Eureka Service is now shutdown...", new Date().toString());
    }

    /**
     * Server context shutdown hook. Override for custom logic
     */
    protected void destroyEurekaServerContext() throws Exception {
        EurekaMonitors.shutdown();
        if (awsBinder != null) {
            awsBinder.shutdown();
        }
        if (serverContext != null) {
            serverContext.shutdown();
        }
    }

    /**
     * Users can override to clean up the environment themselves.
     */
    protected void destroyEurekaEnvironment() throws Exception {

    }

    protected boolean isAws(InstanceInfo selfInstanceInfo) {
        boolean result =
                DataCenterInfo.Name.Amazon == selfInstanceInfo.getDataCenterInfo().getName();
        logger.info("isAws returned {}", result);
        return result;
    }

    protected boolean isCloud(DeploymentContext deploymentContext) {
        logger.info("Deployment datacenter is {}", deploymentContext.getDeploymentDatacenter());
        return CLOUD.equals(deploymentContext.getDeploymentDatacenter());
    }
}
