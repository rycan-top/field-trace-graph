package io.fieldtracegraph.cli.project;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// 作用：基于文件的进程间写锁。
// 使用场景：多个 index 同时运行时串行化，避免并发写坏索引。

/**
 * Bounded cross-process write lock for a single project index. It never blocks
 * indefinitely: when the timeout is exhausted it fails with INDEX_BUSY so the
 * caller can surface a resource-budget exit code.
 */
public final class ProjectWriteLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    // 作用：保存已获得的锁资源；逻辑：持有文件通道与文件锁供关闭时释放。
    private ProjectWriteLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    // 作用：获取项目写锁；逻辑：建目录与通道后轮询尝试加锁，超时或中断时报 INDEX_BUSY。
    public static ProjectWriteLock acquire(Path lockPath, long timeoutMillis) {
        try {
            Files.createDirectories(lockPath.getParent());
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = tryLock(channel);
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (lock == null && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    closeQuietly(channel, null);
                    throw new IllegalStateException("INDEX_BUSY", e);
                }
                lock = tryLock(channel);
            }
            if (lock == null) {
                closeQuietly(channel, null);
                throw new IllegalStateException("INDEX_BUSY");
            }
            return new ProjectWriteLock(channel, lock);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to acquire project write lock", e);
        }
    }

    // 作用：尝试非阻塞加锁；逻辑：处理同进程重入与 IO 异常并返回 null 表示未获得。
    private static FileLock tryLock(FileChannel channel) {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    // 作用：释放写锁；逻辑：委托静默关闭方法释放锁并关闭通道。
    @Override
    public void close() {
        closeQuietly(channel, lock);
    }

    // 作用：静默释放资源；逻辑：忽略释放与关闭过程中的 IO 异常。
    private static void closeQuietly(FileChannel channel, FileLock lock) {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // Best-effort release.
            }
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Best-effort close.
        }
    }
}
