package com.automation.framework.resources;

// External imports
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Optional;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Internal imports
import com.automation.framework.core.FrameworkManager;
import com.automation.framework.core.ShutdownHandler;
import com.automation.framework.core.ConfigurationManager;

// Standard imports
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ThreadPoolManager provides comprehensive thread pool lifecycle management for the automation framework.
 * 
 * Key Features:
 * - Isolated thread pools per module (WEB, API, REPORTING, MONITORING, CORE) for bulkhead pattern
 * - Dynamic thread pool sizing based on system resources and performance requirements
 * - Automatic rebalancing capabilities with monitoring and adjustment
 * - ThreadLocal cleanup to prevent memory leaks using remove() method
 * - Ordered shutdown sequence integration with framework shutdown procedure
 * - Comprehensive monitoring with thread allocation efficiency tracking
 * - Failure isolation boundaries to prevent cascading failures
 * 
 * This class implements enterprise-grade thread pool management with proactive
 * resource monitoring and automatic optimization capabilities.
 */
public class ThreadPoolManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolManager.class);
    
    // Singleton instance management
    private static volatile ThreadPoolManager instance;
    private static final Object instanceLock = new Object();
    
    // Framework integration components
    private final FrameworkManager frameworkManager;
    private final ShutdownHandler shutdownHandler;
    private final ConfigurationManager configurationManager;
    
    // Thread pool storage - isolated pools per module
    private final Map<ModulePool, ThreadPoolExecutor> modulePools;
    private final Map<ModulePool, ThreadPoolConfiguration> poolConfigurations;
    private final Map<ModulePool, ThreadPoolMetrics> poolMetrics;
    private final Map<ModulePool, ThreadPoolStatus> poolStatuses;
    
    // ThreadLocal registry for cleanup management
    private final ThreadLocalRegistry threadLocalRegistry;
    
    // Monitoring and rebalancing
    private final AtomicInteger totalActiveThreads;
    private final AtomicInteger totalAvailableThreads;
    private final ScheduledExecutorService monitoringExecutor;
    private final ScheduledExecutorService rebalancingExecutor;
    
    // Thread safety and lifecycle management
    private final ReentrantReadWriteLock poolLock;
    private volatile boolean isInitialized;
    private volatile boolean isShuttingDown;
    
    // System resource monitoring
    private final ThreadMXBean threadMXBean;
    private final Runtime runtime;
    private final int availableProcessors;
    
    // Rebalancing metrics and timing
    private volatile Instant lastRebalanceTime;
    private final AtomicInteger rebalanceCount;
    
    /**
     * Private constructor for singleton pattern.
     * Initializes all core components and prepares for thread pool management.
     */
    private ThreadPoolManager() {
        // Initialize framework integration components
        this.frameworkManager = FrameworkManager.getInstance();
        this.shutdownHandler = ShutdownHandler.getInstance();
        this.configurationManager = ConfigurationManager.getInstance();
        
        // Initialize thread pool storage
        this.modulePools = new ConcurrentHashMap<>();
        this.poolConfigurations = new ConcurrentHashMap<>();
        this.poolMetrics = new ConcurrentHashMap<>();
        this.poolStatuses = new ConcurrentHashMap<>();
        
        // Initialize ThreadLocal registry
        this.threadLocalRegistry = new ThreadLocalRegistry();
        
        // Initialize monitoring components
        this.totalActiveThreads = new AtomicInteger(0);
        this.totalAvailableThreads = new AtomicInteger(0);
        this.poolLock = new ReentrantReadWriteLock();
        
        // Initialize system monitoring
        this.threadMXBean = ManagementFactory.getThreadMXBean();
        this.runtime = Runtime.getRuntime();
        this.availableProcessors = runtime.availableProcessors();
        
        // Initialize rebalancing metrics
        this.lastRebalanceTime = Instant.now();
        this.rebalanceCount = new AtomicInteger(0);
        
        // Initialize monitoring executor (daemon thread)
        this.monitoringExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ThreadPoolManager-Monitor");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) -> 
                logger.error("Uncaught exception in monitoring thread", ex));
            return t;
        });
        
        // Initialize rebalancing executor (daemon thread)
        this.rebalancingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ThreadPoolManager-Rebalancer");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) -> 
                logger.error("Uncaught exception in rebalancing thread", ex));
            return t;
        });
        
        // Set initial state
        this.isInitialized = false;
        this.isShuttingDown = false;
        
        logger.info("ThreadPoolManager instance created successfully");
    }
    
    /**
     * Returns the singleton instance of ThreadPoolManager.
     * Thread-safe lazy initialization with double-checked locking.
     * 
     * @return ThreadPoolManager instance
     */
    public static ThreadPoolManager getInstance() {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new ThreadPoolManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Initializes all module thread pools with configurations based on system resources.
     * Creates isolated thread pools for each module to prevent cascading failures.
     * Registers with framework manager and sets up monitoring.
     * 
     * @throws IllegalStateException if already initialized or if initialization fails
     */
    public void initializeModulePools() {
        if (isInitialized) {
            throw new IllegalStateException("ThreadPoolManager already initialized");
        }
        
        if (isShuttingDown) {
            throw new IllegalStateException("Cannot initialize during shutdown");
        }
        
        poolLock.writeLock().lock();
        try {
            logger.info("Initializing module thread pools for {} modules", ModulePool.values().length);
            
            // Initialize each module pool with appropriate configuration
            for (ModulePool module : ModulePool.values()) {
                ThreadPoolConfiguration config = createModuleConfiguration(module);
                ThreadPoolExecutor executor = createThreadPoolExecutor(module, config);
                
                // Store pool and configuration
                modulePools.put(module, executor);
                poolConfigurations.put(module, config);
                poolMetrics.put(module, new ThreadPoolMetrics(module));
                poolStatuses.put(module, ThreadPoolStatus.INITIALIZING);
                
                logger.debug("Initialized {} thread pool: core={}, max={}, queue={}", 
                           module, config.getCorePoolSize(), config.getMaxPoolSize(), config.getQueueCapacity());
            }
            
            // Register with framework manager
            try {
                frameworkManager.registerModule("ThreadPoolManager", this);
                logger.debug("Registered ThreadPoolManager with FrameworkManager");
            } catch (Exception e) {
                logger.error("Failed to register with FrameworkManager", e);
                throw new RuntimeException("Framework registration failed", e);
            }
            
            // Register shutdown hook with shutdown handler
            try {
                shutdownHandler.addShutdownHook("ThreadPoolManager", this::shutdownAllPools);
                logger.debug("Registered shutdown hook with ShutdownHandler");
            } catch (Exception e) {
                logger.error("Failed to register shutdown hook", e);
                throw new RuntimeException("Shutdown hook registration failed", e);
            }
            
            // Start monitoring
            startMonitoring();
            
            // Start rebalancing
            startRebalancing();
            
            // Update all pool statuses to running
            poolStatuses.replaceAll((module, status) -> ThreadPoolStatus.RUNNING);
            
            // Calculate initial available threads
            updateAvailableThreads();
            
            isInitialized = true;
            
            logger.info("ThreadPoolManager initialization completed successfully. Total pools: {}", modulePools.size());
            
        } catch (Exception e) {
            logger.error("Failed to initialize ThreadPoolManager", e);
            // Cleanup any partially initialized pools
            cleanupPartialInitialization();
            throw new RuntimeException("ThreadPoolManager initialization failed", e);
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    /**
     * Returns the thread pool executor for web automation module.
     * 
     * @return ThreadPoolExecutor for web module
     * @throws IllegalStateException if not initialized
     */
    public ThreadPoolExecutor getWebThreadPool() {
        ensureInitialized();
        return getModuleThreadPool(ModulePool.WEB);
    }
    
    /**
     * Returns the thread pool executor for API automation module.
     * 
     * @return ThreadPoolExecutor for API module
     * @throws IllegalStateException if not initialized
     */
    public ThreadPoolExecutor getApiThreadPool() {
        ensureInitialized();
        return getModuleThreadPool(ModulePool.API);
    }
    
    /**
     * Returns the thread pool executor for reporting module.
     * 
     * @return ThreadPoolExecutor for reporting module
     * @throws IllegalStateException if not initialized
     */
    public ThreadPoolExecutor getReportingThreadPool() {
        ensureInitialized();
        return getModuleThreadPool(ModulePool.REPORTING);
    }
    
    /**
     * Gets the thread pool utilization as a percentage.
     * Calculates utilization across all active pools.
     * 
     * @return utilization percentage (0.0 to 100.0)
     */
    public double getThreadPoolUtilization() {
        ensureInitialized();
        
        poolLock.readLock().lock();
        try {
            int totalActive = 0;
            int totalMax = 0;
            
            for (ThreadPoolExecutor executor : modulePools.values()) {
                totalActive += executor.getActiveCount();
                totalMax += executor.getMaximumPoolSize();
            }
            
            return totalMax > 0 ? (totalActive * 100.0 / totalMax) : 0.0;
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Returns the total number of active threads across all pools.
     * 
     * @return total active thread count
     */
    public int getActiveThreads() {
        ensureInitialized();
        return totalActiveThreads.get();
    }
    
    /**
     * Returns the total number of available threads across all pools.
     * 
     * @return total available thread count
     */
    public int getAvailableThreads() {
        ensureInitialized();
        return totalAvailableThreads.get();
    }
    
    /**
     * Returns comprehensive thread pool metrics for all modules.
     * 
     * @return map of module to metrics
     */
    public Map<ModulePool, ThreadPoolMetrics> getThreadPoolMetrics() {
        ensureInitialized();
        
        poolLock.readLock().lock();
        try {
            Map<ModulePool, ThreadPoolMetrics> currentMetrics = new HashMap<>();
            
            for (ModulePool module : ModulePool.values()) {
                ThreadPoolMetrics metrics = poolMetrics.get(module);
                if (metrics != null) {
                    // Update metrics with current values
                    metrics.updateFromExecutor(modulePools.get(module));
                    currentMetrics.put(module, metrics);
                }
            }
            
            return Collections.unmodifiableMap(currentMetrics);
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Checks if all thread pools are healthy and operating within normal parameters.
     * 
     * @return true if all pools are healthy, false otherwise
     */
    public boolean isThreadPoolHealthy() {
        ensureInitialized();
        
        poolLock.readLock().lock();
        try {
            for (Map.Entry<ModulePool, ThreadPoolExecutor> entry : modulePools.entrySet()) {
                ThreadPoolExecutor executor = entry.getValue();
                ModulePool module = entry.getKey();
                
                // Check if pool is terminated or shutting down
                if (executor.isTerminated() || executor.isShutdown()) {
                    if (!isShuttingDown) {
                        logger.warn("Thread pool {} is terminated unexpectedly", module);
                        return false;
                    }
                    continue;
                }
                
                // Check for excessive queue size
                int queueSize = executor.getQueue().size();
                ThreadPoolConfiguration config = poolConfigurations.get(module);
                if (queueSize > config.getQueueCapacity() * 0.9) {
                    logger.warn("Thread pool {} queue is nearly full: {}/{}", module, queueSize, config.getQueueCapacity());
                    return false;
                }
                
                // Check for excessive rejection count
                ThreadPoolMetrics metrics = poolMetrics.get(module);
                if (metrics.getTaskRejectionCount() > 100) {
                    logger.warn("Thread pool {} has high rejection count: {}", module, metrics.getTaskRejectionCount());
                    return false;
                }
            }
            
            return true;
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Returns the total queue size across all thread pools.
     * 
     * @return total queued task count
     */
    public int getQueueSize() {
        ensureInitialized();
        
        poolLock.readLock().lock();
        try {
            return modulePools.values().stream()
                    .mapToInt(executor -> executor.getQueue().size())
                    .sum();
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Detects and returns count of potential thread leaks.
     * Uses ThreadLocal registry to identify unreleased ThreadLocal variables.
     * 
     * @return number of detected thread leaks
     */
    public int getThreadLeaks() {
        ensureInitialized();
        return threadLocalRegistry.getThreadLocalLeaks();
    }
    
    /**
     * Performs comprehensive ThreadLocal cleanup using remove() method.
     * Prevents memory leaks in thread pools by cleaning up thread-local storage.
     */
    public void cleanupThreadLocals() {
        ensureInitialized();
        threadLocalRegistry.cleanupAllThreadLocals();
        logger.debug("ThreadLocal cleanup completed");
    }
    
    /**
     * Triggers rebalancing of all thread pools based on current performance metrics.
     * Adjusts pool sizes dynamically based on system resources and load patterns.
     * 
     * @return true if rebalancing was performed, false if not needed
     */
    public boolean rebalanceThreadPools() {
        if (!isInitialized || isShuttingDown) {
            return false;
        }
        
        poolLock.writeLock().lock();
        try {
            logger.info("Starting thread pool rebalancing");
            
            boolean rebalanced = false;
            long freeMemory = runtime.freeMemory();
            long totalMemory = runtime.totalMemory();
            double memoryUtilization = 1.0 - (double) freeMemory / totalMemory;
            
            for (ModulePool module : ModulePool.values()) {
                ThreadPoolExecutor executor = modulePools.get(module);
                ThreadPoolConfiguration config = poolConfigurations.get(module);
                ThreadPoolMetrics metrics = poolMetrics.get(module);
                
                // Calculate optimal pool size based on metrics
                int optimalSize = calculateOptimalPoolSize(module, executor, metrics, memoryUtilization);
                
                if (optimalSize != executor.getCorePoolSize()) {
                    adjustPoolSize(module, optimalSize);
                    rebalanced = true;
                }
                
                // Update pool status
                poolStatuses.put(module, ThreadPoolStatus.REBALANCING);
            }
            
            if (rebalanced) {
                lastRebalanceTime = Instant.now();
                rebalanceCount.incrementAndGet();
                
                // Update pool statuses back to running
                poolStatuses.replaceAll((module, status) -> ThreadPoolStatus.RUNNING);
                
                logger.info("Thread pool rebalancing completed. Rebalance count: {}", rebalanceCount.get());
            } else {
                logger.debug("No rebalancing needed - pools are optimally sized");
            }
            
            return rebalanced;
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    /**
     * Shuts down all thread pools gracefully with timeout protection.
     * Implements ordered shutdown sequence as part of framework shutdown.
     * 
     * @return true if all pools shut down successfully within timeout
     */
    public boolean shutdownAllPools() {
        if (isShuttingDown) {
            logger.warn("Shutdown already in progress");
            return false;
        }
        
        poolLock.writeLock().lock();
        try {
            logger.info("Starting graceful shutdown of all thread pools");
            isShuttingDown = true;
            
            // Stop monitoring and rebalancing first
            stopMonitoring();
            stopRebalancing();
            
            // Update all pool statuses
            poolStatuses.replaceAll((module, status) -> ThreadPoolStatus.SHUTTING_DOWN);
            
            // Shutdown all module pools in reverse dependency order
            boolean allShutdown = true;
            List<ModulePool> shutdownOrder = Arrays.asList(
                ModulePool.MONITORING,  // Stop monitoring first
                ModulePool.REPORTING,   // Then reporting
                ModulePool.WEB,         // Then web automation
                ModulePool.API,         // Then API automation
                ModulePool.CORE         // Finally core services
            );
            
            for (ModulePool module : shutdownOrder) {
                boolean moduleShutdown = shutdownPoolGracefully(module);
                if (!moduleShutdown) {
                    allShutdown = false;
                    logger.warn("Failed to shutdown {} pool gracefully", module);
                }
            }
            
            // Final ThreadLocal cleanup
            threadLocalRegistry.cleanupAllThreadLocals();
            
            // Update final status
            if (allShutdown) {
                poolStatuses.replaceAll((module, status) -> ThreadPoolStatus.TERMINATED);
                logger.info("All thread pools shut down successfully");
            } else {
                poolStatuses.replaceAll((module, status) -> ThreadPoolStatus.ERROR);
                logger.error("Some thread pools failed to shutdown gracefully");
            }
            
            return allShutdown;
            
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    /**
     * Shuts down a specific thread pool gracefully with timeout.
     * 
     * @param module the module pool to shutdown
     * @return true if shutdown successful within timeout
     */
    public boolean shutdownPoolGracefully(ModulePool module) {
        ensureInitialized();
        
        poolLock.writeLock().lock();
        try {
            ThreadPoolExecutor executor = modulePools.get(module);
            if (executor == null) {
                logger.warn("Thread pool {} not found for shutdown", module);
                return false;
            }
            
            if (executor.isShutdown()) {
                logger.debug("Thread pool {} already shutdown", module);
                return true;
            }
            
            logger.info("Shutting down {} thread pool gracefully", module);
            
            // Get shutdown timeout from configuration
            ThreadPoolConfiguration config = poolConfigurations.get(module);
            Duration timeout = config.getShutdownTimeout();
            
            // Initiate graceful shutdown
            executor.shutdown();
            
            try {
                // Wait for existing tasks to complete
                boolean terminated = executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
                
                if (!terminated) {
                    logger.warn("Thread pool {} did not terminate within timeout, forcing shutdown", module);
                    return forceShutdownPool(module);
                }
                
                logger.debug("Thread pool {} shut down gracefully", module);
                poolStatuses.put(module, ThreadPoolStatus.TERMINATED);
                return true;
                
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting for {} pool shutdown", module, e);
                Thread.currentThread().interrupt();
                return forceShutdownPool(module);
            }
            
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    /**
     * Forces immediate shutdown of a specific thread pool.
     * 
     * @param module the module pool to force shutdown
     * @return true if force shutdown completed
     */
    public boolean forceShutdownPool(ModulePool module) {
        poolLock.writeLock().lock();
        try {
            ThreadPoolExecutor executor = modulePools.get(module);
            if (executor == null) {
                return false;
            }
            
            logger.warn("Force shutting down {} thread pool", module);
            
            // Cancel currently executing tasks
            List<Runnable> pendingTasks = executor.shutdownNow();
            
            if (!pendingTasks.isEmpty()) {
                logger.warn("Thread pool {} had {} pending tasks that were cancelled", module, pendingTasks.size());
            }
            
            try {
                // Wait a short time for forced termination
                boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
                if (!terminated) {
                    logger.error("Thread pool {} failed to terminate even after force shutdown", module);
                    poolStatuses.put(module, ThreadPoolStatus.ERROR);
                    return false;
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted during force shutdown of {} pool", module, e);
                Thread.currentThread().interrupt();
                poolStatuses.put(module, ThreadPoolStatus.ERROR);
                return false;
            }
            
            poolStatuses.put(module, ThreadPoolStatus.TERMINATED);
            logger.info("Thread pool {} force shutdown completed", module);
            return true;
            
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    /**
     * Adjusts the pool size for a specific module.
     * 
     * @param module the module pool to adjust
     * @param newSize the new core pool size
     */
    public void adjustPoolSize(ModulePool module, int newSize) {
        ensureInitialized();
        
        poolLock.writeLock().lock();
        try {
            ThreadPoolExecutor executor = modulePools.get(module);
            ThreadPoolConfiguration config = poolConfigurations.get(module);
            
            if (executor == null || config == null) {
                logger.error("Cannot adjust pool size for {}: pool or configuration not found", module);
                return;
            }
            
            int currentSize = executor.getCorePoolSize();
            if (newSize == currentSize) {
                return;
            }
            
            // Validate new size is within acceptable bounds
            int minSize = Math.max(1, availableProcessors / 4);
            int maxSize = Math.min(availableProcessors * 4, config.getMaxPoolSize());
            int adjustedSize = Math.max(minSize, Math.min(maxSize, newSize));
            
            logger.info("Adjusting {} pool size from {} to {} (requested: {})", 
                       module, currentSize, adjustedSize, newSize);
            
            // Update core pool size
            executor.setCorePoolSize(adjustedSize);
            
            // Also update maximum pool size if needed
            if (adjustedSize > executor.getMaximumPoolSize()) {
                executor.setMaximumPoolSize(adjustedSize);
            }
            
            // Update configuration
            ThreadPoolConfiguration updatedConfig = new ThreadPoolConfiguration.Builder(config)
                    .corePoolSize(adjustedSize)
                    .build();
            poolConfigurations.put(module, updatedConfig);
            
            logger.debug("Pool size adjustment completed for {}", module);
            
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    /**
     * Gets the configuration for a specific module pool.
     * 
     * @param module the module pool
     * @return thread pool configuration
     */
    public ThreadPoolConfiguration getPoolConfiguration(ModulePool module) {
        ensureInitialized();
        
        poolLock.readLock().lock();
        try {
            return poolConfigurations.get(module);
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Sets the configuration for a specific module pool.
     * 
     * @param module the module pool
     * @param config the new configuration
     */
    public void setPoolConfiguration(ModulePool module, ThreadPoolConfiguration config) {
        ensureInitialized();
        
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }
        
        poolLock.writeLock().lock();
        try {
            ThreadPoolExecutor executor = modulePools.get(module);
            if (executor == null) {
                logger.error("Cannot set configuration for {}: pool not found", module);
                return;
            }
            
            logger.info("Updating configuration for {} pool", module);
            
            // Apply configuration to executor
            executor.setCorePoolSize(config.getCorePoolSize());
            executor.setMaximumPoolSize(config.getMaxPoolSize());
            executor.setKeepAliveTime(config.getKeepAliveTime().toMillis(), TimeUnit.MILLISECONDS);
            executor.allowCoreThreadTimeOut(config.isAllowCoreThreadTimeOut());
            
            // Store updated configuration
            poolConfigurations.put(module, config);
            
            logger.debug("Configuration updated successfully for {}", module);
            
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    /**
     * Monitors thread allocations and efficiency across all pools.
     * 
     * @return monitoring report as string
     */
    public String monitorThreadAllocations() {
        ensureInitialized();
        
        poolLock.readLock().lock();
        try {
            StringBuilder report = new StringBuilder();
            report.append("=== Thread Pool Allocation Report ===\n");
            report.append(String.format("Total Available Processors: %d\n", availableProcessors));
            report.append(String.format("Total Active Threads: %d\n", getActiveThreads()));
            report.append(String.format("Total Available Threads: %d\n", getAvailableThreads()));
            report.append(String.format("Overall Utilization: %.2f%%\n", getThreadPoolUtilization()));
            report.append("\n");
            
            for (ModulePool module : ModulePool.values()) {
                ThreadPoolExecutor executor = modulePools.get(module);
                ThreadPoolMetrics metrics = poolMetrics.get(module);
                ThreadPoolStatus status = poolStatuses.get(module);
                
                if (executor != null && metrics != null) {
                    report.append(String.format("Module: %s (Status: %s)\n", module, status));
                    report.append(String.format("  Active: %d, Core: %d, Max: %d, Queue: %d\n",
                                                executor.getActiveCount(),
                                                executor.getCorePoolSize(),
                                                executor.getMaximumPoolSize(),
                                                executor.getQueue().size()));
                    report.append(String.format("  Completed: %d, Rejected: %d\n",
                                                executor.getCompletedTaskCount(),
                                                metrics.getTaskRejectionCount()));
                    report.append(String.format("  Utilization: %.2f%%, Throughput: %.2f tasks/sec\n",
                                                metrics.getPoolUtilization(),
                                                metrics.getThroughputPerSecond()));
                    report.append("\n");
                }
            }
            
            return report.toString();
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Detects thread leaks using various heuristics.
     * 
     * @return number of detected leaks
     */
    public int detectThreadLeaks() {
        ensureInitialized();
        
        int leaks = 0;
        
        // Check ThreadLocal leaks
        leaks += threadLocalRegistry.getThreadLocalLeaks();
        
        // Check for threads that should have been cleaned up
        long totalThreads = threadMXBean.getThreadCount();
        long expectedThreads = modulePools.values().stream()
                .mapToLong(executor -> executor.getPoolSize())
                .sum();
        
        // Add daemon threads and system threads (rough estimate)
        expectedThreads += 10;
        
        if (totalThreads > expectedThreads * 1.5) {
            int suspiciousThreads = (int) (totalThreads - expectedThreads);
            logger.warn("Detected {} potentially leaked threads (total: {}, expected: {})",
                       suspiciousThreads, totalThreads, expectedThreads);
            leaks += suspiciousThreads;
        }
        
        return leaks;
    }
    
    /**
     * Gets the status for all module pools.
     * 
     * @return map of module to status
     */
    public Map<ModulePool, ThreadPoolStatus> getModulePoolStatus() {
        ensureInitialized();
        
        poolLock.readLock().lock();
        try {
            return new HashMap<>(poolStatuses);
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Registers a ThreadLocal for cleanup tracking.
     * 
     * @param threadLocal the ThreadLocal to register
     * @param name descriptive name for the ThreadLocal
     */
    public void registerThreadLocalCleanup(ThreadLocal<?> threadLocal, String name) {
        threadLocalRegistry.registerThreadLocal(threadLocal, name);
    }
    
    /**
     * Executes a task in the specified module pool.
     * 
     * @param module the target module pool
     * @param task the task to execute
     * @return Future representing the task execution
     */
    public Future<?> executeInPool(ModulePool module, Runnable task) {
        ensureInitialized();
        
        ThreadPoolExecutor executor = getModuleThreadPool(module);
        
        // Wrap task with ThreadLocal cleanup
        Runnable wrappedTask = () -> {
            try {
                task.run();
            } finally {
                // Cleanup ThreadLocals after task execution
                threadLocalRegistry.cleanupAllThreadLocals();
            }
        };
        
        return executor.submit(wrappedTask);
    }
    
    /**
     * Submits an asynchronous task with CompletableFuture.
     * 
     * @param module the target module pool
     * @param supplier the task supplier
     * @param <T> the return type
     * @return CompletableFuture for the result
     */
    public <T> CompletableFuture<T> submitAsyncTask(ModulePool module, Callable<T> supplier) {
        ensureInitialized();
        
        ThreadPoolExecutor executor = getModuleThreadPool(module);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.call();
            } catch (Exception e) {
                throw new RuntimeException("Async task execution failed", e);
            } finally {
                // Cleanup ThreadLocals after task execution
                threadLocalRegistry.cleanupAllThreadLocals();
            }
        }, executor);
    }
    
    /**
     * Gets metrics related to rebalancing operations.
     * 
     * @return rebalancing metrics as formatted string
     */
    public String getRebalancingMetrics() {
        ensureInitialized();
        
        return String.format("Rebalancing Metrics: Count=%d, Last=%s, Interval=%s",
                           rebalanceCount.get(),
                           lastRebalanceTime,
                           configurationManager.getProperty("threadpool.rebalancing.interval"));
    }
    
    /**
     * Manually triggers a rebalancing operation.
     * 
     * @return true if rebalancing was triggered successfully
     */
    public boolean triggerRebalancing() {
        if (!isInitialized || isShuttingDown) {
            return false;
        }
        
        logger.info("Manual rebalancing triggered");
        return rebalanceThreadPools();
    }
    
    /**
     * Checks if shutdown is complete for all pools.
     * 
     * @return true if all pools are terminated
     */
    public boolean isShutdownComplete() {
        if (!isShuttingDown) {
            return false;
        }
        
        poolLock.readLock().lock();
        try {
            return poolStatuses.values().stream()
                    .allMatch(status -> status == ThreadPoolStatus.TERMINATED);
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    // ================= PRIVATE HELPER METHODS =================
    
    /**
     * Ensures the ThreadPoolManager is initialized.
     * 
     * @throws IllegalStateException if not initialized
     */
    private void ensureInitialized() {
        if (!isInitialized) {
            throw new IllegalStateException("ThreadPoolManager not initialized. Call initializeModulePools() first.");
        }
    }
    
    /**
     * Gets the thread pool executor for a specific module.
     * 
     * @param module the module pool
     * @return ThreadPoolExecutor for the module
     */
    private ThreadPoolExecutor getModuleThreadPool(ModulePool module) {
        poolLock.readLock().lock();
        try {
            ThreadPoolExecutor executor = modulePools.get(module);
            if (executor == null) {
                throw new IllegalStateException("Thread pool for " + module + " not found");
            }
            return executor;
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Creates a module-specific thread pool configuration based on system resources.
     * 
     * @param module the target module
     * @return ThreadPoolConfiguration for the module
     */
    private ThreadPoolConfiguration createModuleConfiguration(ModulePool module) {
        // Base configuration from ConfigurationManager
        String baseKey = "threadpool." + module.name().toLowerCase();
        
        // Calculate core and max pool sizes based on module type and available processors
        int corePoolSize = calculateCorePoolSize(module);
        int maxPoolSize = calculateMaxPoolSize(module, corePoolSize);
        int queueCapacity = calculateQueueCapacity(module);
        
        // Get configuration values or use defaults
        Duration keepAliveTime = Duration.ofSeconds(
            Integer.parseInt(configurationManager.getPropertyWithDefault(baseKey + ".keepalive.seconds", "60")));
        Duration shutdownTimeout = Duration.ofSeconds(
            Integer.parseInt(configurationManager.getPropertyWithDefault(baseKey + ".shutdown.timeout.seconds", "30")));
        boolean allowCoreThreadTimeOut = Boolean.parseBoolean(
            configurationManager.getPropertyWithDefault(baseKey + ".allow.core.timeout", "true"));
        
        return new ThreadPoolConfiguration.Builder()
                .corePoolSize(corePoolSize)
                .maxPoolSize(maxPoolSize)
                .keepAliveTime(keepAliveTime)
                .queueCapacity(queueCapacity)
                .threadNamePrefix(module.name().toLowerCase() + "-pool-")
                .rejectionPolicy(createRejectionHandler(module))
                .allowCoreThreadTimeOut(allowCoreThreadTimeOut)
                .rebalancingThreshold(0.8)
                .monitoringInterval(Duration.ofSeconds(30))
                .shutdownTimeout(shutdownTimeout)
                .build();
    }
    
    /**
     * Creates a ThreadPoolExecutor for a specific module.
     * 
     * @param module the target module
     * @param config the thread pool configuration
     * @return configured ThreadPoolExecutor
     */
    private ThreadPoolExecutor createThreadPoolExecutor(ModulePool module, ThreadPoolConfiguration config) {
        // Create blocking queue
        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(config.getQueueCapacity());
        
        // Create thread factory
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
            
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, config.getThreadNamePrefix() + threadNumber.getAndIncrement());
                t.setDaemon(false);  // Not daemon threads for proper shutdown
                t.setPriority(Thread.NORM_PRIORITY);
                
                // Set uncaught exception handler
                t.setUncaughtExceptionHandler((thread, ex) -> {
                    logger.error("Uncaught exception in thread {}", thread.getName(), ex);
                    // Update metrics for the exception
                    ThreadPoolMetrics metrics = poolMetrics.get(module);
                    if (metrics != null) {
                        metrics.incrementTaskRejectionCount();
                    }
                });
                
                return t;
            }
        };
        
        // Create executor
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                config.getCorePoolSize(),
                config.getMaxPoolSize(),
                config.getKeepAliveTime().toMillis(),
                TimeUnit.MILLISECONDS,
                workQueue,
                threadFactory,
                config.getRejectionPolicy()
        );
        
        // Configure additional settings
        executor.allowCoreThreadTimeOut(config.isAllowCoreThreadTimeOut());
        
        logger.debug("Created ThreadPoolExecutor for {}: core={}, max={}, queue={}", 
                   module, config.getCorePoolSize(), config.getMaxPoolSize(), config.getQueueCapacity());
        
        return executor;
    }
    
    /**
     * Calculates the core pool size for a module based on system resources.
     * 
     * @param module the target module
     * @return calculated core pool size
     */
    private int calculateCorePoolSize(ModulePool module) {
        // Base calculation on available processors
        int baseSize = Math.max(1, availableProcessors / 2);
        
        // Module-specific adjustments
        switch (module) {
            case WEB:
                // Web automation needs more threads for browser management
                return Math.max(2, Math.min(baseSize * 2, 8));
            case API:
                // API testing can be more concurrent
                return Math.max(2, Math.min(baseSize * 3, 12));
            case REPORTING:
                // Reporting is typically less intensive
                return Math.max(1, baseSize);
            case MONITORING:
                // Monitoring needs minimal threads
                return Math.max(1, 2);
            case CORE:
                // Core services need stable thread count
                return Math.max(2, baseSize);
            default:
                return Math.max(1, baseSize);
        }
    }
    
    /**
     * Calculates the maximum pool size for a module.
     * 
     * @param module the target module
     * @param coreSize the core pool size
     * @return calculated maximum pool size
     */
    private int calculateMaxPoolSize(ModulePool module, int coreSize) {
        // Maximum should be at least double the core size
        int baseMax = Math.max(coreSize * 2, availableProcessors);
        
        // Module-specific adjustments
        switch (module) {
            case WEB:
                return Math.min(baseMax, 16);  // Limit for browser resource management
            case API:
                return Math.min(baseMax, 24);  // Higher for API concurrency
            case REPORTING:
                return Math.min(baseMax, 8);   // Limited for reporting
            case MONITORING:
                return Math.min(baseMax, 4);   // Minimal for monitoring
            case CORE:
                return Math.min(baseMax, 12);  // Moderate for core services
            default:
                return baseMax;
        }
    }
    
    /**
     * Calculates the queue capacity for a module.
     * 
     * @param module the target module
     * @return calculated queue capacity
     */
    private int calculateQueueCapacity(ModulePool module) {
        switch (module) {
            case WEB:
                return 100;  // Moderate queue for web tests
            case API:
                return 200;  // Larger queue for API tests
            case REPORTING:
                return 50;   // Smaller queue for reports
            case MONITORING:
                return 25;   // Minimal queue for monitoring
            case CORE:
                return 75;   // Moderate queue for core services
            default:
                return 100;
        }
    }
    
    /**
     * Creates a rejection handler for a specific module.
     * 
     * @param module the target module
     * @return RejectedExecutionHandler for the module
     */
    private RejectedExecutionHandler createRejectionHandler(ModulePool module) {
        return new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                // Update rejection metrics
                ThreadPoolMetrics metrics = poolMetrics.get(module);
                if (metrics != null) {
                    metrics.incrementTaskRejectionCount();
                }
                
                // Log rejection
                logger.warn("Task rejected by {} thread pool. Active: {}, Queue: {}, Max: {}",
                          module,
                          executor.getActiveCount(),
                          executor.getQueue().size(),
                          executor.getMaximumPoolSize());
                
                // Module-specific rejection handling
                switch (module) {
                    case WEB:
                    case API:
                        // For critical modules, try to run in calling thread
                        try {
                            if (!executor.isShutdown()) {
                                r.run();
                                logger.debug("Executed rejected {} task in calling thread", module);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to execute rejected {} task in calling thread", module, e);
                        }
                        break;
                    case REPORTING:
                    case MONITORING:
                        // For non-critical modules, just log and discard
                        logger.warn("Discarded rejected {} task", module);
                        break;
                    case CORE:
                        // For core services, throw exception to indicate failure
                        throw new RejectedExecutionException("Core service task rejected", null);
                    default:
                        throw new RejectedExecutionException("Task rejected by " + module + " pool", null);
                }
            }
        };
    }
    
    /**
     * Starts monitoring of thread pools.
     */
    private void startMonitoring() {
        Duration interval = Duration.ofSeconds(
            Integer.parseInt(configurationManager.getPropertyWithDefault("threadpool.monitoring.interval.seconds", "30")));
        
        monitoringExecutor.scheduleAtFixedRate(() -> {
            try {
                updateMetrics();
                updateAvailableThreads();
                logPerformanceMetrics();
            } catch (Exception e) {
                logger.error("Error during thread pool monitoring", e);
            }
        }, interval.toSeconds(), interval.toSeconds(), TimeUnit.SECONDS);
        
        logger.debug("Thread pool monitoring started with interval: {}", interval);
    }
    
    /**
     * Starts automatic rebalancing of thread pools.
     */
    private void startRebalancing() {
        Duration interval = Duration.ofMinutes(
            Integer.parseInt(configurationManager.getPropertyWithDefault("threadpool.rebalancing.interval.minutes", "5")));
        
        rebalancingExecutor.scheduleAtFixedRate(() -> {
            try {
                if (shouldTriggerRebalancing()) {
                    rebalanceThreadPools();
                }
            } catch (Exception e) {
                logger.error("Error during thread pool rebalancing", e);
            }
        }, interval.toMinutes(), interval.toMinutes(), TimeUnit.MINUTES);
        
        logger.debug("Thread pool rebalancing started with interval: {}", interval);
    }
    
    /**
     * Stops monitoring executor.
     */
    private void stopMonitoring() {
        if (monitoringExecutor != null && !monitoringExecutor.isShutdown()) {
            monitoringExecutor.shutdown();
            try {
                if (!monitoringExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    monitoringExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                monitoringExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.debug("Thread pool monitoring stopped");
        }
    }
    
    /**
     * Stops rebalancing executor.
     */
    private void stopRebalancing() {
        if (rebalancingExecutor != null && !rebalancingExecutor.isShutdown()) {
            rebalancingExecutor.shutdown();
            try {
                if (!rebalancingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    rebalancingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                rebalancingExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.debug("Thread pool rebalancing stopped");
        }
    }
    
    /**
     * Updates metrics for all thread pools.
     */
    private void updateMetrics() {
        poolLock.readLock().lock();
        try {
            for (Map.Entry<ModulePool, ThreadPoolExecutor> entry : modulePools.entrySet()) {
                ModulePool module = entry.getKey();
                ThreadPoolExecutor executor = entry.getValue();
                ThreadPoolMetrics metrics = poolMetrics.get(module);
                
                if (metrics != null) {
                    metrics.updateFromExecutor(executor);
                }
            }
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Updates the total available threads count.
     */
    private void updateAvailableThreads() {
        int active = 0;
        int available = 0;
        
        poolLock.readLock().lock();
        try {
            for (ThreadPoolExecutor executor : modulePools.values()) {
                active += executor.getActiveCount();
                available += (executor.getMaximumPoolSize() - executor.getActiveCount());
            }
        } finally {
            poolLock.readLock().unlock();
        }
        
        totalActiveThreads.set(active);
        totalAvailableThreads.set(available);
    }
    
    /**
     * Logs performance metrics periodically.
     */
    private void logPerformanceMetrics() {
        if (logger.isDebugEnabled()) {
            String metrics = monitorThreadAllocations();
            logger.debug("Thread Pool Performance Metrics:\n{}", metrics);
        }
    }
    
    /**
     * Determines if rebalancing should be triggered based on current metrics.
     * 
     * @return true if rebalancing should be triggered
     */
    private boolean shouldTriggerRebalancing() {
        // Check if enough time has passed since last rebalancing
        Duration timeSinceLastRebalance = Duration.between(lastRebalanceTime, Instant.now());
        Duration minInterval = Duration.ofMinutes(2);
        
        if (timeSinceLastRebalance.compareTo(minInterval) < 0) {
            return false;
        }
        
        // Check utilization thresholds
        double utilization = getThreadPoolUtilization();
        double highThreshold = Double.parseDouble(
            configurationManager.getPropertyWithDefault("threadpool.rebalancing.high.threshold", "80.0"));
        double lowThreshold = Double.parseDouble(
            configurationManager.getPropertyWithDefault("threadpool.rebalancing.low.threshold", "20.0"));
        
        return utilization > highThreshold || utilization < lowThreshold;
    }
    
    /**
     * Calculates optimal pool size based on current metrics and system state.
     * 
     * @param module the target module
     * @param executor the current executor
     * @param metrics the current metrics
     * @param memoryUtilization current memory utilization
     * @return optimal pool size
     */
    private int calculateOptimalPoolSize(ModulePool module, ThreadPoolExecutor executor, 
                                       ThreadPoolMetrics metrics, double memoryUtilization) {
        int currentSize = executor.getCorePoolSize();
        double utilization = metrics.getPoolUtilization();
        double throughput = metrics.getThroughputPerSecond();
        
        // Base optimization on utilization and throughput
        int optimalSize = currentSize;
        
        if (utilization > 80.0 && throughput > 0) {
            // High utilization - consider increasing
            optimalSize = Math.min(currentSize + 1, executor.getMaximumPoolSize());
        } else if (utilization < 20.0 && currentSize > 1) {
            // Low utilization - consider decreasing
            optimalSize = Math.max(currentSize - 1, 1);
        }
        
        // Adjust for memory pressure
        if (memoryUtilization > 0.8) {
            optimalSize = Math.min(optimalSize, currentSize);  // Don't increase under memory pressure
        }
        
        // Adjust for queue size
        int queueSize = executor.getQueue().size();
        ThreadPoolConfiguration config = poolConfigurations.get(module);
        if (queueSize > config.getQueueCapacity() * 0.7) {
            optimalSize = Math.min(optimalSize + 1, executor.getMaximumPoolSize());
        }
        
        return optimalSize;
    }
    
    /**
     * Cleans up partially initialized pools in case of initialization failure.
     */
    private void cleanupPartialInitialization() {
        poolLock.writeLock().lock();
        try {
            for (ThreadPoolExecutor executor : modulePools.values()) {
                if (executor != null && !executor.isShutdown()) {
                    executor.shutdownNow();
                }
            }
            
            modulePools.clear();
            poolConfigurations.clear();
            poolMetrics.clear();
            poolStatuses.clear();
            
            stopMonitoring();
            stopRebalancing();
            
            logger.info("Partial initialization cleanup completed");
        } finally {
            poolLock.writeLock().unlock();
        }
    }
}

/**
 * Configuration class for thread pool settings.
 * Provides immutable configuration with builder pattern for easy construction.
 */
class ThreadPoolConfiguration {
    
    private final int corePoolSize;
    private final int maxPoolSize;
    private final Duration keepAliveTime;
    private final int queueCapacity;
    private final String threadNamePrefix;
    private final RejectedExecutionHandler rejectionPolicy;
    private final boolean allowCoreThreadTimeOut;
    private final double rebalancingThreshold;
    private final Duration monitoringInterval;
    private final Duration shutdownTimeout;
    
    private ThreadPoolConfiguration(Builder builder) {
        this.corePoolSize = builder.corePoolSize;
        this.maxPoolSize = builder.maxPoolSize;
        this.keepAliveTime = builder.keepAliveTime;
        this.queueCapacity = builder.queueCapacity;
        this.threadNamePrefix = builder.threadNamePrefix;
        this.rejectionPolicy = builder.rejectionPolicy;
        this.allowCoreThreadTimeOut = builder.allowCoreThreadTimeOut;
        this.rebalancingThreshold = builder.rebalancingThreshold;
        this.monitoringInterval = builder.monitoringInterval;
        this.shutdownTimeout = builder.shutdownTimeout;
    }
    
    public int getCorePoolSize() { return corePoolSize; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public Duration getKeepAliveTime() { return keepAliveTime; }
    public int getQueueCapacity() { return queueCapacity; }
    public String getThreadNamePrefix() { return threadNamePrefix; }
    public RejectedExecutionHandler getRejectionPolicy() { return rejectionPolicy; }
    public boolean isAllowCoreThreadTimeOut() { return allowCoreThreadTimeOut; }
    public double getRebalancingThreshold() { return rebalancingThreshold; }
    public Duration getMonitoringInterval() { return monitoringInterval; }
    public Duration getShutdownTimeout() { return shutdownTimeout; }
    
    /**
     * Builder for ThreadPoolConfiguration.
     */
    public static class Builder {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private Duration keepAliveTime = Duration.ofSeconds(60);
        private int queueCapacity = 100;
        private String threadNamePrefix = "pool-";
        private RejectedExecutionHandler rejectionPolicy;
        private boolean allowCoreThreadTimeOut = true;
        private double rebalancingThreshold = 0.8;
        private Duration monitoringInterval = Duration.ofSeconds(30);
        private Duration shutdownTimeout = Duration.ofSeconds(30);
        
        public Builder() {}
        
        public Builder(ThreadPoolConfiguration config) {
            this.corePoolSize = config.corePoolSize;
            this.maxPoolSize = config.maxPoolSize;
            this.keepAliveTime = config.keepAliveTime;
            this.queueCapacity = config.queueCapacity;
            this.threadNamePrefix = config.threadNamePrefix;
            this.rejectionPolicy = config.rejectionPolicy;
            this.allowCoreThreadTimeOut = config.allowCoreThreadTimeOut;
            this.rebalancingThreshold = config.rebalancingThreshold;
            this.monitoringInterval = config.monitoringInterval;
            this.shutdownTimeout = config.shutdownTimeout;
        }
        
        public Builder corePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
            return this;
        }
        
        public Builder maxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
            return this;
        }
        
        public Builder keepAliveTime(Duration keepAliveTime) {
            this.keepAliveTime = keepAliveTime;
            return this;
        }
        
        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }
        
        public Builder threadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
            return this;
        }
        
        public Builder rejectionPolicy(RejectedExecutionHandler rejectionPolicy) {
            this.rejectionPolicy = rejectionPolicy;
            return this;
        }
        
        public Builder allowCoreThreadTimeOut(boolean allowCoreThreadTimeOut) {
            this.allowCoreThreadTimeOut = allowCoreThreadTimeOut;
            return this;
        }
        
        public Builder rebalancingThreshold(double rebalancingThreshold) {
            this.rebalancingThreshold = rebalancingThreshold;
            return this;
        }
        
        public Builder monitoringInterval(Duration monitoringInterval) {
            this.monitoringInterval = monitoringInterval;
            return this;
        }
        
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }
        
        public ThreadPoolConfiguration build() {
            return new ThreadPoolConfiguration(this);
        }
    }
}

/**
 * Metrics tracking class for thread pool performance monitoring.
 * Provides comprehensive metrics collection and analysis capabilities.
 */
class ThreadPoolMetrics {
    
    private final ModulePool module;
    private final AtomicInteger taskRejectionCount;
    private final AtomicInteger threadLeakCount;
    private volatile long lastUpdateTime;
    private volatile long lastCompletedTaskCount;
    private volatile double averageTaskDuration;
    private volatile double throughputPerSecond;
    
    // Current snapshot values
    private volatile int activeThreadCount;
    private volatile long completedTaskCount;
    private volatile int queueSize;
    private volatile int largestPoolSize;
    private volatile double poolUtilization;
    private volatile Instant lastRebalanceTime;
    private volatile Instant timestamp;
    
    public ThreadPoolMetrics(ModulePool module) {
        this.module = module;
        this.taskRejectionCount = new AtomicInteger(0);
        this.threadLeakCount = new AtomicInteger(0);
        this.lastUpdateTime = System.currentTimeMillis();
        this.lastCompletedTaskCount = 0;
        this.averageTaskDuration = 0.0;
        this.throughputPerSecond = 0.0;
        this.lastRebalanceTime = Instant.now();
        this.timestamp = Instant.now();
    }
    
    /**
     * Updates metrics from ThreadPoolExecutor state.
     * 
     * @param executor the thread pool executor
     */
    public synchronized void updateFromExecutor(ThreadPoolExecutor executor) {
        long currentTime = System.currentTimeMillis();
        long currentCompletedTasks = executor.getCompletedTaskCount();
        
        // Update basic metrics
        this.activeThreadCount = executor.getActiveCount();
        this.completedTaskCount = currentCompletedTasks;
        this.queueSize = executor.getQueue().size();
        this.largestPoolSize = executor.getLargestPoolSize();
        this.timestamp = Instant.now();
        
        // Calculate utilization
        int maxPoolSize = executor.getMaximumPoolSize();
        this.poolUtilization = maxPoolSize > 0 ? (activeThreadCount * 100.0 / maxPoolSize) : 0.0;
        
        // Calculate throughput
        long timeDelta = currentTime - lastUpdateTime;
        long taskDelta = currentCompletedTasks - lastCompletedTaskCount;
        
        if (timeDelta > 0) {
            this.throughputPerSecond = (taskDelta * 1000.0) / timeDelta;
        }
        
        // Update tracking variables
        this.lastUpdateTime = currentTime;
        this.lastCompletedTaskCount = currentCompletedTasks;
    }
    
    public int getActiveThreadCount() { return activeThreadCount; }
    public long getCompletedTaskCount() { return completedTaskCount; }
    public int getQueueSize() { return queueSize; }
    public int getLargestPoolSize() { return largestPoolSize; }
    public double getPoolUtilization() { return poolUtilization; }
    
    public int getTaskRejectionCount() { 
        return taskRejectionCount.get(); 
    }
    
    public void incrementTaskRejectionCount() {
        taskRejectionCount.incrementAndGet();
    }
    
    public double getAverageTaskDuration() { return averageTaskDuration; }
    public double getThroughputPerSecond() { return throughputPerSecond; }
    
    public int getThreadLeakCount() { 
        return threadLeakCount.get(); 
    }
    
    public void incrementThreadLeakCount() {
        threadLeakCount.incrementAndGet();
    }
    
    public Instant getLastRebalanceTime() { return lastRebalanceTime; }
    
    public void setLastRebalanceTime(Instant lastRebalanceTime) {
        this.lastRebalanceTime = lastRebalanceTime;
    }
    
    public Instant getTimestamp() { return timestamp; }
}

/**
 * Registry for tracking and cleaning up ThreadLocal variables.
 * Prevents memory leaks by providing centralized ThreadLocal management.
 */
class ThreadLocalRegistry {
    
    private static final Logger logger = LoggerFactory.getLogger(ThreadLocalRegistry.class);
    
    private final Map<ThreadLocal<?>, String> registeredThreadLocals;
    private final Map<ThreadLocal<?>, Instant> registrationTimes;
    private final AtomicInteger cleanupCount;
    private final AtomicInteger leakCount;
    
    public ThreadLocalRegistry() {
        this.registeredThreadLocals = new ConcurrentHashMap<>();
        this.registrationTimes = new ConcurrentHashMap<>();
        this.cleanupCount = new AtomicInteger(0);
        this.leakCount = new AtomicInteger(0);
    }
    
    /**
     * Registers a ThreadLocal for cleanup tracking.
     * 
     * @param threadLocal the ThreadLocal to register
     * @param name descriptive name for the ThreadLocal
     */
    public void registerThreadLocal(ThreadLocal<?> threadLocal, String name) {
        if (threadLocal == null) {
            throw new IllegalArgumentException("ThreadLocal cannot be null");
        }
        
        registeredThreadLocals.put(threadLocal, name != null ? name : "unnamed");
        registrationTimes.put(threadLocal, Instant.now());
        
        logger.debug("Registered ThreadLocal: {}", name);
    }
    
    /**
     * Unregisters a ThreadLocal from cleanup tracking.
     * 
     * @param threadLocal the ThreadLocal to unregister
     */
    public void unregisterThreadLocal(ThreadLocal<?> threadLocal) {
        if (threadLocal == null) {
            return;
        }
        
        String name = registeredThreadLocals.remove(threadLocal);
        registrationTimes.remove(threadLocal);
        
        if (name != null) {
            logger.debug("Unregistered ThreadLocal: {}", name);
        }
    }
    
    /**
     * Cleans up all registered ThreadLocal variables using remove() method.
     * Prevents memory leaks in thread pools by clearing thread-local storage.
     */
    public void cleanupAllThreadLocals() {
        int cleaned = 0;
        
        for (Map.Entry<ThreadLocal<?>, String> entry : registeredThreadLocals.entrySet()) {
            ThreadLocal<?> threadLocal = entry.getKey();
            String name = entry.getValue();
            
            try {
                threadLocal.remove();
                cleaned++;
                logger.trace("Cleaned ThreadLocal: {}", name);
            } catch (Exception e) {
                logger.warn("Failed to clean ThreadLocal: {}", name, e);
                leakCount.incrementAndGet();
            }
        }
        
        if (cleaned > 0) {
            cleanupCount.addAndGet(cleaned);
            logger.debug("Cleaned {} ThreadLocal variables", cleaned);
        }
    }
    
    /**
     * Cleans up a specific ThreadLocal variable.
     * 
     * @param threadLocal the ThreadLocal to clean up
     */
    public void cleanupThreadLocal(ThreadLocal<?> threadLocal) {
        if (threadLocal == null) {
            return;
        }
        
        String name = registeredThreadLocals.get(threadLocal);
        try {
            threadLocal.remove();
            cleanupCount.incrementAndGet();
            logger.trace("Cleaned specific ThreadLocal: {}", name != null ? name : "unknown");
        } catch (Exception e) {
            logger.warn("Failed to clean specific ThreadLocal: {}", name, e);
            leakCount.incrementAndGet();
        }
    }
    
    /**
     * Returns the set of currently registered ThreadLocal variables.
     * 
     * @return unmodifiable set of registered ThreadLocals
     */
    public Set<ThreadLocal<?>> getRegisteredThreadLocals() {
        return Collections.unmodifiableSet(registeredThreadLocals.keySet());
    }
    
    /**
     * Detects potential ThreadLocal leaks based on registration time.
     * 
     * @return number of potential leaks detected
     */
    public int getThreadLocalLeaks() {
        int leaks = 0;
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        
        for (Map.Entry<ThreadLocal<?>, Instant> entry : registrationTimes.entrySet()) {
            if (entry.getValue().isBefore(cutoff)) {
                leaks++;
                logger.warn("Potential ThreadLocal leak detected: {} (registered at {})", 
                           registeredThreadLocals.get(entry.getKey()), entry.getValue());
            }
        }
        
        return leaks + leakCount.get();
    }
    
    /**
     * Returns cleanup metrics as a formatted string.
     * 
     * @return cleanup metrics
     */
    public String getCleanupMetrics() {
        return String.format("ThreadLocal Cleanup Metrics: Registered=%d, Cleaned=%d, Leaks=%d",
                           registeredThreadLocals.size(),
                           cleanupCount.get(),
                           getThreadLocalLeaks());
    }
}

/**
 * Enumeration of module pools for isolated thread pool management.
 * Each module has its own dedicated thread pool to prevent cascading failures.
 */
enum ModulePool {
    /**
     * Web automation module pool for browser-based testing
     */
    WEB,
    
    /**
     * API automation module pool for RESTful API testing
     */
    API,
    
    /**
     * Reporting module pool for test result processing and report generation
     */
    REPORTING,
    
    /**
     * Monitoring module pool for framework health and performance monitoring
     */
    MONITORING,
    
    /**
     * Core services module pool for framework orchestration and coordination
     */
    CORE
}

/**
 * Enumeration of thread pool status states for lifecycle tracking.
 * Provides clear visibility into the operational state of each thread pool.
 */
enum ThreadPoolStatus {
    /**
     * Thread pool is being initialized
     */
    INITIALIZING,
    
    /**
     * Thread pool is running normally
     */
    RUNNING,
    
    /**
     * Thread pool is being rebalanced (temporary state)
     */
    REBALANCING,
    
    /**
     * Thread pool is shutting down gracefully
     */
    SHUTTING_DOWN,
    
    /**
     * Thread pool has terminated successfully
     */
    TERMINATED,
    
    /**
     * Thread pool encountered an error
     */
    ERROR
}