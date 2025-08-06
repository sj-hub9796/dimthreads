package me.srrapero720.dimthread.thread;

import me.srrapero720.dimthread.DimConfig;
import me.srrapero720.dimthread.DimThread;
import me.srrapero720.dimthread.util.ExclusionManager;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ThreadPoolSafe extends ThreadPool {
    private final ScheduledExecutorService watchdog;
    private final ConcurrentHashMap<ServerLevel, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private final AtomicInteger taskIdCounter = new AtomicInteger(0);
    
    public ThreadPoolSafe(int threadCount) {
        super(threadCount);
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName(DimThread.MOD_ID + "_watchdog");
            return t;
        });
    }
    
    public void executeSafe(Iterable<ServerLevel> levels, Consumer<ServerLevel> action) {
        for (ServerLevel level : levels) {
            if (ExclusionManager.shouldExcludeDimension(level)) {
                if (DimConfig.DEBUG_MODE.get()) {
                    DimThread.LOGGER.debug("Running dimension {} on main thread (excluded)", 
                        level.dimension().location());
                }
                executeOnMainThread(level, action);
            } else {
                executeWithFallback(level, action);
            }
        }
    }
    
    private void executeWithFallback(ServerLevel level, Consumer<ServerLevel> action) {
        if (!DimConfig.ENABLE_FALLBACK.get()) {
            super.execute(() -> executeDimensionTick(level, action));
            return;
        }
        
        int taskId = taskIdCounter.incrementAndGet();
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        Future<?> watchdogTask = watchdog.schedule(() -> {
            if (!future.isDone()) {
                DimThread.LOGGER.warn("Dimension {} tick timeout after {}ms, moving to fallback", 
                    level.dimension().location(), DimConfig.FALLBACK_TIMEOUT_MS.get());
                
                future.cancel(true);
                ExclusionManager.recordFailure(level, new TimeoutException("Tick timeout"));
                executeOnMainThread(level, action);
            }
        }, DimConfig.FALLBACK_TIMEOUT_MS.get(), TimeUnit.MILLISECONDS);
        
        super.execute(() -> {
            try {
                executeDimensionTick(level, action);
                ExclusionManager.clearFailures(level);
                future.complete(null);
            } catch (Exception e) {
                ExclusionManager.recordFailure(level, e);
                
                if (!future.isDone()) {
                    future.completeExceptionally(e);
                    executeOnMainThread(level, action);
                }
            } finally {
                watchdogTask.cancel(false);
                runningTasks.remove(level);
            }
        });
        
        runningTasks.put(level, future);
    }
    
    private void executeDimensionTick(ServerLevel level, Consumer<ServerLevel> action) {
        Thread currentThread = Thread.currentThread();
        DimThread.attach(currentThread, level);
        
        try {
            if (DimConfig.AGGRESSIVE_SYNC.get()) {
                synchronized (level) {
                    action.accept(level);
                }
            } else {
                action.accept(level);
            }
        } catch (Exception e) {
            if (DimConfig.DEBUG_MODE.get()) {
                DimThread.LOGGER.error("Error ticking dimension {}", level.dimension().location(), e);
            }
            throw e;
        }
    }
    
    private void executeOnMainThread(ServerLevel level, Consumer<ServerLevel> action) {
        level.getServer().execute(() -> {
            try {
                action.accept(level);
            } catch (Exception e) {
                DimThread.LOGGER.error("Error ticking dimension {} on main thread", 
                    level.dimension().location(), e);
                if (!DimConfig.IGNORE_TICK_CRASH.get()) {
                    throw e;
                }
            }
        });
    }
    
    @Override
    public void awaitCompletion() {
        super.awaitCompletion();
        
        for (Future<?> future : runningTasks.values()) {
            try {
                future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                DimThread.LOGGER.debug("Task still running during await");
            } catch (Exception e) {
                DimThread.LOGGER.error("Error waiting for task completion", e);
            }
        }
    }
    
    @Override
    public void shutdown() {
        watchdog.shutdownNow();
        runningTasks.clear();
        super.shutdown();
    }
    
    public void emergencyStop() {
        DimThread.LOGGER.warn("Emergency stop requested - cancelling all tasks");
        
        for (Future<?> future : runningTasks.values()) {
            future.cancel(true);
        }
        
        runningTasks.clear();
        ExclusionManager.reset();
    }
}