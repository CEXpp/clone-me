package cyou.jujube.config;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** 排他锁确保同一资料目录只有一个进程运行，避免另一实例把在途调用误认为崩溃遗留。 */
public final class DataDirectoryLock implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;

  public DataDirectoryLock(Path root) throws IOException {
    Files.createDirectories(root);
    channel =
        FileChannel.open(
            root.resolve(".instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = channel.tryLock();
    } catch (OverlappingFileLockException ex) {
      acquired = null;
    }
    if (acquired == null) {
      channel.close();
      throw new IOException("Another application is using this data directory");
    }
    lock = acquired;
  }

  @Override
  public void close() throws IOException {
    try {
      lock.release();
    } finally {
      channel.close();
    }
  }
}
