package me.srrapero720.dimthread.util;

import me.srrapero720.dimthread.DimConfig;
import me.srrapero720.dimthread.DimThread;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ExclusionManager {
    private static final Set<String> excludedDimensions = new HashSet<>();
    private static final Set<String> excludedPackages = new HashSet<>();
    private static final Set<String> excludedClasses = new HashSet<>();
    private static final Set<ResourceLocation> fallbackDimensions = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<ResourceLocation, Integer> dimensionFailures = new ConcurrentHashMap<>();
    
    private static final int MAX_FAILURES_BEFORE_EXCLUDE = 3;
    
    public static void reload() {
        excludedDimensions.clear();
        excludedPackages.clear();
        excludedClasses.clear();
        
        excludedDimensions.addAll(DimConfig.EXCLUDED_DIMENSIONS.get());
        excludedPackages.addAll(DimConfig.EXCLUDED_PACKAGES.get());
        excludedClasses.addAll(DimConfig.EXCLUDED_CLASSES.get());
        
        if (DimConfig.DEBUG_MODE.get()) {
            DimThread.LOGGER.info("Loaded exclusions - Dimensions: {}, Packages: {}, Classes: {}",
                excludedDimensions.size(), excludedPackages.size(), excludedClasses.size());
        }
    }
    
    public static boolean shouldExcludeDimension(ServerLevel level) {
        ResourceLocation dimId = level.dimension().location();
        
        if (fallbackDimensions.contains(dimId)) {
            return true;
        }
        
        if (excludedDimensions.contains(dimId.toString())) {
            return true;
        }
        
        return false;
    }
    
    public static boolean shouldExcludeClass(Class<?> clazz) {
        if (clazz == null) return false;
        
        String className = clazz.getName();
        
        if (excludedClasses.contains(className)) {
            return true;
        }
        
        for (String packageName : excludedPackages) {
            if (className.startsWith(packageName)) {
                return true;
            }
        }
        
        return false;
    }
    
    public static boolean shouldExcludeStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            
            if (excludedClasses.contains(className)) {
                return true;
            }
            
            for (String packageName : excludedPackages) {
                if (className.startsWith(packageName)) {
                    if (DimConfig.DEBUG_MODE.get()) {
                        DimThread.LOGGER.debug("Excluding due to package match: {} in {}", 
                            packageName, className);
                    }
                    return true;
                }
            }
        }
        
        return false;
    }
    
    public static void recordFailure(ServerLevel level, Throwable throwable) {
        if (!DimConfig.ENABLE_FALLBACK.get()) return;
        
        ResourceLocation dimId = level.dimension().location();
        int failures = dimensionFailures.compute(dimId, (k, v) -> v == null ? 1 : v + 1);
        
        DimThread.LOGGER.warn("Dimension {} failed (attempt {}/{}): {}", 
            dimId, failures, MAX_FAILURES_BEFORE_EXCLUDE, throwable.getMessage());
        
        if (failures >= MAX_FAILURES_BEFORE_EXCLUDE) {
            fallbackDimensions.add(dimId);
            DimThread.LOGGER.error("Dimension {} permanently moved to single-thread mode after {} failures", 
                dimId, failures);
        }
    }
    
    public static void clearFailures(ServerLevel level) {
        if (level != null) {
            dimensionFailures.remove(level.dimension().location());
        }
    }
    
    public static boolean isInFallbackMode(ServerLevel level) {
        return fallbackDimensions.contains(level.dimension().location());
    }
    
    public static void reset() {
        fallbackDimensions.clear();
        dimensionFailures.clear();
    }
}