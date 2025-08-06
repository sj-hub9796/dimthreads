package me.srrapero720.dimthread.util;

import it.unimi.dsi.fastutil.objects.Object2BooleanArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import me.srrapero720.dimthread.DimThread;
import me.srrapero720.dimthread.init.ModGameRules;
import me.srrapero720.dimthread.thread.ThreadPool;
import me.srrapero720.dimthread.thread.ThreadPoolSafe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;

import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Map;

public class ServerManager {
    private final Map<MinecraftServer, Boolean> actives = Collections.synchronizedMap(new Object2BooleanArrayMap<>());
    public final Map<MinecraftServer, ThreadPool> threadPools = Collections.synchronizedMap(new Object2ObjectArrayMap<>());
    private final Map<MinecraftServer, Long> lastTickTime = Collections.synchronizedMap(new Object2ObjectArrayMap<>());
    
    public boolean isActive(MinecraftServer server) {
        return this.actives.computeIfAbsent(server, s -> s.getGameRules().getBoolean(ModGameRules.ACTIVE.getKey()));
    }
    
    public void setActive(MinecraftServer server, GameRules.BooleanValue value) {
        boolean newValue = value.get();
        boolean oldValue = this.actives.put(server, newValue);
        
        if (oldValue != newValue) {
            DimThread.LOGGER.info("DimThreads {} for server", newValue ? "activated" : "deactivated");
            
            if (!newValue) {
                ThreadPool pool = threadPools.get(server);
                if (pool instanceof ThreadPoolSafe) {
                    ((ThreadPoolSafe) pool).emergencyStop();
                }
            } else {
                ExclusionManager.reload();
            }
        }
    }
    
    public ThreadPool getThreadPool(MinecraftServer server) {
        return this.threadPools.computeIfAbsent(server, s -> {
            int threadCount = s.getGameRules().getInt(ModGameRules.THREAD_COUNT.getKey());
            DimThread.LOGGER.info("Creating thread pool with {} threads", threadCount);
            return new ThreadPoolSafe(threadCount);
        });
    }
    
    public void setThreadCount(MinecraftServer server, GameRules.IntegerValue value) {
        ThreadPool current = getThreadPool(server);
        
        if (current.getActiveCount() != 0) {
            DimThread.LOGGER.warn("Cannot change thread count while dimensions are ticking");
            throw new ConcurrentModificationException("Setting the thread count in wrong phase");
        }
        
        int newCount = value.get();
        DimThread.LOGGER.info("Changing thread count from {} to {}", 
            current.getThreadCount(), newCount);
        
        this.threadPools.put(server, new ThreadPoolSafe(newCount));
        current.shutdown();
        
        ExclusionManager.reload();
    }
    
    public void recordTickTime(MinecraftServer server) {
        lastTickTime.put(server, System.currentTimeMillis());
    }
    
    public long getLastTickTime(MinecraftServer server) {
        return lastTickTime.getOrDefault(server, 0L);
    }
    
    public boolean isServerResponsive(MinecraftServer server) {
        long lastTick = getLastTickTime(server);
        if (lastTick == 0) return true;
        
        long timeSinceLastTick = System.currentTimeMillis() - lastTick;
        return timeSinceLastTick < 10000;
    }
    
    public void clear() {
        actives.clear();
        threadPools.values().forEach(pool -> {
            if (pool instanceof ThreadPoolSafe) {
                ((ThreadPoolSafe) pool).emergencyStop();
            }
            pool.shutdown();
        });
        threadPools.clear();
        lastTickTime.clear();
        ExclusionManager.reset();
    }
}