package me.srrapero720.dimthread;

import me.srrapero720.dimthread.commands.DimThreadCommands;
import me.srrapero720.dimthread.init.ModGameRules;
import me.srrapero720.dimthread.thread.ThreadPool;
import me.srrapero720.dimthread.util.ExclusionManager;
import me.srrapero720.dimthread.util.ServerManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLLoader;
import me.srrapero720.dimthread.thread.IMutableMainThread;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

@Mod(DimThread.MOD_ID)
@Mod.EventBusSubscriber(modid = DimThread.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class DimThread {
    public static final String MOD_ID = "dimthread";
    public static final ServerManager MANAGER = new ServerManager();
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    private static final Marker MARKER = MarkerManager.getMarker("MAIN");

    public DimThread() {
        DimConfig.register();
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info(MARKER, "DimThreads initialized - Available processors: {}",
                Runtime.getRuntime().availableProcessors());
    }

    @SubscribeEvent
    public static void onCommonSetupEvent(FMLCommonSetupEvent e) {
        ModGameRules.register();
        ExclusionManager.reload();

        LOGGER.info(MARKER, "DimThreads setup complete");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        DimThreadCommands.register(event.getDispatcher());
        LOGGER.debug(MARKER, "Commands registered");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ExclusionManager.reload();
        LOGGER.info(MARKER, "Server started - DimThreads active: {}",
                MANAGER.isActive(event.getServer()));
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info(MARKER, "Server stopping - cleaning up thread pools");
        MANAGER.clear();
    }

    public static ThreadPool getThreadPool(MinecraftServer server) {
        return MANAGER.getThreadPool(server);
    }

    public static boolean isModPresent(String modid) {
        return FMLLoader.getLoadingModList().getModFileById(modid) != null;
    }

    public static synchronized void swapThreadsAndRun(Runnable task, Object... threadedObjects) {
        if (ExclusionManager.shouldExcludeStackTrace()) {
            if (DimConfig.DEBUG_MODE.get()) {
                LOGGER.debug("Thread swap skipped due to exclusion");
            }
            task.run();
            return;
        }

        Thread currentThread = Thread.currentThread();
        Thread[] oldThreads = new Thread[threadedObjects.length];

        for (int i = 0; i < oldThreads.length; i++) {
            oldThreads[i] = ((IMutableMainThread) threadedObjects[i]).dimThreads$getMainThread();
            ((IMutableMainThread) threadedObjects[i]).dimThreads$setMainThread(currentThread);
        }

        try {
            task.run();
        } finally {
            for (int i = 0; i < oldThreads.length; i++) {
                ((IMutableMainThread) threadedObjects[i]).dimThreads$setMainThread(oldThreads[i]);
            }
        }
    }

    public static void attach(Thread thread, String name) {
        thread.setName(MOD_ID + "_server_" + name);
    }

    public static void attach(Thread thread, ServerLevel world) {
        attach(thread, world.dimension().location().getPath());
    }

    public static boolean owns(Thread thread) {
        return thread.getName().startsWith(MOD_ID + "_server_");
    }

    public static boolean isDebugMode() {
        return DimConfig.DEBUG_MODE.get();
    }
}