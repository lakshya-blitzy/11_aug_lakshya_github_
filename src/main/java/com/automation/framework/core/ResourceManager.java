package com.automation.framework.core;

// External imports for concurrent operations and time management
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

// Internal imports from framework dependencies
import com.automation.framework.resources.ConnectionPoolManager;
import com.automation.framework.web.WebDriverPool;
import com.automation.framework.core.ConfigurationManager;
import com.automation.framework.core.ShutdownHandler;

// Standard Java imports for collections and utilities
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * ResourceManager provides comprehensive lifecycle management for all automation framework resources.
 * 
 * This class serves as the central orchestration hub for managing WebDriver sessions, HTTP connections,
 * thread pools, file handles, and memory resources. It implements automatic resource management patterns
 * with leak detection, connection pooling, and memory monitoring as specified in the technical requirements.
 * 
 * Key Features:
 * - WebDriver session pooling (up to 10 concurrent sessions, 50MB memory limit per session)
 * - HTTP connection pooling (up to 50 concurrent requests, 5-second leak detection threshold)
 * - ThreadLocal cleanup patterns to prevent memory leaks
 * - Try-with-resources patterns for file operations
 * - Real-time metrics and automatic throttling
 * - Graceful shutdown coordination
 * 
 * The ResourceManager follows the Singleton pattern to ensure consistent resource management
 * across the entire automation framework and integrates with all major framework components
 * including ConnectionPoolManager, WebDriverPool, ConfigurationManager, and ShutdownHandler.
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class ResourceManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ResourceManager.class);
    
    // Singleton instance management with thread-safe initialization
    private static volatile ResourceManager instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Dependency components for resource management coordination
    private final ConnectionPoolManager connectionPoolManager;
    private final WebDriverPool webDriverPool;
    private final ConfigurationManager configurationManager;
    private final ShutdownHandler shutdownHandler;
    
    // Resource state management with thread-safe atomic operations
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private final AtomicBoolean throttlingEnabled = new AtomicBoolean(false);
    
    // Resource registry and tracking with concurrent collections
    private final ConcurrentHashMap<String, Object> resourceRegistry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceType, Integer> activeResourceCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> resourceLastAccess = new ConcurrentHashMap<>();
    
    // Monitoring and metrics collection
    private ScheduledExecutorService monitoringExecutor;
    private final ResourceMetrics metrics = new ResourceMetrics();
    private final ResourceHealth health = new ResourceHealth();
    private ResourceConfiguration configuration = new ResourceConfiguration();
    
    // Thread-safe access control for configuration updates
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();
    
    /**
     * Private constructor for singleton pattern.
     * Initializes all dependency components and sets up default configuration.
     */
    private ResourceManager() {
        // Initialize dependency components
        this.connectionPoolManager = new ConnectionPoolManager();
        this.webDriverPool = new WebDriverPool();
        this.configurationManager = ConfigurationManager.getInstance();
        this.shutdownHandler = ShutdownHandler.getInstance();
        
        // Initialize resource type counters
        for (ResourceType type : ResourceType.values()) {
            activeResourceCounts.put(type, 0);
        }
        
        logger.info("ResourceManager initialized with default configuration");
    }
    
    /**
     * Gets the singleton instance of ResourceManager.
     * Thread-safe lazy initialization with double-checked locking pattern.
     * 
     * @return ResourceManager singleton instance
     */
    public static ResourceManager getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new ResourceManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Initializes all framework resources and starts monitoring systems.
     * This method sets up connection pools, WebDriver sessions, and monitoring infrastructure
     * according to the configuration parameters.
     * 
     * @return boolean indicating successful initialization
     */
    public boolean initializeResources() {
        if (initialized.compareAndSet(false, true)) {
            logger.info("Initializing all framework resources");
            
            try {
                // Load configuration settings
                loadResourceConfiguration();
                
                // Initialize connection pool with configured limits
                if (!initializeConnectionPool()) {
                    logger.error("Failed to initialize connection pool");
                    initialized.set(false);
                    return false;
                }
                
                // Initialize WebDriver pool with session limits
                if (!initializeWebDriverPool()) {
                    logger.error("Failed to initialize WebDriver pool");
                    initialized.set(false);
                    return false;
                }
                
                // Register shutdown hooks for graceful cleanup
                registerShutdownHooks();
                
                // Start resource monitoring if configured
                if (configuration.isAutoCleanupEnabled()) {
                    startResourceMonitoring();
                }
                
                logger.info("Resource initialization completed successfully");
                return true;
                
            } catch (Exception e) {
                logger.error("Exception during resource initialization", e);
                initialized.set(false);
                return false;
            }
        }
        
        logger.debug("Resources already initialized");
        return true;
    }
    
    /**
     * Shuts down all framework resources in ordered sequence.
     * Coordinates with ShutdownHandler to ensure proper cleanup timing and resource release.
     * 
     * @return boolean indicating successful shutdown completion
     */
    public boolean shutdownResources() {
        if (shutdownInProgress.compareAndSet(false, true)) {
            logger.info("Initiating resource shutdown sequence");
            
            try {
                // Stop resource monitoring first
                stopResourceMonitoring();
                
                // Initiate graceful shutdown through ShutdownHandler
                boolean gracefulShutdown = shutdownHandler.initiateGracefulShutdown().get(
                    configuration.getCleanupTimeout(), TimeUnit.MILLISECONDS);
                
                if (!gracefulShutdown) {
                    logger.warn("Graceful shutdown failed, executing emergency shutdown");
                    shutdownHandler.executeEmergencyShutdown();
                }
                
                // Force cleanup of any remaining resources
                forceResourceCleanup();
                
                // Clear resource registry
                resourceRegistry.clear();
                activeResourceCounts.replaceAll((type, count) -> 0);
                resourceLastAccess.clear();
                
                initialized.set(false);
                logger.info("Resource shutdown completed successfully");
                return true;
                
            } catch (Exception e) {
                logger.error("Exception during resource shutdown", e);
                return false;
            }
        }
        
        logger.debug("Shutdown already in progress");
        return false;
    }
    
    /**
     * Gets comprehensive health status for all managed resources.
     * Aggregates health information from connection pools, WebDriver pools, and system resources.
     * 
     * @return ResourceHealth object containing detailed health information
     */
    public ResourceHealth getResourceHealth() {
        updateHealthStatus();
        return health;
    }
    
    /**
     * Gets current performance metrics for all managed resources.
     * Provides real-time statistics on resource utilization, throughput, and leak detection.
     * 
     * @return ResourceMetrics object containing current performance data
     */
    public ResourceMetrics getResourceMetrics() {
        updateMetrics();
        return metrics;
    }
    
    /**
     * Gets the current status of the HTTP connection pool.
     * Provides detailed information about pool utilization, active connections, and health.
     * 
     * @return Map containing connection pool status information
     */
    public Map<String, Object> getConnectionPoolStatus() {
        Map<String, Object> status = new HashMap<>();
        
        try {
            status.put("poolUtilization", connectionPoolManager.getPoolUtilization());
            status.put("activeConnections", connectionPoolManager.getActiveConnections());
            status.put("connectionLeaks", connectionPoolManager.getConnectionLeaks());
            status.put("isHealthy", connectionPoolManager.isPoolHealthy());
            status.put("maxConnections", connectionPoolManager.getMaxConnections());
            status.put("connectionTimeout", connectionPoolManager.getConnectionTimeout());
            status.put("timestamp", Instant.now());
            
        } catch (Exception e) {
            logger.error("Error retrieving connection pool status", e);
            status.put("error", e.getMessage());
            status.put("isHealthy", false);
        }
        
        return status;
    }
    
    /**
     * Gets the current status of the WebDriver pool.
     * Provides detailed information about browser sessions, pool utilization, and health.
     * 
     * @return Map containing WebDriver pool status information
     */
    public Map<String, Object> getWebDriverPoolStatus() {
        Map<String, Object> status = new HashMap<>();
        
        try {
            status.put("poolUtilization", webDriverPool.getPoolUtilization());
            status.put("activeBrowserSessions", webDriverPool.getActiveBrowserSessions());
            status.put("isHealthy", webDriverPool.isPoolHealthy());
            status.put("sessionLeaks", webDriverPool.getSessionLeaks());
            status.put("browserPoolMetrics", webDriverPool.getBrowserPoolMetrics());
            status.put("timestamp", Instant.now());
            
        } catch (Exception e) {
            logger.error("Error retrieving WebDriver pool status", e);
            status.put("error", e.getMessage());
            status.put("isHealthy", false);
        }
        
        return status;
    }
    
    /**
     * Starts resource monitoring with configurable intervals.
     * Initiates periodic health checks, leak detection, and performance monitoring.
     * 
     * @return boolean indicating successful monitoring startup
     */
    public boolean startResourceMonitoring() {
        if (monitoring.compareAndSet(false, true)) {
            logger.info("Starting resource monitoring");
            
            try {
                Duration monitoringInterval = configuration.getMonitoringInterval();
                
                monitoringExecutor = Executors.newScheduledThreadPool(2, r -> {
                    Thread thread = new Thread(r, "ResourceManager-Monitor");
                    thread.setDaemon(true);
                    return thread;
                });
                
                // Schedule periodic health checks
                monitoringExecutor.scheduleAtFixedRate(
                    this::performHealthCheck,
                    0,
                    configuration.getHealthCheckInterval().toMillis(),
                    TimeUnit.MILLISECONDS
                );
                
                // Schedule periodic leak detection
                monitoringExecutor.scheduleAtFixedRate(
                    this::detectResourceLeaks,
                    monitoringInterval.toMillis(),
                    monitoringInterval.toMillis(),
                    TimeUnit.MILLISECONDS
                );
                
                logger.info("Resource monitoring started with interval: {}", monitoringInterval);
                return true;
                
            } catch (Exception e) {
                logger.error("Failed to start resource monitoring", e);
                monitoring.set(false);
                return false;
            }
        }
        
        logger.debug("Resource monitoring already active");
        return true;
    }
    
    /**
     * Stops resource monitoring and cleanup background tasks.
     * Gracefully shuts down monitoring executor and saves final metrics.
     * 
     * @return boolean indicating successful monitoring shutdown
     */
    public boolean stopResourceMonitoring() {
        if (monitoring.compareAndSet(true, false)) {
            logger.info("Stopping resource monitoring");
            
            try {
                if (monitoringExecutor != null) {
                    monitoringExecutor.shutdown();
                    
                    if (!monitoringExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warn("Monitoring executor did not terminate gracefully, forcing shutdown");
                        monitoringExecutor.shutdownNow();
                    }
                    
                    monitoringExecutor = null;
                }
                
                logger.info("Resource monitoring stopped successfully");
                return true;
                
            } catch (Exception e) {
                logger.error("Error during monitoring shutdown", e);
                return false;
            }
        }
        
        logger.debug("Resource monitoring already stopped");
        return true;
    }
    
    /**
     * Forces immediate cleanup of all resources without graceful shutdown.
     * Used during emergency situations or when normal cleanup procedures fail.
     * 
     * @return boolean indicating successful force cleanup completion
     */
    public boolean forceResourceCleanup() {
        logger.warn("Executing force resource cleanup");
        
        try {
            // Force shutdown connection pool
            connectionPoolManager.shutdownConnectionPool();
            
            // Force shutdown WebDriver pool
            webDriverPool.shutdownPool();
            
            // Force cleanup orphaned sessions
            webDriverPool.cleanupOrphanedSessions();
            
            // Clear all resource registrations
            resourceRegistry.clear();
            activeResourceCounts.replaceAll((type, count) -> 0);
            resourceLastAccess.clear();
            
            // Stop monitoring if still active
            if (monitoring.get()) {
                stopResourceMonitoring();
            }
            
            logger.info("Force resource cleanup completed");
            return true;
            
        } catch (Exception e) {
            logger.error("Error during force resource cleanup", e);
            return false;
        }
    }
    
    /**
     * Gets the current resource registry containing all tracked resources.
     * Provides access to the central resource tracking system.
     * 
     * @return Unmodifiable map of the current resource registry
     */
    public Map<String, Object> getResourceRegistry() {
        return Collections.unmodifiableMap(resourceRegistry);
    }
    
    /**
     * Registers a resource for tracking and lifecycle management.
     * Adds the resource to the central registry with appropriate metadata.
     * 
     * @param resourceId Unique identifier for the resource
     * @param resource The resource object to register
     * @param type The type of resource being registered
     * @return boolean indicating successful registration
     */
    public boolean registerResource(String resourceId, Object resource, ResourceType type) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            logger.warn("Cannot register resource with null or empty ID");
            return false;
        }
        
        if (resource == null) {
            logger.warn("Cannot register null resource with ID: {}", resourceId);
            return false;
        }
        
        try {
            resourceRegistry.put(resourceId, resource);
            resourceLastAccess.put(resourceId, Instant.now());
            activeResourceCounts.compute(type, (key, count) -> count == null ? 1 : count + 1);
            
            logger.debug("Registered resource: {} of type: {}", resourceId, type);
            return true;
            
        } catch (Exception e) {
            logger.error("Error registering resource: {}", resourceId, e);
            return false;
        }
    }
    
    /**
     * Unregisters a resource from tracking and lifecycle management.
     * Removes the resource from the central registry and updates counters.
     * 
     * @param resourceId Unique identifier of the resource to unregister
     * @param type The type of resource being unregistered
     * @return boolean indicating successful unregistration
     */
    public boolean unregisterResource(String resourceId, ResourceType type) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            logger.warn("Cannot unregister resource with null or empty ID");
            return false;
        }
        
        try {
            Object removed = resourceRegistry.remove(resourceId);
            if (removed != null) {
                resourceLastAccess.remove(resourceId);
                activeResourceCounts.compute(type, (key, count) -> count == null || count <= 1 ? 0 : count - 1);
                
                logger.debug("Unregistered resource: {} of type: {}", resourceId, type);
                return true;
            } else {
                logger.debug("Resource not found for unregistration: {}", resourceId);
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Error unregistering resource: {}", resourceId, e);
            return false;
        }
    }
    
    /**
     * Checks if all managed resources are in a healthy state.
     * Performs comprehensive health validation across all resource types.
     * 
     * @return boolean indicating overall resource health status
     */
    public boolean isResourceHealthy() {
        try {
            updateHealthStatus();
            return health.isHealthy();
            
        } catch (Exception e) {
            logger.error("Error checking resource health", e);
            return false;
        }
    }
    
    /**
     * Gets the count of currently active resources by type.
     * Provides real-time counts of managed resources across all categories.
     * 
     * @return Map containing active resource counts by type
     */
    public Map<ResourceType, Integer> getActiveResources() {
        return Collections.unmodifiableMap(activeResourceCounts);
    }
    
    /**
     * Releases all managed resources in coordinated sequence.
     * Delegates to ShutdownHandler for proper resource release coordination.
     * 
     * @return boolean indicating successful resource release
     */
    public boolean releaseAllResources() {
        logger.info("Releasing all managed resources");
        
        try {
            return shutdownHandler.releaseAllResources();
            
        } catch (Exception e) {
            logger.error("Error releasing all resources", e);
            return false;
        }
    }
    
    /**
     * Configures resource pools with specified parameters.
     * Updates connection pool and WebDriver pool configurations based on provided settings.
     * 
     * @param poolConfig Map containing pool configuration parameters
     * @return boolean indicating successful configuration
     */
    public boolean configureResourcePools(Map<String, Object> poolConfig) {
        if (poolConfig == null || poolConfig.isEmpty()) {
            logger.warn("Cannot configure pools with null or empty configuration");
            return false;
        }
        
        configLock.writeLock().lock();
        try {
            logger.info("Configuring resource pools with {} parameters", poolConfig.size());
            
            // Update configuration from provided parameters
            poolConfig.forEach((key, value) -> {
                try {
                    configurationManager.setProperty(key, value.toString());
                } catch (Exception e) {
                    logger.warn("Failed to set configuration property: {} = {}", key, value, e);
                }
            });
            
            // Reload configuration to apply changes
            loadResourceConfiguration();
            
            logger.info("Resource pool configuration updated successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error configuring resource pools", e);
            return false;
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Gets current pool utilization across all managed pools.
     * Aggregates utilization data from connection pools and WebDriver pools.
     * 
     * @return Map containing pool utilization percentages
     */
    public Map<String, Double> getPoolUtilization() {
        Map<String, Double> utilization = new HashMap<>();
        
        try {
            utilization.put("connectionPool", connectionPoolManager.getPoolUtilization());
            utilization.put("webDriverPool", webDriverPool.getPoolUtilization());
            utilization.put("timestamp", (double) System.currentTimeMillis());
            
        } catch (Exception e) {
            logger.error("Error retrieving pool utilization", e);
            utilization.put("error", -1.0);
        }
        
        return utilization;
    }
    
    /**
     * Gets current resource leak information across all pools.
     * Identifies and reports potential resource leaks for monitoring and alerting.
     * 
     * @return Map containing leak detection results
     */
    public Map<String, Object> getResourceLeaks() {
        Map<String, Object> leaks = new HashMap<>();
        
        try {
            leaks.put("connectionLeaks", connectionPoolManager.getConnectionLeaks());
            leaks.put("sessionLeaks", webDriverPool.getSessionLeaks());
            leaks.put("detectionTimestamp", Instant.now());
            leaks.put("leakCount", getResourceLeakCount());
            
        } catch (Exception e) {
            logger.error("Error retrieving resource leaks", e);
            leaks.put("error", e.getMessage());
        }
        
        return leaks;
    }
    
    /**
     * Triggers manual resource maintenance procedures.
     * Executes cleanup, optimization, and health recovery operations.
     * 
     * @return boolean indicating successful maintenance completion
     */
    public boolean triggerResourceMaintenance() {
        logger.info("Triggering manual resource maintenance");
        
        try {
            // Detect and handle connection leaks
            connectionPoolManager.detectConnectionLeaks();
            
            // Cleanup orphaned WebDriver sessions
            webDriverPool.cleanupOrphanedSessions();
            
            // Update health and metrics
            updateHealthStatus();
            updateMetrics();
            
            // Perform garbage collection hint for memory management
            System.gc();
            
            logger.info("Resource maintenance completed successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error during resource maintenance", e);
            return false;
        }
    }
    
    /**
     * Checks if shutdown is currently in progress.
     * Provides status information for coordinating resource operations.
     * 
     * @return boolean indicating if shutdown is in progress
     */
    public boolean isShutdownInProgress() {
        return shutdownInProgress.get() || shutdownHandler.isShutdownInProgress();
    }
    
    /**
     * Gets the resource coordinator for advanced resource management.
     * Provides access to the underlying resource coordination mechanisms.
     * 
     * @return ResourceManager instance acting as coordinator
     */
    public ResourceManager getResourceCoordinator() {
        return this;
    }
    
    /**
     * Enables resource throttling to prevent resource exhaustion.
     * Activates throttling mechanisms when resource limits are approached.
     * 
     * @return boolean indicating successful throttling activation
     */
    public boolean enableResourceThrottling() {
        if (throttlingEnabled.compareAndSet(false, true)) {
            logger.info("Resource throttling enabled");
            configuration.setThrottlingEnabled(true);
            return true;
        }
        
        logger.debug("Resource throttling already enabled");
        return true;
    }
    
    /**
     * Disables resource throttling to allow full resource utilization.
     * Deactivates throttling mechanisms when resource pressure decreases.
     * 
     * @return boolean indicating successful throttling deactivation
     */
    public boolean disableResourceThrottling() {
        if (throttlingEnabled.compareAndSet(true, false)) {
            logger.info("Resource throttling disabled");
            configuration.setThrottlingEnabled(false);
            return true;
        }
        
        logger.debug("Resource throttling already disabled");
        return true;
    }
    
    /**
     * Gets the current throttling status and configuration.
     * Provides information about resource throttling state and parameters.
     * 
     * @return Map containing throttling status information
     */
    public Map<String, Object> getThrottlingStatus() {
        Map<String, Object> status = new HashMap<>();
        
        status.put("throttlingEnabled", throttlingEnabled.get());
        status.put("configurationThrottling", configuration.isThrottlingEnabled());
        status.put("connectionPoolUtilization", connectionPoolManager.getPoolUtilization());
        status.put("webDriverPoolUtilization", webDriverPool.getPoolUtilization());
        status.put("resourceLimits", configuration.getResourceLimits());
        status.put("timestamp", Instant.now());
        
        return status;
    }
    
    /**
     * Optimizes resource usage across all managed pools.
     * Implements resource optimization strategies to improve performance and efficiency.
     * 
     * @return boolean indicating successful optimization
     */
    public boolean optimizeResourceUsage() {
        logger.info("Optimizing resource usage across all pools");
        
        try {
            // Optimize connection pool settings
            double connectionUtilization = connectionPoolManager.getPoolUtilization();
            if (connectionUtilization > 0.8) {
                logger.info("High connection pool utilization detected: {}%, triggering optimization", 
                          connectionUtilization * 100);
                // Trigger connection pool optimization
                connectionPoolManager.detectConnectionLeaks();
            }
            
            // Optimize WebDriver pool settings
            double webDriverUtilization = webDriverPool.getPoolUtilization();
            if (webDriverUtilization > 0.8) {
                logger.info("High WebDriver pool utilization detected: {}%, triggering optimization", 
                          webDriverUtilization * 100);
                // Cleanup orphaned sessions
                webDriverPool.cleanupOrphanedSessions();
            }
            
            // Enable throttling if resources are under pressure
            if (connectionUtilization > 0.9 || webDriverUtilization > 0.9) {
                enableResourceThrottling();
            } else if (connectionUtilization < 0.5 && webDriverUtilization < 0.5) {
                disableResourceThrottling();
            }
            
            logger.info("Resource optimization completed successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error during resource optimization", e);
            return false;
        }
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Loads resource configuration from ConfigurationManager.
     */
    private void loadResourceConfiguration() {
        try {
            configLock.writeLock().lock();
            
            // Load monitoring intervals
            String monitoringInterval = configurationManager.getPropertyWithDefault(
                "resource.monitoring.interval", "30");
            configuration.setMonitoringInterval(Duration.ofSeconds(Long.parseLong(monitoringInterval)));
            
            // Load cleanup timeout
            String cleanupTimeout = configurationManager.getPropertyWithDefault(
                "resource.cleanup.timeout", "30000");
            configuration.setCleanupTimeout(Duration.ofMillis(Long.parseLong(cleanupTimeout)));
            
            // Load health check interval
            String healthCheckInterval = configurationManager.getPropertyWithDefault(
                "resource.healthcheck.interval", "60");
            configuration.setHealthCheckInterval(Duration.ofSeconds(Long.parseLong(healthCheckInterval)));
            
            // Load throttling setting
            String throttlingEnabled = configurationManager.getPropertyWithDefault(
                "resource.throttling.enabled", "false");
            configuration.setThrottlingEnabled(Boolean.parseBoolean(throttlingEnabled));
            
            // Load auto cleanup setting
            String autoCleanup = configurationManager.getPropertyWithDefault(
                "resource.autocleanup.enabled", "true");
            configuration.setAutoCleanupEnabled(Boolean.parseBoolean(autoCleanup));
            
            logger.debug("Resource configuration loaded successfully");
            
        } catch (Exception e) {
            logger.error("Error loading resource configuration", e);
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Initializes the HTTP connection pool with configured parameters.
     */
    private boolean initializeConnectionPool() {
        try {
            logger.debug("Initializing HTTP connection pool");
            
            // Connection pool is automatically initialized via constructor
            // Verify it's healthy and ready
            if (connectionPoolManager.isPoolHealthy()) {
                registerResource("connectionPool", connectionPoolManager, ResourceType.CONNECTION_POOL);
                logger.info("HTTP connection pool initialized successfully");
                return true;
            } else {
                logger.error("HTTP connection pool failed health check");
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Error initializing connection pool", e);
            return false;
        }
    }
    
    /**
     * Initializes the WebDriver pool with configured parameters.
     */
    private boolean initializeWebDriverPool() {
        try {
            logger.debug("Initializing WebDriver pool");
            
            // WebDriver pool is automatically initialized via constructor
            // Verify it's healthy and ready
            if (webDriverPool.isPoolHealthy()) {
                registerResource("webDriverPool", webDriverPool, ResourceType.WEBDRIVER_POOL);
                logger.info("WebDriver pool initialized successfully");
                return true;
            } else {
                logger.error("WebDriver pool failed health check");
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Error initializing WebDriver pool", e);
            return false;
        }
    }
    
    /**
     * Registers shutdown hooks with the ShutdownHandler.
     */
    private void registerShutdownHooks() {
        try {
            // Register resource cleanup callback with shutdown handler
            shutdownHandler.registerShutdownHook();
            
            logger.debug("Shutdown hooks registered successfully");
            
        } catch (Exception e) {
            logger.error("Error registering shutdown hooks", e);
        }
    }
    
    /**
     * Updates the current health status for all resources.
     */
    private void updateHealthStatus() {
        try {
            health.updateConnectionPoolHealth(connectionPoolManager.isPoolHealthy());
            health.updateWebDriverPoolHealth(webDriverPool.isPoolHealthy());
            health.updateResourceLeakCount(getResourceLeakCount());
            health.updateHealthTimestamp(Instant.now());
            
        } catch (Exception e) {
            logger.error("Error updating health status", e);
        }
    }
    
    /**
     * Updates the current metrics for all resources.
     */
    private void updateMetrics() {
        try {
            metrics.updateConnectionPoolUtilization(connectionPoolManager.getPoolUtilization());
            metrics.updateWebDriverPoolUtilization(webDriverPool.getPoolUtilization());
            metrics.updateTotalActiveResources(getTotalActiveResourceCount());
            metrics.updateResourceLeakRate(calculateResourceLeakRate());
            metrics.updateTimestamp(Instant.now());
            
        } catch (Exception e) {
            logger.error("Error updating metrics", e);
        }
    }
    
    /**
     * Performs periodic health checks on all resources.
     */
    private void performHealthCheck() {
        try {
            logger.debug("Performing periodic health check");
            
            updateHealthStatus();
            
            if (!health.isHealthy()) {
                logger.warn("Health check detected unhealthy resources: {}", 
                          health.getUnhealthyResources());
            }
            
        } catch (Exception e) {
            logger.error("Error during health check", e);
        }
    }
    
    /**
     * Detects resource leaks across all managed pools.
     */
    private void detectResourceLeaks() {
        try {
            logger.debug("Detecting resource leaks");
            
            // Detect connection leaks
            connectionPoolManager.detectConnectionLeaks();
            
            // Check for WebDriver session leaks
            List<?> sessionLeaks = webDriverPool.getSessionLeaks();
            if (!sessionLeaks.isEmpty()) {
                logger.warn("Detected {} WebDriver session leaks", sessionLeaks.size());
            }
            
        } catch (Exception e) {
            logger.error("Error during leak detection", e);
        }
    }
    
    /**
     * Gets the total count of resource leaks across all pools.
     */
    private int getResourceLeakCount() {
        try {
            int connectionLeaks = connectionPoolManager.getConnectionLeaks().size();
            int sessionLeaks = webDriverPool.getSessionLeaks().size();
            return connectionLeaks + sessionLeaks;
            
        } catch (Exception e) {
            logger.error("Error counting resource leaks", e);
            return 0;
        }
    }
    
    /**
     * Gets the total count of active resources across all types.
     */
    private int getTotalActiveResourceCount() {
        return activeResourceCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
    
    /**
     * Calculates the current resource leak rate as a percentage.
     */
    private double calculateResourceLeakRate() {
        try {
            int totalResources = getTotalActiveResourceCount();
            int leakCount = getResourceLeakCount();
            
            if (totalResources == 0) {
                return 0.0;
            }
            
            return (double) leakCount / totalResources * 100.0;
            
        } catch (Exception e) {
            logger.error("Error calculating resource leak rate", e);
            return 0.0;
        }
    }
}

/**
 * ResourceHealth provides comprehensive health monitoring for all managed resources.
 * 
 * This class tracks the health status of connection pools, WebDriver pools, and other
 * system resources, providing detailed health information for monitoring and alerting.
 * It maintains real-time health scores and identifies unhealthy resources for remediation.
 */
public class ResourceHealth {
    
    private static final Logger logger = LoggerFactory.getLogger(ResourceHealth.class);
    
    private volatile boolean connectionPoolHealthy = true;
    private volatile boolean webDriverPoolHealthy = true;
    private volatile int resourceLeakCount = 0;
    private volatile Instant healthTimestamp = Instant.now();
    private final Map<String, String> healthDetails = new ConcurrentHashMap<>();
    private final List<String> unhealthyResources = new ArrayList<>();
    
    /**
     * Gets the overall health status across all managed resources.
     * 
     * @return Overall health status as ResourceStatus enum
     */
    public ResourceStatus getOverallHealth() {
        if (!connectionPoolHealthy || !webDriverPoolHealthy) {
            return ResourceStatus.CRITICAL;
        }
        
        if (resourceLeakCount > 0) {
            return ResourceStatus.WARNING;
        }
        
        return ResourceStatus.HEALTHY;
    }
    
    /**
     * Gets the health status of the HTTP connection pool.
     * 
     * @return Connection pool health as ResourceStatus enum
     */
    public ResourceStatus getConnectionPoolHealth() {
        return connectionPoolHealthy ? ResourceStatus.HEALTHY : ResourceStatus.CRITICAL;
    }
    
    /**
     * Gets the health status of the WebDriver pool.
     * 
     * @return WebDriver pool health as ResourceStatus enum
     */
    public ResourceStatus getWebDriverPoolHealth() {
        return webDriverPoolHealthy ? ResourceStatus.HEALTHY : ResourceStatus.CRITICAL;
    }
    
    /**
     * Gets the current count of detected resource leaks.
     * 
     * @return Number of resource leaks detected
     */
    public int getResourceLeakCount() {
        return resourceLeakCount;
    }
    
    /**
     * Gets the timestamp of the last health check.
     * 
     * @return Instant representing last health check time
     */
    public Instant getHealthTimestamp() {
        return healthTimestamp;
    }
    
    /**
     * Checks if all resources are healthy.
     * 
     * @return true if all resources are healthy, false otherwise
     */
    public boolean isHealthy() {
        return getOverallHealth() == ResourceStatus.HEALTHY;
    }
    
    /**
     * Gets detailed health information for all resources.
     * 
     * @return Map containing detailed health information
     */
    public Map<String, String> getHealthDetails() {
        Map<String, String> details = new HashMap<>(healthDetails);
        details.put("overallHealth", getOverallHealth().toString());
        details.put("connectionPoolHealth", getConnectionPoolHealth().toString());
        details.put("webDriverPoolHealth", getWebDriverPoolHealth().toString());
        details.put("resourceLeakCount", String.valueOf(resourceLeakCount));
        details.put("lastHealthCheck", healthTimestamp.toString());
        return details;
    }
    
    /**
     * Calculates and returns a numeric health score (0-100).
     * 
     * @return Health score between 0 (critical) and 100 (perfect health)
     */
    public int getHealthScore() {
        int score = 100;
        
        if (!connectionPoolHealthy) {
            score -= 40; // Connection pool is critical
        }
        
        if (!webDriverPoolHealthy) {
            score -= 40; // WebDriver pool is critical
        }
        
        // Reduce score based on leak count
        score -= Math.min(20, resourceLeakCount * 2);
        
        return Math.max(0, score);
    }
    
    /**
     * Gets a list of resources that are currently unhealthy.
     * 
     * @return List of unhealthy resource names
     */
    public List<String> getUnhealthyResources() {
        List<String> resources = new ArrayList<>();
        
        if (!connectionPoolHealthy) {
            resources.add("ConnectionPool");
        }
        
        if (!webDriverPoolHealthy) {
            resources.add("WebDriverPool");
        }
        
        if (resourceLeakCount > 0) {
            resources.add("ResourceLeaks(" + resourceLeakCount + ")");
        }
        
        return resources;
    }
    
    /**
     * Gets a formatted summary of the current health status.
     * 
     * @return Formatted health summary string
     */
    public String getHealthSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Resource Health Summary ===\n");
        summary.append("Overall Status: ").append(getOverallHealth()).append("\n");
        summary.append("Health Score: ").append(getHealthScore()).append("/100\n");
        summary.append("Connection Pool: ").append(getConnectionPoolHealth()).append("\n");
        summary.append("WebDriver Pool: ").append(getWebDriverPoolHealth()).append("\n");
        summary.append("Resource Leaks: ").append(resourceLeakCount).append("\n");
        summary.append("Last Check: ").append(healthTimestamp).append("\n");
        
        List<String> unhealthy = getUnhealthyResources();
        if (!unhealthy.isEmpty()) {
            summary.append("Unhealthy Resources: ").append(String.join(", ", unhealthy)).append("\n");
        }
        
        return summary.toString();
    }
    
    // Package-private update methods for ResourceManager
    
    void updateConnectionPoolHealth(boolean healthy) {
        this.connectionPoolHealthy = healthy;
        healthDetails.put("connectionPool", healthy ? "HEALTHY" : "UNHEALTHY");
    }
    
    void updateWebDriverPoolHealth(boolean healthy) {
        this.webDriverPoolHealthy = healthy;
        healthDetails.put("webDriverPool", healthy ? "HEALTHY" : "UNHEALTHY");
    }
    
    void updateResourceLeakCount(int count) {
        this.resourceLeakCount = count;
        healthDetails.put("resourceLeaks", String.valueOf(count));
    }
    
    void updateHealthTimestamp(Instant timestamp) {
        this.healthTimestamp = timestamp;
        healthDetails.put("lastUpdate", timestamp.toString());
    }
}

/**
 * ResourceMetrics provides comprehensive performance metrics for all managed resources.
 * 
 * This class collects and maintains real-time performance statistics including
 * resource utilization, throughput metrics, and resource lifecycle information.
 * It supports metrics export for integration with monitoring systems.
 */
public class ResourceMetrics {
    
    private static final Logger logger = LoggerFactory.getLogger(ResourceMetrics.class);
    
    private volatile double connectionPoolUtilization = 0.0;
    private volatile double webDriverPoolUtilization = 0.0;
    private volatile int totalActiveResources = 0;
    private volatile double resourceThroughput = 0.0;
    private volatile Duration averageResourceLifetime = Duration.ZERO;
    private volatile double resourceLeakRate = 0.0;
    private volatile Instant timestamp = Instant.now();
    private volatile long metricsResetTime = System.currentTimeMillis();
    
    // Performance counters
    private volatile long resourceCreationCount = 0;
    private volatile long resourceDestructionCount = 0;
    private volatile long resourceAccessCount = 0;
    
    /**
     * Gets the current connection pool utilization percentage.
     * 
     * @return Connection pool utilization (0.0 - 1.0)
     */
    public double getConnectionPoolUtilization() {
        return connectionPoolUtilization;
    }
    
    /**
     * Gets the current WebDriver pool utilization percentage.
     * 
     * @return WebDriver pool utilization (0.0 - 1.0)
     */
    public double getWebDriverPoolUtilization() {
        return webDriverPoolUtilization;
    }
    
    /**
     * Gets the total count of currently active resources.
     * 
     * @return Total active resource count
     */
    public int getTotalActiveResources() {
        return totalActiveResources;
    }
    
    /**
     * Gets the current resource throughput (resources per second).
     * 
     * @return Resource throughput rate
     */
    public double getResourceThroughput() {
        return resourceThroughput;
    }
    
    /**
     * Gets the average lifetime of managed resources.
     * 
     * @return Average resource lifetime duration
     */
    public Duration getAverageResourceLifetime() {
        return averageResourceLifetime;
    }
    
    /**
     * Gets the current resource leak rate as a percentage.
     * 
     * @return Resource leak rate (0.0 - 100.0)
     */
    public double getResourceLeakRate() {
        return resourceLeakRate;
    }
    
    /**
     * Gets the timestamp of the last metrics update.
     * 
     * @return Instant representing last metrics update
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * Gets a formatted summary of all current metrics.
     * 
     * @return Formatted metrics summary string
     */
    public String getMetricsSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Resource Metrics Summary ===\n");
        summary.append("Connection Pool Utilization: ").append(String.format("%.2f%%", connectionPoolUtilization * 100)).append("\n");
        summary.append("WebDriver Pool Utilization: ").append(String.format("%.2f%%", webDriverPoolUtilization * 100)).append("\n");
        summary.append("Total Active Resources: ").append(totalActiveResources).append("\n");
        summary.append("Resource Throughput: ").append(String.format("%.2f/sec", resourceThroughput)).append("\n");
        summary.append("Average Resource Lifetime: ").append(averageResourceLifetime.toMillis()).append("ms\n");
        summary.append("Resource Leak Rate: ").append(String.format("%.2f%%", resourceLeakRate)).append("\n");
        summary.append("Last Update: ").append(timestamp).append("\n");
        summary.append("Resources Created: ").append(resourceCreationCount).append("\n");
        summary.append("Resources Destroyed: ").append(resourceDestructionCount).append("\n");
        summary.append("Resource Accesses: ").append(resourceAccessCount).append("\n");
        return summary.toString();
    }
    
    /**
     * Resets all metrics counters to zero.
     * Used for periodic metrics collection cycles.
     */
    public void resetMetrics() {
        connectionPoolUtilization = 0.0;
        webDriverPoolUtilization = 0.0;
        totalActiveResources = 0;
        resourceThroughput = 0.0;
        averageResourceLifetime = Duration.ZERO;
        resourceLeakRate = 0.0;
        resourceCreationCount = 0;
        resourceDestructionCount = 0;
        resourceAccessCount = 0;
        metricsResetTime = System.currentTimeMillis();
        timestamp = Instant.now();
        
        logger.debug("Resource metrics reset at {}", timestamp);
    }
    
    /**
     * Exports current metrics in a structured format.
     * 
     * @return Map containing all metrics data for export
     */
    public Map<String, Object> exportMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        metrics.put("connectionPoolUtilization", connectionPoolUtilization);
        metrics.put("webDriverPoolUtilization", webDriverPoolUtilization);
        metrics.put("totalActiveResources", totalActiveResources);
        metrics.put("resourceThroughput", resourceThroughput);
        metrics.put("averageResourceLifetimeMs", averageResourceLifetime.toMillis());
        metrics.put("resourceLeakRate", resourceLeakRate);
        metrics.put("timestamp", timestamp.toEpochMilli());
        metrics.put("resourceCreationCount", resourceCreationCount);
        metrics.put("resourceDestructionCount", resourceDestructionCount);
        metrics.put("resourceAccessCount", resourceAccessCount);
        metrics.put("metricsResetTime", metricsResetTime);
        
        return metrics;
    }
    
    // Package-private update methods for ResourceManager
    
    void updateConnectionPoolUtilization(double utilization) {
        this.connectionPoolUtilization = utilization;
    }
    
    void updateWebDriverPoolUtilization(double utilization) {
        this.webDriverPoolUtilization = utilization;
    }
    
    void updateTotalActiveResources(int total) {
        this.totalActiveResources = total;
    }
    
    void updateResourceThroughput(double throughput) {
        this.resourceThroughput = throughput;
    }
    
    void updateResourceLeakRate(double rate) {
        this.resourceLeakRate = rate;
    }
    
    void updateTimestamp(Instant time) {
        this.timestamp = time;
    }
    
    void incrementResourceCreation() {
        this.resourceCreationCount++;
    }
    
    void incrementResourceDestruction() {
        this.resourceDestructionCount++;
    }
    
    void incrementResourceAccess() {
        this.resourceAccessCount++;
    }
}

/**
 * ResourceStatus enumeration represents the current operational status of resources.
 * 
 * This enum provides standardized status values for tracking resource health
 * and operational states throughout the resource lifecycle.
 */
public enum ResourceStatus {
    /**
     * Resource is currently being initialized and not yet ready for use.
     */
    INITIALIZING,
    
    /**
     * Resource is operating normally and is healthy.
     */
    HEALTHY,
    
    /**
     * Resource is operational but showing warning signs that require attention.
     */
    WARNING,
    
    /**
     * Resource is in critical state and may not function properly.
     */
    CRITICAL,
    
    /**
     * Resource is in the process of shutting down gracefully.
     */
    SHUTTING_DOWN,
    
    /**
     * Resource has been shut down and is no longer available.
     */
    SHUTDOWN,
    
    /**
     * Resource has encountered an error and is not functioning.
     */
    ERROR,
    
    /**
     * Resource is temporarily offline for maintenance operations.
     */
    MAINTENANCE
}

/**
 * ResourceType enumeration represents different categories of managed resources.
 * 
 * This enum provides classification for different types of resources managed
 * by the ResourceManager for tracking, monitoring, and lifecycle management.
 */
public enum ResourceType {
    /**
     * HTTP connection pool resources for API testing and communication.
     */
    CONNECTION_POOL,
    
    /**
     * WebDriver browser session pool resources for web automation.
     */
    WEBDRIVER_POOL,
    
    /**
     * Thread pool resources for concurrent operation management.
     */
    THREAD_POOL,
    
    /**
     * Memory resources including heap and non-heap memory management.
     */
    MEMORY,
    
    /**
     * File handle resources for file I/O operations.
     */
    FILE_HANDLE,
    
    /**
     * Network connection resources for external communication.
     */
    NETWORK,
    
    /**
     * Database connection and transaction resources.
     */
    DATABASE,
    
    /**
     * Cache resources for temporary data storage and retrieval.
     */
    CACHE
}

/**
 * ResourceConfiguration provides configuration management for resource pools and monitoring.
 * 
 * This class manages all configuration parameters related to resource management including
 * monitoring intervals, timeout values, throttling settings, and resource limits.
 * It supports validation and serialization for persistence and external configuration.
 */
public class ResourceConfiguration {
    
    private static final Logger logger = LoggerFactory.getLogger(ResourceConfiguration.class);
    
    // Default configuration values
    private static final Duration DEFAULT_MONITORING_INTERVAL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_CLEANUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_HEALTH_CHECK_INTERVAL = Duration.ofSeconds(60);
    private static final boolean DEFAULT_THROTTLING_ENABLED = false;
    private static final boolean DEFAULT_AUTO_CLEANUP_ENABLED = true;
    
    // Configuration parameters
    private volatile Duration monitoringInterval = DEFAULT_MONITORING_INTERVAL;
    private volatile Duration cleanupTimeout = DEFAULT_CLEANUP_TIMEOUT;
    private volatile Duration healthCheckInterval = DEFAULT_HEALTH_CHECK_INTERVAL;
    private volatile boolean throttlingEnabled = DEFAULT_THROTTLING_ENABLED;
    private volatile boolean autoCleanupEnabled = DEFAULT_AUTO_CLEANUP_ENABLED;
    
    // Resource limits configuration
    private final Map<ResourceType, Integer> resourceLimits = new ConcurrentHashMap<>();
    
    /**
     * Default constructor initializes configuration with default values.
     */
    public ResourceConfiguration() {
        // Initialize default resource limits
        resourceLimits.put(ResourceType.CONNECTION_POOL, 50);
        resourceLimits.put(ResourceType.WEBDRIVER_POOL, 10);
        resourceLimits.put(ResourceType.THREAD_POOL, 20);
        resourceLimits.put(ResourceType.MEMORY, 1024); // MB
        resourceLimits.put(ResourceType.FILE_HANDLE, 100);
        resourceLimits.put(ResourceType.NETWORK, 50);
        resourceLimits.put(ResourceType.DATABASE, 20);
        resourceLimits.put(ResourceType.CACHE, 100); // MB
    }
    
    /**
     * Gets the monitoring interval for resource health checks.
     * 
     * @return Monitoring interval duration
     */
    public Duration getMonitoringInterval() {
        return monitoringInterval;
    }
    
    /**
     * Sets the monitoring interval for resource health checks.
     * 
     * @param monitoringInterval New monitoring interval (must be positive)
     * @throws IllegalArgumentException if interval is null or non-positive
     */
    public void setMonitoringInterval(Duration monitoringInterval) {
        if (monitoringInterval == null || monitoringInterval.isNegative() || monitoringInterval.isZero()) {
            throw new IllegalArgumentException("Monitoring interval must be positive");
        }
        this.monitoringInterval = monitoringInterval;
        logger.debug("Monitoring interval set to: {}", monitoringInterval);
    }
    
    /**
     * Gets the cleanup timeout for resource shutdown operations.
     * 
     * @return Cleanup timeout duration
     */
    public Duration getCleanupTimeout() {
        return cleanupTimeout;
    }
    
    /**
     * Sets the cleanup timeout for resource shutdown operations.
     * 
     * @param cleanupTimeout New cleanup timeout (must be positive)
     * @throws IllegalArgumentException if timeout is null or non-positive
     */
    public void setCleanupTimeout(Duration cleanupTimeout) {
        if (cleanupTimeout == null || cleanupTimeout.isNegative() || cleanupTimeout.isZero()) {
            throw new IllegalArgumentException("Cleanup timeout must be positive");
        }
        this.cleanupTimeout = cleanupTimeout;
        logger.debug("Cleanup timeout set to: {}", cleanupTimeout);
    }
    
    /**
     * Checks if resource throttling is enabled.
     * 
     * @return true if throttling is enabled, false otherwise
     */
    public boolean isThrottlingEnabled() {
        return throttlingEnabled;
    }
    
    /**
     * Sets the resource throttling enablement status.
     * 
     * @param throttlingEnabled true to enable throttling, false to disable
     */
    public void setThrottlingEnabled(boolean throttlingEnabled) {
        this.throttlingEnabled = throttlingEnabled;
        logger.debug("Resource throttling set to: {}", throttlingEnabled);
    }
    
    /**
     * Gets the resource limits configuration for all resource types.
     * 
     * @return Unmodifiable map of resource limits by type
     */
    public Map<ResourceType, Integer> getResourceLimits() {
        return Collections.unmodifiableMap(resourceLimits);
    }
    
    /**
     * Sets resource limits for all resource types.
     * 
     * @param resourceLimits Map of resource limits by type
     * @throws IllegalArgumentException if limits map is null or contains invalid values
     */
    public void setResourceLimits(Map<ResourceType, Integer> resourceLimits) {
        if (resourceLimits == null) {
            throw new IllegalArgumentException("Resource limits map cannot be null");
        }
        
        // Validate all limits are positive
        for (Map.Entry<ResourceType, Integer> entry : resourceLimits.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalArgumentException("Resource limit must be positive for type: " + entry.getKey());
            }
        }
        
        this.resourceLimits.clear();
        this.resourceLimits.putAll(resourceLimits);
        logger.debug("Resource limits updated with {} types", resourceLimits.size());
    }
    
    /**
     * Gets the health check interval for resource monitoring.
     * 
     * @return Health check interval duration
     */
    public Duration getHealthCheckInterval() {
        return healthCheckInterval;
    }
    
    /**
     * Sets the health check interval for resource monitoring.
     * 
     * @param healthCheckInterval New health check interval (must be positive)
     * @throws IllegalArgumentException if interval is null or non-positive
     */
    public void setHealthCheckInterval(Duration healthCheckInterval) {
        if (healthCheckInterval == null || healthCheckInterval.isNegative() || healthCheckInterval.isZero()) {
            throw new IllegalArgumentException("Health check interval must be positive");
        }
        this.healthCheckInterval = healthCheckInterval;
        logger.debug("Health check interval set to: {}", healthCheckInterval);
    }
    
    /**
     * Checks if automatic cleanup is enabled.
     * 
     * @return true if auto cleanup is enabled, false otherwise
     */
    public boolean isAutoCleanupEnabled() {
        return autoCleanupEnabled;
    }
    
    /**
     * Sets the automatic cleanup enablement status.
     * 
     * @param autoCleanupEnabled true to enable auto cleanup, false to disable
     */
    public void setAutoCleanupEnabled(boolean autoCleanupEnabled) {
        this.autoCleanupEnabled = autoCleanupEnabled;
        logger.debug("Auto cleanup set to: {}", autoCleanupEnabled);
    }
    
    /**
     * Validates the current configuration for consistency and correctness.
     * 
     * @throws IllegalStateException if configuration is invalid
     */
    public void validate() {
        List<String> errors = new ArrayList<>();
        
        // Validate intervals are positive
        if (monitoringInterval == null || monitoringInterval.isNegative() || monitoringInterval.isZero()) {
            errors.add("Monitoring interval must be positive");
        }
        
        if (cleanupTimeout == null || cleanupTimeout.isNegative() || cleanupTimeout.isZero()) {
            errors.add("Cleanup timeout must be positive");
        }
        
        if (healthCheckInterval == null || healthCheckInterval.isNegative() || healthCheckInterval.isZero()) {
            errors.add("Health check interval must be positive");
        }
        
        // Validate resource limits
        for (Map.Entry<ResourceType, Integer> entry : resourceLimits.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                errors.add("Resource limit must be positive for type: " + entry.getKey());
            }
        }
        
        // Check for reasonable interval relationships
        if (monitoringInterval.compareTo(healthCheckInterval) > 0) {
            errors.add("Monitoring interval should not exceed health check interval");
        }
        
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Configuration validation failed: " + String.join(", ", errors));
        }
        
        logger.debug("Configuration validation passed successfully");
    }
    
    /**
     * Converts the current configuration to a Properties object.
     * 
     * @return Properties object containing all configuration values
     */
    public Properties toProperties() {
        Properties props = new Properties();
        
        props.setProperty("resource.monitoring.interval", String.valueOf(monitoringInterval.getSeconds()));
        props.setProperty("resource.cleanup.timeout", String.valueOf(cleanupTimeout.toMillis()));
        props.setProperty("resource.healthcheck.interval", String.valueOf(healthCheckInterval.getSeconds()));
        props.setProperty("resource.throttling.enabled", String.valueOf(throttlingEnabled));
        props.setProperty("resource.autocleanup.enabled", String.valueOf(autoCleanupEnabled));
        
        // Add resource limits
        for (Map.Entry<ResourceType, Integer> entry : resourceLimits.entrySet()) {
            props.setProperty("resource.limit." + entry.getKey().name().toLowerCase(), 
                            String.valueOf(entry.getValue()));
        }
        
        return props;
    }
    
    /**
     * Loads configuration from a Properties object.
     * 
     * @param props Properties object containing configuration values
     * @throws IllegalArgumentException if properties contain invalid values
     */
    public void fromProperties(Properties props) {
        if (props == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }
        
        try {
            // Load intervals
            String monitoringIntervalStr = props.getProperty("resource.monitoring.interval");
            if (monitoringIntervalStr != null) {
                setMonitoringInterval(Duration.ofSeconds(Long.parseLong(monitoringIntervalStr)));
            }
            
            String cleanupTimeoutStr = props.getProperty("resource.cleanup.timeout");
            if (cleanupTimeoutStr != null) {
                setCleanupTimeout(Duration.ofMillis(Long.parseLong(cleanupTimeoutStr)));
            }
            
            String healthCheckIntervalStr = props.getProperty("resource.healthcheck.interval");
            if (healthCheckIntervalStr != null) {
                setHealthCheckInterval(Duration.ofSeconds(Long.parseLong(healthCheckIntervalStr)));
            }
            
            // Load boolean settings
            String throttlingEnabledStr = props.getProperty("resource.throttling.enabled");
            if (throttlingEnabledStr != null) {
                setThrottlingEnabled(Boolean.parseBoolean(throttlingEnabledStr));
            }
            
            String autoCleanupEnabledStr = props.getProperty("resource.autocleanup.enabled");
            if (autoCleanupEnabledStr != null) {
                setAutoCleanupEnabled(Boolean.parseBoolean(autoCleanupEnabledStr));
            }
            
            // Load resource limits
            for (ResourceType type : ResourceType.values()) {
                String limitStr = props.getProperty("resource.limit." + type.name().toLowerCase());
                if (limitStr != null) {
                    resourceLimits.put(type, Integer.parseInt(limitStr));
                }
            }
            
            logger.debug("Configuration loaded from properties successfully");
            
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value in properties", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Error loading configuration from properties", e);
        }
    }
}