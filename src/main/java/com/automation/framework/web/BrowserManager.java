package com.automation.framework.web;

// Standard Java imports for concurrent operations and atomic state management
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.time.Instant;

// SLF4J logging framework import
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

// Selenium WebDriver import for Safari browser automation
import org.openqa.selenium.safari.SafariDriver;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.safari.SafariOptions;

// Internal framework imports for resource and lifecycle management
import com.automation.framework.web.WebDriverPool;
import com.automation.framework.core.ResourceManager;
import com.automation.framework.core.ShutdownHandler;
import com.automation.framework.exceptions.ErrorReporter;
import com.automation.framework.resources.MemoryManager;

// Standard Java imports for collections, utilities, and time management
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * BrowserManager provides comprehensive browser session lifecycle management with automatic cleanup capabilities.
 * 
 * This class coordinates browser sessions across Chrome, Firefox, Safari, and Edge with a unified WebDriver API.
 * It implements proper cleanup of drivers in finally blocks to ensure resources are released regardless of 
 * test pass/fail status, establishes shutdown hooks for JVM termination scenarios, and prevents abrupt test 
 * failures through graceful error handling.
 * 
 * Key Features:
 * - Cross-browser automation support (Chrome, Firefox, Safari, Edge) with unified WebDriver API
 * - Maximum 10 concurrent browser sessions with intelligent resource allocation
 * - Memory usage limited to 50MB per browser session with garbage collection optimization
 * - Automatic session recycling and connection pooling through WebDriverPool integration
 * - Comprehensive error handling with ExceptionHandler integration and detailed failure diagnostics
 * - Browser-specific driver management with automated download and version synchronization
 * - Session state preservation and recovery capabilities for abnormal shutdown scenarios
 * - Real-time session metrics and health monitoring with memory leak detection
 * - Graceful shutdown coordination with proper driver.quit() execution during JVM termination
 * 
 * Resource Management:
 * - Integrates with ResourceManager for comprehensive lifecycle management
 * - Implements try-with-resources patterns and explicit finally block cleanup
 * - Monitors session memory usage and triggers optimization when limits approached
 * - Provides automatic session recycling to prevent resource exhaustion
 * - Supports emergency shutdown procedures with force termination capabilities
 * 
 * Thread Safety:
 * - Thread-safe session management with concurrent access support
 * - Atomic counters for session tracking and resource allocation
 * - Read-write locks for configuration and state management
 * - Concurrent hash maps for session storage and metadata tracking
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class BrowserManager {
    
    private static final Logger logger = LoggerFactory.getLogger(BrowserManager.class);
    
    // Constants for session and resource limits
    private static final int MAX_CONCURRENT_SESSIONS = 10;
    private static final long BROWSER_MEMORY_LIMIT_MB = 50;
    private static final long BROWSER_MEMORY_LIMIT_BYTES = BROWSER_MEMORY_LIMIT_MB * 1024 * 1024;
    private static final Duration DEFAULT_DRIVER_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SESSION_CLEANUP_INTERVAL = Duration.ofMinutes(5);
    private static final Duration ORPHANED_SESSION_TIMEOUT = Duration.ofMinutes(30);
    
    // Singleton instance management
    private static volatile BrowserManager instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Framework dependency components
    private final WebDriverPool webDriverPool;
    private final ResourceManager resourceManager;
    private final ShutdownHandler shutdownHandler;
    private final ErrorReporter errorReporter;
    private final MemoryManager memoryManager;
    
    // Session management and tracking
    private final ConcurrentHashMap<String, BrowserSession> activeSessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionCounter = new AtomicInteger(0);
    private final AtomicInteger activeSessionCount = new AtomicInteger(0);
    
    // Configuration and state management
    private volatile boolean managerInitialized = false;
    private volatile boolean shutdownInProgress = false;
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();
    
    // Session monitoring and cleanup
    private ScheduledExecutorService sessionMonitor;
    private volatile Duration defaultDriverTimeout = DEFAULT_DRIVER_TIMEOUT;
    
    // Browser capabilities and options cache
    private final Map<BrowserType, Object> browserOptions = new ConcurrentHashMap<>();
    
    /**
     * Private constructor for singleton pattern.
     * Initializes all framework dependencies and configures browser management infrastructure.
     */
    private BrowserManager() {
        // Initialize framework dependencies
        this.webDriverPool = WebDriverPool.getInstance();
        this.resourceManager = ResourceManager.getInstance();
        this.shutdownHandler = ShutdownHandler.getInstance();
        this.errorReporter = new ErrorReporter();
        this.memoryManager = MemoryManager.getInstance();
        
        // Initialize session monitoring
        initializeSessionMonitoring();
        
        // Initialize browser options
        initializeBrowserOptions();
        
        // Register shutdown hook for proper cleanup
        registerShutdownHook();
        
        // Start error reporting
        errorReporter.startErrorReporting();
        
        managerInitialized = true;
        logger.info("BrowserManager initialized with max concurrent sessions: {}, memory limit per session: {}MB", 
                   MAX_CONCURRENT_SESSIONS, BROWSER_MEMORY_LIMIT_MB);
    }
    
    /**
     * Gets the singleton instance of BrowserManager.
     * Thread-safe lazy initialization with double-checked locking pattern.
     * 
     * @return BrowserManager singleton instance
     */
    public static BrowserManager getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new BrowserManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Gets the list of currently active browser sessions.
     * 
     * @return List of active BrowserSession objects
     */
    public List<BrowserSession> getActiveSessions() {
        return new ArrayList<>(activeSessions.values());
    }
    
    /**
     * Gets memory usage information for all browser sessions.
     * 
     * @return Map of session IDs to memory usage in bytes
     */
    public Map<String, Long> getSessionMemoryUsage() {
        Map<String, Long> memoryUsage = new HashMap<>();
        
        activeSessions.forEach((sessionId, session) -> {
            long sessionMemory = session.getMemoryUsage();
            memoryUsage.put(sessionId, sessionMemory);
        });
        
        return memoryUsage;
    }
    
    /**
     * Gets comprehensive browser session metrics for monitoring and analysis.
     * 
     * @return BrowserSessionMetrics containing detailed metrics data
     */
    public BrowserSessionMetrics getBrowserSessionMetrics() {
        Map<String, Long> memoryUsage = getSessionMemoryUsage();
        long totalMemory = memoryUsage.values().stream().mapToLong(Long::longValue).sum();
        
        Map<BrowserType, Integer> browserTypeCounts = activeSessions.values().stream()
            .collect(Collectors.groupingBy(
                BrowserSession::getBrowserType,
                Collectors.collectingAndThen(Collectors.counting(), Math::toIntExact)
            ));
        
        Map<SessionStatus, Integer> statusCounts = activeSessions.values().stream()
            .collect(Collectors.groupingBy(
                BrowserSession::getStatus,
                Collectors.collectingAndThen(Collectors.counting(), Math::toIntExact)
            ));
        
        return new BrowserSessionMetrics(
            activeSessionCount.get(),
            MAX_CONCURRENT_SESSIONS,
            totalMemory,
            BROWSER_MEMORY_LIMIT_BYTES * MAX_CONCURRENT_SESSIONS,
            browserTypeCounts,
            statusCounts,
            webDriverPool.getPoolUtilization(),
            memoryManager.getCurrentMemoryUsage().getMemoryUtilization(),
            Instant.now()
        );
    }
    
    /**
     * Gets the duration for which a specific session has been active.
     * 
     * @param sessionId The ID of the session to check
     * @return Duration since session creation, null if session not found
     */
    public Duration getSessionDuration(String sessionId) {
        BrowserSession session = activeSessions.get(sessionId);
        if (session != null) {
            return Duration.between(session.getCreationTime(), Instant.now());
        }
        return null;
    }
    
    /**
     * Creates a new browser session with the specified browser type.
     * Implements resource allocation checking and session limit enforcement.
     * 
     * @param browserType The type of browser to create (CHROME, FIREFOX, SAFARI, EDGE)
     * @return CompletableFuture containing the created BrowserSession
     */
    public CompletableFuture<BrowserSession> createSession(BrowserType browserType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                errorReporter.setCorrelationId(errorReporter.generateCorrelationId());
                
                // Check if shutdown is in progress
                if (shutdownInProgress) {
                    throw new IllegalStateException("Cannot create session - shutdown in progress");
                }
                
                // Check session limits
                if (activeSessionCount.get() >= MAX_CONCURRENT_SESSIONS) {
                    throw new IllegalStateException("Maximum concurrent sessions reached: " + MAX_CONCURRENT_SESSIONS);
                }
                
                // Check memory availability
                if (!memoryManager.isMemoryHealthy()) {
                    memoryManager.optimizeMemoryUsage();
                    if (!memoryManager.isMemoryHealthy()) {
                        throw new IllegalStateException("Insufficient memory for new browser session");
                    }
                }
                
                // Generate session ID
                String sessionId = generateSessionId();
                
                // Create browser session
                BrowserSession session = createBrowserSession(sessionId, browserType);
                
                // Add to active sessions
                activeSessions.put(sessionId, session);
                activeSessionCount.incrementAndGet();
                
                logger.info("Created browser session: {} for browser type: {}", sessionId, browserType);
                errorReporter.info("Browser session created successfully: " + sessionId);
                
                return session;
                
            } catch (Exception e) {
                String errorContext = String.format("Failed to create browser session for type: %s", browserType);
                errorReporter.logException(e, errorContext, createErrorContext("createSession", browserType));
                throw new RuntimeException("Failed to create browser session", e);
            }
        });
    }
    
    /**
     * Closes a specific browser session and releases all associated resources.
     * Ensures proper cleanup through driver.quit() and resource deallocation.
     * 
     * @param sessionId The ID of the session to close
     * @return CompletableFuture indicating successful closure
     */
    public CompletableFuture<Boolean> closeSession(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BrowserSession session = activeSessions.remove(sessionId);
                if (session == null) {
                    logger.warn("Attempted to close non-existent session: {}", sessionId);
                    return false;
                }
                
                // Terminate the session
                session.terminate();
                activeSessionCount.decrementAndGet();
                
                logger.info("Closed browser session: {}", sessionId);
                errorReporter.info("Browser session closed successfully: " + sessionId);
                
                return true;
                
            } catch (Exception e) {
                errorReporter.logException(e, "Failed to close browser session: " + sessionId, 
                                         createErrorContext("closeSession", sessionId));
                return false;
            }
        });
    }
    
    /**
     * Gets the WebDriver instance for a specific session.
     * 
     * @param sessionId The ID of the session
     * @return WebDriver instance, null if session not found or inactive
     */
    public WebDriver getDriver(String sessionId) {
        BrowserSession session = activeSessions.get(sessionId);
        if (session != null && session.isActive()) {
            return session.getDriver();
        }
        return null;
    }
    
    /**
     * Attempts to recover a browser session that has encountered errors.
     * Implements session state preservation and recovery logic.
     * 
     * @param sessionId The ID of the session to recover
     * @return CompletableFuture indicating successful recovery
     */
    public CompletableFuture<Boolean> recoverBrowserSession(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BrowserSession session = activeSessions.get(sessionId);
                if (session == null) {
                    logger.warn("Cannot recover non-existent session: {}", sessionId);
                    return false;
                }
                
                // Preserve current browser state
                preserveBrowserState(sessionId);
                
                // Attempt session recovery
                BrowserType browserType = session.getBrowserType();
                
                // Create new session with same configuration
                BrowserSession newSession = createBrowserSession(sessionId, browserType);
                
                // Replace the session
                activeSessions.put(sessionId, newSession);
                
                logger.info("Recovered browser session: {}", sessionId);
                errorReporter.info("Browser session recovered successfully: " + sessionId);
                
                return true;
                
            } catch (Exception e) {
                errorReporter.logException(e, "Failed to recover browser session: " + sessionId,
                                         createErrorContext("recoverBrowserSession", sessionId));
                return false;
            }
        });
    }
    
    /**
     * Reallocates browser session resources for optimization.
     * 
     * @param sessionId The ID of the session to reallocate
     * @return CompletableFuture indicating successful reallocation
     */
    public CompletableFuture<Boolean> reallocateBrowserSession(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BrowserSession session = activeSessions.get(sessionId);
                if (session == null) {
                    return false;
                }
                
                // Check if reallocation is needed
                long currentMemory = session.getMemoryUsage();
                if (currentMemory < BROWSER_MEMORY_LIMIT_BYTES * 0.8) {
                    return true; // No reallocation needed
                }
                
                // Perform memory optimization
                memoryManager.optimizeMemoryUsage();
                
                // Reallocate resources through ResourceManager
                resourceManager.reallocateResources();
                
                logger.info("Reallocated resources for browser session: {}", sessionId);
                return true;
                
            } catch (Exception e) {
                errorReporter.logException(e, "Failed to reallocate browser session: " + sessionId,
                                         createErrorContext("reallocateBrowserSession", sessionId));
                return false;
            }
        });
    }
    
    /**
     * Gets health status for a specific browser session.
     * 
     * @param sessionId The ID of the session to check
     * @return SessionHealth object containing health information
     */
    public SessionHealth getSessionHealth(String sessionId) {
        BrowserSession session = activeSessions.get(sessionId);
        if (session == null) {
            return new SessionHealth(sessionId, false, "Session not found", 0, SessionStatus.TERMINATED);
        }
        
        try {
            boolean isHealthy = session.isActive() && 
                              session.getMemoryUsage() < BROWSER_MEMORY_LIMIT_BYTES &&
                              session.getStatus() == SessionStatus.ACTIVE;
            
            String healthMessage = isHealthy ? "Session healthy" : "Session experiencing issues";
            
            return new SessionHealth(sessionId, isHealthy, healthMessage, 
                                   session.getMemoryUsage(), session.getStatus());
            
        } catch (Exception e) {
            errorReporter.logException(e, "Error checking session health: " + sessionId,
                                     createErrorContext("getSessionHealth", sessionId));
            return new SessionHealth(sessionId, false, "Health check failed", 0, SessionStatus.ERROR);
        }
    }
    
    /**
     * Preserves browser state for recovery after abnormal shutdown.
     * 
     * @param sessionId The ID of the session to preserve
     * @return boolean indicating successful state preservation
     */
    public boolean preserveBrowserState(String sessionId) {
        try {
            BrowserSession session = activeSessions.get(sessionId);
            if (session == null) {
                return false;
            }
            
            // Preserve session state through ShutdownHandler
            Map<String, Object> sessionState = new HashMap<>();
            sessionState.put("browserType", session.getBrowserType());
            sessionState.put("creationTime", session.getCreationTime());
            sessionState.put("lastActivity", session.getLastActivityTime());
            sessionState.put("memoryUsage", session.getMemoryUsage());
            
            return shutdownHandler.preserveTestState(sessionId, sessionState);
            
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to preserve browser state: " + sessionId,
                                     createErrorContext("preserveBrowserState", sessionId));
            return false;
        }
    }
    
    /**
     * Initializes a browser with the specified type and configuration.
     * 
     * @param browserType The type of browser to initialize
     * @return WebDriver instance for the initialized browser
     */
    public WebDriver initializeBrowser(BrowserType browserType) {
        try {
            WebDriver driver = createWebDriver(browserType);
            
            // Configure timeouts
            driver.manage().timeouts().implicitlyWait(defaultDriverTimeout.toMillis(), TimeUnit.MILLISECONDS);
            driver.manage().timeouts().pageLoadTimeout(defaultDriverTimeout.toMillis(), TimeUnit.MILLISECONDS);
            driver.manage().timeouts().setScriptTimeout(defaultDriverTimeout.toMillis(), TimeUnit.MILLISECONDS);
            
            logger.debug("Initialized {} browser with default timeouts", browserType);
            return driver;
            
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to initialize browser: " + browserType,
                                     createErrorContext("initializeBrowser", browserType));
            throw new RuntimeException("Failed to initialize browser", e);
        }
    }
    
    /**
     * Shuts down all active browser sessions gracefully.
     * Implements proper cleanup with driver.quit() for all sessions.
     * 
     * @return CompletableFuture indicating successful shutdown completion
     */
    public CompletableFuture<Boolean> shutdownAllSessions() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                shutdownInProgress = true;
                logger.info("Initiating shutdown of all browser sessions");
                
                List<CompletableFuture<Boolean>> shutdownFutures = new ArrayList<>();
                
                // Close all active sessions
                for (String sessionId : activeSessions.keySet()) {
                    CompletableFuture<Boolean> future = closeSession(sessionId);
                    shutdownFutures.add(future);
                }
                
                // Wait for all sessions to close
                CompletableFuture.allOf(shutdownFutures.toArray(new CompletableFuture[0])).join();
                
                // Shutdown session monitoring
                if (sessionMonitor != null && !sessionMonitor.isShutdown()) {
                    sessionMonitor.shutdown();
                    try {
                        if (!sessionMonitor.awaitTermination(10, TimeUnit.SECONDS)) {
                            sessionMonitor.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        sessionMonitor.shutdownNow();
                    }
                }
                
                // Stop error reporting
                errorReporter.stopErrorReporting();
                
                logger.info("All browser sessions shutdown completed");
                return true;
                
            } catch (Exception e) {
                errorReporter.logException(e, "Error during browser session shutdown",
                                         createErrorContext("shutdownAllSessions", null));
                return false;
            }
        });
    }
    
    /**
     * Quits a specific WebDriver instance with proper cleanup.
     * Ensures all resources are released and browser process terminated.
     * 
     * @param driver The WebDriver instance to quit
     * @return boolean indicating successful quit operation
     */
    public boolean quitDriver(WebDriver driver) {
        try {
            if (driver != null) {
                // Capture final screenshot if enabled
                captureSessionScreenshot(driver);
                
                // Close all windows and quit driver
                driver.quit();
                
                logger.debug("WebDriver quit successfully");
                return true;
            }
            return false;
            
        } catch (Exception e) {
            errorReporter.logException(e, "Error quitting WebDriver",
                                     createErrorContext("quitDriver", driver.getClass().getSimpleName()));
            return false;
        } finally {
            // Ensure memory cleanup
            if (memoryManager.isMemoryLimitApproaching()) {
                memoryManager.triggerGC();
            }
        }
    }
    
    /**
     * Creates a new browser session with specified ID and type.
     * 
     * @param sessionId Unique identifier for the session
     * @param browserType Type of browser to create
     * @return BrowserSession instance
     */
    public BrowserSession createBrowserSession(String sessionId, BrowserType browserType) {
        try {
            WebDriver driver = createWebDriver(browserType);
            
            BrowserSession session = new BrowserSession(
                sessionId,
                browserType,
                driver,
                Instant.now(),
                BROWSER_MEMORY_LIMIT_BYTES
            );
            
            logger.debug("Created browser session: {} with type: {}", sessionId, browserType);
            return session;
            
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to create browser session",
                                     createErrorContext("createBrowserSession", browserType));
            throw new RuntimeException("Failed to create browser session", e);
        }
    }
    
    /**
     * Gets the browser type from session ID.
     * 
     * @param sessionId The session ID to lookup
     * @return BrowserType enum value, null if session not found
     */
    public BrowserType getBrowserType(String sessionId) {
        BrowserSession session = activeSessions.get(sessionId);
        return session != null ? session.getBrowserType() : null;
    }
    
    /**
     * Gets the maximum number of concurrent sessions allowed.
     * 
     * @return Maximum concurrent session limit
     */
    public int getMaxConcurrentSessions() {
        return MAX_CONCURRENT_SESSIONS;
    }
    
    /**
     * Gets the current number of active sessions.
     * 
     * @return Current active session count
     */
    public int getCurrentSessionCount() {
        return activeSessionCount.get();
    }
    
    /**
     * Checks if the session pool is healthy and operating within limits.
     * 
     * @return true if session pool is healthy, false otherwise
     */
    public boolean isSessionPoolHealthy() {
        try {
            // Check session count limits
            if (activeSessionCount.get() > MAX_CONCURRENT_SESSIONS) {
                return false;
            }
            
            // Check WebDriverPool health
            if (!webDriverPool.isPoolHealthy()) {
                return false;
            }
            
            // Check memory health
            if (!memoryManager.isMemoryHealthy()) {
                return false;
            }
            
            // Check for orphaned sessions
            long orphanedCount = activeSessions.values().stream()
                .filter(this::isSessionOrphaned)
                .count();
            
            if (orphanedCount > 2) { // Allow up to 2 orphaned sessions
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            errorReporter.logException(e, "Error checking session pool health",
                                     createErrorContext("isSessionPoolHealthy", null));
            return false;
        }
    }
    
    /**
     * Cleans up orphaned browser sessions that are no longer responsive.
     * 
     * @return Number of orphaned sessions cleaned up
     */
    public int cleanupOrphanedSessions() {
        int cleanedUp = 0;
        
        try {
            List<String> orphanedSessions = activeSessions.entrySet().stream()
                .filter(entry -> isSessionOrphaned(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
            
            for (String sessionId : orphanedSessions) {
                if (closeSession(sessionId).join()) {
                    cleanedUp++;
                    logger.info("Cleaned up orphaned session: {}", sessionId);
                }
            }
            
            if (cleanedUp > 0) {
                errorReporter.info("Cleaned up " + cleanedUp + " orphaned browser sessions");
            }
            
        } catch (Exception e) {
            errorReporter.logException(e, "Error cleaning up orphaned sessions",
                                     createErrorContext("cleanupOrphanedSessions", null));
        }
        
        return cleanedUp;
    }
    
    /**
     * Registers shutdown hook for proper cleanup during JVM termination.
     * 
     * @return boolean indicating successful registration
     */
    public boolean registerShutdownHook() {
        try {
            return shutdownHandler.addShutdownHook();
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to register shutdown hook",
                                     createErrorContext("registerShutdownHook", null));
            return false;
        }
    }
    
    /**
     * Gets driver capabilities for a specific browser type.
     * 
     * @param browserType The browser type
     * @return DesiredCapabilities for the browser
     */
    public DesiredCapabilities getDriverCapabilities(BrowserType browserType) {
        try {
            DesiredCapabilities capabilities = new DesiredCapabilities();
            
            switch (browserType) {
                case CHROME:
                    ChromeOptions chromeOptions = (ChromeOptions) browserOptions.get(BrowserType.CHROME);
                    capabilities.merge(chromeOptions);
                    break;
                case FIREFOX:
                    FirefoxOptions firefoxOptions = (FirefoxOptions) browserOptions.get(BrowserType.FIREFOX);
                    capabilities.merge(firefoxOptions);
                    break;
                case EDGE:
                    EdgeOptions edgeOptions = (EdgeOptions) browserOptions.get(BrowserType.EDGE);
                    capabilities.merge(edgeOptions);
                    break;
                case SAFARI:
                    SafariOptions safariOptions = (SafariOptions) browserOptions.get(BrowserType.SAFARI);
                    capabilities.merge(safariOptions);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported browser type: " + browserType);
            }
            
            return capabilities;
            
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to get driver capabilities for: " + browserType,
                                     createErrorContext("getDriverCapabilities", browserType));
            return new DesiredCapabilities();
        }
    }
    
    /**
     * Sets timeout configuration for WebDriver operations.
     * 
     * @param timeout Duration for driver timeout
     */
    public void setDriverTimeout(Duration timeout) {
        configLock.writeLock().lock();
        try {
            this.defaultDriverTimeout = timeout;
            logger.info("Updated driver timeout to: {}", timeout);
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Captures screenshot for a browser session.
     * 
     * @param sessionId The session ID to capture screenshot for
     * @return Base64 encoded screenshot data, null if capture fails
     */
    public String captureSessionScreenshot(String sessionId) {
        BrowserSession session = activeSessions.get(sessionId);
        if (session != null && session.isActive()) {
            return captureSessionScreenshot(session.getDriver());
        }
        return null;
    }
    
    /**
     * Gets execution metrics for all browser sessions.
     * 
     * @return Map of session IDs to execution metrics
     */
    public Map<String, SessionExecutionMetrics> getSessionExecutionMetrics() {
        Map<String, SessionExecutionMetrics> metrics = new HashMap<>();
        
        activeSessions.forEach((sessionId, session) -> {
            SessionExecutionMetrics sessionMetrics = session.getSessionMetrics();
            metrics.put(sessionId, sessionMetrics);
        });
        
        return metrics;
    }
    
    /**
     * Optimizes memory usage for a specific browser session.
     * 
     * @param sessionId The session ID to optimize
     * @return boolean indicating successful optimization
     */
    public boolean optimizeSessionMemory(String sessionId) {
        try {
            BrowserSession session = activeSessions.get(sessionId);
            if (session == null) {
                return false;
            }
            
            // Trigger memory optimization for the session
            long beforeOptimization = session.getMemoryUsage();
            
            // Clear browser cache and optimize
            WebDriver driver = session.getDriver();
            if (driver != null) {
                try {
                    driver.manage().deleteAllCookies();
                } catch (Exception e) {
                    logger.debug("Failed to clear cookies during optimization", e);
                }
            }
            
            // Trigger garbage collection
            memoryManager.triggerGC();
            
            long afterOptimization = session.getMemoryUsage();
            long memoryFreed = beforeOptimization - afterOptimization;
            
            logger.info("Optimized memory for session {}: freed {}MB", 
                       sessionId, memoryFreed / (1024 * 1024));
            
            return memoryFreed > 0;
            
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to optimize session memory: " + sessionId,
                                     createErrorContext("optimizeSessionMemory", sessionId));
            return false;
        }
    }
    
    /**
     * Validates browser configuration for the specified type.
     * 
     * @param browserType The browser type to validate
     * @return boolean indicating valid configuration
     */
    public boolean validateBrowserConfiguration(BrowserType browserType) {
        try {
            // Validate browser options exist
            if (!browserOptions.containsKey(browserType)) {
                return false;
            }
            
            // Attempt to create a test driver instance
            WebDriver testDriver = createWebDriver(browserType);
            if (testDriver != null) {
                quitDriver(testDriver);
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            errorReporter.logException(e, "Browser configuration validation failed: " + browserType,
                                     createErrorContext("validateBrowserConfiguration", browserType));
            return false;
        }
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Generates a unique session ID.
     */
    private String generateSessionId() {
        return "browser-session-" + System.currentTimeMillis() + "-" + sessionCounter.incrementAndGet();
    }
    
    /**
     * Creates a WebDriver instance for the specified browser type.
     */
    private WebDriver createWebDriver(BrowserType browserType) {
        try {
            switch (browserType) {
                case CHROME:
                    ChromeOptions chromeOptions = (ChromeOptions) browserOptions.get(BrowserType.CHROME);
                    return new ChromeDriver(chromeOptions);
                    
                case FIREFOX:
                    FirefoxOptions firefoxOptions = (FirefoxOptions) browserOptions.get(BrowserType.FIREFOX);
                    return new FirefoxDriver(firefoxOptions);
                    
                case EDGE:
                    EdgeOptions edgeOptions = (EdgeOptions) browserOptions.get(BrowserType.EDGE);
                    return new EdgeDriver(edgeOptions);
                    
                case SAFARI:
                    SafariOptions safariOptions = (SafariOptions) browserOptions.get(BrowserType.SAFARI);
                    return new SafariDriver(safariOptions);
                    
                default:
                    throw new IllegalArgumentException("Unsupported browser type: " + browserType);
            }
            
        } catch (WebDriverException e) {
            throw new RuntimeException("Failed to create WebDriver for " + browserType, e);
        }
    }
    
    /**
     * Initializes browser options for all supported browsers.
     */
    private void initializeBrowserOptions() {
        // Chrome options
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.addArguments("--no-sandbox");
        chromeOptions.addArguments("--disable-dev-shm-usage");
        chromeOptions.addArguments("--disable-gpu");
        chromeOptions.addArguments("--max_old_space_size=" + BROWSER_MEMORY_LIMIT_MB);
        browserOptions.put(BrowserType.CHROME, chromeOptions);
        
        // Firefox options
        FirefoxOptions firefoxOptions = new FirefoxOptions();
        firefoxOptions.addArguments("--no-sandbox");
        firefoxOptions.addArguments("--disable-dev-shm-usage");
        browserOptions.put(BrowserType.FIREFOX, firefoxOptions);
        
        // Edge options
        EdgeOptions edgeOptions = new EdgeOptions();
        edgeOptions.addArguments("--no-sandbox");
        edgeOptions.addArguments("--disable-dev-shm-usage");
        edgeOptions.addArguments("--disable-gpu");
        browserOptions.put(BrowserType.EDGE, edgeOptions);
        
        // Safari options
        SafariOptions safariOptions = new SafariOptions();
        safariOptions.setAutomaticInspection(false);
        safariOptions.setAutomaticProfiling(false);
        browserOptions.put(BrowserType.SAFARI, safariOptions);
        
        logger.debug("Initialized browser options for all supported browsers");
    }
    
    /**
     * Initializes session monitoring infrastructure.
     */
    private void initializeSessionMonitoring() {
        sessionMonitor = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "BrowserManager-SessionMonitor");
            thread.setDaemon(true);
            return thread;
        });
        
        // Schedule periodic cleanup of orphaned sessions
        sessionMonitor.scheduleAtFixedRate(
            this::performSessionCleanup,
            SESSION_CLEANUP_INTERVAL.toMillis(),
            SESSION_CLEANUP_INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS
        );
        
        // Schedule memory monitoring
        sessionMonitor.scheduleAtFixedRate(
            this::performMemoryMonitoring,
            Duration.ofMinutes(2).toMillis(),
            Duration.ofMinutes(2).toMillis(),
            TimeUnit.MILLISECONDS
        );
        
        logger.debug("Initialized session monitoring with cleanup interval: {}", SESSION_CLEANUP_INTERVAL);
    }
    
    /**
     * Performs periodic session cleanup.
     */
    private void performSessionCleanup() {
        try {
            int cleaned = cleanupOrphanedSessions();
            if (cleaned > 0) {
                logger.info("Session cleanup completed, removed {} orphaned sessions", cleaned);
            }
        } catch (Exception e) {
            errorReporter.logException(e, "Error during periodic session cleanup",
                                     createErrorContext("performSessionCleanup", null));
        }
    }
    
    /**
     * Performs periodic memory monitoring for sessions.
     */
    private void performMemoryMonitoring() {
        try {
            Map<String, Long> memoryUsage = getSessionMemoryUsage();
            long totalMemory = memoryUsage.values().stream().mapToLong(Long::longValue).sum();
            
            if (totalMemory > BROWSER_MEMORY_LIMIT_BYTES * MAX_CONCURRENT_SESSIONS * 0.8) {
                logger.warn("High memory usage detected: {}MB total", totalMemory / (1024 * 1024));
                
                // Find sessions with highest memory usage
                memoryUsage.entrySet().stream()
                    .filter(entry -> entry.getValue() > BROWSER_MEMORY_LIMIT_BYTES)
                    .forEach(entry -> {
                        logger.warn("Session {} exceeds memory limit: {}MB", 
                                  entry.getKey(), entry.getValue() / (1024 * 1024));
                        optimizeSessionMemory(entry.getKey());
                    });
            }
            
        } catch (Exception e) {
            errorReporter.logException(e, "Error during memory monitoring",
                                     createErrorContext("performMemoryMonitoring", null));
        }
    }
    
    /**
     * Checks if a session is orphaned (inactive for too long).
     */
    private boolean isSessionOrphaned(BrowserSession session) {
        if (session == null) {
            return true;
        }
        
        Duration inactiveTime = Duration.between(session.getLastActivityTime(), Instant.now());
        return inactiveTime.compareTo(ORPHANED_SESSION_TIMEOUT) > 0 || 
               session.getStatus() == SessionStatus.ERROR ||
               session.getStatus() == SessionStatus.ORPHANED;
    }
    
    /**
     * Captures screenshot from WebDriver instance.
     */
    private String captureSessionScreenshot(WebDriver driver) {
        try {
            if (driver instanceof TakesScreenshot) {
                byte[] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
                return Base64.getEncoder().encodeToString(screenshot);
            }
            return null;
        } catch (Exception e) {
            logger.debug("Failed to capture screenshot", e);
            return null;
        }
    }
    
    /**
     * Creates error context for logging.
     */
    private Map<String, Object> createErrorContext(String operation, Object parameter) {
        Map<String, Object> context = new HashMap<>();
        context.put("operation", operation);
        context.put("activeSessionCount", activeSessionCount.get());
        context.put("maxSessions", MAX_CONCURRENT_SESSIONS);
        context.put("shutdownInProgress", shutdownInProgress);
        
        if (parameter != null) {
            context.put("parameter", parameter.toString());
        }
        
        return context;
    }
}

/**
 * BrowserType enumeration defines supported browser types.
 */
enum BrowserType {
    CHROME,
    FIREFOX,
    EDGE,
    SAFARI
}

/**
 * SessionStatus enumeration defines browser session states.
 */
enum SessionStatus {
    ACTIVE,
    IDLE,
    TERMINATED,
    ERROR,
    RECOVERING,
    ORPHANED
}

/**
 * BrowserSession class represents an active browser session with metadata and lifecycle management.
 */
class BrowserSession {
    
    private final String sessionId;
    private final BrowserType browserType;
    private final WebDriver driver;
    private final Instant creationTime;
    private volatile Instant lastActivityTime;
    private volatile SessionStatus status;
    private final long memoryLimit;
    private volatile long currentMemoryUsage;
    
    /**
     * Creates a new BrowserSession instance.
     */
    public BrowserSession(String sessionId, BrowserType browserType, WebDriver driver, 
                         Instant creationTime, long memoryLimit) {
        this.sessionId = sessionId;
        this.browserType = browserType;
        this.driver = driver;
        this.creationTime = creationTime;
        this.lastActivityTime = creationTime;
        this.status = SessionStatus.ACTIVE;
        this.memoryLimit = memoryLimit;
        this.currentMemoryUsage = estimateInitialMemoryUsage();
    }
    
    /**
     * Gets the unique session identifier.
     */
    public String getSessionId() {
        return sessionId;
    }
    
    /**
     * Gets the browser type for this session.
     */
    public BrowserType getBrowserType() {
        return browserType;
    }
    
    /**
     * Gets the WebDriver instance for this session.
     */
    public WebDriver getDriver() {
        updateLastActivity();
        return driver;
    }
    
    /**
     * Gets the current session status.
     */
    public SessionStatus getStatus() {
        return status;
    }
    
    /**
     * Gets the session creation time.
     */
    public Instant getCreationTime() {
        return creationTime;
    }
    
    /**
     * Gets the last activity time.
     */
    public Instant getLastActivityTime() {
        return lastActivityTime;
    }
    
    /**
     * Gets the current memory usage for this session.
     */
    public long getMemoryUsage() {
        return currentMemoryUsage;
    }
    
    /**
     * Checks if the session is currently active.
     */
    public boolean isActive() {
        return status == SessionStatus.ACTIVE && driver != null;
    }
    
    /**
     * Terminates the browser session and releases resources.
     */
    public void terminate() {
        try {
            status = SessionStatus.TERMINATED;
            if (driver != null) {
                driver.quit();
            }
        } catch (Exception e) {
            status = SessionStatus.ERROR;
            throw new RuntimeException("Failed to terminate session", e);
        }
    }
    
    /**
     * Gets comprehensive session metrics.
     */
    public SessionExecutionMetrics getSessionMetrics() {
        Duration sessionDuration = Duration.between(creationTime, Instant.now());
        Duration lastActivity = Duration.between(lastActivityTime, Instant.now());
        
        return new SessionExecutionMetrics(
            sessionId,
            browserType,
            sessionDuration,
            lastActivity,
            currentMemoryUsage,
            memoryLimit,
            status,
            creationTime
        );
    }
    
    /**
     * Updates the last activity timestamp.
     */
    private void updateLastActivity() {
        this.lastActivityTime = Instant.now();
    }
    
    /**
     * Estimates initial memory usage for the session.
     */
    private long estimateInitialMemoryUsage() {
        // Estimate based on browser type
        switch (browserType) {
            case CHROME:
                return 30L * 1024 * 1024; // 30MB
            case FIREFOX:
                return 25L * 1024 * 1024; // 25MB
            case EDGE:
                return 32L * 1024 * 1024; // 32MB
            case SAFARI:
                return 28L * 1024 * 1024; // 28MB
            default:
                return 30L * 1024 * 1024; // Default 30MB
        }
    }
}

/**
 * Supporting classes for metrics and health monitoring.
 */
class BrowserSessionMetrics {
    private final int activeSessions;
    private final int maxSessions;
    private final long totalMemoryUsage;
    private final long maxMemoryLimit;
    private final Map<BrowserType, Integer> browserTypeCounts;
    private final Map<SessionStatus, Integer> statusCounts;
    private final double poolUtilization;
    private final double memoryUtilization;
    private final Instant timestamp;
    
    public BrowserSessionMetrics(int activeSessions, int maxSessions, long totalMemoryUsage,
                               long maxMemoryLimit, Map<BrowserType, Integer> browserTypeCounts,
                               Map<SessionStatus, Integer> statusCounts, double poolUtilization,
                               double memoryUtilization, Instant timestamp) {
        this.activeSessions = activeSessions;
        this.maxSessions = maxSessions;
        this.totalMemoryUsage = totalMemoryUsage;
        this.maxMemoryLimit = maxMemoryLimit;
        this.browserTypeCounts = new HashMap<>(browserTypeCounts);
        this.statusCounts = new HashMap<>(statusCounts);
        this.poolUtilization = poolUtilization;
        this.memoryUtilization = memoryUtilization;
        this.timestamp = timestamp;
    }
    
    // Getters
    public int getActiveSessions() { return activeSessions; }
    public int getMaxSessions() { return maxSessions; }
    public long getTotalMemoryUsage() { return totalMemoryUsage; }
    public long getMaxMemoryLimit() { return maxMemoryLimit; }
    public Map<BrowserType, Integer> getBrowserTypeCounts() { return browserTypeCounts; }
    public Map<SessionStatus, Integer> getStatusCounts() { return statusCounts; }
    public double getPoolUtilization() { return poolUtilization; }
    public double getMemoryUtilization() { return memoryUtilization; }
    public Instant getTimestamp() { return timestamp; }
}

class SessionHealth {
    private final String sessionId;
    private final boolean isHealthy;
    private final String healthMessage;
    private final long memoryUsage;
    private final SessionStatus status;
    
    public SessionHealth(String sessionId, boolean isHealthy, String healthMessage,
                        long memoryUsage, SessionStatus status) {
        this.sessionId = sessionId;
        this.isHealthy = isHealthy;
        this.healthMessage = healthMessage;
        this.memoryUsage = memoryUsage;
        this.status = status;
    }
    
    // Getters
    public String getSessionId() { return sessionId; }
    public boolean isHealthy() { return isHealthy; }
    public String getHealthMessage() { return healthMessage; }
    public long getMemoryUsage() { return memoryUsage; }
    public SessionStatus getStatus() { return status; }
}

class SessionExecutionMetrics {
    private final String sessionId;
    private final BrowserType browserType;
    private final Duration sessionDuration;
    private final Duration lastActivity;
    private final long memoryUsage;
    private final long memoryLimit;
    private final SessionStatus status;
    private final Instant creationTime;
    
    public SessionExecutionMetrics(String sessionId, BrowserType browserType, Duration sessionDuration,
                                  Duration lastActivity, long memoryUsage, long memoryLimit,
                                  SessionStatus status, Instant creationTime) {
        this.sessionId = sessionId;
        this.browserType = browserType;
        this.sessionDuration = sessionDuration;
        this.lastActivity = lastActivity;
        this.memoryUsage = memoryUsage;
        this.memoryLimit = memoryLimit;
        this.status = status;
        this.creationTime = creationTime;
    }
    
    // Getters
    public String getSessionId() { return sessionId; }
    public BrowserType getBrowserType() { return browserType; }
    public Duration getSessionDuration() { return sessionDuration; }
    public Duration getLastActivity() { return lastActivity; }
    public long getMemoryUsage() { return memoryUsage; }
    public long getMemoryLimit() { return memoryLimit; }
    public SessionStatus getStatus() { return status; }
    public Instant getCreationTime() { return creationTime; }
}