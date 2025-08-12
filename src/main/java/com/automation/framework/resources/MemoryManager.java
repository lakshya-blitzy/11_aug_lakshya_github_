package com.automation.framework.resources;

// Internal imports from framework dependencies
import com.automation.framework.core.FrameworkManager;
import com.automation.framework.core.ConfigurationManager;

// External imports for JVM memory management and monitoring
import java.lang.management.MemoryUsage;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.GarbageCollectorMXBean;

// External imports for concurrent operations and scheduling
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// External imports for time-based operations and monitoring
import java.time.Instant;
import java.time.Duration;

// External imports for optional handling and data structures
import java.util.Optional;
import java.util.*;
import java.util.stream.Collectors;

// External imports for structured logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MemoryManager provides comprehensive memory monitoring and management for the automation framework.
 * 
 * This class monitors heap usage, detects memory leaks, and triggers garbage collection optimization
 * to maintain framework memory within operational limits. It tracks baseline heap usage after each
 * garbage collection cycle to detect patterns where the application neglects to release references
 * to objects no longer needed.
 * 
 * Key Features:
 * - Real-time memory usage monitoring with 100MB baseline overhead target
 * - Memory leak detection through post-GC trend analysis
 * - Component-specific memory allocation tracking
 * - Automatic garbage collection optimization for long-running test suites
 * - Memory health status monitoring against 2GB total framework limit
 * - Browser session memory limiting to 50MB per session
 * - Proactive memory cleanup and optimization recommendations
 * 
 * Memory Limits and Thresholds:
 * - Baseline Overhead: 100MB maximum
 * - Total Framework Limit: 2GB maximum
 * - Browser Session Limit: 50MB per session
 * - Warning Threshold: 80% of total limit
 * - Critical Threshold: 95% of total limit
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class MemoryManager {
    
    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);
    
    // Memory limit constants (in bytes)
    private static final long BASELINE_OVERHEAD_LIMIT = 100L * 1024L * 1024L; // 100MB
    private static final long TOTAL_FRAMEWORK_LIMIT = 2L * 1024L * 1024L * 1024L; // 2GB
    private static final long BROWSER_SESSION_LIMIT = 50L * 1024L * 1024L; // 50MB
    private static final double WARNING_THRESHOLD = 0.80; // 80%
    private static final double CRITICAL_THRESHOLD = 0.95; // 95%
    
    // Singleton instance management
    private static volatile MemoryManager instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Framework dependency components
    private final FrameworkManager frameworkManager;
    private final ConfigurationManager configurationManager;
    
    // Memory monitoring infrastructure
    private final MemoryMXBean memoryMXBean;
    private final List<GarbageCollectorMXBean> gcMXBeans;
    private ScheduledExecutorService monitoringExecutor;
    
    // Memory metrics tracking with thread-safe operations
    private final AtomicLong totalMemoryChecks = new AtomicLong(0);
    private final AtomicLong gcCycleCount = new AtomicLong(0);
    private final AtomicLong lastGcCollectionCount = new AtomicLong(0);
    private final AtomicLong baselineHeapUsage = new AtomicLong(0);
    private final AtomicLong peakMemoryUsage = new AtomicLong(0);
    
    // Component memory tracking
    private final ConcurrentHashMap<String, ComponentMemoryTracker> componentMemoryMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> browserSessionMemory = new ConcurrentHashMap<>();
    
    // Memory leak detection
    private final Queue<MemorySnapshot> memoryHistory = new LinkedList<>();
    private final List<MemoryLeak> detectedLeaks = Collections.synchronizedList(new ArrayList<>());
    private final ReentrantReadWriteLock memoryLock = new ReentrantReadWriteLock();
    
    // Monitoring state management
    private volatile boolean monitoringActive = false;
    private volatile Instant monitoringStartTime;
    private volatile Duration monitoringInterval = Duration.ofSeconds(30);
    
    // Configuration and thresholds
    private volatile long configuredBaselineLimit = BASELINE_OVERHEAD_LIMIT;
    private volatile long configuredTotalLimit = TOTAL_FRAMEWORK_LIMIT;
    private volatile boolean gcOptimizationEnabled = true;
    
    /**
     * Private constructor for singleton pattern.
     * Initializes memory monitoring infrastructure and framework dependencies.
     */
    private MemoryManager() {
        this.frameworkManager = FrameworkManager.getInstance();
        this.configurationManager = ConfigurationManager.getInstance();
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.gcMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        // Initialize baseline heap usage
        initializeBaselineHeapUsage();
        
        // Load configuration settings
        loadMemoryConfiguration();
        
        logger.info("MemoryManager initialized with baseline limit: {}MB, total limit: {}MB", 
                   configuredBaselineLimit / (1024 * 1024), configuredTotalLimit / (1024 * 1024));
    }
    
    /**
     * Gets the singleton instance of MemoryManager.
     * Thread-safe lazy initialization with double-checked locking pattern.
     * 
     * @return MemoryManager singleton instance
     */
    public static MemoryManager getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new MemoryManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Gets the current heap usage baseline after last garbage collection.
     * This baseline represents the minimum memory required by the framework
     * and is used for memory leak detection.
     * 
     * @return Heap usage baseline in bytes
     */
    public long getHeapUsageBaseline() {
        return baselineHeapUsage.get();
    }
    
    /**
     * Gets comprehensive current memory usage information.
     * 
     * @return MemoryMetrics containing current memory usage data
     */
    public MemoryMetrics getCurrentMemoryUsage() {
        memoryLock.readLock().lock();
        try {
            MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
            MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
            
            long usedMemory = heapUsage.getUsed() + nonHeapUsage.getUsed();
            long maxMemory = heapUsage.getMax() + nonHeapUsage.getMax();
            long freeMemory = maxMemory - usedMemory;
            
            double utilization = maxMemory > 0 ? (double) usedMemory / maxMemory * 100.0 : 0.0;
            
            // Get GC metrics
            long totalGcCount = gcMXBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
            long totalGcTime = gcMXBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
            
            Map<String, ComponentMemoryUsage> componentBreakdown = getComponentMemoryBreakdown();
            
            return new MemoryMetrics(
                heapUsage,
                nonHeapUsage,
                usedMemory,
                maxMemory,
                freeMemory,
                utilization,
                totalGcCount,
                totalGcTime,
                Instant.now(),
                componentBreakdown,
                calculateMemoryGrowthRate(),
                isMemoryWithinLimits(usedMemory)
            );
            
        } finally {
            memoryLock.readLock().unlock();
        }
    }
    
    /**
     * Gets all detected memory leaks with detailed analysis.
     * 
     * @return List of detected MemoryLeak instances
     */
    public List<MemoryLeak> getMemoryLeaks() {
        memoryLock.readLock().lock();
        try {
            return new ArrayList<>(detectedLeaks);
        } finally {
            memoryLock.readLock().unlock();
        }
    }
    
    /**
     * Gets comprehensive garbage collection metrics.
     * 
     * @return MemoryMetrics containing GC-specific information
     */
    public MemoryMetrics getGCMetrics() {
        long totalCollections = gcMXBeans.stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionCount)
            .sum();
        
        long totalGcTime = gcMXBeans.stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionTime)
            .sum();
        
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        
        return new MemoryMetrics(
            heapUsage,
            nonHeapUsage,
            heapUsage.getUsed(),
            heapUsage.getMax(),
            heapUsage.getMax() - heapUsage.getUsed(),
            calculateHeapUtilization(),
            totalCollections,
            totalGcTime,
            Instant.now(),
            Collections.emptyMap(),
            0.0,
            true
        );
    }
    
    /**
     * Checks if memory usage is within healthy operational parameters.
     * 
     * @return true if memory is healthy, false if approaching limits or leaks detected
     */
    public boolean isMemoryHealthy() {
        MemoryMetrics current = getCurrentMemoryUsage();
        
        // Check if within baseline and total limits
        if (current.getUsedMemory() > configuredTotalLimit) {
            return false;
        }
        
        // Check for critical memory utilization
        if (current.getMemoryUtilization() > CRITICAL_THRESHOLD * 100) {
            return false;
        }
        
        // Check for active memory leaks
        if (!detectedLeaks.isEmpty() && detectedLeaks.stream().anyMatch(MemoryLeak::isConfirmed)) {
            return false;
        }
        
        // Check baseline deviation
        long currentBaseline = getHeapUsageBaseline();
        if (currentBaseline > configuredBaselineLimit * 1.5) { // 50% above baseline limit
            return false;
        }
        
        return true;
    }
    
    /**
     * Gets memory usage breakdown by framework component.
     * 
     * @return Map of component names to ComponentMemoryUsage data
     */
    public Map<String, ComponentMemoryUsage> getComponentMemoryUsage() {
        return getComponentMemoryBreakdown();
    }
    
    /**
     * Gets the configured maximum memory limit for the framework.
     * 
     * @return Maximum memory limit in bytes
     */
    public long getMaxMemoryLimit() {
        return configuredTotalLimit;
    }
    
    /**
     * Gets current heap utilization as a percentage.
     * 
     * @return Heap utilization percentage (0.0 to 100.0)
     */
    public double getCurrentHeapUtilization() {
        return calculateHeapUtilization();
    }
    
    /**
     * Gets memory usage trends over the monitoring period.
     * 
     * @return List of MemoryTrend data points
     */
    public List<MemoryTrend> getMemoryTrends() {
        memoryLock.readLock().lock();
        try {
            return memoryHistory.stream()
                .map(snapshot -> new MemoryTrend(
                    snapshot.getTimestamp(),
                    snapshot.getHeapUsed(),
                    snapshot.getHeapUtilization(),
                    snapshot.getGcCount()
                ))
                .collect(Collectors.toList());
        } finally {
            memoryLock.readLock().unlock();
        }
    }
    
    /**
     * Triggers immediate garbage collection for memory optimization.
     * This method requests but does not guarantee garbage collection.
     * 
     * @return true if GC was requested successfully
     */
    public boolean triggerGC() {
        try {
            logger.debug("Triggering garbage collection for memory optimization");
            
            long beforeGc = getCurrentMemoryUsage().getUsedMemory();
            
            // Request garbage collection
            System.gc();
            
            // Wait briefly for GC to complete
            Thread.sleep(100);
            
            long afterGc = getCurrentMemoryUsage().getUsedMemory();
            long memoryFreed = beforeGc - afterGc;
            
            logger.info("Garbage collection completed. Memory freed: {}MB", 
                       memoryFreed / (1024 * 1024));
            
            // Update baseline after GC
            updateHeapUsageBaseline();
            
            return memoryFreed > 0;
            
        } catch (Exception e) {
            logger.error("Error during garbage collection trigger", e);
            return false;
        }
    }
    
    /**
     * Starts continuous memory monitoring with configured interval.
     * 
     * @return true if monitoring started successfully
     */
    public boolean startMemoryMonitoring() {
        if (monitoringActive) {
            logger.warn("Memory monitoring is already active");
            return true;
        }
        
        try {
            monitoringExecutor = Executors.newScheduledThreadPool(2, r -> {
                Thread thread = new Thread(r, "MemoryManager-Monitor");
                thread.setDaemon(true);
                return thread;
            });
            
            // Schedule memory monitoring task
            monitoringExecutor.scheduleAtFixedRate(
                this::performMemoryCheck,
                0,
                monitoringInterval.toMillis(),
                TimeUnit.MILLISECONDS
            );
            
            // Schedule memory leak detection task
            monitoringExecutor.scheduleAtFixedRate(
                this::performMemoryLeakDetection,
                Duration.ofMinutes(5).toMillis(),
                Duration.ofMinutes(5).toMillis(),
                TimeUnit.MILLISECONDS
            );
            
            monitoringActive = true;
            monitoringStartTime = Instant.now();
            
            logger.info("Memory monitoring started with interval: {}", monitoringInterval);
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to start memory monitoring", e);
            return false;
        }
    }
    
    /**
     * Stops memory monitoring and cleanup monitoring resources.
     * 
     * @return true if monitoring stopped successfully
     */
    public boolean stopMemoryMonitoring() {
        if (!monitoringActive) {
            logger.warn("Memory monitoring is not active");
            return true;
        }
        
        try {
            monitoringActive = false;
            
            if (monitoringExecutor != null) {
                monitoringExecutor.shutdown();
                if (!monitoringExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    monitoringExecutor.shutdownNow();
                }
                monitoringExecutor = null;
            }
            
            Duration monitoringDuration = Duration.between(monitoringStartTime, Instant.now());
            logger.info("Memory monitoring stopped after duration: {}", monitoringDuration);
            return true;
            
        } catch (Exception e) {
            logger.error("Error stopping memory monitoring", e);
            return false;
        }
    }
    
    /**
     * Creates a memory checkpoint for trend analysis and leak detection.
     * 
     * @return MemorySnapshot containing current memory state
     */
    public MemorySnapshot createMemoryCheckpoint() {
        MemoryMetrics current = getCurrentMemoryUsage();
        
        MemorySnapshot snapshot = new MemorySnapshot(
            Instant.now(),
            current.getHeapUsage().getUsed(),
            current.getHeapUsage().getMax(),
            current.getMemoryUtilization(),
            current.getGCCount(),
            getComponentMemoryBreakdown()
        );
        
        memoryLock.writeLock().lock();
        try {
            memoryHistory.offer(snapshot);
            
            // Keep only last 100 snapshots for trend analysis
            while (memoryHistory.size() > 100) {
                memoryHistory.poll();
            }
            
        } finally {
            memoryLock.writeLock().unlock();
        }
        
        logger.debug("Created memory checkpoint: {}MB used, {}% utilization", 
                    snapshot.getHeapUsed() / (1024 * 1024), snapshot.getHeapUtilization());
        
        return snapshot;
    }
    
    /**
     * Performs comprehensive memory leak detection analysis.
     * Analyzes memory usage patterns after GC cycles to identify potential leaks.
     * 
     * @return List of newly detected MemoryLeak instances
     */
    public List<MemoryLeak> detectMemoryLeaks() {
        List<MemoryLeak> newLeaks = new ArrayList<>();
        
        memoryLock.readLock().lock();
        try {
            if (memoryHistory.size() < 10) {
                logger.debug("Insufficient memory history for leak detection");
                return newLeaks;
            }
            
            // Analyze heap growth trend after GC cycles
            MemoryLeak heapGrowthLeak = analyzeHeapGrowthPattern();
            if (heapGrowthLeak != null) {
                newLeaks.add(heapGrowthLeak);
            }
            
            // Analyze component-specific memory leaks
            List<MemoryLeak> componentLeaks = analyzeComponentMemoryLeaks();
            newLeaks.addAll(componentLeaks);
            
            // Analyze browser session memory leaks
            MemoryLeak browserLeak = analyzeBrowserSessionLeaks();
            if (browserLeak != null) {
                newLeaks.add(browserLeak);
            }
            
            // Add new leaks to detection list
            newLeaks.forEach(leak -> {
                detectedLeaks.add(leak);
                logger.warn("Memory leak detected: {} - {}", leak.getLeakType(), leak.getComponentName());
            });
            
        } finally {
            memoryLock.readLock().unlock();
        }
        
        return newLeaks;
    }
    
    /**
     * Performs memory optimization including GC triggering and cleanup.
     * 
     * @return true if optimization was performed successfully
     */
    public boolean optimizeMemoryUsage() {
        try {
            logger.info("Starting memory optimization process");
            
            MemoryMetrics beforeOptimization = getCurrentMemoryUsage();
            
            // Step 1: Force garbage collection
            if (gcOptimizationEnabled) {
                triggerGC();
            }
            
            // Step 2: Clear expired component memory trackers
            cleanupExpiredComponentTrackers();
            
            // Step 3: Cleanup browser session memory tracking
            cleanupBrowserSessionMemory();
            
            // Step 4: Compact memory history if needed
            compactMemoryHistory();
            
            // Step 5: Update baseline measurements
            updateHeapUsageBaseline();
            
            MemoryMetrics afterOptimization = getCurrentMemoryUsage();
            long memoryFreed = beforeOptimization.getUsedMemory() - afterOptimization.getUsedMemory();
            
            logger.info("Memory optimization completed. Memory freed: {}MB", 
                       memoryFreed / (1024 * 1024));
            
            return memoryFreed >= 0;
            
        } catch (Exception e) {
            logger.error("Error during memory optimization", e);
            return false;
        }
    }
    
    /**
     * Gets memory optimization recommendations based on current usage patterns.
     * 
     * @return List of MemoryOptimizationRecommendation objects
     */
    public List<MemoryOptimizationRecommendation> getMemoryOptimizationRecommendations() {
        List<MemoryOptimizationRecommendation> recommendations = new ArrayList<>();
        
        MemoryMetrics current = getCurrentMemoryUsage();
        
        // Analyze memory utilization
        if (current.getMemoryUtilization() > WARNING_THRESHOLD * 100) {
            recommendations.add(new MemoryOptimizationRecommendation(
                "HIGH_MEMORY_USAGE",
                "Memory utilization is above warning threshold",
                "Consider increasing JVM heap size or reducing memory consumption",
                OptimizationPriority.HIGH
            ));
        }
        
        // Analyze baseline deviation
        long currentBaseline = getHeapUsageBaseline();
        if (currentBaseline > configuredBaselineLimit) {
            recommendations.add(new MemoryOptimizationRecommendation(
                "BASELINE_EXCEEDED",
                "Baseline heap usage exceeds configured limit",
                "Review object retention policies and implement cleanup procedures",
                OptimizationPriority.MEDIUM
            ));
        }
        
        // Analyze GC patterns
        double gcTime = current.getGCTime();
        if (gcTime > 1000) { // More than 1 second total GC time
            recommendations.add(new MemoryOptimizationRecommendation(
                "HIGH_GC_TIME",
                "Excessive garbage collection time detected",
                "Consider tuning GC parameters or reducing allocation rate",
                OptimizationPriority.MEDIUM
            ));
        }
        
        // Analyze component memory usage
        Map<String, ComponentMemoryUsage> componentUsage = getComponentMemoryBreakdown();
        componentUsage.entrySet().stream()
            .filter(entry -> entry.getValue().getMemoryUsage() > 100 * 1024 * 1024) // > 100MB
            .forEach(entry -> {
                recommendations.add(new MemoryOptimizationRecommendation(
                    "COMPONENT_HIGH_USAGE",
                    "Component " + entry.getKey() + " using excessive memory",
                    "Review " + entry.getKey() + " component for memory optimization opportunities",
                    OptimizationPriority.MEDIUM
                ));
            });
        
        return recommendations;
    }
    
    /**
     * Resets the memory baseline to current post-GC heap usage.
     * 
     * @return true if baseline was reset successfully
     */
    public boolean resetMemoryBaseline() {
        try {
            // Trigger GC first to get accurate baseline
            triggerGC();
            
            // Wait for GC to complete
            Thread.sleep(200);
            
            long newBaseline = memoryMXBean.getHeapMemoryUsage().getUsed();
            baselineHeapUsage.set(newBaseline);
            
            logger.info("Memory baseline reset to: {}MB", newBaseline / (1024 * 1024));
            return true;
            
        } catch (Exception e) {
            logger.error("Error resetting memory baseline", e);
            return false;
        }
    }
    
    /**
     * Gets memory allocation patterns for analysis.
     * 
     * @return List of MemoryAllocationPattern objects
     */
    public List<MemoryAllocationPattern> getMemoryAllocationPatterns() {
        List<MemoryAllocationPattern> patterns = new ArrayList<>();
        
        memoryLock.readLock().lock();
        try {
            if (memoryHistory.size() < 5) {
                return patterns;
            }
            
            // Analyze allocation patterns from memory history
            List<MemorySnapshot> snapshots = new ArrayList<>(memoryHistory);
            
            for (int i = 1; i < snapshots.size(); i++) {
                MemorySnapshot current = snapshots.get(i);
                MemorySnapshot previous = snapshots.get(i - 1);
                
                long memoryChange = current.getHeapUsed() - previous.getHeapUsed();
                Duration timeDiff = Duration.between(previous.getTimestamp(), current.getTimestamp());
                
                if (memoryChange > 0) {
                    double allocationRate = (double) memoryChange / timeDiff.toMillis() * 1000; // bytes per second
                    
                    patterns.add(new MemoryAllocationPattern(
                        current.getTimestamp(),
                        memoryChange,
                        allocationRate,
                        timeDiff,
                        determineAllocationCategory(allocationRate)
                    ));
                }
            }
            
        } finally {
            memoryLock.readLock().unlock();
        }
        
        return patterns;
    }
    
    /**
     * Checks if memory usage is approaching configured limits.
     * 
     * @return true if memory usage is approaching limits (above warning threshold)
     */
    public boolean isMemoryLimitApproaching() {
        MemoryMetrics current = getCurrentMemoryUsage();
        
        // Check against total framework limit
        if (current.getUsedMemory() > configuredTotalLimit * WARNING_THRESHOLD) {
            return true;
        }
        
        // Check heap utilization
        if (current.getMemoryUtilization() > WARNING_THRESHOLD * 100) {
            return true;
        }
        
        // Check baseline deviation
        long currentBaseline = getHeapUsageBaseline();
        if (currentBaseline > configuredBaselineLimit * WARNING_THRESHOLD) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Gets detailed memory breakdown by framework component.
     * 
     * @return Map of component names to ComponentMemoryUsage objects
     */
    public Map<String, ComponentMemoryUsage> getComponentMemoryBreakdown() {
        Map<String, ComponentMemoryUsage> breakdown = new ConcurrentHashMap<>();
        
        componentMemoryMap.forEach((componentName, tracker) -> {
            breakdown.put(componentName, new ComponentMemoryUsage(
                componentName,
                tracker.getCurrentMemoryUsage(),
                tracker.getPeakMemoryUsage(),
                tracker.getLastUpdateTime(),
                tracker.getAllocationCount(),
                tracker.getDeallocationCount()
            ));
        });
        
        return breakdown;
    }
    
    /**
     * Gets total framework memory usage across all components.
     * 
     * @return Total framework memory usage in bytes
     */
    public long getTotalFrameworkMemoryUsage() {
        return getCurrentMemoryUsage().getUsedMemory();
    }
    
    /**
     * Gets history of detected memory leaks.
     * 
     * @return List of all MemoryLeak instances detected over time
     */
    public List<MemoryLeak> getMemoryLeakHistory() {
        memoryLock.readLock().lock();
        try {
            return new ArrayList<>(detectedLeaks);
        } finally {
            memoryLock.readLock().unlock();
        }
    }
    
    /**
     * Gets garbage collection optimization metrics.
     * 
     * @return MemoryMetrics containing GC optimization data
     */
    public MemoryMetrics getGCOptimizationMetrics() {
        return getGCMetrics(); // Delegate to existing GC metrics method
    }
    
    /**
     * Configures memory thresholds and limits.
     * 
     * @param baselineLimit New baseline memory limit in bytes
     * @param totalLimit New total memory limit in bytes
     * @return true if configuration was updated successfully
     */
    public boolean configureMemoryThresholds(long baselineLimit, long totalLimit) {
        if (baselineLimit <= 0 || totalLimit <= 0 || baselineLimit > totalLimit) {
            logger.error("Invalid memory threshold configuration: baseline={}, total={}", 
                        baselineLimit, totalLimit);
            return false;
        }
        
        this.configuredBaselineLimit = baselineLimit;
        this.configuredTotalLimit = totalLimit;
        
        logger.info("Memory thresholds updated: baseline={}MB, total={}MB",
                   baselineLimit / (1024 * 1024), totalLimit / (1024 * 1024));
        
        return true;
    }
    
    /**
     * Gets the current memory monitoring interval.
     * 
     * @return Duration representing the monitoring interval
     */
    public Duration getMemoryMonitoringInterval() {
        return monitoringInterval;
    }
    
    /**
     * Checks if garbage collection optimization is enabled.
     * 
     * @return true if GC optimization is enabled
     */
    public boolean isGCOptimizationEnabled() {
        return gcOptimizationEnabled;
    }
    
    /**
     * Forces immediate memory cleanup across all framework components.
     * 
     * @return true if cleanup was performed successfully
     */
    public boolean forceMemoryCleanup() {
        try {
            logger.info("Performing forced memory cleanup");
            
            // Clear memory history beyond essential data
            memoryLock.writeLock().lock();
            try {
                while (memoryHistory.size() > 10) {
                    memoryHistory.poll();
                }
                
                // Clear old memory leaks
                detectedLeaks.removeIf(leak -> 
                    Duration.between(leak.getDetectionTime(), Instant.now()).toHours() > 24);
                
            } finally {
                memoryLock.writeLock().unlock();
            }
            
            // Clear expired component trackers
            cleanupExpiredComponentTrackers();
            
            // Clear browser session memory tracking
            browserSessionMemory.clear();
            
            // Reset counters
            totalMemoryChecks.set(0);
            
            // Force garbage collection
            triggerGC();
            
            logger.info("Forced memory cleanup completed");
            return true;
            
        } catch (Exception e) {
            logger.error("Error during forced memory cleanup", e);
            return false;
        }
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Initializes the baseline heap usage measurement.
     */
    private void initializeBaselineHeapUsage() {
        try {
            // Force initial GC to get clean baseline
            System.gc();
            Thread.sleep(100);
            
            long initialBaseline = memoryMXBean.getHeapMemoryUsage().getUsed();
            baselineHeapUsage.set(initialBaseline);
            
            logger.debug("Initial baseline heap usage: {}MB", initialBaseline / (1024 * 1024));
            
        } catch (Exception e) {
            logger.warn("Error initializing baseline heap usage", e);
            baselineHeapUsage.set(0);
        }
    }
    
    /**
     * Loads memory configuration from ConfigurationManager.
     */
    private void loadMemoryConfiguration() {
        try {
            // Load baseline limit
            String baselineConfig = configurationManager.getProperty("memory.baseline.limit.mb");
            if (baselineConfig != null) {
                configuredBaselineLimit = Long.parseLong(baselineConfig) * 1024 * 1024;
            }
            
            // Load total limit
            String totalConfig = configurationManager.getProperty("memory.total.limit.gb");
            if (totalConfig != null) {
                configuredTotalLimit = Long.parseLong(totalConfig) * 1024 * 1024 * 1024;
            }
            
            // Load monitoring interval
            String intervalConfig = configurationManager.getProperty("memory.monitoring.interval.seconds");
            if (intervalConfig != null) {
                monitoringInterval = Duration.ofSeconds(Long.parseLong(intervalConfig));
            }
            
            // Load GC optimization setting
            String gcOptConfig = configurationManager.getProperty("memory.gc.optimization.enabled");
            if (gcOptConfig != null) {
                gcOptimizationEnabled = Boolean.parseBoolean(gcOptConfig);
            }
            
        } catch (Exception e) {
            logger.warn("Error loading memory configuration, using defaults", e);
        }
    }
    
    /**
     * Performs periodic memory check and monitoring.
     */
    private void performMemoryCheck() {
        try {
            totalMemoryChecks.incrementAndGet();
            
            MemorySnapshot snapshot = createMemoryCheckpoint();
            
            // Check for memory threshold violations
            if (snapshot.getHeapUtilization() > CRITICAL_THRESHOLD * 100) {
                logger.warn("Critical memory utilization detected: {}%", snapshot.getHeapUtilization());
            }
            
            // Update peak memory tracking
            long currentUsed = snapshot.getHeapUsed();
            long currentPeak = peakMemoryUsage.get();
            if (currentUsed > currentPeak) {
                peakMemoryUsage.set(currentUsed);
            }
            
            // Check GC activity
            checkGCActivity();
            
        } catch (Exception e) {
            logger.error("Error during memory check", e);
        }
    }
    
    /**
     * Performs memory leak detection analysis.
     */
    private void performMemoryLeakDetection() {
        try {
            detectMemoryLeaks();
        } catch (Exception e) {
            logger.error("Error during memory leak detection", e);
        }
    }
    
    /**
     * Checks for garbage collection activity and updates baseline.
     */
    private void checkGCActivity() {
        long currentGcCount = gcMXBeans.stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionCount)
            .sum();
        
        long lastGcCount = lastGcCollectionCount.get();
        
        if (currentGcCount > lastGcCount) {
            // GC occurred, update baseline
            lastGcCollectionCount.set(currentGcCount);
            gcCycleCount.incrementAndGet();
            updateHeapUsageBaseline();
        }
    }
    
    /**
     * Updates heap usage baseline after garbage collection.
     */
    private void updateHeapUsageBaseline() {
        try {
            long currentHeapUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
            baselineHeapUsage.set(currentHeapUsed);
            
            logger.debug("Updated heap baseline to: {}MB", currentHeapUsed / (1024 * 1024));
            
        } catch (Exception e) {
            logger.debug("Error updating heap baseline", e);
        }
    }
    
    /**
     * Calculates current heap utilization percentage.
     */
    private double calculateHeapUtilization() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        if (heapUsage.getMax() > 0) {
            return (double) heapUsage.getUsed() / heapUsage.getMax() * 100.0;
        }
        return 0.0;
    }
    
    /**
     * Calculates memory growth rate based on recent history.
     */
    private double calculateMemoryGrowthRate() {
        memoryLock.readLock().lock();
        try {
            if (memoryHistory.size() < 2) {
                return 0.0;
            }
            
            List<MemorySnapshot> snapshots = new ArrayList<>(memoryHistory);
            MemorySnapshot latest = snapshots.get(snapshots.size() - 1);
            MemorySnapshot earlier = snapshots.get(Math.max(0, snapshots.size() - 10));
            
            long memoryDiff = latest.getHeapUsed() - earlier.getHeapUsed();
            long timeDiff = Duration.between(earlier.getTimestamp(), latest.getTimestamp()).toMillis();
            
            if (timeDiff > 0) {
                return (double) memoryDiff / timeDiff * 1000; // bytes per second
            }
            
            return 0.0;
            
        } finally {
            memoryLock.readLock().unlock();
        }
    }
    
    /**
     * Checks if current memory usage is within configured limits.
     */
    private boolean isMemoryWithinLimits(long usedMemory) {
        return usedMemory <= configuredTotalLimit;
    }
    
    /**
     * Analyzes heap growth pattern for potential memory leaks.
     */
    private MemoryLeak analyzeHeapGrowthPattern() {
        if (memoryHistory.size() < 10) {
            return null;
        }
        
        List<MemorySnapshot> snapshots = new ArrayList<>(memoryHistory);
        Collections.reverse(snapshots); // Most recent first
        
        // Look for sustained growth pattern
        int growthCount = 0;
        long totalGrowth = 0;
        
        for (int i = 1; i < Math.min(10, snapshots.size()); i++) {
            long growth = snapshots.get(i-1).getHeapUsed() - snapshots.get(i).getHeapUsed();
            if (growth > 0) {
                growthCount++;
                totalGrowth += growth;
            }
        }
        
        // If more than 70% of recent samples show growth
        if (growthCount > 7 && totalGrowth > 10 * 1024 * 1024) { // > 10MB growth
            return new MemoryLeak(
                MemoryLeakType.HEAP_GROWTH,
                "Framework",
                Instant.now(),
                totalGrowth,
                (double) totalGrowth / (growthCount * monitoringInterval.toMillis()) * 1000, // growth rate
                MemoryLeakSeverity.MEDIUM,
                generateStackTrace(),
                "Review object retention and implement periodic cleanup",
                Duration.between(snapshots.get(9).getTimestamp(), snapshots.get(0).getTimestamp()),
                true
            );
        }
        
        return null;
    }
    
    /**
     * Analyzes component-specific memory leaks.
     */
    private List<MemoryLeak> analyzeComponentMemoryLeaks() {
        List<MemoryLeak> leaks = new ArrayList<>();
        
        componentMemoryMap.forEach((componentName, tracker) -> {
            // Check for memory growth without corresponding deallocations
            if (tracker.getAllocationCount() > tracker.getDeallocationCount() * 2) {
                long leakAmount = tracker.getCurrentMemoryUsage();
                if (leakAmount > 50 * 1024 * 1024) { // > 50MB
                    leaks.add(new MemoryLeak(
                        MemoryLeakType.OBJECT_RETENTION,
                        componentName,
                        Instant.now(),
                        leakAmount,
                        0.0, // Unknown growth rate
                        MemoryLeakSeverity.HIGH,
                        generateStackTrace(),
                        "Review " + componentName + " component for proper resource cleanup",
                        Duration.ofMinutes(30), // Estimated duration
                        false
                    ));
                }
            }
        });
        
        return leaks;
    }
    
    /**
     * Analyzes browser session memory leaks.
     */
    private MemoryLeak analyzeBrowserSessionLeaks() {
        long totalBrowserMemory = browserSessionMemory.values().stream()
            .mapToLong(Long::longValue)
            .sum();
        
        if (totalBrowserMemory > browserSessionMemory.size() * BROWSER_SESSION_LIMIT * 1.5) {
            return new MemoryLeak(
                MemoryLeakType.UNCLOSED_RESOURCE,
                "BrowserSessions",
                Instant.now(),
                totalBrowserMemory,
                0.0,
                MemoryLeakSeverity.HIGH,
                generateStackTrace(),
                "Ensure proper browser session cleanup with driver.quit()",
                Duration.ofMinutes(15),
                true
            );
        }
        
        return null;
    }
    
    /**
     * Cleans up expired component memory trackers.
     */
    private void cleanupExpiredComponentTrackers() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        
        componentMemoryMap.entrySet().removeIf(entry -> 
            entry.getValue().getLastUpdateTime().isBefore(cutoff));
    }
    
    /**
     * Cleans up browser session memory tracking.
     */
    private void cleanupBrowserSessionMemory() {
        // Remove sessions that haven't been updated recently
        // This is a simplified cleanup - in practice, would coordinate with browser manager
        if (browserSessionMemory.size() > 20) {
            browserSessionMemory.clear();
        }
    }
    
    /**
     * Compacts memory history to conserve space.
     */
    private void compactMemoryHistory() {
        memoryLock.writeLock().lock();
        try {
            while (memoryHistory.size() > 50) {
                memoryHistory.poll();
            }
        } finally {
            memoryLock.writeLock().unlock();
        }
    }
    
    /**
     * Generates a stack trace for memory leak analysis.
     */
    private String generateStackTrace() {
        StringBuilder trace = new StringBuilder();
        StackTraceElement[] elements = Thread.currentThread().getStackTrace();
        
        for (int i = 0; i < Math.min(10, elements.length); i++) {
            trace.append(elements[i].toString()).append("\n");
        }
        
        return trace.toString();
    }
    
    /**
     * Determines allocation category based on allocation rate.
     */
    private AllocationCategory determineAllocationCategory(double allocationRate) {
        if (allocationRate > 1024 * 1024) { // > 1MB/sec
            return AllocationCategory.HIGH;
        } else if (allocationRate > 100 * 1024) { // > 100KB/sec
            return AllocationCategory.MEDIUM;
        } else {
            return AllocationCategory.LOW;
        }
    }
}

/**
 * MemoryMetrics class represents comprehensive memory usage information.
 */
class MemoryMetrics {
    
    private final MemoryUsage heapUsage;
    private final MemoryUsage nonHeapUsage;
    private final long usedMemory;
    private final long maxMemory;
    private final long freeMemory;
    private final double memoryUtilization;
    private final long gcCount;
    private final long gcTime;
    private final Instant timestamp;
    private final Map<String, ComponentMemoryUsage> componentBreakdown;
    private final double memoryGrowthRate;
    private final boolean withinLimits;
    
    public MemoryMetrics(MemoryUsage heapUsage, MemoryUsage nonHeapUsage, long usedMemory,
                        long maxMemory, long freeMemory, double memoryUtilization,
                        long gcCount, long gcTime, Instant timestamp,
                        Map<String, ComponentMemoryUsage> componentBreakdown,
                        double memoryGrowthRate, boolean withinLimits) {
        this.heapUsage = heapUsage;
        this.nonHeapUsage = nonHeapUsage;
        this.usedMemory = usedMemory;
        this.maxMemory = maxMemory;
        this.freeMemory = freeMemory;
        this.memoryUtilization = memoryUtilization;
        this.gcCount = gcCount;
        this.gcTime = gcTime;
        this.timestamp = timestamp;
        this.componentBreakdown = new HashMap<>(componentBreakdown);
        this.memoryGrowthRate = memoryGrowthRate;
        this.withinLimits = withinLimits;
    }
    
    public MemoryUsage getHeapUsage() { return heapUsage; }
    public MemoryUsage getNonHeapUsage() { return nonHeapUsage; }
    public long getUsedMemory() { return usedMemory; }
    public long getMaxMemory() { return maxMemory; }
    public long getFreeMemory() { return freeMemory; }
    public double getMemoryUtilization() { return memoryUtilization; }
    public long getGCCount() { return gcCount; }
    public long getGCTime() { return gcTime; }
    public Instant getTimestamp() { return timestamp; }
    public Map<String, ComponentMemoryUsage> getComponentBreakdown() { return componentBreakdown; }
    public double getMemoryGrowthRate() { return memoryGrowthRate; }
    public boolean isWithinLimits() { return withinLimits; }
}

/**
 * MemoryLeak class represents a detected memory leak with detailed analysis.
 */
class MemoryLeak {
    
    private final MemoryLeakType leakType;
    private final String componentName;
    private final Instant detectionTime;
    private final long memoryAmount;
    private final double growthRate;
    private final MemoryLeakSeverity severity;
    private final String stackTrace;
    private final String recommendedAction;
    private final Duration duration;
    private final boolean confirmed;
    
    public MemoryLeak(MemoryLeakType leakType, String componentName, Instant detectionTime,
                     long memoryAmount, double growthRate, MemoryLeakSeverity severity,
                     String stackTrace, String recommendedAction, Duration duration, boolean confirmed) {
        this.leakType = leakType;
        this.componentName = componentName;
        this.detectionTime = detectionTime;
        this.memoryAmount = memoryAmount;
        this.growthRate = growthRate;
        this.severity = severity;
        this.stackTrace = stackTrace;
        this.recommendedAction = recommendedAction;
        this.duration = duration;
        this.confirmed = confirmed;
    }
    
    public MemoryLeakType getLeakType() { return leakType; }
    public String getComponentName() { return componentName; }
    public Instant getDetectionTime() { return detectionTime; }
    public long getMemoryAmount() { return memoryAmount; }
    public double getGrowthRate() { return growthRate; }
    public MemoryLeakSeverity getSeverity() { return severity; }
    public String getStackTrace() { return stackTrace; }
    public String getRecommendedAction() { return recommendedAction; }
    public Duration getDuration() { return duration; }
    public boolean isConfirmed() { return confirmed; }
}

/**
 * MemoryLeakType enumeration defines different types of memory leaks.
 */
enum MemoryLeakType {
    HEAP_GROWTH,
    THREAD_LOCAL,
    UNCLOSED_RESOURCE,
    OBJECT_RETENTION,
    NATIVE_MEMORY,
    WEAK_REFERENCE
}

/**
 * MemoryHealthStatus enumeration defines memory health states.
 */
enum MemoryHealthStatus {
    HEALTHY,
    WARNING,
    CRITICAL,
    OPTIMIZING,
    LEAK_DETECTED
}

// ========== SUPPORTING CLASSES ==========

/**
 * MemoryLeakSeverity enumeration defines severity levels for memory leaks.
 */
enum MemoryLeakSeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}

/**
 * ComponentMemoryTracker tracks memory usage for framework components.
 */
class ComponentMemoryTracker {
    private volatile long currentMemoryUsage = 0;
    private volatile long peakMemoryUsage = 0;
    private volatile Instant lastUpdateTime = Instant.now();
    private final AtomicLong allocationCount = new AtomicLong(0);
    private final AtomicLong deallocationCount = new AtomicLong(0);
    
    public long getCurrentMemoryUsage() { return currentMemoryUsage; }
    public long getPeakMemoryUsage() { return peakMemoryUsage; }
    public Instant getLastUpdateTime() { return lastUpdateTime; }
    public long getAllocationCount() { return allocationCount.get(); }
    public long getDeallocationCount() { return deallocationCount.get(); }
    
    public void updateMemoryUsage(long usage) {
        currentMemoryUsage = usage;
        if (usage > peakMemoryUsage) {
            peakMemoryUsage = usage;
        }
        lastUpdateTime = Instant.now();
    }
    
    public void recordAllocation() { allocationCount.incrementAndGet(); }
    public void recordDeallocation() { deallocationCount.incrementAndGet(); }
}

/**
 * ComponentMemoryUsage represents memory usage data for a specific component.
 */
class ComponentMemoryUsage {
    private final String componentName;
    private final long memoryUsage;
    private final long peakUsage;
    private final Instant lastUpdate;
    private final long allocationCount;
    private final long deallocationCount;
    
    public ComponentMemoryUsage(String componentName, long memoryUsage, long peakUsage,
                               Instant lastUpdate, long allocationCount, long deallocationCount) {
        this.componentName = componentName;
        this.memoryUsage = memoryUsage;
        this.peakUsage = peakUsage;
        this.lastUpdate = lastUpdate;
        this.allocationCount = allocationCount;
        this.deallocationCount = deallocationCount;
    }
    
    public String getComponentName() { return componentName; }
    public long getMemoryUsage() { return memoryUsage; }
    public long getPeakUsage() { return peakUsage; }
    public Instant getLastUpdate() { return lastUpdate; }
    public long getAllocationCount() { return allocationCount; }
    public long getDeallocationCount() { return deallocationCount; }
}

/**
 * MemorySnapshot captures memory state at a specific point in time.
 */
class MemorySnapshot {
    private final Instant timestamp;
    private final long heapUsed;
    private final long heapMax;
    private final double heapUtilization;
    private final long gcCount;
    private final Map<String, ComponentMemoryUsage> componentUsage;
    
    public MemorySnapshot(Instant timestamp, long heapUsed, long heapMax, double heapUtilization,
                         long gcCount, Map<String, ComponentMemoryUsage> componentUsage) {
        this.timestamp = timestamp;
        this.heapUsed = heapUsed;
        this.heapMax = heapMax;
        this.heapUtilization = heapUtilization;
        this.gcCount = gcCount;
        this.componentUsage = new HashMap<>(componentUsage);
    }
    
    public Instant getTimestamp() { return timestamp; }
    public long getHeapUsed() { return heapUsed; }
    public long getHeapMax() { return heapMax; }
    public double getHeapUtilization() { return heapUtilization; }
    public long getGcCount() { return gcCount; }
    public Map<String, ComponentMemoryUsage> getComponentUsage() { return componentUsage; }
}

/**
 * MemoryTrend represents memory usage trend data.
 */
class MemoryTrend {
    private final Instant timestamp;
    private final long memoryUsage;
    private final double utilization;
    private final long gcCount;
    
    public MemoryTrend(Instant timestamp, long memoryUsage, double utilization, long gcCount) {
        this.timestamp = timestamp;
        this.memoryUsage = memoryUsage;
        this.utilization = utilization;
        this.gcCount = gcCount;
    }
    
    public Instant getTimestamp() { return timestamp; }
    public long getMemoryUsage() { return memoryUsage; }
    public double getUtilization() { return utilization; }
    public long getGcCount() { return gcCount; }
}

/**
 * MemoryOptimizationRecommendation provides actionable memory optimization advice.
 */
class MemoryOptimizationRecommendation {
    private final String recommendationType;
    private final String description;
    private final String action;
    private final OptimizationPriority priority;
    
    public MemoryOptimizationRecommendation(String recommendationType, String description,
                                           String action, OptimizationPriority priority) {
        this.recommendationType = recommendationType;
        this.description = description;
        this.action = action;
        this.priority = priority;
    }
    
    public String getRecommendationType() { return recommendationType; }
    public String getDescription() { return description; }
    public String getAction() { return action; }
    public OptimizationPriority getPriority() { return priority; }
}

/**
 * OptimizationPriority enumeration for recommendation prioritization.
 */
enum OptimizationPriority {
    LOW, MEDIUM, HIGH, CRITICAL
}

/**
 * MemoryAllocationPattern represents memory allocation patterns for analysis.
 */
class MemoryAllocationPattern {
    private final Instant timestamp;
    private final long allocationAmount;
    private final double allocationRate;
    private final Duration duration;
    private final AllocationCategory category;
    
    public MemoryAllocationPattern(Instant timestamp, long allocationAmount, double allocationRate,
                                  Duration duration, AllocationCategory category) {
        this.timestamp = timestamp;
        this.allocationAmount = allocationAmount;
        this.allocationRate = allocationRate;
        this.duration = duration;
        this.category = category;
    }
    
    public Instant getTimestamp() { return timestamp; }
    public long getAllocationAmount() { return allocationAmount; }
    public double getAllocationRate() { return allocationRate; }
    public Duration getDuration() { return duration; }
    public AllocationCategory getCategory() { return category; }
}

/**
 * AllocationCategory enumeration for categorizing allocation rates.
 */
enum AllocationCategory {
    LOW, MEDIUM, HIGH
}