package me.srrapero720.dimthread.mixin.impl;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.srrapero720.dimthread.DimConfig;
import me.srrapero720.dimthread.DimThread;
import me.srrapero720.dimthread.thread.ThreadPool;
import me.srrapero720.dimthread.thread.ThreadPoolSafe;
import me.srrapero720.dimthread.util.CrashInfo;
import me.srrapero720.dimthread.util.ExclusionManager;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@Mixin(value = MinecraftServer.class, priority = 1010)
public abstract class MinecraftServerMixin {
    @Shadow private int tickCount;
    @Shadow private PlayerList playerList;
    @Shadow public abstract Iterable<ServerLevel> getAllLevels();

    @Unique private final AtomicReference<CrashInfo> dimthreads$initialException = new AtomicReference<>();
    @Unique private final AtomicBoolean dimthreads$configReloaded = new AtomicBoolean(false);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onServerInit(CallbackInfo ci) {
        ExclusionManager.reload();
    }

    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getWorldArray()[Lnet/minecraft/server/level/ServerLevel;", remap = false))
    public ServerLevel[] tickWorlds(MinecraftServer instance, Operation<ServerLevel[]> original) {
        return DimThread.MANAGER.isActive((MinecraftServer) (Object) this) ? new ServerLevel[]{} : original.call(instance);
    }

    @Inject(method = "tickChildren", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getWorldArray()[Lnet/minecraft/server/level/ServerLevel;", remap = false))
    public void tickWorlds(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (!DimThread.MANAGER.isActive(self())) return;

        if (!dimthreads$configReloaded.getAndSet(true)) {
            ExclusionManager.reload();
        }

        AtomicReference<CrashInfo> crash = new AtomicReference<>();
        ThreadPoolSafe pool = (ThreadPoolSafe) DimThread.getThreadPool(self());

        List<ServerLevel> normalLevels = new ArrayList<>();
        List<ServerLevel> excludedLevels = new ArrayList<>();

        for (ServerLevel level : this.getAllLevels()) {
            if (ExclusionManager.shouldExcludeDimension(level)) {
                excludedLevels.add(level);
            } else {
                normalLevels.add(level);
            }
        }

        pool.executeSafe(normalLevels, level -> {
            dimthreads$tickLevel(level, shouldKeepTicking, crash);
        });

        for (ServerLevel level : excludedLevels) {
            try {
                dimthreads$tickLevelSync(level, shouldKeepTicking);
            } catch (Throwable throwable) {
                crash.set(new CrashInfo(level, throwable));
            }
        }

        pool.awaitCompletion();

        dimthreads$handleCrash(crash.get());
    }

    @Unique
    private void dimthreads$tickLevel(ServerLevel level, BooleanSupplier shouldKeepTicking, AtomicReference<CrashInfo> crash) {
        DimThread.attach(Thread.currentThread(), level);

        if (this.tickCount % 20 == 0) {
            dimthreads$sendTimeUpdate(level);
        }

        DimThread.swapThreadsAndRun(() -> {
            ForgeEventFactory.onPreLevelTick(level, shouldKeepTicking);
            try {
                if (ExclusionManager.shouldExcludeStackTrace()) {
                    if (DimConfig.DEBUG_MODE.get()) {
                        DimThread.LOGGER.debug("Detected excluded class in stack, falling back to sync tick");
                    }
                    self().execute(() -> dimthreads$tickLevelSync(level, shouldKeepTicking));
                } else {
                    level.tick(shouldKeepTicking);
                }
            } catch (Throwable throwable) {
                ExclusionManager.recordFailure(level, throwable);
                crash.set(new CrashInfo(level, throwable));
            }
            ForgeEventFactory.onPostLevelTick(level, shouldKeepTicking);
        }, level, level.getChunkSource());
    }

    @Unique
    private void dimthreads$tickLevelSync(ServerLevel level, BooleanSupplier shouldKeepTicking) {
        if (this.tickCount % 20 == 0) {
            dimthreads$sendTimeUpdate(level);
        }

        ForgeEventFactory.onPreLevelTick(level, shouldKeepTicking);
        level.tick(shouldKeepTicking);
        ForgeEventFactory.onPostLevelTick(level, shouldKeepTicking);
    }

    @Unique
    private void dimthreads$sendTimeUpdate(ServerLevel level) {
        ClientboundSetTimePacket timeUpdatePacket = new ClientboundSetTimePacket(
                level.getGameTime(), level.getDayTime(),
                level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT));
        this.playerList.broadcastAll(timeUpdatePacket, level.dimension());
    }

    @Unique
    private void dimthreads$handleCrash(CrashInfo crashInfo) {
        if (crashInfo != null) {
            if (DimConfig.IGNORE_TICK_CRASH.get() && dimthreads$initialException.compareAndSet(null, crashInfo)) {
                crashInfo.report("Exception ticking world (asynchronously) -> IGNORED");
            } else {
                crashInfo.crash("Exception ticking world (asynchronously)");
            }
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    public void shutdownThreadpool(CallbackInfo ci) {
        DimThread.MANAGER.threadPools.forEach((server, pool) -> {
            if (pool instanceof ThreadPoolSafe) {
                ((ThreadPoolSafe) pool).emergencyStop();
            }
            pool.shutdown();
        });
        DimThread.MANAGER.clear();
        ExclusionManager.reset();
    }

    @Unique
    private MinecraftServer self() {
        return (MinecraftServer) (Object) this;
    }
}