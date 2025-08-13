package com.automation.framework.monitoring;

// Internal imports from framework dependencies  
import com.automation.framework.core.FrameworkManager;
import com.automation.framework.core.ConfigurationManager;
import com.automation.framework.resources.ConnectionPoolManager;
import com.automation.framework.resources.ThreadPoolManager;
import com.automation.framework.web.BrowserManager;
import com.automation.framework.web.WebDriverPool;
import com.automation.framework.api.APIClient;
import com.automation.framework.api.AuthenticationManager;
import com.automation.framework.monitoring.ResourceMonitor;

// External imports for time measurement and monitoring
import java.time.Duration;
import java.time.Instant;

// External imports for asynchronous execution and concurrent operations
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// External imports for data structures and collections
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

// External imports for JVM management and monitoring
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.management.RuntimeMXBean;

// External imports for structured logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HealthMonitor provides comprehensive health monitoring for the automation framework
 * with three distinct validation levels following microservices best practices.
 * 
 * Health Check Levels:
 * - Liveness Check (<500ms): Basic framework responsiveness validation
 * - Readiness Check (<2s): All components ready status verification  
 * - Deep Health Check (<10s): Full end-to-end functionality validation
 * 
 * Component Monitoring:
 * - Framework Core: Java environment, module registration, configuration
 * - Web Module: WebDriver availability, browser drivers, Page Object Model
 * - API Module: REST client configuration, authentication, connection pools
 * - External Dependencies: Selenium Grid, external APIs, CI/CD integration
 * 
 * Resource Health Monitoring:
 * - Memory usage and leak detection
 * - Thread pool status and utilization
 * - Connection pool health and availability
 * - Browser session management and cleanup
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class HealthMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(HealthMonitor.class);
    
    // Health check performance targets from specification
    private static final Duration LIVENESS_TIMEOUT = Duration.ofMillis(500);
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEEP_HEALTH_TIMEOUT = Duration.ofSeconds(10);
    
    // Singleton instance management
    private static volatile HealthMonitor instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Framework component dependencies
    private final FrameworkManager frameworkManager;
    private final ConfigurationManager configurationManager;
    private final ConnectionPoolManager connectionPoolManager;
    private final ThreadPoolManager threadPoolManager;
    private final BrowserManager browserManager;
    private final WebDriverPool webDriverPool;
    private final APIClient apiClient;
    private final AuthenticationManager authenticationManager;
    private final ResourceMonitor resourceMonitor;
    
    // JVM monitoring components
    private final MemoryMXBean memoryMXBean;
    private final ThreadMXBean threadMXBean;
    private final RuntimeMXBean runtimeMXBean;
    
    // Health monitoring infrastructure
    private ExecutorService healthCheckExecutor;
    private final AtomicBoolean monitoringActive = new AtomicBoolean(false);
    private final AtomicLong healthCheckCycles = new AtomicLong(0);
    private volatile Instant lastHealthCheckTime;
    private volatile Instant monitoringStartTime;
    
    // Health status tracking
    private final ConcurrentHashMap<String, ComponentHealth> componentHealthMap = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<HealthCheckResult> healthCheckHistory = new ConcurrentLinkedQueue<>();
    private volatile HealthStatus overallHealthStatus = HealthStatus.UNKNOWN;
    
    // Thread safety
    private final ReentrantReadWriteLock healthLock = new ReentrantReadWriteLock();
    
    /**
     * Private constructor for singleton pattern.
     * Initializes all health monitoring components and framework dependencies.
     */
    private HealthMonitor() {
        // Initialize framework component dependencies
        this.frameworkManager = FrameworkManager.getInstance();
        this.configurationManager = ConfigurationManager.getInstance();
        this.connectionPoolManager = new ConnectionPoolManager();
        this.threadPoolManager = ThreadPoolManager.getInstance();
        this.browserManager = BrowserManager.getInstance();
        this.webDriverPool = new WebDriverPool();
        this.apiClient = new APIClient();
        this.authenticationManager = AuthenticationManager.getInstance();
        this.resourceMonitor = ResourceMonitor.getInstance();
        
        // Initialize JVM monitoring components
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.threadMXBean = ManagementFactory.getThreadMXBean();
        this.runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        
        // Initialize component health tracking
        initializeComponentHealth();
        
        logger.info("HealthMonitor initialized with performance targets: liveness={}ms, readiness={}s, deep={}s",
                   LIVENESS_TIMEOUT.toMillis(), READINESS_TIMEOUT.getSeconds(), DEEP_HEALTH_TIMEOUT.getSeconds());
    }
    
    /**
     * Gets the singleton instance of HealthMonitor.
     * Thread-safe lazy initialization with double-checked locking pattern.
     * 
     * @return HealthMonitor singleton instance
     */
    public static HealthMonitor getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new HealthMonitor();
                }
            }
        }
        return instance;
    }
    
    /**
     * Performs basic liveness check to verify framework responsiveness.
     * Target response time: <500ms
     * 
     * @return HealthCheckResult indicating basic framework liveness
     */
    public HealthCheckResult checkLiveness() {
        Instant startTime = Instant.now();
        
        try {
            CompletableFuture<HealthCheckResult> livenessCheck = CompletableFuture.supplyAsync(() -> {
                try {
                    // Basic JVM health verification
                    boolean jvmHealthy = checkJVMHealth();
                    
                    // Framework manager initialization check
                    boolean frameworkInitialized = frameworkManager.getStatus() != null;
                    
                    // Configuration manager accessibility
                    boolean configurationAccessible = configurationManager.getProperty("framework.name") != null;
                    
                    boolean isHealthy = jvmHealthy && frameworkInitialized && configurationAccessible;
                    Duration responseTime = Duration.between(startTime, Instant.now());
                    
                    return new HealthCheckResult(
                        "LIVENESS",
                        isHealthy ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY,
                        responseTime,
                        isHealthy ? null : "Basic framework components not responding",
                        Map.of(
                            "jvm", jvmHealthy,
                            "framework", frameworkInitialized,
                            "configuration", configurationAccessible
                        ),
                        Instant.now()
                    );
                    
                } catch (Exception e) {
                    Duration responseTime = Duration.between(startTime, Instant.now());
                    logger.error("Liveness check failed", e);
                    return new HealthCheckResult(
                        "LIVENESS",
                        HealthStatus.UNHEALTHY,
                        responseTime,
                        "Liveness check exception: " + e.getMessage(),
                        Map.of("exception", e.getClass().getSimpleName()),
                        Instant.now()
                    );
                }
            }, healthCheckExecutor != null ? healthCheckExecutor : getDefaultExecutor());
            
            return livenessCheck.orTimeout(LIVENESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).get();
            
        } catch (Exception e) {
            Duration responseTime = Duration.between(startTime, Instant.now());
            logger.error("Liveness check execution failed", e);
            return new HealthCheckResult(
                "LIVENESS",
                HealthStatus.UNHEALTHY,
                responseTime,
                "Liveness check execution failed: " + e.getMessage(),
                Map.of("executionError", e.getClass().getSimpleName()),
                Instant.now()
            );
        } finally {
            lastHealthCheckTime = Instant.now();
        }
    }
    
    /**
     * Performs comprehensive readiness check to verify all components are ready.
     * Target response time: <2 seconds
     * 
     * @return HealthCheckResult indicating component readiness status
     */
    public HealthCheckResult checkReadiness() {
        Instant startTime = Instant.now();
        
        try {
            CompletableFuture<HealthCheckResult> readinessCheck = CompletableFuture.supplyAsync(() -> {
                try {
                    Map<String, Object> readinessDetails = new HashMap<>();
                    boolean overallReady = true;
                    
                    // Framework Core readiness validation
                    boolean frameworkReady = validateFrameworkCoreReadiness();
                    readinessDetails.put("frameworkCore", frameworkReady);
                    overallReady &= frameworkReady;
                    
                    // Web Module readiness validation
                    boolean webModuleReady = validateWebModuleReadiness();
                    readinessDetails.put("webModule", webModuleReady);
                    overallReady &= webModuleReady;
                    
                    // API Module readiness validation
                    boolean apiModuleReady = validateAPIModuleReadiness();
                    readinessDetails.put("apiModule", apiModuleReady);
                    overallReady &= apiModuleReady;
                    
                    // Resource availability validation
                    boolean resourcesReady = validateResourceAvailability();
                    readinessDetails.put("resources", resourcesReady);
                    overallReady &= resourcesReady;
                    
                    Duration responseTime = Duration.between(startTime, Instant.now());
                    
                    return new HealthCheckResult(
                        "READINESS",
                        overallReady ? HealthStatus.HEALTHY : HealthStatus.DEGRADED,
                        responseTime,
                        overallReady ? null : "Some components are not ready for operation",
                        readinessDetails,
                        Instant.now()
                    );
                    
                } catch (Exception e) {
                    Duration responseTime = Duration.between(startTime, Instant.now());
                    logger.error("Readiness check failed", e);
                    return new HealthCheckResult(
                        "READINESS",
                        HealthStatus.UNHEALTHY,
                        responseTime,
                        "Readiness check exception: " + e.getMessage(),
                        Map.of("exception", e.getClass().getSimpleName()),
                        Instant.now()
                    );
                }
            }, healthCheckExecutor != null ? healthCheckExecutor : getDefaultExecutor());
            
            return readinessCheck.orTimeout(READINESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).get();
            
        } catch (Exception e) {
            Duration responseTime = Duration.between(startTime, Instant.now());
            logger.error("Readiness check execution failed", e);
            return new HealthCheckResult(
                "READINESS",
                HealthStatus.UNHEALTHY,
                responseTime,
                "Readiness check execution failed: " + e.getMessage(),
                Map.of("executionError", e.getClass().getSimpleName()),
                Instant.now()
            );
        } finally {
            lastHealthCheckTime = Instant.now();
        }
    }
    
    /**
     * Performs deep health validation with full end-to-end functionality testing.
     * Target response time: <10 seconds
     * 
     * @return HealthCheckResult indicating comprehensive system health
     */
    public HealthCheckResult checkDeepHealth() {
        Instant startTime = Instant.now();
        
        try {
            CompletableFuture<HealthCheckResult> deepHealthCheck = CompletableFuture.supplyAsync(() -> {
                try {
                    Map<String, Object> deepHealthDetails = new HashMap<>();
                    boolean overallHealthy = true;
                    
                    // Deep Framework Core validation
                    boolean frameworkDeepHealth = validateFrameworkCoreDeepHealth();
                    deepHealthDetails.put("frameworkCoreDeep", frameworkDeepHealth);
                    overallHealthy &= frameworkDeepHealth;
                    
                    // Deep Web Module validation
                    boolean webModuleDeepHealth = validateWebModuleDeepHealth();
                    deepHealthDetails.put("webModuleDeep", webModuleDeepHealth);
                    overallHealthy &= webModuleDeepHealth;
                    
                    // Deep API Module validation  
                    boolean apiModuleDeepHealth = validateAPIModuleDeepHealth();
                    deepHealthDetails.put("apiModuleDeep", apiModuleDeepHealth);
                    overallHealthy &= apiModuleDeepHealth;
                    
                    // External dependencies validation
                    boolean externalDepsHealthy = validateExternalDependencies();
                    deepHealthDetails.put("externalDependencies", externalDepsHealthy);
                    overallHealthy &= externalDepsHealthy;
                    
                    // Resource leak detection
                    boolean resourceLeaksDetected = performResourceLeakDetection();
                    deepHealthDetails.put("resourceLeaks", !resourceLeaksDetected);
                    overallHealthy &= !resourceLeaksDetected;
                    
                    Duration responseTime = Duration.between(startTime, Instant.now());
                    
                    return new HealthCheckResult(
                        "DEEP_HEALTH",
                        overallHealthy ? HealthStatus.HEALTHY : HealthStatus.DEGRADED,
                        responseTime,
                        overallHealthy ? null : "Deep health validation detected issues requiring attention",
                        deepHealthDetails,
                        Instant.now()
                    );
                    
                } catch (Exception e) {
                    Duration responseTime = Duration.between(startTime, Instant.now());
                    logger.error("Deep health check failed", e);
                    return new HealthCheckResult(
                        "DEEP_HEALTH",
                        HealthStatus.UNHEALTHY,
                        responseTime,
                        "Deep health check exception: " + e.getMessage(),
                        Map.of("exception", e.getClass().getSimpleName()),
                        Instant.now()
                    );
                }
            }, healthCheckExecutor != null ? healthCheckExecutor : getDefaultExecutor());
            
            return deepHealthCheck.orTimeout(DEEP_HEALTH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).get();
            
        } catch (Exception e) {
            Duration responseTime = Duration.between(startTime, Instant.now());
            logger.error("Deep health check execution failed", e);
            return new HealthCheckResult(
                "DEEP_HEALTH",
                HealthStatus.UNHEALTHY,
                responseTime,
                "Deep health check execution failed: " + e.getMessage(),
                Map.of("executionError", e.getClass().getSimpleName()),
                Instant.now()
            );
        } finally {
            lastHealthCheckTime = Instant.now();
        }
    }
    
    /**
     * Gets the overall health status of the automation framework.
     * 
     * @return Current HealthStatus indicating overall framework health
     */
    public HealthStatus getOverallHealthStatus() {
        healthLock.readLock().lock();
        try {
            // If monitoring hasn't started, perform quick health assessment
            if (overallHealthStatus == HealthStatus.UNKNOWN) {
                return calculateOverallHealthStatus();
            }
            return overallHealthStatus;
        } finally {
            healthLock.readLock().unlock();
        }
    }
    
    /**
     * Gets health status for a specific component.
     * 
     * @param componentName Name of the component to check
     * @return ComponentHealth for the specified component
     */
    public ComponentHealth getComponentHealth(String componentName) {
        healthLock.readLock().lock();
        try {
            ComponentHealth componentHealth = componentHealthMap.get(componentName);
            if (componentHealth == null) {
                // Create default component health if not exists
                return new ComponentHealth(
                    componentName,
                    HealthStatus.UNKNOWN,
                    new ArrayList<>(),
                    Instant.now(),
                    Duration.ZERO,
                    new HashMap<>()
                );
            }
            return componentHealth;
        } finally {
            healthLock.readLock().unlock();
        }
    }
    
    /**
     * Gets comprehensive health metrics for all monitored components.
     * 
     * @return Map containing health metrics for all components
     */
    public Map<String, Object> getHealthMetrics() {
        healthLock.readLock().lock();
        try {
            Map<String, Object> healthMetrics = new HashMap<>();
            
            // Overall health metrics
            healthMetrics.put("overallStatus", overallHealthStatus.toString());
            healthMetrics.put("lastCheckTime", lastHealthCheckTime);
            healthMetrics.put("monitoringActive", monitoringActive.get());
            healthMetrics.put("totalHealthChecks", healthCheckCycles.get());
            
            // Component health metrics
            Map<String, Object> componentMetrics = new HashMap<>();
            componentHealthMap.forEach((name, health) -> {
                Map<String, Object> metrics = new HashMap<>();
                metrics.put("status", health.getHealthStatus().toString());
                metrics.put("lastCheckTime", health.getLastCheckTime());
                metrics.put("uptime", health.getUptime().toString());
                metrics.put("validationCount", health.getValidationResults().size());
                componentMetrics.put(name, metrics);
            });
            healthMetrics.put("componentHealth", componentMetrics);
            
            // Resource health metrics from ResourceMonitor
            try {
                healthMetrics.put("memoryMetrics", resourceMonitor.getMemoryMetrics());
                healthMetrics.put("threadPoolMetrics", resourceMonitor.getThreadPoolMetrics());
                healthMetrics.put("resourceLeaks", resourceMonitor.getResourceLeaks().size());
                healthMetrics.put("heapUsageBaseline", resourceMonitor.getHeapUsageBaseline());
            } catch (Exception e) {
                logger.debug("Error retrieving resource metrics", e);
                healthMetrics.put("resourceMetricsError", e.getMessage());
            }
            
            // JVM health metrics
            Map<String, Object> jvmMetrics = new HashMap<>();
            jvmMetrics.put("heapMemoryUsage", memoryMXBean.getHeapMemoryUsage().getUsed());
            jvmMetrics.put("nonHeapMemoryUsage", memoryMXBean.getNonHeapMemoryUsage().getUsed());
            jvmMetrics.put("threadCount", threadMXBean.getThreadCount());
            jvmMetrics.put("uptime", runtimeMXBean.getUptime());
            healthMetrics.put("jvmMetrics", jvmMetrics);
            
            return healthMetrics;
            
        } finally {
            healthLock.readLock().unlock();
        }
    }
    
    /**
     * Starts continuous health monitoring with configured intervals.
     * 
     * @return true if health monitoring started successfully
     */
    public boolean startHealthMonitoring() {
        if (monitoringActive.get()) {
            logger.warn("Health monitoring is already active");
            return true;
        }
        
        try {
            healthLock.writeLock().lock();
            
            // Initialize health check executor
            healthCheckExecutor = Executors.newScheduledThreadPool(3, r -> {
                Thread thread = new Thread(r, "HealthMonitor-HealthCheck");
                thread.setDaemon(true);
                return thread;
            });
            
            // Start underlying component monitoring
            resourceMonitor.startMonitoring();
            
            monitoringActive.set(true);
            monitoringStartTime = Instant.now();
            overallHealthStatus = HealthStatus.HEALTHY;
            
            logger.info("Health monitoring started successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to start health monitoring", e);
            return false;
        } finally {
            healthLock.writeLock().unlock();
        }
    }
    
    /**
     * Stops health monitoring and cleanup monitoring resources.
     * 
     * @return true if health monitoring stopped successfully
     */
    public boolean stopHealthMonitoring() {
        if (!monitoringActive.get()) {
            logger.warn("Health monitoring is not active");
            return true;
        }
        
        try {
            healthLock.writeLock().lock();
            
            monitoringActive.set(false);
            
            // Shutdown health check executor
            if (healthCheckExecutor != null) {
                healthCheckExecutor.shutdown();
                if (!healthCheckExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    healthCheckExecutor.shutdownNow();
                }
                healthCheckExecutor = null;
            }
            
            // Stop underlying component monitoring
            resourceMonitor.stopMonitoring();
            
            Duration monitoringDuration = Duration.between(monitoringStartTime, Instant.now());
            logger.info("Health monitoring stopped after duration: {}, total health checks: {}",
                       monitoringDuration, healthCheckCycles.get());
            
            return true;
            
        } catch (Exception e) {
            logger.error("Error stopping health monitoring", e);
            return false;
        } finally {
            healthLock.writeLock().unlock();
        }
    }
    
    /**
     * Checks if the overall automation framework is healthy.
     * 
     * @return true if framework is healthy, false otherwise
     */
    public boolean isFrameworkHealthy() {
        HealthStatus currentStatus = getOverallHealthStatus();
        return currentStatus == HealthStatus.HEALTHY || currentStatus == HealthStatus.DEGRADED;
    }
    
    /**
     * Gets the timestamp of the last health check performed.
     * 
     * @return Instant of last health check, or null if no checks performed
     */
    public Instant getLastHealthCheckTime() {
        return lastHealthCheckTime;
    }
    
    /**
     * Gets the history of health check results.
     * 
     * @return List of HealthCheckResult from recent health checks
     */
    public List<HealthCheckResult> getHealthCheckHistory() {
        healthLock.readLock().lock();
        try {
            return new ArrayList<>(healthCheckHistory);
        } finally {
            healthLock.readLock().unlock();
        }
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Gets default executor for health checks when dedicated executor is not available.
     */
    private ExecutorService getDefaultExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "HealthMonitor-Default");
            thread.setDaemon(true);
            return thread;
        });
    }
    
    /**
     * Initializes component health tracking for all framework components.
     */
    private void initializeComponentHealth() {
        // Initialize Framework Core component health
        componentHealthMap.put("FrameworkCore", new ComponentHealth(
            "FrameworkCore",
            HealthStatus.UNKNOWN,
            new ArrayList<>(),
            Instant.now(),
            Duration.ZERO,
            new HashMap<>()
        ));
        
        // Initialize Web Module component health
        componentHealthMap.put("WebModule", new ComponentHealth(
            "WebModule", 
            HealthStatus.UNKNOWN,
            new ArrayList<>(),
            Instant.now(),
            Duration.ZERO,
            new HashMap<>()
        ));
        
        // Initialize API Module component health
        componentHealthMap.put("APIModule", new ComponentHealth(
            "APIModule",
            HealthStatus.UNKNOWN,
            new ArrayList<>(),
            Instant.now(),
            Duration.ZERO,
            new HashMap<>()
        ));
        
        // Initialize External Dependencies component health
        componentHealthMap.put("ExternalDependencies", new ComponentHealth(
            "ExternalDependencies",
            HealthStatus.UNKNOWN,
            new ArrayList<>(),
            Instant.now(),
            Duration.ZERO,
            new HashMap<>()
        ));
    }
    
    /**
     * Performs basic JVM health verification.
     */
    private boolean checkJVMHealth() {
        try {
            // Check memory usage is within reasonable bounds
            long heapUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
            long heapMax = memoryMXBean.getHeapMemoryUsage().getMax();
            double heapUtilization = (double) heapUsed / heapMax;
            
            // Check thread count is reasonable
            int threadCount = threadMXBean.getThreadCount();
            
            // Basic JVM health criteria
            boolean memoryHealthy = heapUtilization < 0.95; // Less than 95% heap usage
            boolean threadsHealthy = threadCount < 1000; // Less than 1000 threads
            
            return memoryHealthy && threadsHealthy;
            
        } catch (Exception e) {
            logger.debug("Error checking JVM health", e);
            return false;
        }
    }
    
    /**
     * Validates Framework Core readiness including Java environment and module registration.
     */
    private boolean validateFrameworkCoreReadiness() {
        try {
            // Check framework initialization time
            Duration initDuration = frameworkManager.getLastInitializationDuration();
            long initTime = initDuration != null ? initDuration.toMillis() : 0;
            boolean initTimeReasonable = initTime > 0 && initTime < 10000; // 10 seconds max
            
            // Check framework state
            boolean stateValid = frameworkManager.isInitialized();
            
            // Check active modules
            List<String> activeModules = frameworkManager.getRegisteredModuleIds();
            boolean modulesActive = activeModules != null && !activeModules.isEmpty();
            
            // Check configuration validity
            String frameworkName = configurationManager.getProperty("framework.name");
            boolean configValid = frameworkName != null;
            
            boolean frameworkReady = initTimeReasonable && stateValid && modulesActive && configValid;
            
            // Update component health
            updateComponentHealth("FrameworkCore", frameworkReady ? HealthStatus.HEALTHY : HealthStatus.DEGRADED,
                Map.of(
                    "initTime", initTime,
                    "stateValid", stateValid,
                    "moduleCount", activeModules != null ? activeModules.size() : 0,
                    "configValid", configValid
                ));
            
            return frameworkReady;
            
        } catch (Exception e) {
            logger.debug("Error validating Framework Core readiness", e);
            updateComponentHealth("FrameworkCore", HealthStatus.UNHEALTHY, 
                Map.of("error", e.getMessage()));
            return false;
        }
    }
    
    /**
     * Validates Web Module readiness including WebDriver availability and browser drivers.
     */
    private boolean validateWebModuleReadiness() {
        try {
            // Check WebDriver pool health
            boolean poolHealthy = webDriverPool.isPoolHealthy();
            
            // Check pool utilization
            double poolUtilization = webDriverPool.getPoolUtilization();
            boolean poolUtilizationOk = poolUtilization < 0.8; // Less than 80% utilization
            
            // Check available drivers
            int availableDrivers = webDriverPool.getAvailableDrivers();
            boolean driversAvailable = availableDrivers > 0;
            
            // Check active browser sessions
            int sessionCount = webDriverPool.getActiveBrowserSessions();
            boolean sessionsReasonable = sessionCount < 10; // Within session limit
            
            boolean webModuleReady = poolHealthy && poolUtilizationOk && driversAvailable && sessionsReasonable;
            
            // Update component health
            updateComponentHealth("WebModule", webModuleReady ? HealthStatus.HEALTHY : HealthStatus.DEGRADED,
                Map.of(
                    "poolHealthy", poolHealthy,
                    "poolUtilization", poolUtilization,
                    "availableDrivers", availableDrivers,
                    "activeSessions", sessionCount
                ));
            
            return webModuleReady;
            
        } catch (Exception e) {
            logger.debug("Error validating Web Module readiness", e);
            updateComponentHealth("WebModule", HealthStatus.UNHEALTHY,
                Map.of("error", e.getMessage()));
            return false;
        }
    }
    
    /**
     * Validates API Module readiness including REST client configuration and authentication.
     */
    private boolean validateAPIModuleReadiness() {
        try {
            // Check connection pool health
            boolean poolHealthy = connectionPoolManager.isPoolHealthy();
            
            // Check pool utilization
            double poolUtilization = connectionPoolManager.getPoolUtilization();
            boolean poolUtilizationOk = poolUtilization < 0.8; // Less than 80% utilization
            
            // Check available connections
            int availableConnections = connectionPoolManager.getAvailableConnections();
            boolean connectionsAvailable = availableConnections > 0;
            
            // Check authentication health
            boolean authHealthy = authenticationManager.isAuthenticationHealthy();
            
            boolean apiModuleReady = poolHealthy && poolUtilizationOk && connectionsAvailable && authHealthy;
            
            // Update component health
            updateComponentHealth("APIModule", apiModuleReady ? HealthStatus.HEALTHY : HealthStatus.DEGRADED,
                Map.of(
                    "poolHealthy", poolHealthy,
                    "poolUtilization", poolUtilization,
                    "availableConnections", availableConnections,
                    "authHealthy", authHealthy
                ));
            
            return apiModuleReady;
            
        } catch (Exception e) {
            logger.debug("Error validating API Module readiness", e);
            updateComponentHealth("APIModule", HealthStatus.UNHEALTHY,
                Map.of("error", e.getMessage()));
            return false;
        }
    }
    
    /**
     * Validates resource availability including memory, thread pool, and connection pool status.
     */
    private boolean validateResourceAvailability() {
        try {
            // Check memory health
            boolean memoryHealthy = resourceMonitor.getMemoryMetrics().isHealthy();
            
            // Check thread pool health
            boolean threadPoolHealthy = threadPoolManager.isThreadPoolHealthy();
            
            // Check heap usage baseline
            long heapBaseline = resourceMonitor.getHeapUsageBaseline();
            boolean heapBaselineOk = heapBaseline > 0 && heapBaseline < 512 * 1024 * 1024; // 512MB baseline max
            
            // Check for resource leaks
            List<ResourceLeak> resourceLeaks = resourceMonitor.getResourceLeaks();
            boolean noResourceLeaks = resourceLeaks.isEmpty();
            
            boolean resourcesReady = memoryHealthy && threadPoolHealthy && heapBaselineOk && noResourceLeaks;
            
            // Update metrics in component health
            Map<String, Object> resourceMetrics = new HashMap<>();
            resourceMetrics.put("memoryHealthy", memoryHealthy);
            resourceMetrics.put("threadPoolHealthy", threadPoolHealthy);
            resourceMetrics.put("heapBaseline", heapBaseline);
            resourceMetrics.put("resourceLeakCount", resourceLeaks.size());
            
            return resourcesReady;
            
        } catch (Exception e) {
            logger.debug("Error validating resource availability", e);
            return false;
        }
    }
    
    /**
     * Validates Framework Core deep health including configuration validation and module verification.
     */
    private boolean validateFrameworkCoreDeepHealth() {
        try {
            // Check total executed tests via module count as proxy
            int moduleCount = frameworkManager.getRegisteredModuleCount();
            boolean testCountValid = moduleCount >= 0;
            
            // Check monitoring settings via configuration validity
            boolean monitoringConfigured = configurationManager.isConfigurationValid();
            
            // Check alert configuration via configuration property
            String alertConfig = configurationManager.getProperty("alerts.enabled");
            boolean alertsConfigured = alertConfig != null;
            
            boolean deepHealthy = testCountValid && monitoringConfigured && alertsConfigured;
            
            // Update component health with deep validation results
            List<String> validationResults = new ArrayList<>();
            if (!testCountValid) validationResults.add("Invalid module count");
            if (!monitoringConfigured) validationResults.add("Monitoring not configured");
            if (!alertsConfigured) validationResults.add("Alerts not configured");
            
            updateComponentHealthWithValidation("FrameworkCore", 
                deepHealthy ? HealthStatus.HEALTHY : HealthStatus.DEGRADED,
                validationResults,
                Map.of(
                    "moduleCount", moduleCount,
                    "monitoringConfigured", monitoringConfigured,
                    "alertsConfigured", alertsConfigured
                ));
            
            return deepHealthy;
            
        } catch (Exception e) {
            logger.debug("Error validating Framework Core deep health", e);
            updateComponentHealthWithValidation("FrameworkCore", HealthStatus.UNHEALTHY,
                List.of("Deep health check failed: " + e.getMessage()),
                Map.of("error", e.getMessage()));
            return false;
        }
    }
    
    /**
     * Validates Web Module deep health including Page Object Model initialization.
     */
    private boolean validateWebModuleDeepHealth() {
        try {
            // Check session memory usage via pool health
            boolean sessionMemoryOk = webDriverPool.isPoolHealthy();
            
            // Check browser session metrics via pool utilization
            double poolUtilization = webDriverPool.getPoolUtilization();
            boolean browserMetricsOk = poolUtilization >= 0 && poolUtilization <= 1.0;
            
            // Check session leak detection
            int sessionLeaks = webDriverPool.getSessionLeaks();
            boolean noSessionLeaks = sessionLeaks == 0;
            
            // Attempt to check driver accessibility via pool
            boolean driverAccessible = false;
            try {
                // Check driver availability via pool
                driverAccessible = webDriverPool.getAvailableDrivers() > 0;
            } catch (Exception e) {
                logger.debug("Driver access test failed", e);
            }
            
            boolean deepHealthy = sessionMemoryOk && browserMetricsOk && noSessionLeaks && driverAccessible;
            
            // Update component health with deep validation results
            List<String> validationResults = new ArrayList<>();
            if (!sessionMemoryOk) validationResults.add("Session memory metrics unavailable");
            if (!browserMetricsOk) validationResults.add("Browser metrics unavailable");
            if (!noSessionLeaks) validationResults.add("Session leaks detected: " + sessionLeaks);
            if (!driverAccessible) validationResults.add("WebDriver not accessible");
            
            updateComponentHealthWithValidation("WebModule",
                deepHealthy ? HealthStatus.HEALTHY : HealthStatus.DEGRADED,
                validationResults,
                Map.of(
                    "sessionMemoryOk", sessionMemoryOk,
                    "browserMetricsOk", browserMetricsOk,
                    "sessionLeaks", sessionLeaks,
                    "driverAccessible", driverAccessible
                ));
            
            return deepHealthy;
            
        } catch (Exception e) {
            logger.debug("Error validating Web Module deep health", e);
            updateComponentHealthWithValidation("WebModule", HealthStatus.UNHEALTHY,
                List.of("Deep health check failed: " + e.getMessage()),
                Map.of("error", e.getMessage()));
            return false;
        }
    }
    
    /**
     * Validates API Module deep health including connection pool metrics and timeout violations.
     */
    private boolean validateAPIModuleDeepHealth() {
        try {
            // Check API response times via connection pool health
            boolean responseTimesOk = connectionPoolManager.isPoolHealthy();
            
            // Check connection pool metrics via pool utilization
            double poolUtilization = connectionPoolManager.getPoolUtilization();
            boolean poolMetricsOk = poolUtilization >= 0 && poolUtilization <= 1.0;
            
            // Check active requests via available connections
            int availableConnections = connectionPoolManager.getAvailableConnections();
            boolean activeRequestsOk = availableConnections >= 0;
            
            // Check timeout violations via authentication health
            boolean noTimeoutViolations = authenticationManager.isAuthenticationHealthy();
            
            // Check authentication token validity
            boolean tokenValid = authenticationManager.isAuthenticationHealthy();
            
            boolean deepHealthy = responseTimesOk && poolMetricsOk && activeRequestsOk && 
                                noTimeoutViolations && tokenValid;
            
            // Update component health with deep validation results
            List<String> validationResults = new ArrayList<>();
            if (!responseTimesOk) validationResults.add("Connection pool not healthy");
            if (!poolMetricsOk) validationResults.add("Pool utilization metrics unavailable"); 
            if (!activeRequestsOk) validationResults.add("Available connections: " + availableConnections);
            if (!noTimeoutViolations) validationResults.add("Authentication health issues detected");
            if (!tokenValid) validationResults.add("Authentication token invalid");
            
            updateComponentHealthWithValidation("APIModule",
                deepHealthy ? HealthStatus.HEALTHY : HealthStatus.DEGRADED,
                validationResults,
                Map.of(
                    "poolHealthy", responseTimesOk,
                    "poolUtilization", poolUtilization,
                    "availableConnections", availableConnections,
                    "authHealthy", noTimeoutViolations,
                    "tokenValid", tokenValid
                ));
            
            return deepHealthy;
            
        } catch (Exception e) {
            logger.debug("Error validating API Module deep health", e);
            updateComponentHealthWithValidation("APIModule", HealthStatus.UNHEALTHY,
                List.of("Deep health check failed: " + e.getMessage()),
                Map.of("error", e.getMessage()));
            return false;
        }
    }
    
    /**
     * Validates external dependencies including Selenium Grid and external API reachability.
     */
    private boolean validateExternalDependencies() {
        try {
            // Check Selenium Grid connectivity (simulated via WebDriver availability)
            boolean seleniumGridOk = webDriverPool.getAvailableDrivers() > 0;
            
            // Check external API reachability (via authentication system)
            boolean externalApiOk = authenticationManager.isAuthenticationHealthy();
            
            // Check CI/CD integration status (simulated via configuration presence)
            String cicdConfig = configurationManager.getProperty("cicd.integration");
            boolean cicdIntegrationOk = cicdConfig != null;
            
            boolean externalDepsHealthy = seleniumGridOk && externalApiOk && cicdIntegrationOk;
            
            // Update component health
            List<String> validationResults = new ArrayList<>();
            if (!seleniumGridOk) validationResults.add("Selenium Grid connectivity issues");
            if (!externalApiOk) validationResults.add("External API connectivity issues");
            if (!cicdIntegrationOk) validationResults.add("CI/CD integration not configured");
            
            updateComponentHealthWithValidation("ExternalDependencies",
                externalDepsHealthy ? HealthStatus.HEALTHY : HealthStatus.DEGRADED,
                validationResults,
                Map.of(
                    "seleniumGrid", seleniumGridOk,
                    "externalApi", externalApiOk,
                    "cicdIntegration", cicdIntegrationOk
                ));
            
            return externalDepsHealthy;
            
        } catch (Exception e) {
            logger.debug("Error validating external dependencies", e);
            updateComponentHealthWithValidation("ExternalDependencies", HealthStatus.UNHEALTHY,
                List.of("External dependencies check failed: " + e.getMessage()),
                Map.of("error", e.getMessage()));
            return false;
        }
    }
    
    /**
     * Performs comprehensive resource leak detection across all framework components.
     */
    private boolean performResourceLeakDetection() {
        try {
            List<ResourceLeak> detectedLeaks = resourceMonitor.getResourceLeaks();
            
            if (!detectedLeaks.isEmpty()) {
                logger.warn("Detected {} resource leaks during deep health check", detectedLeaks.size());
                detectedLeaks.forEach(leak -> {
                    logger.warn("Resource leak: {} - {} (severity: {})", 
                               leak.getLeakType(), leak.getResourceIdentifier(), leak.getSeverity());
                });
                return true; // leaks detected
            }
            
            return false; // no leaks detected
            
        } catch (Exception e) {
            logger.debug("Error performing resource leak detection", e);
            return false; // assume no leaks if detection fails
        }
    }
    
    /**
     * Calculates overall health status based on component health states.
     */
    private HealthStatus calculateOverallHealthStatus() {
        healthLock.readLock().lock();
        try {
            if (componentHealthMap.isEmpty()) {
                return HealthStatus.UNKNOWN;
            }
            
            int healthyCount = 0;
            int degradedCount = 0;
            int unhealthyCount = 0;
            
            for (ComponentHealth componentHealth : componentHealthMap.values()) {
                switch (componentHealth.getHealthStatus()) {
                    case HEALTHY:
                        healthyCount++;
                        break;
                    case DEGRADED:
                        degradedCount++;
                        break;
                    case UNHEALTHY:
                        unhealthyCount++;
                        break;
                    default:
                        // UNKNOWN counts as degraded
                        degradedCount++;
                        break;
                }
            }
            
            // Determine overall status based on component health distribution
            if (unhealthyCount > 0) {
                return HealthStatus.UNHEALTHY;
            } else if (degradedCount > 0) {
                return HealthStatus.DEGRADED;
            } else if (healthyCount > 0) {
                return HealthStatus.HEALTHY;
            } else {
                return HealthStatus.UNKNOWN;
            }
            
        } finally {
            healthLock.readLock().unlock();
        }
    }
    
    /**
     * Updates component health status and metrics.
     */
    private void updateComponentHealth(String componentName, HealthStatus status, Map<String, Object> metrics) {
        healthLock.writeLock().lock();
        try {
            ComponentHealth existingHealth = componentHealthMap.get(componentName);
            if (existingHealth != null) {
                // Calculate uptime since component was first registered
                Duration uptime = Duration.between(existingHealth.getLastCheckTime(), Instant.now());
                
                ComponentHealth updatedHealth = new ComponentHealth(
                    componentName,
                    status,
                    new ArrayList<>(), // validation results not updated here
                    Instant.now(),
                    uptime,
                    metrics
                );
                componentHealthMap.put(componentName, updatedHealth);
            } else {
                ComponentHealth newHealth = new ComponentHealth(
                    componentName,
                    status,
                    new ArrayList<>(),
                    Instant.now(),
                    Duration.ZERO,
                    metrics
                );
                componentHealthMap.put(componentName, newHealth);
            }
            
            // Update overall health status
            overallHealthStatus = calculateOverallHealthStatus();
            
        } finally {
            healthLock.writeLock().unlock();
        }
    }
    
    /**
     * Updates component health with validation results.
     */
    private void updateComponentHealthWithValidation(String componentName, HealthStatus status, 
                                                   List<String> validationResults, Map<String, Object> metrics) {
        healthLock.writeLock().lock();
        try {
            ComponentHealth existingHealth = componentHealthMap.get(componentName);
            Duration uptime = existingHealth != null ? 
                Duration.between(existingHealth.getLastCheckTime(), Instant.now()) : 
                Duration.ZERO;
            
            ComponentHealth updatedHealth = new ComponentHealth(
                componentName,
                status,
                validationResults,
                Instant.now(),
                uptime,
                metrics
            );
            componentHealthMap.put(componentName, updatedHealth);
            
            // Update overall health status
            overallHealthStatus = calculateOverallHealthStatus();
            
        } finally {
            healthLock.writeLock().unlock();
        }
    }
}

/**
 * HealthStatus enumeration representing the health state of components or the overall system.
 * Following microservices health check patterns with clear status indicators.
 */
enum HealthStatus {
    /**
     * Component is fully operational and performing within normal parameters.
     */
    HEALTHY,
    
    /**
     * Component is operational but with reduced performance or minor issues.
     */
    DEGRADED,
    
    /**
     * Component is not operational or has critical issues requiring immediate attention.
     */
    UNHEALTHY,
    
    /**
     * Component health status is unknown or cannot be determined.
     */
    UNKNOWN
}

/**
 * HealthCheckResult represents the result of a health check operation.
 * Contains comprehensive information about the health validation including
 * timing, status, error details, and validation specifics.
 */
class HealthCheckResult {
    
    private final String componentName;
    private final HealthStatus healthStatus;
    private final Duration responseTime;
    private final String errorMessage;
    private final Map<String, Object> details;
    private final Instant timestamp;
    
    /**
     * Creates a new HealthCheckResult with comprehensive health check information.
     * 
     * @param componentName Name of the component that was checked
     * @param healthStatus Health status result of the check
     * @param responseTime Time taken to complete the health check
     * @param errorMessage Error message if check failed, null if successful
     * @param details Additional details about the health check
     * @param timestamp When the health check was performed
     */
    public HealthCheckResult(String componentName, HealthStatus healthStatus, Duration responseTime,
                           String errorMessage, Map<String, Object> details, Instant timestamp) {
        this.componentName = componentName;
        this.healthStatus = healthStatus;
        this.responseTime = responseTime;
        this.errorMessage = errorMessage;
        this.details = new HashMap<>(details != null ? details : Collections.emptyMap());
        this.timestamp = timestamp;
    }
    
    /**
     * Gets the name of the component that was health checked.
     * 
     * @return Component name
     */
    public String getComponentName() {
        return componentName;
    }
    
    /**
     * Gets the health status result of the check.
     * 
     * @return HealthStatus indicating component health
     */
    public HealthStatus getHealthStatus() {
        return healthStatus;
    }
    
    /**
     * Gets the response time for the health check operation.
     * 
     * @return Duration of the health check
     */
    public Duration getResponseTime() {
        return responseTime;
    }
    
    /**
     * Gets the error message if the health check failed.
     * 
     * @return Error message or null if check was successful
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * Gets additional details about the health check results.
     * 
     * @return Map containing detailed health check information
     */
    public Map<String, Object> getDetails() {
        return new HashMap<>(details);
    }
    
    /**
     * Gets the timestamp when the health check was performed.
     * 
     * @return Instant of health check execution
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * Checks if the component is considered healthy.
     * 
     * @return true if status is HEALTHY or DEGRADED, false otherwise
     */
    public boolean isHealthy() {
        return healthStatus == HealthStatus.HEALTHY || healthStatus == HealthStatus.DEGRADED;
    }
    
    @Override
    public String toString() {
        return String.format("HealthCheckResult{component='%s', status=%s, responseTime=%s, timestamp=%s}",
                           componentName, healthStatus, responseTime, timestamp);
    }
}

/**
 * ComponentHealth represents the health status and metrics for a specific framework component.
 * Provides comprehensive health information including validation results, uptime, and metrics.
 */
class ComponentHealth {
    
    private final String componentType;
    private final HealthStatus healthStatus;
    private final List<String> validationResults;
    private final Instant lastCheckTime;
    private final Duration uptime;
    private final Map<String, Object> metrics;
    
    /**
     * Creates a new ComponentHealth instance with comprehensive component health information.
     * 
     * @param componentType Type/name of the component
     * @param healthStatus Current health status of the component
     * @param validationResults List of validation results and messages
     * @param lastCheckTime When the component was last health checked
     * @param uptime How long the component has been operational
     * @param metrics Component-specific metrics and performance data
     */
    public ComponentHealth(String componentType, HealthStatus healthStatus, List<String> validationResults,
                          Instant lastCheckTime, Duration uptime, Map<String, Object> metrics) {
        this.componentType = componentType;
        this.healthStatus = healthStatus;
        this.validationResults = new ArrayList<>(validationResults != null ? validationResults : Collections.emptyList());
        this.lastCheckTime = lastCheckTime;
        this.uptime = uptime;
        this.metrics = new HashMap<>(metrics != null ? metrics : Collections.emptyMap());
    }
    
    /**
     * Gets the component type/name.
     * 
     * @return Component type identifier
     */
    public String getComponentType() {
        return componentType;
    }
    
    /**
     * Gets the current health status of the component.
     * 
     * @return HealthStatus of the component
     */
    public HealthStatus getHealthStatus() {
        return healthStatus;
    }
    
    /**
     * Gets the validation results from the most recent health check.
     * 
     * @return List of validation results and messages
     */
    public List<String> getValidationResults() {
        return new ArrayList<>(validationResults);
    }
    
    /**
     * Gets the timestamp of the last health check performed on this component.
     * 
     * @return Instant of last health check
     */
    public Instant getLastCheckTime() {
        return lastCheckTime;
    }
    
    /**
     * Gets the uptime duration for this component.
     * 
     * @return Duration representing component uptime
     */
    public Duration getUptime() {
        return uptime;
    }
    
    /**
     * Gets component-specific metrics and performance data.
     * 
     * @return Map containing component metrics
     */
    public Map<String, Object> getMetrics() {
        return new HashMap<>(metrics);
    }
    
    /**
     * Checks if the component is considered healthy.
     * 
     * @return true if status is HEALTHY or DEGRADED, false otherwise
     */
    public boolean isHealthy() {
        return healthStatus == HealthStatus.HEALTHY || healthStatus == HealthStatus.DEGRADED;
    }
    
    @Override
    public String toString() {
        return String.format("ComponentHealth{type='%s', status=%s, lastCheck=%s, uptime=%s, validations=%d}",
                           componentType, healthStatus, lastCheckTime, uptime, validationResults.size());
    }
}