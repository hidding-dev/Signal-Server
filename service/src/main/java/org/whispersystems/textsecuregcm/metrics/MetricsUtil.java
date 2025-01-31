/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.metrics;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import io.dropwizard.lifecycle.JettyManaged;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.metrics.ScheduledReporterManager;
import io.dropwizard.setup.Environment;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.datadog.DatadogMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.whispersystems.textsecuregcm.WhisperServerConfiguration;
import org.whispersystems.textsecuregcm.WhisperServerVersion;
import org.whispersystems.textsecuregcm.util.Constants;
import org.whispersystems.textsecuregcm.util.HostnameUtil;

public class MetricsUtil {

  public static final String PREFIX = "chat";

  /**
   * Returns a dot-separated ('.') name for the given class and name parts
   */
  public static String name(Class<?> clazz, String... parts) {
    return name(clazz.getSimpleName(), parts);
  }

  private static String name(String name, String... parts) {
    final StringBuilder sb = new StringBuilder(PREFIX);
    sb.append(".").append(name);
    for (String part : parts) {
      sb.append(".").append(part);
    }
    return sb.toString();
  }

  public static void configureRegistries(final WhisperServerConfiguration config, final Environment environment) {
    SharedMetricRegistries.add(Constants.METRICS_NAME, environment.metrics());

    final DistributionStatisticConfig defaultDistributionStatisticConfig = DistributionStatisticConfig.builder()
        .percentiles(.75, .95, .99, .999)
        .build();

    {
      final DatadogMeterRegistry datadogMeterRegistry = new DatadogMeterRegistry(
          config.getDatadogConfiguration(), io.micrometer.core.instrument.Clock.SYSTEM);

      datadogMeterRegistry.config().commonTags(
              Tags.of(
                  "service", "chat",
                  "host", HostnameUtil.getLocalHostname(),
                  "version", WhisperServerVersion.getServerVersion(),
                  "env", config.getDatadogConfiguration().getEnvironment()))
          .meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(final Meter.Id id, final DistributionStatisticConfig config) {
              return defaultDistributionStatisticConfig.merge(config);
            }
          });

      Metrics.addRegistry(datadogMeterRegistry);
    }

    environment.lifecycle().manage(new MicrometerRegistryManager(Metrics.globalRegistry));
    environment.lifecycle().manage(new ApplicationShutdownMonitor(Metrics.globalRegistry));
  }

  public static void registerSystemResourceMetrics(final Environment environment) {
    environment.metrics().register(name(CpuUsageGauge.class, "cpu"), new CpuUsageGauge(3, TimeUnit.SECONDS));
    environment.metrics().register(name(FreeMemoryGauge.class, "free_memory"), new FreeMemoryGauge());
    environment.metrics().register(name(NetworkSentGauge.class, "bytes_sent"), new NetworkSentGauge());
    environment.metrics().register(name(NetworkReceivedGauge.class, "bytes_received"), new NetworkReceivedGauge());
    environment.metrics().register(name(FileDescriptorGauge.class, "fd_count"), new FileDescriptorGauge());
    environment.metrics().register(name(MaxFileDescriptorGauge.class, "max_fd_count"), new MaxFileDescriptorGauge());
    environment.metrics()
        .register(name(OperatingSystemMemoryGauge.class, "buffers"), new OperatingSystemMemoryGauge("Buffers"));
    environment.metrics()
        .register(name(OperatingSystemMemoryGauge.class, "cached"), new OperatingSystemMemoryGauge("Cached"));

    BufferPoolGauges.registerMetrics();
    GarbageCollectionGauges.registerMetrics();
  }

  /**
   * For use in commands where {@link JettyManaged} doesn't apply
   *
   * @see io.dropwizard.metrics.MetricsFactory#configure(LifecycleEnvironment, MetricRegistry)
   */
  public static void startManagedReporters(Environment environment) {
    environment.lifecycle().getManagedObjects().forEach(managedObject -> {
      if (managedObject instanceof JettyManaged jettyManaged) {
        if (jettyManaged.getManaged() instanceof ScheduledReporterManager scheduledReporterManager) {
          try {
            scheduledReporterManager.start();
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
    });
  }

  /**
   * For use in commands where {@link JettyManaged} doesn't apply
   *
   * @see io.dropwizard.metrics.MetricsFactory#configure(LifecycleEnvironment, MetricRegistry)
   */
  public static void stopManagedReporters(final Environment environment) {
    environment.lifecycle().getManagedObjects().forEach(lifeCycle -> {
      if (lifeCycle instanceof JettyManaged jettyManaged) {
        if (jettyManaged.getManaged() instanceof ScheduledReporterManager scheduledReporterManager) {
          try {
            scheduledReporterManager.stop();
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
    });
  }
}
