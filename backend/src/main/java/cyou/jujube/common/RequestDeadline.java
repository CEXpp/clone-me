package cyou.jujube.common;

import java.time.Duration;

/** 当前同步请求的总时限；各外部调用只获得剩余时间，不为每个阶段重置总预算。 */
public final class RequestDeadline implements AutoCloseable {
  private static final ThreadLocal<Long> END = new ThreadLocal<>();
  private final Long previous;

  private RequestDeadline(Duration duration) {
    previous = END.get();
    long deadline = System.nanoTime() + duration.toNanos();
    END.set(previous == null ? deadline : Math.min(previous, deadline));
  }

  public static RequestDeadline start(Duration duration) {
    return new RequestDeadline(duration);
  }

  public static Duration remaining(Duration maximum) {
    Long end = END.get();
    if (Thread.currentThread().isInterrupted())
      throw new ApiException(504, "REQUEST_TIMEOUT", "本次处理已中止，请先刷新聊天记录确认结果");
    if (end == null) return maximum;
    long nanos = end - System.nanoTime();
    if (nanos <= 0) throw new ApiException(504, "REQUEST_TIMEOUT", "本次处理已超时，请先刷新聊天记录确认结果");
    return Duration.ofNanos(Math.min(nanos, maximum.toNanos()));
  }

  public static void check() {
    remaining(Duration.ofDays(1));
  }

  @Override
  public void close() {
    if (previous == null) END.remove();
    else END.set(previous);
  }
}
