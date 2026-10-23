package cyou.jujube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 可配置的运行上限；默认值用于小型单实例，生产仍需验证实际资源峰值。 */
@ConfigurationProperties("clone.operations")
public record OperationsProperties(
    @DefaultValue("false") boolean production,
    @DefaultValue("true") boolean chatEnabled,
    @DefaultValue("256") long minFreeDiskMb,
    @DefaultValue("200") int maxSources,
    @DefaultValue("200") long maxSourceMb,
    @DefaultValue("32") int maxQueuedJobs,
    @DefaultValue("3000") int maxActiveRecords,
    @DefaultValue("20") int maxReleases,
    @DefaultValue("256") long maxDatabaseMb) {
  public static OperationsProperties defaults() {
    return new OperationsProperties(false, true, 256, 200, 200, 32, 3000, 20, 256);
  }
}
