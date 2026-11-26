package cyou.jujube.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 面向学习者的流程日志：只输出到日志系统，不往诊断表增加数据库记录。 与 Diagnostics 的业务事件分开，避免为了观察每一步而大量写库。 */
public final class FlowLog {
  private static final Logger LOG = LoggerFactory.getLogger(FlowLog.class);

  private FlowLog() {}

  /** 接口只保留路径形状，移除查询参数、动态资源名称和控制字符。 */
  public static String path(String path) {
    return path.split("\\?", 2)[0]
        .replaceAll("(/(?:requests|sources|candidates|releases|backups)/)[^/]+", "$1:id")
        .replaceAll("\\p{Cntrl}", "?");
  }

  /**
   * stage 是可搜索的阶段名，message 解释当前动作，details 只放数量、状态等非秘密信息。 requestId 由请求过滤器或后台任务放入
   * MDC，日志框架会自动打印，无需层层传参。 不传整个请求、异常对象或 SQL 参数：它们可能含密码、令牌、问题和资料原文。
   */
  public static void step(String stage, String message, Map<String, ?> details) {
    if (LOG.isDebugEnabled()) LOG.debug("流程 stage={} 动作={} detail={}", stage, message, details);
  }
}
