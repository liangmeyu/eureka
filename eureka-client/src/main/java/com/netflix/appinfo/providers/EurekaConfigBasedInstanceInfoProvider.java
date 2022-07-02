package com.netflix.appinfo.providers;

import javax.inject.Singleton;
import javax.inject.Provider;
import java.util.Map;

import com.google.inject.Inject;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.InstanceInfo.PortType;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.appinfo.RefreshableInstanceConfig;
import com.netflix.appinfo.UniqueIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InstanceInfo provider that constructs the InstanceInfo this this instance using
 * EurekaInstanceConfig.
 * <p>
 * This provider is @Singleton scope as it provides the InstanceInfo for both DiscoveryClient
 * and ApplicationInfoManager, and need to provide the same InstanceInfo to both.
 *
 * @author elandau
 */
@Singleton
public class EurekaConfigBasedInstanceInfoProvider implements Provider<InstanceInfo> {
    private static final Logger LOG =
            LoggerFactory.getLogger(EurekaConfigBasedInstanceInfoProvider.class);

    private final EurekaInstanceConfig config;

    private InstanceInfo instanceInfo;

    @Inject(optional = true)
    private VipAddressResolver vipAddressResolver = null;

    @Inject
    public EurekaConfigBasedInstanceInfoProvider(EurekaInstanceConfig config) {
        this.config = config;
    }

    /**
     * 构造器模式 构造 eureka-client实例信息 实例
     *
     * @return
     */
    @Override
    public synchronized InstanceInfo get() {
        if (instanceInfo == null) {
            // eureka-client 实例信息 实例为null
            // 基于 eureka-client 实例配置信息 构建 租约信息
            // 续约间隔，单位是秒。默认30s
            // 租约过期自动摘除间隔，单位是秒。默认90s
            // Build the lease information to be passed to the server based on config
            LeaseInfo.Builder leaseInfoBuilder =
                    LeaseInfo.Builder.newBuilder().setRenewalIntervalInSecs(config.getLeaseRenewalIntervalInSeconds()).setDurationInSecs(config.getLeaseExpirationDurationInSeconds());

            // 获取vip地址解析器
            if (vipAddressResolver == null) {
                vipAddressResolver = new Archaius1VipAddressResolver();
            }

            // 获取 eureka-client 实例信息构造器 实例
            // Builder the instance information to be registered with eureka server
            InstanceInfo.Builder builder = InstanceInfo.Builder.newBuilder(vipAddressResolver);

            // 为InstanceInfo设置合适的id，
            // set the appropriate id for the InstanceInfo, falling back to datacenter Id if
            // applicable, else hostname
            String instanceId = config.getInstanceId(); // null
            // dataCenterInfo 默认是 MyOwn
            DataCenterInfo dataCenterInfo = config.getDataCenterInfo();
            if (instanceId == null || instanceId.isEmpty()) {
                if (dataCenterInfo instanceof UniqueIdentifier) {
                    instanceId = ((UniqueIdentifier) dataCenterInfo).getId();
                } else {
                    instanceId = config.getHostName(false); // 192.168.1.2
                }
            }

            String defaultAddress;
            if (config instanceof RefreshableInstanceConfig) {
                // Refresh AWS data center info, and return up to date address
                defaultAddress = ((RefreshableInstanceConfig) config).resolveDefaultAddress(false);
            } else {
                // config instanceof EurekaInfoConfig
                defaultAddress = config.getHostName(false); // 192.168.1.2
            }

            // fail safe
            if (defaultAddress == null || defaultAddress.isEmpty()) {
                defaultAddress = config.getIpAddress(); // 192.168.1.2
            }

            builder.setNamespace(
                            // eureka.
                            config.getNamespace())
                    .setInstanceId(
                            // 192.168.1.2
                            instanceId)
                    .setAppName(
                            // eureka.name=eureka
                            config.getAppname())
                    .setAppGroupName(
                            // eureka.appGroup = null
                            config.getAppGroupName())
                    .setDataCenterInfo(
                            // new DataCenterInfo() {
                            //        @Override
                            //        public Name getName() {
                            //            return Name.MyOwn;
                            //        }
                            //    };
                            config.getDataCenterInfo())
                    .setIPAddr(
                            // 192.168.1.2
                            config.getIpAddress())
                    .setHostName(
                            // 192.168.1.2
                            defaultAddress)
                    .setPort(
                            // eureka.port = 8080
                            config.getNonSecurePort())
                    .enablePort(PortType.UNSECURE,
                            // eureka.port.enabled = treu
                            config.isNonSecurePortEnabled())
                    .setSecurePort(
                            // eureka.securePort = 443
                            config.getSecurePort())
                    .enablePort(PortType.SECURE,
                            // eureka.securePort.enabled = false
                            config.getSecurePortEnabled())
                    .setVIPAddress(config.getVirtualHostName())
                    .setSecureVIPAddress(config.getSecureVirtualHostName())
                    .setHomePageUrl(config.getHomePageUrlPath(), config.getHomePageUrl())
                    .setStatusPageUrl(config.getStatusPageUrlPath(), config.getStatusPageUrl())
                    .setASGName(config.getASGName())
                    .setHealthCheckUrls(config.getHealthCheckUrlPath(),
                            config.getHealthCheckUrl(), config.getSecureHealthCheckUrl());


            // Start off with the STARTING state to avoid traffic
            // eureka.traffic.enabled = false
            if (!config.isInstanceEnabledOnit()) {
                InstanceStatus initialStatus = InstanceStatus.STARTING;
                LOG.info("Setting initial instance status as: " + initialStatus);
                builder.setStatus(initialStatus);
            } else {
                LOG.info("Setting initial instance status as: {}. This may be too early for the " + "instance to advertise " + "itself as available. You would instead want to control this " + "via a healthcheck handler.", InstanceStatus.UP);
            }

            //
            // Add any user-specific metadata information
            for (Map.Entry<String, String> mapEntry : config.getMetadataMap().entrySet()) {
                String key = mapEntry.getKey();
                String value = mapEntry.getValue();
                builder.add(key, value);
            }

            instanceInfo = builder.build();
            instanceInfo.setLeaseInfo(leaseInfoBuilder.build());
        }
        return instanceInfo;
    }

}
