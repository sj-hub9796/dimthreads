package me.srrapero720.dimthread.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.srrapero720.dimthread.DimThread;
import me.srrapero720.dimthread.thread.ThreadPool;
import me.srrapero720.dimthread.thread.ThreadPoolSafe;
import me.srrapero720.dimthread.util.ExclusionManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

public class DimThreadCommands {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("dimthread")
            .requires(source -> source.hasPermission(2));
        
        command.then(Commands.literal("status")
            .executes(context -> {
                return showStatus(context.getSource());
            }));
        
        command.then(Commands.literal("reload")
            .executes(context -> {
                ExclusionManager.reload();
                context.getSource().sendSuccess(() -> 
                    Component.literal("§aDimThread configuration reloaded"), true);
                return 1;
            }));
        
        command.then(Commands.literal("exclude")
            .then(Commands.literal("dimension")
                .then(Commands.argument("dimension", StringArgumentType.string())
                    .executes(context -> {
                        String dim = StringArgumentType.getString(context, "dimension");
                        return excludeDimension(context.getSource(), dim);
                    }))));
        
        command.then(Commands.literal("exclude")
            .then(Commands.literal("package")
                .then(Commands.argument("package", StringArgumentType.string())
                    .executes(context -> {
                        String pkg = StringArgumentType.getString(context, "package");
                        return excludePackage(context.getSource(), pkg);
                    }))));
        
        command.then(Commands.literal("reset")
            .then(Commands.literal("failures")
                .executes(context -> {
                    ExclusionManager.reset();
                    context.getSource().sendSuccess(() -> 
                        Component.literal("§aFailure counters reset"), true);
                    return 1;
                })));
        
        command.then(Commands.literal("emergency")
            .then(Commands.literal("stop")
                .executes(context -> {
                    return emergencyStop(context.getSource());
                })));
        
        dispatcher.register(command);
    }
    
    private static int showStatus(CommandSourceStack source) {
        ThreadPool pool = DimThread.getThreadPool(source.getServer());
        
        source.sendSuccess(() -> Component.literal("§6=== DimThread Status ==="), false);
        source.sendSuccess(() -> Component.literal(String.format(
            "§eActive: §f%s", DimThread.MANAGER.isActive(source.getServer()))), false);
        source.sendSuccess(() -> Component.literal(String.format(
            "§eThread Pool: §f%d/%d threads", 
            pool.getActiveCount(), pool.getThreadCount())), false);
        
        source.sendSuccess(() -> Component.literal("§eDimensions:"), false);
        for (ServerLevel level : source.getServer().getAllLevels()) {
            boolean excluded = ExclusionManager.shouldExcludeDimension(level);
            boolean fallback = ExclusionManager.isInFallbackMode(level);
            
            String status = excluded ? "§cEXCLUDED" : 
                           fallback ? "§eFALLBACK" : "§aTHREADED";
            
            source.sendSuccess(() -> Component.literal(String.format(
                "  §7- §f%s: %s", 
                level.dimension().location(), status)), false);
        }
        
        return 1;
    }
    
    private static int excludeDimension(CommandSourceStack source, String dimension) {
        source.sendSuccess(() -> Component.literal(
            "§eDimension exclusion requires config file modification and restart"), false);
        source.sendSuccess(() -> Component.literal(
            "§7Add to config: excluded_dimensions = [\"" + dimension + "\"]"), false);
        return 1;
    }
    
    private static int excludePackage(CommandSourceStack source, String packageName) {
        source.sendSuccess(() -> Component.literal(
            "§ePackage exclusion requires config file modification and restart"), false);
        source.sendSuccess(() -> Component.literal(
            "§7Add to config: excluded_packages = [\"" + packageName + "\"]"), false);
        return 1;
    }
    
    private static int emergencyStop(CommandSourceStack source) {
        ThreadPool pool = DimThread.getThreadPool(source.getServer());
        
        if (pool instanceof ThreadPoolSafe) {
            ((ThreadPoolSafe) pool).emergencyStop();
            source.sendSuccess(() -> Component.literal(
                "§cEmergency stop executed - all dimension threads halted"), true);
            source.sendSuccess(() -> Component.literal(
                "§eRecommended to restart server soon"), true);
        } else {
            source.sendSuccess(() -> Component.literal(
                "§cEmergency stop not available for current thread pool"), false);
        }
        
        return 1;
    }
}