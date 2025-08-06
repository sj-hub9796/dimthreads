package me.srrapero720.dimthread;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.*;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.Arrays;
import java.util.List;

public class DimConfig {
    private static final ForgeConfigSpec SPEC;

    public static final IntValue DEFAULT_GAMERULE_THREADS;
    public static final BooleanValue IGNORE_TICK_CRASH;
    public static final BooleanValue ENABLE_FALLBACK;
    public static final IntValue FALLBACK_TIMEOUT_MS;
    public static final ConfigValue<List<? extends String>> EXCLUDED_DIMENSIONS;
    public static final ConfigValue<List<? extends String>> EXCLUDED_PACKAGES;
    public static final ConfigValue<List<? extends String>> EXCLUDED_CLASSES;
    public static final BooleanValue AGGRESSIVE_SYNC;
    public static final BooleanValue DEBUG_MODE;

    static {
        Builder B = new Builder();

        B.push("general");

        DEFAULT_GAMERULE_THREADS = B
                .comment(
                        "Define the initial thread count number of threads",
                        "If the value is 6, new worlds will start with 6 thread counts as a initial value"
                )
                .defineInRange("default_gamerule_threads", 3, 2, Runtime.getRuntime().availableProcessors());

        IGNORE_TICK_CRASH = B
                .comment(
                        "WARNING: EXPERIMENTAL - Ignore crashes ticking levels",
                        "This will attempt to continue running even if a dimension crashes"
                )
                .define("ignore_tick_crash", false);

        B.pop();

        B.push("fallback");

        ENABLE_FALLBACK = B
                .comment(
                        "Enable automatic fallback to single-threaded mode when errors occur",
                        "This greatly improves stability with incompatible mods"
                )
                .define("enable_fallback", true);

        FALLBACK_TIMEOUT_MS = B
                .comment(
                        "Maximum time in milliseconds to wait for dimension tick before fallback",
                        "Lower values = faster fallback but may cause false positives"
                )
                .defineInRange("fallback_timeout_ms", 5000, 1000, 30000);

        B.pop();

        B.push("exclusions");

        EXCLUDED_DIMENSIONS = B
                .comment(
                        "List of dimension IDs to exclude from multi-threading",
                        "Example: [\"minecraft:the_end\", \"twilightforest:twilight_forest\"]"
                )
                .defineList("excluded_dimensions",
                        Arrays.asList(),
                        obj -> obj instanceof String);

        EXCLUDED_PACKAGES = B
                .comment(
                        "List of package names to exclude from multi-threading",
                        "Any class in these packages will run on main thread",
                        "Example: [\"com.example.mod\", \"net.somemod.unstable\"]"
                )
                .defineList("excluded_packages",
                        Arrays.asList("appeng", "refinedstorage", "mekanism"),
                        obj -> obj instanceof String);

        EXCLUDED_CLASSES = B
                .comment(
                        "List of specific class names to exclude from multi-threading",
                        "Example: [\"com.example.mod.TileEntity\", \"net.somemod.WorldHandler\"]"
                )
                .defineList("excluded_classes",
                        Arrays.asList(),
                        obj -> obj instanceof String);

        AGGRESSIVE_SYNC = B
                .comment(
                        "Use more aggressive synchronization for better compatibility",
                        "May reduce performance but increases stability"
                )
                .define("aggressive_sync", false);

        B.pop();

        B.push("debug");

        DEBUG_MODE = B
                .comment("Enable detailed logging for debugging threading issues")
                .define("debug_mode", false);

        B.pop();

        SPEC = B.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
    }
}