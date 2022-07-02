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

package com.netflix.discovery;

import static com.netflix.discovery.EurekaClientNames.METRIC_REGISTRATION_PREFIX;
import static com.netflix.discovery.EurekaClientNames.METRIC_REGISTRY_PREFIX;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.PreDestroy;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.ws.rs.core.Response.Status;

import com.netflix.discovery.shared.transport.jersey.Jersey1DiscoveryClientOptionalArgs;
import com.netflix.discovery.shared.transport.jersey.Jersey1TransportClientFactories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckCallback;
import com.netflix.appinfo.HealthCheckCallbackToHandlerBridge;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.endpoint.EndpointUtils;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.resolver.ClosableResolver;
import com.netflix.discovery.shared.resolver.aws.ApplicationsResolver;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpClientFactory;
import com.netflix.discovery.shared.transport.EurekaHttpClients;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.EurekaTransportConfig;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.jersey.EurekaJerseyClient;
import com.netflix.discovery.shared.transport.jersey.TransportClientFactories;
import com.netflix.discovery.util.ThresholdLevelsMetric;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;

/**
 * The class that is instrumental for interactions with <tt>Eureka Server</tt>.
 *
 * <p>
 * <tt>Eureka Client</tt> is responsible for a) <em>Registering</em> the
 * instance with <tt>Eureka Server</tt> b) <em>Renewal</em>of the lease with
 * <tt>Eureka Server</tt> c) <em>Cancellation</em> of the lease from
 * <tt>Eureka Server</tt> during shutdown
 * <p>
 * d) <em>Querying</em> the list of services/instances registered with
 * <tt>Eureka Server</tt>
 * <p>
 *
 * <p>
 * <tt>Eureka Client</tt> needs a configured list of <tt>Eureka Server</tt>
 * {@link java.net.URL}s to talk to.These {@link java.net.URL}s are typically amazon elastic eips
 * which do not change. All of the functions defined above fail-over to other
 * {@link java.net.URL}s specified in the list in the case of failure.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim
 * @author Spencer Gibb
 */
@Singleton
public class DiscoveryClient implements EurekaClient {
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryClient.class);

    // Constants
    public static final String HTTP_X_DISCOVERY_ALLOW_REDIRECT = "X-Discovery-AllowRedirect";

    private static final String VALUE_DELIMITER = ",";
    private static final String COMMA_STRING = VALUE_DELIMITER;

    /**
     * @deprecated here for legacy support as the client config has moved to be an instance variable
     */
    @Deprecated
    private static EurekaClientConfig staticClientConfig;

    // Timers
    private static final String PREFIX = "DiscoveryClient_";
    private final Counter RECONCILE_HASH_CODES_MISMATCH = Monitors.newCounter(PREFIX +
            "ReconcileHashCodeMismatch");
    private final com.netflix.servo.monitor.Timer FETCH_REGISTRY_TIMER =
            Monitors.newTimer(PREFIX + "FetchRegistry");
    private final Counter REREGISTER_COUNTER = Monitors.newCounter(PREFIX + "Reregister");

    // instance variables
    /**
     * A scheduler to be used for the following 3 tasks:
     * - updating service urls
     * - scheduling a TimedSuperVisorTask
     */
    private final ScheduledExecutorService scheduler;
    // additional executors for supervised subtasks
    private final ThreadPoolExecutor heartbeatExecutor;
    private final ThreadPoolExecutor cacheRefreshExecutor;

    private final Provider<HealthCheckHandler> healthCheckHandlerProvider;
    private final Provider<HealthCheckCallback> healthCheckCallbackProvider;
    private final PreRegistrationHandler preRegistrationHandler;
    private final AtomicReference<Applications> localRegionApps =
            new AtomicReference<Applications>();
    private final Lock fetchRegistryUpdateLock = new ReentrantLock();
    // monotonically increasing generation counter to ensure stale threads do not reset registry
    // to an older version
    private final AtomicLong fetchRegistryGeneration;
    private final ApplicationInfoManager applicationInfoManager;
    private final InstanceInfo instanceInfo;
    private final AtomicReference<String> remoteRegionsToFetch;
    private final AtomicReference<String[]> remoteRegionsRef;
    private final InstanceRegionChecker instanceRegionChecker;

    private final EndpointUtils.ServiceUrlRandomizer urlRandomizer;
    private final Provider<BackupRegistry> backupRegistryProvider;
    private final EurekaTransport eurekaTransport;

    private volatile HealthCheckHandler healthCheckHandler;
    private volatile Map<String, Applications> remoteRegionVsApps = new ConcurrentHashMap<>();
    private volatile InstanceInfo.InstanceStatus lastRemoteInstanceStatus =
            InstanceInfo.InstanceStatus.UNKNOWN;
    private final CopyOnWriteArraySet<EurekaEventListener> eventListeners =
            new CopyOnWriteArraySet<>();

    private String appPathIdentifier;
    private ApplicationInfoManager.StatusChangeListener statusChangeListener;

    private InstanceInfoReplicator instanceInfoReplicator;

    private volatile int registrySize = 0;
    private volatile long lastSuccessfulRegistryFetchTimestamp = -1;
    private volatile long lastSuccessfulHeartbeatTimestamp = -1;
    private final ThresholdLevelsMetric heartbeatStalenessMonitor;
    private final ThresholdLevelsMetric registryStalenessMonitor;

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    protected final EurekaClientConfig clientConfig;
    protected final EurekaTransportConfig transportConfig;

    private final long initTimestampMs;

    private static final class EurekaTransport {
        private ClosableResolver bootstrapResolver;
        private TransportClientFactory transportClientFactory;

        private EurekaHttpClient registrationClient;
        private EurekaHttpClientFactory registrationClientFactory;

        private EurekaHttpClient queryClient;
        private EurekaHttpClientFactory queryClientFactory;

        void shutdown() {
            if (registrationClientFactory != null) {
                registrationClientFactory.shutdown();
            }

            if (queryClientFactory != null) {
                queryClientFactory.shutdown();
            }

            if (registrationClient != null) {
                registrationClient.shutdown();
            }

            if (queryClient != null) {
                queryClient.shutdown();
            }

            if (transportClientFactory != null) {
                transportClientFactory.shutdown();
            }

            if (bootstrapResolver != null) {
                bootstrapResolver.shutdown();
            }
        }
    }

    public static class DiscoveryClientOptionalArgs extends Jersey1DiscoveryClientOptionalArgs {

    }

    /**
     * Assumes applicationInfoManager is already initialized
     *
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo
     * directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config) {
        this(myInfo, config, null);
    }

    /**
     * Assumes applicationInfoManager is already initialized
     *
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo
     * directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config,
                           DiscoveryClientOptionalArgs args) {
        this(ApplicationInfoManager.getInstance(), config, args);
    }

    /**
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo
     * directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config,
                           AbstractDiscoveryClientOptionalArgs args) {
        this(ApplicationInfoManager.getInstance(), config, args);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager,
                           EurekaClientConfig config) {
        this(applicationInfoManager, config, null);
    }

    /**
     * @deprecated use the version that take
     * {@link com.netflix.discovery.AbstractDiscoveryClientOptionalArgs} instead
     */
    @Deprecated
    public DiscoveryClient(ApplicationInfoManager applicationInfoManager,
                           final EurekaClientConfig config, DiscoveryClientOptionalArgs args) {
        this(applicationInfoManager, config, (AbstractDiscoveryClientOptionalArgs) args);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager,
                           final EurekaClientConfig config,
                           AbstractDiscoveryClientOptionalArgs args) {
        this(applicationInfoManager, config, args, new Provider<BackupRegistry>() {
            private volatile BackupRegistry backupRegistryInstance;

            @Override
            public synchronized BackupRegistry get() {
                if (backupRegistryInstance == null) {
                    String backupRegistryClassName = config.getBackupRegistryImpl();
                    if (null != backupRegistryClassName) {
                        try {
                            backupRegistryInstance =
                                    (BackupRegistry) Class.forName(backupRegistryClassName).newInstance();
                            logger.info("Enabled backup registry of type " + backupRegistryInstance.getClass());
                        } catch (InstantiationException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        } catch (IllegalAccessException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        } catch (ClassNotFoundException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        }
                    }

                    if (backupRegistryInstance == null) {
                        logger.warn("Using default backup registry implementation which does not "
                                + "do anything.");
                        backupRegistryInstance = new NotImplementedRegistryImpl();
                    }
                }

                return backupRegistryInstance;
            }
        });
    }

    /**
     * 先抓取注册表，后注册服务
     * @param applicationInfoManager
     * @param config
     * @param args
     * @param backupRegistryProvider
     */
    @Inject
    DiscoveryClient(ApplicationInfoManager applicationInfoManager, EurekaClientConfig config,
                    AbstractDiscoveryClientOptionalArgs args,
                    Provider<BackupRegistry> backupRegistryProvider) {
        if (args != null) {
            this.healthCheckHandlerProvider = args.healthCheckHandlerProvider;
            this.healthCheckCallbackProvider = args.healthCheckCallbackProvider;
            this.eventListeners.addAll(args.getEventListeners());
            this.preRegistrationHandler = args.preRegistrationHandler;
        } else {
            // args == null
            this.healthCheckCallbackProvider = null;
            this.healthCheckHandlerProvider = null;
            this.preRegistrationHandler = null;
        }

        // 应用信息管理器
        this.applicationInfoManager = applicationInfoManager;

        // 实例信息
        InstanceInfo myInfo = applicationInfoManager.getInfo();

        // EurekaClientConfig
        clientConfig = config;
        // @Deprecated
        staticClientConfig = clientConfig;

        // EurekaTransportConfig
        transportConfig = config.getTransportConfig();
        instanceInfo = myInfo;
        if (myInfo != null) {
            // appName:服务名称。通过eureka-client.properties文件获取eureka.name=eureka
            // id:instanceId 如果为空，则返回hostname。一个服务可以存在多个服务实例，构建成集群。id（instanceId)表示一个服务实例
            appPathIdentifier = instanceInfo.getAppName() + "/" + instanceInfo.getId();
        } else {
            logger.warn("Setting instanceInfo to a passed in null value");
        }

        // TODO LEON
        this.backupRegistryProvider = backupRegistryProvider;

        this.urlRandomizer = new EndpointUtils.InstanceInfoBasedUrlRandomizer(instanceInfo);
        // 本地服务实例缓存
        localRegionApps.set(new Applications());

        fetchRegistryGeneration = new AtomicLong(0);

        // null
        remoteRegionsToFetch = new AtomicReference<>(clientConfig.fetchRegistryForRemoteRegions());
        remoteRegionsRef = new AtomicReference<>(
                // true
                remoteRegionsToFetch.get() == null ?
                        null :
                        remoteRegionsToFetch.get().split(","));

        // 期望抓取注册表
        if (config.shouldFetchRegistry()) {
            // 允许抓取注册表
            this.registryStalenessMonitor = new ThresholdLevelsMetric(this,
                    METRIC_REGISTRY_PREFIX + "lastUpdateSec_", new long[]{15L, 30L, 60L, 120L,
                    240L, 480L});
        } else {
            this.registryStalenessMonitor = ThresholdLevelsMetric.NO_OP_METRIC;
        }

        // 允许将自己注册到注册中心
        if (config.shouldRegisterWithEureka()) {
            this.heartbeatStalenessMonitor = new ThresholdLevelsMetric(this,
                    METRIC_REGISTRATION_PREFIX + "lastHeartbeatSec_", new long[]{15L, 30L, 60L,
                    120L, 240L, 480L});
        } else {
            this.heartbeatStalenessMonitor = ThresholdLevelsMetric.NO_OP_METRIC;
        }

        logger.info("Initializing Eureka in region {}", clientConfig.getRegion());

        // 如果既不允许注册服务到注册中心，也不允许抓取注册表， 清理资源并结束方法
        if (!config.shouldRegisterWithEureka() && !config.shouldFetchRegistry()) {
            logger.info("Client configured to neither register nor query for data.");
            scheduler = null;
            heartbeatExecutor = null;
            cacheRefreshExecutor = null;
            eurekaTransport = null;
            instanceRegionChecker =
                    new InstanceRegionChecker(new PropertyBasedAzToRegionMapper(config),
                            clientConfig.getRegion());

            // This is a bit of hack to allow for existing code using DiscoveryManager.getInstance()
            // to work with DI'd DiscoveryClient
            DiscoveryManager.getInstance().setDiscoveryClient(this);
            DiscoveryManager.getInstance().setEurekaClientConfig(config);

            initTimestampMs = System.currentTimeMillis();
            logger.info("Discovery Client initialized at timestamp {} with initial instances " +
                    "count: {}", initTimestampMs, this.getApplications().size());

            return;  // no need to setup up an network tasks and we are done
        }

        // 初始化3个线程池（调度、心跳、缓存刷新）
        try {
            // 支持调度的线程池
            // default size of 2 - 1 each for heartbeat and cacheRefresh
            scheduler = Executors.newScheduledThreadPool(2,
                    new ThreadFactoryBuilder().setNameFormat("DiscoveryClient-%d").setDaemon(true).build());

            // 支持心跳的线程池
            heartbeatExecutor = new ThreadPoolExecutor(1,
                    clientConfig.getHeartbeatExecutorThreadPoolSize(), 0, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(), new ThreadFactoryBuilder().setNameFormat(
                    "DiscoveryClient-HeartbeatExecutor-%d").setDaemon(true).build());  //
            // use direct handoff

            // 支持缓存刷新的线程池
            cacheRefreshExecutor = new ThreadPoolExecutor(1,
                    clientConfig.getCacheRefreshExecutorThreadPoolSize(), 0, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(), new ThreadFactoryBuilder().setNameFormat(
                    "DiscoveryClient-CacheRefreshExecutor-%d").setDaemon(true).build());  // use
            // direct handoff

            // 网络通信组件
            eurekaTransport = new EurekaTransport();
            // 初始化网络通信组件
            scheduleServerEndpointTask(eurekaTransport, args);

            AzToRegionMapper azToRegionMapper;
            if (clientConfig.shouldUseDnsForFetchingServiceUrls()) { // false
                azToRegionMapper = new DNSBasedAzToRegionMapper(clientConfig);
            } else {
                azToRegionMapper = new PropertyBasedAzToRegionMapper(clientConfig);
            }
            if (null != remoteRegionsToFetch.get()) {
                azToRegionMapper.setRegionsToFetch(remoteRegionsToFetch.get().split(","));
            }
            instanceRegionChecker = new InstanceRegionChecker(azToRegionMapper,
                    clientConfig.getRegion());
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize DiscoveryClient!", e);
        }

        // 允许拉取注册表，并且拉取（全量、增量）注册表成功
        if (clientConfig.shouldFetchRegistry() && !fetchRegistry(false)) {
            // 抓取失败，从备份里抓取
            fetchRegistryFromBackup();
        }

        // call and execute the pre registration handler before all background tasks (inc
        // registration) is started
        if (this.preRegistrationHandler != null) {
            this.preRegistrationHandler.beforeRegistration();
        }
        // 初始化调度任务(注册、发现）
        initScheduledTasks();

        try {
            // 注册监视器
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register timers", e);
        }

        // This is a bit of hack to allow for existing code using DiscoveryManager.getInstance()
        // to work with DI'd DiscoveryClient
        DiscoveryManager.getInstance().setDiscoveryClient(this);
        DiscoveryManager.getInstance().setEurekaClientConfig(config);

        initTimestampMs = System.currentTimeMillis();
        logger.info("Discovery Client initialized at timestamp {} with initial instances count: " + "{}", initTimestampMs, this.getApplications().size());
    }

    private void scheduleServerEndpointTask(EurekaTransport eurekaTransport,
                                            AbstractDiscoveryClientOptionalArgs args) {


        // 请求头
        Collection<?> additionalFilters = args == null ? Collections.emptyList() :
                args.additionalFilters;

        // providedJerseyClient = null
        EurekaJerseyClient providedJerseyClient = args == null ? null : args.eurekaJerseyClient;

        // argsTransportClientFactories = null
        TransportClientFactories argsTransportClientFactories = null;
        if (args != null && args.getTransportClientFactories() != null) {
            argsTransportClientFactories = args.getTransportClientFactories();
        }

        // Ignore the raw types warnings since the client filter interface changed between jersey
        // 1/2
        // transportClientFactories = Jersey1TransportClientFactories
        @SuppressWarnings("rawtypes") TransportClientFactories transportClientFactories =
                argsTransportClientFactories == null ? new Jersey1TransportClientFactories() :
                        argsTransportClientFactories;

        // If the transport factory was not supplied with args, assume they are using jersey 1
        // for passivity
        // eurekaTransport.transportClientFactory = return new TransportClientFactory() {
        //            @Override
        //            public EurekaHttpClient newClient(EurekaEndpoint serviceUrl) {
        //                return metricsFactory.newClient(serviceUrl); // 最终使用的EurekaHttpClient
        //            }
        //
        //            @Override
        //            public void shutdown() {
        //                metricsFactory.shutdown();
        //                jerseyFactory.shutdown();
        //            }
        //        };
        eurekaTransport.transportClientFactory = providedJerseyClient == null ?
                transportClientFactories.newTransportClientFactory(clientConfig,
                        additionalFilters, applicationInfoManager.getInfo()) :
                transportClientFactories.newTransportClientFactory(additionalFilters,
                        providedJerseyClient);

        ApplicationsResolver.ApplicationsSource applicationsSource =
                new ApplicationsResolver.ApplicationsSource() {
                    @Override
                    public Applications getApplications(int stalenessThreshold, TimeUnit timeUnit) {
                        long thresholdInMs = TimeUnit.MILLISECONDS.convert(stalenessThreshold,
                                timeUnit);
                        long delay = getLastSuccessfulRegistryFetchTimePeriod();
                        if (delay > thresholdInMs) {
                            logger.info("Local registry is too stale for local lookup. " +
                                    "Threshold:{},"
                                    + " " + "actual:{}", thresholdInMs, delay);
                            return null;
                        } else {
                            return localRegionApps.get();
                        }
                    }
                };

        eurekaTransport.bootstrapResolver = EurekaHttpClients.newBootstrapResolver(
                clientConfig,
                transportConfig,
                eurekaTransport.transportClientFactory,
                applicationInfoManager.getInfo(),
                applicationsSource);

        // 允许把自己注册到服务注册中心
        if (clientConfig.shouldRegisterWithEureka()) {
            EurekaHttpClientFactory newRegistrationClientFactory = null;
            EurekaHttpClient newRegistrationClient = null;
            try {
                newRegistrationClientFactory =
                        EurekaHttpClients.registrationClientFactory(
                                eurekaTransport.bootstrapResolver,
                                // metricsFactory
                                eurekaTransport.transportClientFactory,
                                transportConfig);
                newRegistrationClient = newRegistrationClientFactory.newClient();
            } catch (Exception e) {
                logger.warn("Transport initialization failure", e);
            }
            eurekaTransport.registrationClientFactory = newRegistrationClientFactory;
            eurekaTransport.registrationClient = newRegistrationClient;
        }

        // new method (resolve from primary servers for read)
        // Configure new transport layer (candidate for injecting in the future)
        if (clientConfig.shouldFetchRegistry()) {
            EurekaHttpClientFactory newQueryClientFactory = null;
            EurekaHttpClient newQueryClient = null;
            try {
                newQueryClientFactory =
                        EurekaHttpClients.queryClientFactory(eurekaTransport.bootstrapResolver,
                                eurekaTransport.transportClientFactory, clientConfig,
                                transportConfig, applicationInfoManager.getInfo(),
                                applicationsSource);
                newQueryClient = newQueryClientFactory.newClient();
            } catch (Exception e) {
                logger.warn("Transport initialization failure", e);
            }
            eurekaTransport.queryClientFactory = newQueryClientFactory;
            eurekaTransport.queryClient = newQueryClient;
        }
    }

    @Override
    public EurekaClientConfig getEurekaClientConfig() {
        return clientConfig;
    }

    @Override
    public ApplicationInfoManager getApplicationInfoManager() {
        return applicationInfoManager;
    }

    /*
     * (non-Javadoc)
     * @see com.netflix.discovery.shared.LookupService#getApplication(java.lang.String)
     */
    @Override
    public Application getApplication(String appName) {
        return getApplications().getRegisteredApplications(appName);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.discovery.shared.LookupService#getApplications()
     */
    @Override
    public Applications getApplications() {
        // AtomicReference<Applications> localRegionApps
        return localRegionApps.get();
    }

    @Override
    public Applications getApplicationsForARegion(@Nullable String region) {
        if (instanceRegionChecker.isLocalRegion(region)) {
            return localRegionApps.get();
        } else {
            return remoteRegionVsApps.get(region);
        }
    }

    public Set<String> getAllKnownRegions() {
        String localRegion = instanceRegionChecker.getLocalRegion();
        if (!remoteRegionVsApps.isEmpty()) {
            Set<String> regions = remoteRegionVsApps.keySet();
            Set<String> toReturn = new HashSet<String>(regions);
            toReturn.add(localRegion);
            return toReturn;
        } else {
            return Collections.singleton(localRegion);
        }
    }

    /*
     * (non-Javadoc)
     * @see com.netflix.discovery.shared.LookupService#getInstancesById(java.lang.String)
     */
    @Override
    public List<InstanceInfo> getInstancesById(String id) {
        List<InstanceInfo> instancesList = new ArrayList<InstanceInfo>();
        for (Application app : this.getApplications().getRegisteredApplications()) {
            InstanceInfo instanceInfo = app.getByInstanceId(id);
            if (instanceInfo != null) {
                instancesList.add(instanceInfo);
            }
        }
        return instancesList;
    }

    /**
     * Register {@link HealthCheckCallback} with the eureka client.
     * <p>
     * Once registered, the eureka client will invoke the
     * {@link HealthCheckCallback} in intervals specified by
     * {@link EurekaClientConfig#getInstanceInfoReplicationIntervalSeconds()}.
     *
     * @param callback app specific healthcheck.
     * @deprecated Use
     */
    @Deprecated
    @Override
    public void registerHealthCheckCallback(HealthCheckCallback callback) {
        if (instanceInfo == null) {
            logger.error("Cannot register a listener for instance info since it is null!");
        }
        if (callback != null) {
            healthCheckHandler = new HealthCheckCallbackToHandlerBridge(callback);
        }
    }

    @Override
    public void registerHealthCheck(HealthCheckHandler healthCheckHandler) {
        if (instanceInfo == null) {
            logger.error("Cannot register a healthcheck handler when instance info is null!");
        }
        if (healthCheckHandler != null) {
            this.healthCheckHandler = healthCheckHandler;
            // schedule an onDemand update of the instanceInfo when a new healthcheck handler is
            // registered
            if (instanceInfoReplicator != null) {
                instanceInfoReplicator.onDemandUpdate();
            }
        }
    }

    @Override
    public void registerEventListener(EurekaEventListener eventListener) {
        this.eventListeners.add(eventListener);
    }

    @Override
    public boolean unregisterEventListener(EurekaEventListener eventListener) {
        return this.eventListeners.remove(eventListener);
    }

    /**
     * Gets the list of instances matching the given VIP Address.
     *
     * @param vipAddress - The VIP address to match the instances for.
     * @param secure     - true if it is a secure vip address, false otherwise
     * @return - The list of {@link InstanceInfo} objects matching the criteria
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure) {
        return getInstancesByVipAddress(vipAddress, secure, instanceRegionChecker.getLocalRegion());
    }

    /**
     * Gets the list of instances matching the given VIP Address in the passed region.
     *
     * @param vipAddress - The VIP address to match the instances for.
     * @param secure     - true if it is a secure vip address, false otherwise
     * @param region     - region from which the instances are to be fetched. If
     *                   <code>null</code> then local region is
     *                   assumed.
     * @return - The list of {@link InstanceInfo} objects matching the criteria, empty list if
     * not instances found.
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure,
                                                       @Nullable String region) {
        if (vipAddress == null) {
            throw new IllegalArgumentException("Supplied VIP Address cannot be null");
        }
        Applications applications;
        if (instanceRegionChecker.isLocalRegion(region)) {
            applications = this.localRegionApps.get();
        } else {
            applications = remoteRegionVsApps.get(region);
            if (null == applications) {
                logger.debug("No applications are defined for region {}, so returning an empty " + "instance list for vip " + "address {}.", region, vipAddress);
                return Collections.emptyList();
            }
        }

        if (!secure) {
            return applications.getInstancesByVirtualHostName(vipAddress);
        } else {
            return applications.getInstancesBySecureVirtualHostName(vipAddress);

        }

    }

    /**
     * Gets the list of instances matching the given VIP Address and the given
     * application name if both of them are not null. If one of them is null,
     * then that criterion is completely ignored for matching instances.
     *
     * @param vipAddress - The VIP address to match the instances for.
     * @param appName    - The applicationName to match the instances for.
     * @param secure     - true if it is a secure vip address, false otherwise.
     * @return - The list of {@link InstanceInfo} objects matching the criteria.
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddressAndAppName(String vipAddress,
                                                                 String appName, boolean secure) {

        List<InstanceInfo> result = new ArrayList<InstanceInfo>();
        if (vipAddress == null && appName == null) {
            throw new IllegalArgumentException("Supplied VIP Address and application name cannot "
                    + "both be null");
        } else if (vipAddress != null && appName == null) {
            return getInstancesByVipAddress(vipAddress, secure);
        } else if (vipAddress == null && appName != null) {
            Application application = getApplication(appName);
            if (application != null) {
                result = application.getInstances();
            }
            return result;
        }

        String instanceVipAddress;
        for (Application app : getApplications().getRegisteredApplications()) {
            for (InstanceInfo instance : app.getInstances()) {
                if (secure) {
                    instanceVipAddress = instance.getSecureVipAddress();
                } else {
                    instanceVipAddress = instance.getVIPAddress();
                }
                if (instanceVipAddress == null) {
                    continue;
                }
                String[] instanceVipAddresses = instanceVipAddress.split(COMMA_STRING);

                // If the VIP Address is delimited by a comma, then consider to
                // be a list of VIP Addresses.
                // Try to match at least one in the list, if it matches then
                // return the instance info for the same
                for (String vipAddressFromList : instanceVipAddresses) {
                    if (vipAddress.equalsIgnoreCase(vipAddressFromList.trim()) && appName.equalsIgnoreCase(instance.getAppName())) {
                        result.add(instance);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.netflix.discovery.shared.LookupService#getNextServerFromEureka(java
     * .lang.String, boolean)
     */
    @Override
    public InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure) {
        List<InstanceInfo> instanceInfoList = this.getInstancesByVipAddress(virtualHostname,
                secure);
        if (instanceInfoList == null || instanceInfoList.isEmpty()) {
            throw new RuntimeException("No matches for the virtual host name :" + virtualHostname);
        }
        Applications apps = this.localRegionApps.get();
        int index =
                (int) (apps.getNextIndex(virtualHostname.toUpperCase(Locale.ROOT), secure).incrementAndGet() % instanceInfoList.size());
        return instanceInfoList.get(index);
    }

    /**
     * Get all applications registered with a specific eureka service.
     *
     * @param serviceUrl - The string representation of the service url.
     * @return - The registry information containing all applications.
     */
    @Override
    public Applications getApplications(String serviceUrl) {
        try {
            EurekaHttpResponse<Applications> response =
                    clientConfig.getRegistryRefreshSingleVipAddress() == null ?
                            eurekaTransport.queryClient.getApplications() :
                            eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress());
            if (response.getStatusCode() == 200) {
                logger.debug(PREFIX + appPathIdentifier + " -  refresh status: " + response.getStatusCode());
                return response.getEntity();
            }
            logger.error(PREFIX + appPathIdentifier + " - was unable to refresh its cache! " +
                    "status" + " = " + response.getStatusCode());
        } catch (Throwable th) {
            logger.error(PREFIX + appPathIdentifier + " - was unable to refresh its cache! " +
                    "status" + " = " + th.getMessage(), th);
        }
        return null;
    }

    /**
     * Register with the eureka service by making the appropriate REST call.
     */
    boolean register() throws Throwable {
        logger.info(PREFIX + appPathIdentifier + ": registering service...");
        EurekaHttpResponse<Void> httpResponse;
        try {
            httpResponse = eurekaTransport.registrationClient.register(instanceInfo);
        } catch (Exception e) {
            logger.warn("{} - registration failed {}", PREFIX + appPathIdentifier, e.getMessage()
                    , e);
            throw e;
        }
        if (logger.isInfoEnabled()) {
            logger.info("{} - registration status: {}", PREFIX + appPathIdentifier,
                    httpResponse.getStatusCode());
        }
        return httpResponse.getStatusCode() == 204;
    }

    /**
     * Renew with the eureka service by making the appropriate REST call
     */
    boolean renew() {
        EurekaHttpResponse<InstanceInfo> httpResponse;
        try {
            httpResponse =
                    eurekaTransport.registrationClient.sendHeartBeat(instanceInfo.getAppName(),
                            instanceInfo.getId(), instanceInfo, null);
            logger.debug("{} - Heartbeat status: {}", PREFIX + appPathIdentifier,
                    httpResponse.getStatusCode());
            if (httpResponse.getStatusCode() == 404) {
                REREGISTER_COUNTER.increment();
                logger.info("{} - Re-registering apps/{}", PREFIX + appPathIdentifier,
                        instanceInfo.getAppName());
                long timestamp = instanceInfo.setIsDirtyWithTime();
                boolean success = register();
                if (success) {
                    instanceInfo.unsetIsDirty(timestamp);
                }
                return success;
            }
            return httpResponse.getStatusCode() == 200;
        } catch (Throwable e) {
            logger.error("{} - was unable to send heartbeat!", PREFIX + appPathIdentifier, e);
            return false;
        }
    }

    /**
     * @param instanceZone   The zone in which the client resides
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise
     * @return The list of all eureka service urls for the eureka client to talk to
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     * <p>
     * Get the list of all eureka service urls from properties file for the eureka client to talk
     * to.
     */
    @Deprecated
    @Override
    public List<String> getServiceUrlsFromConfig(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromConfig(clientConfig, instanceZone, preferSameZone);
    }

    /**
     * Shuts down Eureka Client. Also sends a deregistration request to the
     * eureka server.
     */
    /**
     * 注解@PostConstruct：
     * <p>
     * 被@PostConstruct修饰的方法会在服务器加载Servlet的时候运行，并且只会被服务器调用一次，类似于Servlet的inti()
     * 方法。被@PostConstruct修饰的方法会在构造函数之后，init()方法之前运行。
     * <p>
     * <p>
     * 注解@PreDestroy：
     * <p>
     * 被@PreDestroy修饰的方法会在服务器卸载Servlet的时候运行，并且只会被服务器调用一次，类似于Servlet的destroy()
     * 方法。被@PreDestroy修饰的方法会在destroy()方法之后运行，在Servlet被彻底卸载之前。
     */
    @PreDestroy
    @Override
    public synchronized void shutdown() {

        // 乐观锁保证只有一个线程能执行
        if (isShutdown.compareAndSet(false, true)) {
            logger.info("Shutting down DiscoveryClient ...");

            if (statusChangeListener != null && applicationInfoManager != null) {
                applicationInfoManager.unregisterStatusChangeListener(statusChangeListener.getId());
            }

            // 关闭线程池（调度器、心跳、缓存刷新、实例信息复制器）
            cancelScheduledTasks();

            // If APPINFO was registered
            if (applicationInfoManager != null && clientConfig.shouldRegisterWithEureka()) {
                // 设置服务实例状态为DOWN
                applicationInfoManager.setInstanceStatus(InstanceStatus.DOWN);
                // eurekaTransport.registrationClient.cancel() 取消注册（服务下线）
                unregister();
            }

            if (eurekaTransport != null) {
                // 网络通信组件关闭
                eurekaTransport.shutdown();
            }

            // 监控器关闭
            heartbeatStalenessMonitor.shutdown();
            registryStalenessMonitor.shutdown();

            logger.info("Completed shut down of DiscoveryClient");
        }
    }

    /**
     * unregister w/ the eureka service.
     */
    void unregister() {
        // It can be null if shouldRegisterWithEureka == false
        if (eurekaTransport != null && eurekaTransport.registrationClient != null) {
            try {
                logger.info("Unregistering ...");
                EurekaHttpResponse<Void> httpResponse =
                        eurekaTransport.registrationClient.cancel(instanceInfo.getAppName(),
                                instanceInfo.getId());
                logger.info(PREFIX + appPathIdentifier + " - deregister  status: " + httpResponse.getStatusCode());
            } catch (Exception e) {
                logger.error(PREFIX + appPathIdentifier + " - de-registration failed" + e.getMessage(), e);
            }
        }
    }

    /**
     * Fetches the registry information.
     *
     * <p>
     * This method tries to get only deltas after the first fetch unless there
     * is an issue in reconciling eureka server and client registry information.
     * </p>
     *
     * @param forceFullRegistryFetch Forces a full registry fetch.
     * @return true if the registry was fetched
     */
    private boolean fetchRegistry(
            boolean forceFullRegistryFetch) {
        Stopwatch tracer = FETCH_REGISTRY_TIMER.start();

        try {
            // If the delta is disabled or if it is the first time, get all applications
            // 如果增量未启用，或者这是第一次调用，获取所有的application
            // 增量抓取时：由于内部的eureka client启动时已经全量抓取过了，此时applications != null
            Applications applications = getApplications();

            if (
                // eureka.disableDelta = null(false)
                    clientConfig.shouldDisableDelta() ||
                            (!Strings.isNullOrEmpty(clientConfig.getRegistryRefreshSingleVipAddress())) ||
                            forceFullRegistryFetch ||
                            (applications == null /** true **/) ||
                            (applications.getRegisteredApplications().size() == 0) ||
                            (applications.getVersion() == -1)
            ) //Client application does not have latest library supporting delta
            {
                logger.info("Disable delta property : {}", clientConfig.shouldDisableDelta());
                logger.info("Single vip registry refresh property : {}",
                        clientConfig.getRegistryRefreshSingleVipAddress());
                logger.info("Force full registry fetch : {}", forceFullRegistryFetch);
                logger.info("Application is null : {}", (applications == null));
                logger.info("Registered Applications size is zero : {}",
                        (applications.getRegisteredApplications().size() == 0));
                logger.info("Application version is -1: {}", (applications.getVersion() == -1));
                // 获取并缓存全量注册表
                getAndStoreFullRegistry();
            } else {
                // 获取并更新增量注册表
                getAndUpdateDelta(applications);
            }
            applications.setAppsHashCode(
                    // 设置 applications 的 一致性Hash值（eureka server 全量表的HashCode）
                    applications.getReconcileHashCode());
            // 大哥日志
            logTotalInstances();
        } catch (Throwable e) {
            logger.error(PREFIX + appPathIdentifier + " - was unable to refresh its cache! " +
                    "status" + " = " + e.getMessage(), e);
            return false;
        } finally {
            if (tracer != null) {
                tracer.stop();
            }
        }

        // Notify about cache refresh before updating the instance remote status
        onCacheRefreshed();

        // Update remote status based on refreshed data held in the cache
        updateInstanceRemoteStatus();

        // registry was fetched successfully, so return true
        return true;
    }

    private synchronized void updateInstanceRemoteStatus() {
        // Determine this instance's status for this app and set to UNKNOWN if not found
        InstanceInfo.InstanceStatus currentRemoteInstanceStatus = null;
        if (instanceInfo.getAppName() != null) {
            Application app = getApplication(instanceInfo.getAppName());
            if (app != null) {
                InstanceInfo remoteInstanceInfo = app.getByInstanceId(instanceInfo.getId());
                if (remoteInstanceInfo != null) {
                    currentRemoteInstanceStatus = remoteInstanceInfo.getStatus();
                }
            }
        }
        if (currentRemoteInstanceStatus == null) {
            currentRemoteInstanceStatus = InstanceInfo.InstanceStatus.UNKNOWN;
        }

        // Notify if status changed
        if (lastRemoteInstanceStatus != currentRemoteInstanceStatus) {
            onRemoteStatusChanged(lastRemoteInstanceStatus, currentRemoteInstanceStatus);
            lastRemoteInstanceStatus = currentRemoteInstanceStatus;
        }
    }

    /**
     * @return Return he current instance status as seen on the Eureka server.
     */
    @Override
    public InstanceInfo.InstanceStatus getInstanceRemoteStatus() {
        return lastRemoteInstanceStatus;
    }

    private String getReconcileHashCode(Applications applications) {
        TreeMap<String, AtomicInteger> instanceCountMap = new TreeMap<String, AtomicInteger>();
        if (isFetchingRemoteRegionRegistries()) {
            for (Applications remoteApp : remoteRegionVsApps.values()) {
                remoteApp.populateInstanceCountMap(instanceCountMap);
            }
        }
        applications.populateInstanceCountMap(instanceCountMap);
        return Applications.getReconcileHashCode(instanceCountMap);
    }

    /**
     * Gets the full registry information from the eureka server and stores it locally.
     * When applying the full registry, the following flow is observed:
     * <p>
     * if (update generation have not advanced (due to another thread))
     * atomically set the registry to the new registry
     * fi
     *
     * @return the full registry information.
     * @throws Throwable on error.
     */
    private void getAndStoreFullRegistry() throws Throwable {
        long currentUpdateGeneration = fetchRegistryGeneration.get();

        logger.info("Getting all instance registry info from the eureka server");

        Applications apps = null;
        EurekaHttpResponse<Applications> httpResponse =
                clientConfig.getRegistryRefreshSingleVipAddress() == null /** true **/ ?
                        eurekaTransport.queryClient.getApplications(
                                // null
                                remoteRegionsRef.get()) :
                        eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress(), remoteRegionsRef.get());
        if (httpResponse.getStatusCode() == Status.OK.getStatusCode()) {
            apps = httpResponse.getEntity();
        }
        logger.info("The response status is {}", httpResponse.getStatusCode());

        if (apps == null) {
            logger.error("The application is null for some reason. Not storing this information");
        } else if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration,
                currentUpdateGeneration + 1)) {
            // AtomicReference<Applications>
            // 更新本地服务实例缓存
            localRegionApps.set(
                    // 过滤服务状态是UP的服务实例并且打乱顺序
                    this.filterAndShuffle(apps));
            logger.debug("Got full registry with apps hashcode {}", apps.getAppsHashCode());
        } else {
            logger.warn("Not updating applications as another thread is updating it already");
        }
    }

    /**
     * Get the delta registry information from the eureka server and update it locally.
     * When applying the delta, the following flow is observed:
     * <p>
     * if (update generation have not advanced (due to another thread))
     * atomically try to: update application with the delta and get reconcileHashCode
     * abort entire processing otherwise
     * do reconciliation if reconcileHashCode clash
     * fi
     *
     * @return the client response
     * @throws Throwable on error
     */
    private void getAndUpdateDelta(Applications applications) throws Throwable {
        long currentUpdateGeneration = fetchRegistryGeneration.get();

        Applications delta = null;
        // 请求 eureka server 获取 delta
        EurekaHttpResponse<Applications> httpResponse =
                eurekaTransport.queryClient.getDelta(remoteRegionsRef.get());
        if (httpResponse.getStatusCode() == Status.OK.getStatusCode()) {
            delta = httpResponse.getEntity();
        }

        if (delta == null) {
            logger.warn("The server does not allow the delta revision to be applied because it " + "is" + " not safe. " + "Hence got the full registry.");
            getAndStoreFullRegistry(); // 如果增量注册表为空，拉取全量注册表
        } else if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration,
                currentUpdateGeneration + 1)) {
            logger.debug("Got delta update with apps hashcode {}", delta.getAppsHashCode());
            String reconcileHashCode = "";
            if (fetchRegistryUpdateLock.tryLock()) {
                try {
                    updateDelta(delta);
                    reconcileHashCode = getReconcileHashCode(applications);
                } finally {
                    fetchRegistryUpdateLock.unlock();
                }
            } else {
                logger.warn("Cannot acquire update lock, aborting getAndUpdateDelta");
            }
            // There is a diff in number of instances for some reason
            // eureka.printDeltaFullDiff = false
            if (!reconcileHashCode.equals(delta.getAppsHashCode()) || clientConfig.shouldLogDeltaDiff()) {
                // 重新拉取全量注册表
                reconcileAndLogDifference(delta, reconcileHashCode);  // this makes a remoteCall
            }
        } else {
            logger.warn("Not updating application delta as another thread is updating it already");
            logger.debug("Ignoring delta update with apps hashcode {}, as another thread is " +
                    "updating it already", delta.getAppsHashCode());
        }
    }

    /**
     * Logs the total number of non-filtered instances stored locally.
     */
    private void logTotalInstances() {
        if (logger.isDebugEnabled()) {
            int totInstances = 0;
            for (Application application : getApplications().getRegisteredApplications()) {
                totInstances += application.getInstancesAsIsFromEureka().size();
            }
            logger.debug("The total number of all instances in the client now is {}", totInstances);
        }
    }

    /**
     * Reconcile the eureka server and client registry information and logs the differences if any.
     * When reconciling, the following flow is observed:
     * <p>
     * make a remote call to the server for the full registry
     * calculate and log differences
     * if (update generation have not advanced (due to another thread))
     * atomically set the registry to the new registry
     * fi
     *
     * @param delta             the last delta registry information received from the eureka
     *                          server.
     * @param reconcileHashCode the hashcode generated by the server for reconciliation.
     * @return ClientResponse the HTTP response object.
     * @throws Throwable on any error.
     */
    private void reconcileAndLogDifference(Applications delta, String reconcileHashCode) throws Throwable {
        logger.debug("The Reconcile hashcodes do not match, client : {}, server : {}. Getting " + "the" + " full registry", reconcileHashCode, delta.getAppsHashCode());

        RECONCILE_HASH_CODES_MISMATCH.increment();

        long currentUpdateGeneration = fetchRegistryGeneration.get();

        EurekaHttpResponse<Applications> httpResponse =
                clientConfig.getRegistryRefreshSingleVipAddress() == null ?
                        eurekaTransport.queryClient.getApplications(remoteRegionsRef.get()) :
                        eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress(), remoteRegionsRef.get());
        Applications serverApps = httpResponse.getEntity();

        if (serverApps == null) {
            logger.warn("Cannot fetch full registry from the server; reconciliation failure");
            return;
        }

        if (logger.isDebugEnabled()) {
            try {
                Map<String, List<String>> reconcileDiffMap =
                        getApplications().getReconcileMapDiff(serverApps);
                StringBuilder reconcileBuilder = new StringBuilder("");
                for (Map.Entry<String, List<String>> mapEntry : reconcileDiffMap.entrySet()) {
                    reconcileBuilder.append(mapEntry.getKey()).append(": ");
                    for (String displayString : mapEntry.getValue()) {
                        reconcileBuilder.append(displayString);
                    }
                    reconcileBuilder.append('\n');
                }
                String reconcileString = reconcileBuilder.toString();
                logger.debug("The reconcile string is {}", reconcileString);
            } catch (Throwable e) {
                logger.error("Could not calculate reconcile string ", e);
            }
        }

        if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration,
                currentUpdateGeneration + 1)) {
            localRegionApps.set(this.filterAndShuffle(serverApps));
            getApplications().setVersion(delta.getVersion());
            logger.debug("The Reconcile hashcodes after complete sync up, client : {}, server : " + "{}.", getApplications().getReconcileHashCode(), delta.getAppsHashCode());
        } else {
            logger.warn("Not setting the applications map as another thread has advanced the " +
                    "update " + "generation");
        }
    }

    /**
     * Updates the delta information fetches from the eureka server into the
     * local cache.
     *
     * @param delta the delta information received from eureka server in the last
     *              poll cycle.
     */
    private void updateDelta(Applications delta) {
        int deltaCount = 0;
        for (Application app : delta.getRegisteredApplications()) {
            for (InstanceInfo instance : app.getInstances()) {
                // 本地缓存的全量注册表
                Applications applications = getApplications();

                String instanceRegion = instanceRegionChecker.getInstanceRegion(instance);

                if (!instanceRegionChecker.isLocalRegion(instanceRegion)) {
                    Applications remoteApps = remoteRegionVsApps.get(instanceRegion);
                    if (null == remoteApps) {
                        remoteApps = new Applications();
                        remoteRegionVsApps.put(instanceRegion, remoteApps);
                    }
                    applications = remoteApps;
                }

                ++deltaCount;

                if (ActionType.ADDED.equals(instance.getActionType())) { // 注册服务实例
                    Application existingApp =
                            applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Added instance {} to the existing apps in region {}",
                            instance.getId(), instanceRegion);
                    applications.getRegisteredApplications(instance.getAppName()).addInstance(instance);
                } else if (ActionType.MODIFIED.equals(instance.getActionType())) { // 服务实例状态变更
                    Application existingApp =
                            applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Modified instance {} to the existing apps ", instance.getId());

                    applications.getRegisteredApplications(instance.getAppName()).addInstance(instance);

                } else if (ActionType.DELETED.equals(instance.getActionType())) { // 删除服务实例
                    Application existingApp =
                            applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Deleted instance {} to the existing apps ", instance.getId());
                    applications.getRegisteredApplications(instance.getAppName()).removeInstance(instance);
                }
            }
        }
        logger.debug("The total number of instances fetched by the delta processor : {}",
                deltaCount);

        getApplications().setVersion(delta.getVersion());
        getApplications().shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());

        for (Applications applications : remoteRegionVsApps.values()) {
            applications.setVersion(delta.getVersion());
            applications.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
        }
    }

    /**
     * Initializes all scheduled tasks.
     */
    private void initScheduledTasks() {
        // 允许抓取注册表
        if (clientConfig.shouldFetchRegistry()) {
            // registry cache refresh timer
            // 增量拉取注册表间隔时间。默认30秒
            int registryFetchIntervalSeconds = clientConfig.getRegistryFetchIntervalSeconds();
            // eureka.client.cacheRefresh.exponentialBackOffBound = 10
            int expBackOffBound = clientConfig.getCacheRefreshExecutorExponentialBackOffBound();

            // 抓取注册表的调度器
            scheduler.schedule(new TimedSupervisorTask("cacheRefresh", scheduler,
                            cacheRefreshExecutor, registryFetchIntervalSeconds, TimeUnit.SECONDS,
                            expBackOffBound, new CacheRefreshThread()),
                    // 30秒
                    registryFetchIntervalSeconds,
                    TimeUnit.SECONDS);
        }

        // 允许注册服务到注册中心
        if (clientConfig.shouldRegisterWithEureka()) {
            int renewalIntervalInSecs = instanceInfo.getLeaseInfo().getRenewalIntervalInSecs();
            int expBackOffBound = clientConfig.getHeartbeatExecutorExponentialBackOffBound();
            logger.info("Starting heartbeat executor: " + "renew interval is: " + renewalIntervalInSecs);

            // 发送心跳的调度器
            // Heartbeat timer
            scheduler.schedule(new TimedSupervisorTask("heartbeat", scheduler, heartbeatExecutor,
                    renewalIntervalInSecs, TimeUnit.SECONDS, expBackOffBound,
                    new HeartbeatThread()), renewalIntervalInSecs, TimeUnit.SECONDS);

            // 服务实例信息同步器
            // InstanceInfo replicator
            instanceInfoReplicator = new InstanceInfoReplicator(this, instanceInfo,
                    clientConfig.getInstanceInfoReplicationIntervalSeconds(), 2); // burstSize

            // 状态更换监听器
            statusChangeListener = new ApplicationInfoManager.StatusChangeListener() {
                @Override
                public String getId() {
                    return "statusChangeListener";
                }

                @Override
                public void notify(StatusChangeEvent statusChangeEvent) {
                    if (InstanceStatus.DOWN == statusChangeEvent.getStatus() || InstanceStatus.DOWN == statusChangeEvent.getPreviousStatus()) {
                        // log at warn level if DOWN was involved
                        logger.warn("Saw local status change event {}", statusChangeEvent);
                    } else {
                        logger.info("Saw local status change event {}", statusChangeEvent);
                    }
                    instanceInfoReplicator.onDemandUpdate();
                }
            };

            if (clientConfig.shouldOnDemandUpdateStatusChange()) {
                applicationInfoManager.registerStatusChangeListener(statusChangeListener);
            }

            instanceInfoReplicator.start(
                    // 40S
                    clientConfig.getInitialInstanceInfoReplicationIntervalSeconds());
        } else {
            logger.info("Not registering with Eureka server per configuration");
        }
    }

    private void cancelScheduledTasks() {
        if (instanceInfoReplicator != null) {
            instanceInfoReplicator.stop();
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        if (cacheRefreshExecutor != null) {
            cacheRefreshExecutor.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * @param instanceZone   The zone in which the client resides.
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise.
     * @return The list of all eureka service urls for the eureka client to talk to.
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     * <p>
     * Get the list of all eureka service urls from DNS for the eureka client to
     * talk to. The client picks up the service url from its zone and then fails over to
     * other zones randomly. If there are multiple servers in the same zone, the client once
     * again picks one randomly. This way the traffic will be distributed in the case of failures.
     */
    @Deprecated
    @Override
    public List<String> getServiceUrlsFromDNS(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromDNS(clientConfig, instanceZone, preferSameZone,
                urlRandomizer);
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     */
    @Deprecated
    @Override
    public List<String> getDiscoveryServiceUrls(String zone) {
        return EndpointUtils.getDiscoveryServiceUrls(clientConfig, zone, urlRandomizer);
    }

    /**
     * @param dnsName The dns name of the zone-specific CNAME
     * @param type    CNAME or EIP that needs to be retrieved
     * @return The list of EC2 URLs associated with the dns name
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     * <p>
     * Get the list of EC2 URLs given the zone name.
     */
    @Deprecated
    public static Set<String> getEC2DiscoveryUrlsFromZone(String dnsName,
                                                          EndpointUtils.DiscoveryUrlType type) {
        return EndpointUtils.getEC2DiscoveryUrlsFromZone(dnsName, type);
    }

    /**
     * Refresh the current local instanceInfo. Note that after a valid refresh where changes are
     * observed, the
     * isDirty flag on the instanceInfo is set to true
     */
    void refreshInstanceInfo() {
        // hostname、ipAddress如果发生变化的话就刷新一下
        applicationInfoManager.refreshDataCenterInfoIfRequired();
        // 租约信息如果发生变化的话就刷新一下
        applicationInfoManager.refreshLeaseInfoIfRequired();

        InstanceStatus status;
        try {
            // 通过健康检查处理器（HealthCheckHandler）获取InstanceStatus并设置到ApplicationInfoManager中
            status = getHealthCheckHandler().getStatus(instanceInfo.getStatus());
        } catch (Exception e) {
            logger.warn("Exception from healthcheckHandler.getStatus, setting status to DOWN", e);
            status = InstanceStatus.DOWN;
        }

        if (null != status) {
            applicationInfoManager.setInstanceStatus(status);
        }
    }

    /**
     * The heartbeat task that renews the lease in the given intervals.
     */
    private class HeartbeatThread implements Runnable {

        @Override
        public void run() {
            if (renew()) {
                lastSuccessfulHeartbeatTimestamp = System.currentTimeMillis();
            }
        }
    }

    @VisibleForTesting
    InstanceInfoReplicator getInstanceInfoReplicator() {
        return instanceInfoReplicator;
    }

    @VisibleForTesting
    InstanceInfo getInstanceInfo() {
        return instanceInfo;
    }

    @Override
    public HealthCheckHandler getHealthCheckHandler() {
        if (healthCheckHandler == null) {
            if (null != healthCheckHandlerProvider) {
                healthCheckHandler = healthCheckHandlerProvider.get();
            } else if (null != healthCheckCallbackProvider) {
                healthCheckHandler =
                        new HealthCheckCallbackToHandlerBridge(healthCheckCallbackProvider.get());
            }

            if (null == healthCheckHandler) {
                healthCheckHandler = new HealthCheckCallbackToHandlerBridge(null);
            }
        }

        return healthCheckHandler;
    }

    /**
     * The task that fetches the registry information at specified intervals.
     */
    class CacheRefreshThread implements Runnable {
        @Override
        public void run() {
            // 刷新注册表（定时任务）
            refreshRegistry();
        }
    }

    @VisibleForTesting
    void refreshRegistry() {
        try {
            // false
            boolean isFetchingRemoteRegionRegistries = isFetchingRemoteRegionRegistries();

            boolean remoteRegionsModified = false;
            // This makes sure that a dynamic change to remote regions to fetch is honored.
            // latestRemoteRegions = null
            String latestRemoteRegions = clientConfig.fetchRegistryForRemoteRegions();
            if (null != latestRemoteRegions) {
                String currentRemoteRegions = remoteRegionsToFetch.get();
                if (!latestRemoteRegions.equals(currentRemoteRegions)) {
                    // Both remoteRegionsToFetch and AzToRegionMapper.regionsToFetch need to be
                    // in sync
                    synchronized (instanceRegionChecker.getAzToRegionMapper()) {
                        if (remoteRegionsToFetch.compareAndSet(currentRemoteRegions,
                                latestRemoteRegions)) {
                            String[] remoteRegions = latestRemoteRegions.split(",");
                            remoteRegionsRef.set(remoteRegions);
                            instanceRegionChecker.getAzToRegionMapper().setRegionsToFetch(remoteRegions);
                            remoteRegionsModified = true;
                        } else {
                            logger.info("Remote regions to fetch modified concurrently," + " " +
                                            "ignoring change from {} to {}", currentRemoteRegions,
                                    latestRemoteRegions);
                        }
                    }
                } else {
                    // Just refresh mapping to reflect any DNS/Property change
                    instanceRegionChecker.getAzToRegionMapper().refreshMapping();
                }
            }

            // 抓取注册表（remoteRegionsModified = false)
            boolean success = fetchRegistry(
                    // false
                    remoteRegionsModified);
            if (success) {
                registrySize = localRegionApps.get().size();
                lastSuccessfulRegistryFetchTimestamp = System.currentTimeMillis();
            }

            if (logger.isDebugEnabled()) {
                StringBuilder allAppsHashCodes = new StringBuilder();
                allAppsHashCodes.append("Local region apps hashcode: ");
                allAppsHashCodes.append(localRegionApps.get().getAppsHashCode());
                allAppsHashCodes.append(", is fetching remote regions? ");
                allAppsHashCodes.append(isFetchingRemoteRegionRegistries);
                for (Map.Entry<String, Applications> entry : remoteRegionVsApps.entrySet()) {
                    allAppsHashCodes.append(", Remote region: ");
                    allAppsHashCodes.append(entry.getKey());
                    allAppsHashCodes.append(" , apps hashcode: ");
                    allAppsHashCodes.append(entry.getValue().getAppsHashCode());
                }
                logger.debug("Completed cache refresh task for discovery. All Apps hash code is " + "{} ", allAppsHashCodes.toString());
            }
        } catch (Throwable e) {
            logger.error("Cannot fetch registry from server", e);
        }
    }

    /**
     * Fetch the registry information from back up registry if all eureka server
     * urls are unreachable.
     */
    private void fetchRegistryFromBackup() {
        try {
            @SuppressWarnings("deprecation") BackupRegistry backupRegistryInstance =
                    newBackupRegistryInstance();
            if (null == backupRegistryInstance) { // backward compatibility with the old
                // protected method, in case it is being used.
                backupRegistryInstance = backupRegistryProvider.get();
            }

            if (null != backupRegistryInstance) {
                Applications apps = null;
                if (isFetchingRemoteRegionRegistries()) {
                    String remoteRegionsStr = remoteRegionsToFetch.get();
                    if (null != remoteRegionsStr) {
                        apps = backupRegistryInstance.fetchRegistry(remoteRegionsStr.split(","));
                    }
                } else {
                    apps = backupRegistryInstance.fetchRegistry();
                }
                if (apps != null) {
                    final Applications applications = this.filterAndShuffle(apps);
                    applications.setAppsHashCode(applications.getReconcileHashCode());
                    localRegionApps.set(applications);
                    logTotalInstances();
                    logger.info("Fetched registry successfully from the backup");
                }
            } else {
                logger.warn("No backup registry instance defined & unable to find any discovery " + "servers.");
            }
        } catch (Throwable e) {
            logger.warn("Cannot fetch applications from apps although backup registry was " +
                    "specified", e);
        }
    }

    /**
     * @deprecated Use injection to provide {@link BackupRegistry} implementation.
     */
    @Deprecated
    @Nullable
    protected BackupRegistry newBackupRegistryInstance() throws ClassNotFoundException,
            IllegalAccessException, InstantiationException {
        return null;
    }

    /**
     * Gets the <em>applications</em> after filtering the applications for
     * instances with only UP states and shuffling them.
     *
     * <p>
     * The filtering depends on the option specified by the configuration
     * {@link EurekaClientConfig#shouldFilterOnlyUpInstances()}. Shuffling helps
     * in randomizing the applications list there by avoiding the same instances
     * receiving traffic during start ups.
     * </p>
     *
     * @param apps The applications that needs to be filtered and shuffled.
     * @return The applications after the filter and the shuffle.
     */
    private Applications filterAndShuffle(Applications apps) {
        if (apps != null) {
            if (
                // false
                    isFetchingRemoteRegionRegistries()) {
                Map<String, Applications> remoteRegionVsApps = new ConcurrentHashMap<String,
                        Applications>();
                apps.shuffleAndIndexInstances(remoteRegionVsApps, clientConfig,
                        instanceRegionChecker);
                for (Applications applications : remoteRegionVsApps.values()) {
                    applications.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
                }
                this.remoteRegionVsApps = remoteRegionVsApps;
            } else {
                apps.shuffleInstances(
                        // true
                        clientConfig.shouldFilterOnlyUpInstances());
            }
        }
        return apps;
    }

    private boolean isFetchingRemoteRegionRegistries() {
        // null != null => false
        return null != remoteRegionsToFetch.get();
    }

    /**
     * Invoked when the remote status of this client has changed.
     * Subclasses may override this method to implement custom behavior if needed.
     *
     * @param oldStatus the previous remote {@link InstanceStatus}
     * @param newStatus the new remote {@link InstanceStatus}
     */
    protected void onRemoteStatusChanged(InstanceInfo.InstanceStatus oldStatus,
                                         InstanceInfo.InstanceStatus newStatus) {
        fireEvent(new StatusChangeEvent(oldStatus, newStatus));
    }


    /**
     * Invoked every time the local registry cache is refreshed (whether changes have
     * been detected or not).
     * <p>
     * Subclasses may override this method to implement custom behavior if needed.
     */
    protected void onCacheRefreshed() {
        fireEvent(new CacheRefreshedEvent());
    }

    /**
     * Send the given event on the EventBus if one is available
     *
     * @param event the event to send on the eventBus
     */
    protected void fireEvent(final EurekaEvent event) {
        for (EurekaEventListener listener : eventListeners) {
            listener.onEvent(event);
        }
    }


    /**
     * @param myInfo - The InstanceInfo object of the instance.
     * @return - The zone in which the particular instance belongs to.
     * @deprecated see {@link com.netflix.appinfo.InstanceInfo#getZone(String[], com.netflix.appinfo.InstanceInfo)}
     * <p>
     * Get the zone that a particular instance is in.
     */
    @Deprecated
    public static String getZone(InstanceInfo myInfo) {
        String[] availZones =
                staticClientConfig.getAvailabilityZones(staticClientConfig.getRegion());
        return InstanceInfo.getZone(availZones, myInfo);
    }

    /**
     * @return - The region in which the particular instance belongs to.
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     * <p>
     * Get the region that this particular instance is in.
     */
    @Deprecated
    public static String getRegion() {
        String region = staticClientConfig.getRegion();
        if (region == null) {
            region = "default";
        }
        region = region.trim().toLowerCase();
        return region;
    }

    /**
     * @deprecated use {@link #getServiceUrlsFromConfig(String, boolean)} instead.
     */
    @Deprecated
    public static List<String> getEurekaServiceUrlsFromConfig(String instanceZone,
                                                              boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromConfig(staticClientConfig, instanceZone,
                preferSameZone);
    }

    public long getLastSuccessfulHeartbeatTimePeriod() {
        return lastSuccessfulHeartbeatTimestamp < 0 ? lastSuccessfulHeartbeatTimestamp :
                System.currentTimeMillis() - lastSuccessfulHeartbeatTimestamp;
    }

    public long getLastSuccessfulRegistryFetchTimePeriod() {
        return lastSuccessfulRegistryFetchTimestamp < 0 ? lastSuccessfulRegistryFetchTimestamp :
                System.currentTimeMillis() - lastSuccessfulRegistryFetchTimestamp;
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRATION_PREFIX +
            "lastSuccessfulHeartbeatTimePeriod", description =
            "How much time has passed from " + "last successful heartbeat", type =
            DataSourceType.GAUGE)
    private long getLastSuccessfulHeartbeatTimePeriodInternal() {
        long delay = getLastSuccessfulHeartbeatTimePeriod();
        heartbeatStalenessMonitor.update(computeStalenessMonitorDelay(delay));
        return delay;
    }

    // for metrics only
    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX +
            "lastSuccessfulRegistryFetchTimePeriod", description = "How much time has passed " +
            "from" + " last successful local registry update", type = DataSourceType.GAUGE)
    private long getLastSuccessfulRegistryFetchTimePeriodInternal() {
        long delay = getLastSuccessfulRegistryFetchTimePeriod();
        registryStalenessMonitor.update(computeStalenessMonitorDelay(delay));
        return delay;
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "localRegistrySize",
            description = "Count of instances in the local registry", type = DataSourceType.GAUGE)
    public int localRegistrySize() {
        return registrySize;
    }


    private long computeStalenessMonitorDelay(long delay) {
        if (delay < 0) {
            return System.currentTimeMillis() - initTimestampMs;
        } else {
            return delay;
        }
    }

}
