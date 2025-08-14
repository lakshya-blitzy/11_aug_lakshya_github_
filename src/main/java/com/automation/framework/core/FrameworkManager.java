package com.automation.framework.core;

// External imports for testing framework support
import org.testng.TestNG;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.ITestContext;

// External imports for WebDriver management
import org.openqa.selenium.support.ui.WebDriverWait;

// External imports for concurrent operations and asynchronous execution
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// External imports for JVM shutdown hook management
import java.lang.Runtime;

// External imports for structured logging and audit trail maintenance
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Internal imports from framework dependencies
import com.automation.framework.core.ConfigurationManager;
import com.automation.framework.core.ResourceManager;

// Standard Java imports for collections and utilities
import java.util.*;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * FrameworkManager serves as the central orchestration hub for the entire automation framework.
 * 
 * This class provides comprehensive test execution coordination, module registration, and lifecycle
 * management capabilities. It implements the Command pattern for test execution and Observer pattern
 * for event management, ensuring loose coupling between testing modules while maintaining centralized
 * control over the entire automation ecosystem.
 * 
 * Key Features:
 * - Framework initialization and shutdown coordination (< 5 seconds initialization SLA)
 * - Module registration and dependency management for web and API testing modules
 * - Test execution orchestration with support for up to 10 concurrent browser sessions
 * - API request coordination with support for up to 50 concurrent requests
 * - Comprehensive error recovery with three-tier recovery system (component, test, suite levels)
 * - JVM shutdown hooks for graceful termination during unexpected scenarios
 * - Real-time monitoring and observability integration
 * - Resource cleanup coordination with automatic leak detection
 * 
 * The FrameworkManager follows the Singleton pattern to ensure consistent orchestration
 * across the entire automation framework and integrates with ConfigurationManager and
 * ResourceManager for comprehensive lifecycle management.
 * 
 * Error Recovery Hierarchy:
 * 1. Component Level: Automatic retry mechanisms for transient failures
 * 2. Test Level: State isolation and continuation decision logic  
 * 3. Suite Level: Graceful degradation and emergency shutdown procedures
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class FrameworkManager implements IFrameworkManager {
    
    private static final Logger logger = LoggerFactory.getLogger(FrameworkManager.class);
    
    // Singleton instance management with thread-safe initialization
    private static volatile FrameworkManager instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Core framework components for orchestration and resource management
    private final ConfigurationManager configurationManager;
    private final ResourceManager resourceManager;
    
    // Framework state management with atomic operations for thread safety
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private final AtomicReference<FrameworkStatus> currentStatus = new AtomicReference<>(FrameworkStatus.INACTIVE);
    
    // Module registry for framework component management
    private final ConcurrentHashMap<String, Object> registeredModules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ModuleConfiguration> moduleConfigurations = new ConcurrentHashMap<>();
    
    // Observer pattern implementation for event management
    private final List<FrameworkObserver> observers = Collections.synchronizedList(new ArrayList<>());
    
    // Command pattern implementation for test execution coordination
    private final ConcurrentHashMap<String, FrameworkCommand> commandRegistry = new ConcurrentHashMap<>();
    private final Queue<FrameworkCommand> commandQueue = new LinkedList<>();
    
    // Execution management with configurable thread pools
    private ExecutorService executionExecutor;
    private ExecutorService observerExecutor;
    private final AtomicReference<TestNG> testNGInstance = new AtomicReference<>();
    
    // Performance monitoring and metrics collection
    private final FrameworkMetrics metrics = new FrameworkMetrics();
    private final AtomicReference<Instant> initializationStartTime = new AtomicReference<>();
    private final AtomicReference<Duration> lastInitializationDuration = new AtomicReference<>(Duration.ZERO);
    
    // Error recovery and shutdown coordination
    private final ReentrantReadWriteLock frameworkLock = new ReentrantReadWriteLock();
    private final List<Runnable> shutdownHooks = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean emergencyShutdownTriggered = new AtomicBoolean(false);
    
    /**
     * Private constructor for singleton pattern.
     * Initializes core framework components and sets up default configuration.
     */
    private FrameworkManager() {
        this.configurationManager = ConfigurationManager.getInstance();
        this.resourceManager = ResourceManager.getInstance();
        
        // Register JVM shutdown hook for graceful termination
        registerJvmShutdownHook();
        
        logger.info("FrameworkManager created with singleton pattern");
    }
    
    /**
     * Gets the singleton instance of FrameworkManager.
     * Thread-safe lazy initialization with double-checked locking pattern.
     * 
     * @return FrameworkManager singleton instance
     */
    public static FrameworkManager getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new FrameworkManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Initializes the entire automation framework with comprehensive startup sequence.
     * Ensures framework initialization completes within the 5-second SLA requirement.
     * Implements hierarchical error recovery for initialization failures.
     * 
     * @return CompletableFuture<Boolean> indicating successful initialization
     */
    public CompletableFuture<Boolean> initialize() {
        if (initialized.compareAndSet(false, true)) {
            initializationStartTime.set(Instant.now());
            currentStatus.set(FrameworkStatus.INITIALIZING);
            
            logger.info("Starting framework initialization sequence");
            
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Phase 1: Configuration validation and loading
                    if (!initializeConfiguration()) {
                        logger.error("Configuration initialization failed");
                        initialized.set(false);
                        currentStatus.set(FrameworkStatus.ERROR);
                        return false;
                    }
                    
                    // Phase 2: Resource management initialization
                    if (!initializeResourceManagement()) {
                        logger.error("Resource management initialization failed");
                        initialized.set(false);
                        currentStatus.set(FrameworkStatus.ERROR);
                        return false;
                    }
                    
                    // Phase 3: Execution infrastructure setup
                    if (!initializeExecutionInfrastructure()) {
                        logger.error("Execution infrastructure initialization failed");
                        initialized.set(false);
                        currentStatus.set(FrameworkStatus.ERROR);
                        return false;
                    }
                    
                    // Phase 4: Default module registration
                    if (!registerDefaultModules()) {
                        logger.error("Default module registration failed");
                        initialized.set(false);
                        currentStatus.set(FrameworkStatus.ERROR);
                        return false;
                    }
                    
                    // Phase 5: Monitoring and observability setup
                    if (!initializeMonitoring()) {
                        logger.error("Monitoring initialization failed");
                        initialized.set(false);
                        currentStatus.set(FrameworkStatus.ERROR);
                        return false;
                    }
                    
                    // Calculate initialization duration and verify SLA compliance
                    Duration initDuration = Duration.between(initializationStartTime.get(), Instant.now());
                    lastInitializationDuration.set(initDuration);
                    
                    if (initDuration.toMillis() > 5000) {
                        logger.warn("Framework initialization exceeded 5-second SLA: {}ms", initDuration.toMillis());
                    }
                    
                    currentStatus.set(FrameworkStatus.READY);
                    notifyObservers(FrameworkEvent.INITIALIZATION_COMPLETE);
                    
                    logger.info("Framework initialization completed successfully in {}ms", initDuration.toMillis());
                    return true;
                    
                } catch (Exception e) {
                    logger.error("Exception during framework initialization", e);
                    initialized.set(false);
                    currentStatus.set(FrameworkStatus.ERROR);
                    notifyObservers(FrameworkEvent.INITIALIZATION_FAILED);
                    return false;
                }
            }).exceptionally(throwable -> {
                logger.error("Asynchronous initialization failure", throwable);
                initialized.set(false);
                currentStatus.set(FrameworkStatus.ERROR);
                notifyObservers(FrameworkEvent.INITIALIZATION_FAILED);
                return false;
            });
        } else {
            logger.debug("Framework already initialized or initialization in progress");
            return CompletableFuture.completedFuture(true);
        }
    }
    
    /**
     * Registers a framework module for lifecycle management and coordination.
     * Supports web automation modules, API testing modules, and custom extensions.
     * Implements validation and dependency checking for module registration.
     * 
     * @param moduleId Unique identifier for the module
     * @param module Module instance to register
     * @param configuration Module-specific configuration parameters
     * @return boolean indicating successful module registration
     */
    @Override
    public boolean registerModule(String moduleId, Object module, Map<String, Object> configuration) {
        if (moduleId == null || moduleId.trim().isEmpty()) {
            logger.warn("Cannot register module with null or empty ID");
            return false;
        }
        
        if (module == null) {
            logger.warn("Cannot register null module with ID: {}", moduleId);
            return false;
        }
        
        frameworkLock.writeLock().lock();
        try {
            // Validate module prerequisites
            if (!validateModulePrerequisites(moduleId, module)) {
                logger.error("Module prerequisites validation failed for: {}", moduleId);
                return false;
            }
            
            // Create module configuration
            ModuleConfiguration moduleConfig = new ModuleConfiguration(moduleId, module.getClass().getName(), configuration);
            
            // Register module and configuration
            Object existingModule = registeredModules.put(moduleId, module);
            moduleConfigurations.put(moduleId, moduleConfig);
            
            if (existingModule != null) {
                logger.warn("Replaced existing module registration for ID: {}", moduleId);
            }
            
            // Initialize module if framework is already initialized
            if (initialized.get()) {
                try {
                    initializeModule(moduleId, module, moduleConfig);
                } catch (Exception e) {
                    logger.error("Failed to initialize newly registered module: {}", moduleId, e);
                    registeredModules.remove(moduleId);
                    moduleConfigurations.remove(moduleId);
                    return false;
                }
            }
            
            logger.info("Successfully registered module: {} of type: {}", moduleId, module.getClass().getSimpleName());
            notifyObservers(FrameworkEvent.MODULE_REGISTERED);
            return true;
            
        } catch (Exception e) {
            logger.error("Exception during module registration for: {}", moduleId, e);
            return false;
        } finally {
            frameworkLock.writeLock().unlock();
        }
    }
    
    /**
     * Executes a framework command using the Command pattern implementation.
     * Supports both synchronous and asynchronous execution modes with timeout handling.
     * Implements three-tier error recovery for command execution failures.
     * 
     * @param command FrameworkCommand to execute
     * @return CompletableFuture<CommandResult> with execution results
     */
    @Override
    public CompletableFuture<CommandResult> executeCommand(FrameworkCommand command) {
        if (command == null) {
            logger.error("Cannot execute null command");
            return CompletableFuture.completedFuture(
                new CommandResult(false, "Command cannot be null", null));
        }
        
        if (!initialized.get()) {
            logger.error("Cannot execute command - framework not initialized");
            return CompletableFuture.completedFuture(
                new CommandResult(false, "Framework not initialized", null));
        }
        
        return CompletableFuture.supplyAsync(() -> {
            String commandId = command.getCommandId();
            Instant startTime = Instant.now();
            
            try {
                logger.debug("Executing command: {} of type: {}", commandId, command.getClass().getSimpleName());
                
                // Component-level error recovery
                CommandResult result = executeCommandWithRetry(command);
                
                Duration executionTime = Duration.between(startTime, Instant.now());
                metrics.recordCommandExecution(commandId, executionTime, result.isSuccess());
                
                if (result.isSuccess()) {
                    logger.debug("Command executed successfully: {} in {}ms", commandId, executionTime.toMillis());
                } else {
                    logger.warn("Command execution failed: {} after {}ms - {}", 
                              commandId, executionTime.toMillis(), result.getErrorMessage());
                }
                
                notifyObservers(result.isSuccess() ? FrameworkEvent.COMMAND_EXECUTED : FrameworkEvent.COMMAND_FAILED);
                return result;
                
            } catch (Exception e) {
                Duration executionTime = Duration.between(startTime, Instant.now());
                logger.error("Exception during command execution: {} after {}ms", commandId, executionTime.toMillis(), e);
                
                // Suite-level error recovery
                CommandResult errorResult = new CommandResult(false, "Command execution exception: " + e.getMessage(), null);
                metrics.recordCommandExecution(commandId, executionTime, false);
                notifyObservers(FrameworkEvent.COMMAND_FAILED);
                
                return errorResult;
            }
        }, executionExecutor).exceptionally(throwable -> {
            logger.error("Asynchronous command execution failure for: {}", command.getCommandId(), throwable);
            return new CommandResult(false, "Asynchronous execution failure: " + throwable.getMessage(), null);
        });
    }
    
    /**
     * Initiates graceful shutdown of the entire automation framework.
     * Implements ordered shutdown sequence with proper resource cleanup and timeout protection.
     * Coordinates with ResourceManager for comprehensive resource management.
     * 
     * @return CompletableFuture<Boolean> indicating successful shutdown completion
     */
    public CompletableFuture<Boolean> shutdown() {
        if (shutdownInProgress.compareAndSet(false, true)) {
            currentStatus.set(FrameworkStatus.SHUTTING_DOWN);
            logger.info("Initiating framework shutdown sequence");
            
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Phase 1: Stop accepting new commands and complete in-progress tests
                    stopCommandProcessing();
                    
                    // Phase 2: Notify all observers of shutdown initiation
                    notifyObservers(FrameworkEvent.SHUTDOWN_INITIATED);
                    
                    // Phase 3: Shutdown framework modules in reverse registration order
                    shutdownRegisteredModules();
                    
                    // Phase 4: Coordinate resource cleanup with ResourceManager
                    if (!resourceManager.releaseAllResources()) {
                        logger.warn("Resource cleanup reported issues during shutdown");
                    }
                    
                    // Phase 5: Shutdown execution infrastructure
                    shutdownExecutionInfrastructure();
                    
                    // Phase 6: Final cleanup and state reset
                    performFinalCleanup();
                    
                    currentStatus.set(FrameworkStatus.SHUTDOWN);
                    initialized.set(false);
                    
                    logger.info("Framework shutdown completed successfully");
                    return true;
                    
                } catch (Exception e) {
                    logger.error("Exception during framework shutdown", e);
                    currentStatus.set(FrameworkStatus.ERROR);
                    return false;
                } finally {
                    shutdownInProgress.set(false);
                }
            }).exceptionally(throwable -> {
                logger.error("Asynchronous shutdown failure", throwable);
                currentStatus.set(FrameworkStatus.ERROR);
                shutdownInProgress.set(false);
                return false;
            });
        } else {
            logger.debug("Shutdown already in progress");
            return CompletableFuture.completedFuture(false);
        }
    }
    
    /**
     * Adds an observer for framework event notifications using the Observer pattern.
     * Supports real-time monitoring and integration with external systems.
     * 
     * @param observer FrameworkObserver to register for event notifications
     * @return boolean indicating successful observer registration
     */
    @Override
    public boolean addObserver(FrameworkObserver observer) {
        if (observer == null) {
            logger.warn("Cannot add null observer");
            return false;
        }
        
        synchronized (observers) {
            if (observers.contains(observer)) {
                logger.debug("Observer already registered: {}", observer.getClass().getSimpleName());
                return true;
            }
            
            try {
                observers.add(observer);
                logger.debug("Added observer: {}", observer.getClass().getSimpleName());
                return true;
            } catch (Exception e) {
                logger.error("Error adding observer", e);
                return false;
            }
        }
    }
    
    /**
     * Gets the current status of the automation framework.
     * Provides real-time status information for monitoring and coordination.
     * 
     * @return FrameworkStatus indicating current operational state
     */
    public FrameworkStatus getStatus() {
        return currentStatus.get();
    }
    
    /**
     * Gets the configuration manager instance for framework settings access.
     * Provides access to configuration management capabilities for modules.
     * 
     * @return ConfigurationManager instance
     */
    @Override
    public ConfigurationManager getConfiguration() {
        return configurationManager;
    }
    
    /**
     * Gets the resource manager instance for resource lifecycle management.
     * Provides access to resource management capabilities for modules.
     * 
     * @return ResourceManager instance
     */
    @Override
    public ResourceManager getResourceManager() {
        return resourceManager;
    }
    
    /**
     * Removes an observer from framework event notifications.
     * 
     * @param observer FrameworkObserver to remove from event notifications
     * @return boolean indicating successful observer removal
     */
    public boolean removeObserver(FrameworkObserver observer) {
        if (observer == null) {
            logger.warn("Cannot remove null observer");
            return false;
        }
        
        synchronized (observers) {
            try {
                boolean removed = observers.remove(observer);
                if (removed) {
                    logger.debug("Removed observer: {}", observer.getClass().getSimpleName());
                } else {
                    logger.debug("Observer not found for removal: {}", observer.getClass().getSimpleName());
                }
                return removed;
            } catch (Exception e) {
                logger.error("Error removing observer", e);
                return false;
            }
        }
    }
    
    /**
     * Gets comprehensive framework metrics for monitoring and performance analysis.
     * 
     * @return FrameworkMetrics containing current performance data
     */
    public FrameworkMetrics getFrameworkMetrics() {
        return metrics;
    }
    
    /**
     * Gets the count of currently registered modules.
     * 
     * @return Number of registered framework modules
     */
    public int getRegisteredModuleCount() {
        return registeredModules.size();
    }
    
    /**
     * Gets a list of all registered module identifiers.
     * 
     * @return List of module IDs currently registered
     */
    public List<String> getRegisteredModuleIds() {
        return new ArrayList<>(registeredModules.keySet());
    }
    
    /**
     * Checks if the framework is currently initialized and ready for operations.
     * 
     * @return true if framework is initialized, false otherwise
     */
    public boolean isInitialized() {
        return initialized.get() && currentStatus.get() == FrameworkStatus.READY;
    }
    
    /**
     * Checks if framework shutdown is currently in progress.
     * 
     * @return true if shutdown is in progress, false otherwise
     */
    public boolean isShutdownInProgress() {
        return shutdownInProgress.get();
    }
    
    /**
     * Gets the duration of the last framework initialization.
     * 
     * @return Duration of last initialization or zero if never initialized
     */
    public Duration getLastInitializationDuration() {
        return lastInitializationDuration.get();
    }
    
    /**
     * Triggers emergency shutdown procedures for critical failure scenarios.
     * Bypasses normal shutdown sequence for immediate resource cleanup.
     * 
     * @return boolean indicating emergency shutdown completion
     */
    public boolean triggerEmergencyShutdown() {
        if (emergencyShutdownTriggered.compareAndSet(false, true)) {
            logger.warn("Triggering emergency shutdown procedures");
            
            try {
                // Immediate status change
                currentStatus.set(FrameworkStatus.CRITICAL);
                
                // Force resource cleanup
                resourceManager.forceResourceCleanup();
                
                // Force shutdown execution infrastructure
                if (executionExecutor != null) {
                    executionExecutor.shutdownNow();
                }
                if (observerExecutor != null) {
                    observerExecutor.shutdownNow();
                }
                
                // Clear module registry
                registeredModules.clear();
                moduleConfigurations.clear();
                
                currentStatus.set(FrameworkStatus.SHUTDOWN);
                initialized.set(false);
                shutdownInProgress.set(false);
                
                logger.warn("Emergency shutdown completed");
                return true;
                
            } catch (Exception e) {
                logger.error("Error during emergency shutdown", e);
                return false;
            }
        }
        
        logger.debug("Emergency shutdown already triggered or in progress");
        return false;
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Registers JVM shutdown hook for graceful framework termination.
     */
    private void registerJvmShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (initialized.get() && !shutdownInProgress.get()) {
                    logger.info("JVM shutdown detected - initiating graceful framework shutdown");
                    
                    try {
                        // Attempt graceful shutdown with timeout
                        CompletableFuture<Boolean> shutdownFuture = shutdown();
                        Boolean shutdownResult = shutdownFuture.get(30, TimeUnit.SECONDS);
                        
                        if (shutdownResult == null || !shutdownResult) {
                            logger.warn("Graceful shutdown failed or timed out - triggering emergency shutdown");
                            triggerEmergencyShutdown();
                        }
                    } catch (Exception e) {
                        logger.error("Error during JVM shutdown hook execution", e);
                        triggerEmergencyShutdown();
                    }
                }
            }, "FrameworkManager-ShutdownHook"));
            
            logger.debug("JVM shutdown hook registered successfully");
            
        } catch (Exception e) {
            logger.error("Failed to register JVM shutdown hook", e);
        }
    }
    
    /**
     * Initializes framework configuration with validation.
     */
    private boolean initializeConfiguration() {
        try {
            logger.debug("Initializing framework configuration");
            
            // Validate configuration
            if (!configurationManager.isConfigurationValid()) {
                logger.error("Configuration validation failed");
                return false;
            }
            
            // Set framework-specific properties
            configurationManager.setProperty("framework.status", currentStatus.get().toString());
            configurationManager.setProperty("framework.initialization.timestamp", Instant.now().toString());
            
            logger.debug("Framework configuration initialized successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error during configuration initialization", e);
            return false;
        }
    }
    
    /**
     * Initializes resource management subsystem.
     */
    private boolean initializeResourceManagement() {
        try {
            logger.debug("Initializing resource management");
            
            // Initialize resource pools and monitoring
            if (!resourceManager.initializeResources()) {
                logger.error("Resource manager initialization failed");
                return false;
            }
            
            // Start resource monitoring
            if (!resourceManager.startResourceMonitoring()) {
                logger.warn("Resource monitoring failed to start");
            }
            
            logger.debug("Resource management initialized successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error during resource management initialization", e);
            return false;
        }
    }
    
    /**
     * Initializes execution infrastructure including thread pools.
     */
    private boolean initializeExecutionInfrastructure() {
        try {
            logger.debug("Initializing execution infrastructure");
            
            // Create execution thread pool for command processing
            int executionThreads = Integer.parseInt(
                configurationManager.getPropertyWithDefault("framework.execution.threads", "5"));
            
            executionExecutor = Executors.newFixedThreadPool(executionThreads, r -> {
                Thread thread = new Thread(r, "FrameworkManager-Execution");
                thread.setDaemon(false);
                return thread;
            });
            
            // Create observer notification thread pool
            observerExecutor = Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "FrameworkManager-Observer");
                thread.setDaemon(true);
                return thread;
            });
            
            // Initialize TestNG instance for test coordination
            TestNG testNG = new TestNG();
            // Note: setParallel(String) was deprecated in TestNG 7.3.0 and removed in 7.4.0
            // Parallel execution should be configured through XML suite configuration
            testNG.setThreadCount(Integer.parseInt(
                configurationManager.getPropertyWithDefault("testng.thread.count", "10")));
            testNGInstance.set(testNG);
            
            logger.debug("Execution infrastructure initialized successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error during execution infrastructure initialization", e);
            return false;
        }
    }
    
    /**
     * Registers default framework modules.
     */
    private boolean registerDefaultModules() {
        try {
            logger.debug("Registering default framework modules");
            
            // Register core framework modules as needed
            // This method can be extended to register default web and API modules
            
            logger.debug("Default modules registered successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error during default module registration", e);
            return false;
        }
    }
    
    /**
     * Initializes monitoring and observability infrastructure.
     */
    private boolean initializeMonitoring() {
        try {
            logger.debug("Initializing monitoring infrastructure");
            
            // Initialize metrics collection
            metrics.initialize();
            
            // Start performance tracking
            metrics.startPerformanceTracking();
            
            logger.debug("Monitoring infrastructure initialized successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error during monitoring initialization", e);
            return false;
        }
    }
    
    /**
     * Validates module prerequisites before registration.
     */
    private boolean validateModulePrerequisites(String moduleId, Object module) {
        try {
            // Check for duplicate registrations
            if (registeredModules.containsKey(moduleId)) {
                logger.warn("Module ID already registered, will be replaced: {}", moduleId);
            }
            
            // Validate module interface compliance
            // This can be extended to check for specific interfaces
            
            return true;
            
        } catch (Exception e) {
            logger.error("Error validating module prerequisites for: {}", moduleId, e);
            return false;
        }
    }
    
    /**
     * Initializes a newly registered module.
     */
    private void initializeModule(String moduleId, Object module, ModuleConfiguration config) {
        try {
            logger.debug("Initializing module: {}", moduleId);
            
            // Module-specific initialization logic can be added here
            // This method can be extended to call module initialization methods
            
            logger.debug("Module initialized successfully: {}", moduleId);
            
        } catch (Exception e) {
            logger.error("Error initializing module: {}", moduleId, e);
            throw new RuntimeException("Module initialization failed: " + moduleId, e);
        }
    }
    
    /**
     * Executes a command with retry mechanism for component-level error recovery.
     */
    private CommandResult executeCommandWithRetry(FrameworkCommand command) {
        int maxRetries = Integer.parseInt(
            configurationManager.getPropertyWithDefault("framework.command.max.retries", "3"));
        
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                logger.debug("Executing command attempt {} of {}: {}", attempt, maxRetries, command.getCommandId());
                
                CommandResult result = command.execute();
                
                if (result.isSuccess()) {
                    if (attempt > 1) {
                        logger.info("Command succeeded on retry attempt {}: {}", attempt, command.getCommandId());
                    }
                    return result;
                }
                
                // Test-level error recovery decision
                if (shouldRetryCommand(command, result, attempt)) {
                    logger.warn("Command failed (attempt {}/{}), retrying: {} - {}", 
                              attempt, maxRetries, command.getCommandId(), result.getErrorMessage());
                    
                    // Progressive delay between retries
                    Thread.sleep(attempt * 1000L);
                    continue;
                } else {
                    logger.warn("Command failed and will not be retried: {}", command.getCommandId());
                    return result;
                }
                
            } catch (Exception e) {
                lastException = e;
                logger.warn("Command execution exception (attempt {}/{}): {} - {}", 
                          attempt, maxRetries, command.getCommandId(), e.getMessage());
                
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(attempt * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        // All retries exhausted
        String errorMessage = lastException != null ? 
            "Command failed after " + maxRetries + " attempts: " + lastException.getMessage() :
            "Command failed after " + maxRetries + " attempts";
            
        return new CommandResult(false, errorMessage, null);
    }
    
    /**
     * Determines if a command should be retried based on failure type and attempt count.
     */
    private boolean shouldRetryCommand(FrameworkCommand command, CommandResult result, int attempt) {
        // Get max retries configuration
        int maxRetries = Integer.parseInt(
            configurationManager.getPropertyWithDefault("framework.command.max.retries", "3"));
        
        if (attempt >= maxRetries) {
            return false;
        }
        
        // Don't retry certain types of failures
        String errorMessage = result.getErrorMessage();
        if (errorMessage != null) {
            String lowerError = errorMessage.toLowerCase();
            
            // Don't retry configuration or validation errors
            if (lowerError.contains("configuration") || 
                lowerError.contains("validation") ||
                lowerError.contains("authentication")) {
                return false;
            }
        }
        
        // Default: retry for transient failures
        return true;
    }
    
    /**
     * Notifies all registered observers of framework events.
     */
    private void notifyObservers(FrameworkEvent event) {
        if (observers.isEmpty()) {
            return;
        }
        
        // Asynchronous observer notification to prevent blocking
        if (observerExecutor != null && !observerExecutor.isShutdown()) {
            observerExecutor.submit(() -> {
                synchronized (observers) {
                    for (FrameworkObserver observer : observers) {
                        try {
                            observer.onFrameworkEvent(event);
                        } catch (Exception e) {
                            logger.warn("Observer notification failed for event: {} - {}", event, e.getMessage());
                        }
                    }
                }
            });
        }
    }
    
    /**
     * Stops command processing and completes in-progress commands.
     */
    private void stopCommandProcessing() {
        try {
            logger.debug("Stopping command processing");
            
            // Stop accepting new commands by clearing the command queue
            synchronized (commandQueue) {
                commandQueue.clear();
            }
            
            // Allow in-progress commands to complete
            if (executionExecutor != null) {
                executionExecutor.shutdown();
                
                if (!executionExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    logger.warn("Command processing did not terminate gracefully, forcing shutdown");
                    executionExecutor.shutdownNow();
                }
            }
            
            logger.debug("Command processing stopped");
            
        } catch (Exception e) {
            logger.error("Error stopping command processing", e);
        }
    }
    
    /**
     * Shuts down all registered modules in reverse registration order.
     */
    private void shutdownRegisteredModules() {
        try {
            logger.debug("Shutting down registered modules");
            
            // Get modules in reverse registration order for proper dependency cleanup
            List<String> moduleIds = new ArrayList<>(registeredModules.keySet());
            Collections.reverse(moduleIds);
            
            for (String moduleId : moduleIds) {
                try {
                    Object module = registeredModules.get(moduleId);
                    logger.debug("Shutting down module: {}", moduleId);
                    
                    // Module-specific shutdown logic can be added here
                    // This method can be extended to call module shutdown methods
                    
                } catch (Exception e) {
                    logger.warn("Error shutting down module: {}", moduleId, e);
                }
            }
            
            // Clear module registrations
            registeredModules.clear();
            moduleConfigurations.clear();
            
            logger.debug("Registered modules shutdown completed");
            
        } catch (Exception e) {
            logger.error("Error during module shutdown", e);
        }
    }
    
    /**
     * Shuts down execution infrastructure including thread pools.
     */
    private void shutdownExecutionInfrastructure() {
        try {
            logger.debug("Shutting down execution infrastructure");
            
            // Shutdown observer executor
            if (observerExecutor != null) {
                observerExecutor.shutdown();
                if (!observerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    observerExecutor.shutdownNow();
                }
                observerExecutor = null;
            }
            
            // Clear TestNG instance
            testNGInstance.set(null);
            
            logger.debug("Execution infrastructure shutdown completed");
            
        } catch (Exception e) {
            logger.error("Error during execution infrastructure shutdown", e);
        }
    }
    
    /**
     * Performs final cleanup of framework state and resources.
     */
    private void performFinalCleanup() {
        try {
            logger.debug("Performing final cleanup");
            
            // Clear observers
            synchronized (observers) {
                observers.clear();
            }
            
            // Clear command registry and queue
            commandRegistry.clear();
            synchronized (commandQueue) {
                commandQueue.clear();
            }
            
            // Execute registered shutdown hooks
            synchronized (shutdownHooks) {
                for (Runnable hook : shutdownHooks) {
                    try {
                        hook.run();
                    } catch (Exception e) {
                        logger.warn("Error executing shutdown hook", e);
                    }
                }
                shutdownHooks.clear();
            }
            
            // Reset state variables
            emergencyShutdownTriggered.set(false);
            
            logger.debug("Final cleanup completed");
            
        } catch (Exception e) {
            logger.error("Error during final cleanup", e);
        }
    }
}

/**
 * IFrameworkManager interface defines the core contract for framework management operations.
 * 
 * This interface provides the essential methods required for framework module registration,
 * command execution, observer management, and resource access. It supports loose coupling
 * between framework components while maintaining centralized coordination capabilities.
 * 
 * Implementing classes must provide thread-safe implementations of all interface methods
 * and ensure proper error handling and resource management across all operations.
 */
interface IFrameworkManager {
    
    /**
     * Registers a framework module for lifecycle management and coordination.
     * 
     * @param moduleId Unique identifier for the module
     * @param module Module instance to register
     * @param configuration Module-specific configuration parameters
     * @return boolean indicating successful module registration
     */
    boolean registerModule(String moduleId, Object module, Map<String, Object> configuration);
    
    /**
     * Executes a framework command using the Command pattern implementation.
     * 
     * @param command FrameworkCommand to execute
     * @return CompletableFuture<CommandResult> with execution results
     */
    CompletableFuture<CommandResult> executeCommand(FrameworkCommand command);
    
    /**
     * Adds an observer for framework event notifications using the Observer pattern.
     * 
     * @param observer FrameworkObserver to register for event notifications
     * @return boolean indicating successful observer registration
     */
    boolean addObserver(FrameworkObserver observer);
    
    /**
     * Gets the configuration manager instance for framework settings access.
     * 
     * @return ConfigurationManager instance
     */
    ConfigurationManager getConfiguration();
    
    /**
     * Gets the resource manager instance for resource lifecycle management.
     * 
     * @return ResourceManager instance
     */
    ResourceManager getResourceManager();
}

/**
 * FrameworkStatus enumeration represents the current operational state of the framework.
 * 
 * This enum provides standardized status values for tracking framework lifecycle
 * and coordinating operations across different framework components.
 */
enum FrameworkStatus {
    /**
     * Framework has not been initialized yet.
     */
    INACTIVE,
    
    /**
     * Framework is currently initializing and not yet ready for operations.
     */
    INITIALIZING,
    
    /**
     * Framework is fully initialized and ready for test execution.
     */
    READY,
    
    /**
     * Framework is currently executing tests or commands.
     */
    EXECUTING,
    
    /**
     * Framework is in the process of shutting down gracefully.
     */
    SHUTTING_DOWN,
    
    /**
     * Framework has been shut down and is no longer operational.
     */
    SHUTDOWN,
    
    /**
     * Framework has encountered a critical error and requires intervention.
     */
    CRITICAL,
    
    /**
     * Framework has encountered an error but may be recoverable.
     */
    ERROR
}

/**
 * FrameworkEvent enumeration represents different types of events that occur during framework operations.
 * 
 * This enum provides standardized event types for the Observer pattern implementation,
 * allowing external components to monitor and react to framework state changes.
 */
enum FrameworkEvent {
    /**
     * Framework initialization has completed successfully.
     */
    INITIALIZATION_COMPLETE,
    
    /**
     * Framework initialization has failed.
     */
    INITIALIZATION_FAILED,
    
    /**
     * A new module has been registered with the framework.
     */
    MODULE_REGISTERED,
    
    /**
     * A module has been unregistered from the framework.
     */
    MODULE_UNREGISTERED,
    
    /**
     * A command has been executed successfully.
     */
    COMMAND_EXECUTED,
    
    /**
     * A command execution has failed.
     */
    COMMAND_FAILED,
    
    /**
     * Framework shutdown has been initiated.
     */
    SHUTDOWN_INITIATED,
    
    /**
     * Framework shutdown has completed successfully.
     */
    SHUTDOWN_COMPLETE,
    
    /**
     * A critical error has occurred requiring immediate attention.
     */
    CRITICAL_ERROR,
    
    /**
     * Emergency shutdown procedures have been triggered.
     */
    EMERGENCY_SHUTDOWN
}

/**
 * FrameworkObserver interface defines the contract for observing framework events.
 * 
 * Classes implementing this interface can register with the FrameworkManager to receive
 * notifications about framework state changes and operational events.
 */
interface FrameworkObserver {
    
    /**
     * Called when a framework event occurs.
     * 
     * @param event The FrameworkEvent that occurred
     */
    void onFrameworkEvent(FrameworkEvent event);
}

/**
 * FrameworkCommand interface defines the contract for executable framework commands.
 * 
 * This interface implements the Command pattern, allowing encapsulation of framework
 * operations as objects that can be queued, executed, and monitored independently.
 */
interface FrameworkCommand {
    
    /**
     * Gets the unique identifier for this command.
     * 
     * @return Command ID string
     */
    String getCommandId();
    
    /**
     * Executes the command and returns the result.
     * 
     * @return CommandResult indicating execution outcome
     */
    CommandResult execute();
    
    /**
     * Gets the expected execution timeout for this command.
     * 
     * @return Duration representing maximum execution time
     */
    Duration getTimeout();
    
    /**
     * Checks if this command can be retried in case of failure.
     * 
     * @return true if command supports retry, false otherwise
     */
    boolean isRetryable();
}

/**
 * CommandResult class represents the outcome of framework command execution.
 * 
 * This class encapsulates the success status, error information, and result data
 * from command execution, supporting comprehensive error handling and result analysis.
 */
class CommandResult {
    
    private final boolean success;
    private final String errorMessage;
    private final Object resultData;
    private final Instant executionTime;
    private final Map<String, Object> metadata;
    
    /**
     * Creates a new CommandResult with execution outcome information.
     * 
     * @param success true if command executed successfully, false otherwise
     * @param errorMessage Error message if execution failed, null if successful
     * @param resultData Data returned by command execution, may be null
     */
    public CommandResult(boolean success, String errorMessage, Object resultData) {
        this.success = success;
        this.errorMessage = errorMessage;
        this.resultData = resultData;
        this.executionTime = Instant.now();
        this.metadata = new HashMap<>();
    }
    
    /**
     * Creates a new CommandResult with additional metadata.
     * 
     * @param success true if command executed successfully, false otherwise
     * @param errorMessage Error message if execution failed, null if successful
     * @param resultData Data returned by command execution, may be null
     * @param metadata Additional metadata about the execution
     */
    public CommandResult(boolean success, String errorMessage, Object resultData, Map<String, Object> metadata) {
        this.success = success;
        this.errorMessage = errorMessage;
        this.resultData = resultData;
        this.executionTime = Instant.now();
        this.metadata = new HashMap<>(metadata != null ? metadata : Collections.emptyMap());
    }
    
    /**
     * Gets the success status of the command execution.
     * 
     * @return true if execution was successful, false otherwise
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * Gets the error message if execution failed.
     * 
     * @return Error message string or null if execution was successful
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * Gets the result data returned by command execution.
     * 
     * @return Result data object or null if no data was returned
     */
    public Object getResultData() {
        return resultData;
    }
    
    /**
     * Gets the execution timestamp for this result.
     * 
     * @return Instant representing when the result was created
     */
    public Instant getExecutionTime() {
        return executionTime;
    }
    
    /**
     * Gets the metadata associated with this command result.
     * 
     * @return Unmodifiable map of metadata
     */
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
    
    /**
     * Adds metadata to this command result.
     * 
     * @param key Metadata key
     * @param value Metadata value
     */
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    @Override
    public String toString() {
        return String.format("CommandResult{success=%s, errorMessage='%s', executionTime=%s}", 
                           success, errorMessage, executionTime);
    }
}

/**
 * ModuleConfiguration class represents configuration data for registered framework modules.
 * 
 * This class encapsulates module-specific configuration parameters and metadata,
 * supporting dynamic module management and configuration validation.
 */
class ModuleConfiguration {
    
    private final String moduleId;
    private final String moduleClassName;
    private final Map<String, Object> configuration;
    private final Instant registrationTime;
    
    /**
     * Creates a new ModuleConfiguration instance.
     * 
     * @param moduleId Unique identifier for the module
     * @param moduleClassName Full class name of the module
     * @param configuration Module-specific configuration parameters
     */
    public ModuleConfiguration(String moduleId, String moduleClassName, Map<String, Object> configuration) {
        this.moduleId = moduleId;
        this.moduleClassName = moduleClassName;
        this.configuration = new HashMap<>(configuration != null ? configuration : Collections.emptyMap());
        this.registrationTime = Instant.now();
    }
    
    /**
     * Gets the module identifier.
     * 
     * @return Module ID string
     */
    public String getModuleId() {
        return moduleId;
    }
    
    /**
     * Gets the module class name.
     * 
     * @return Module class name string
     */
    public String getModuleClassName() {
        return moduleClassName;
    }
    
    /**
     * Gets the module configuration parameters.
     * 
     * @return Unmodifiable map of configuration parameters
     */
    public Map<String, Object> getConfiguration() {
        return Collections.unmodifiableMap(configuration);
    }
    
    /**
     * Gets the module registration timestamp.
     * 
     * @return Instant representing when the module was registered
     */
    public Instant getRegistrationTime() {
        return registrationTime;
    }
    
    /**
     * Gets a configuration value by key.
     * 
     * @param key Configuration parameter key
     * @return Configuration value or null if not found
     */
    public Object getConfigurationValue(String key) {
        return configuration.get(key);
    }
    
    /**
     * Gets a configuration value by key with a default value.
     * 
     * @param key Configuration parameter key
     * @param defaultValue Default value to return if key not found
     * @return Configuration value or default value
     */
    public Object getConfigurationValue(String key, Object defaultValue) {
        return configuration.getOrDefault(key, defaultValue);
    }
    
    @Override
    public String toString() {
        return String.format("ModuleConfiguration{moduleId='%s', className='%s', registrationTime=%s}", 
                           moduleId, moduleClassName, registrationTime);
    }
}

/**
 * FrameworkMetrics class provides comprehensive performance metrics for framework operations.
 * 
 * This class collects and maintains real-time performance statistics including
 * command execution metrics, module performance data, and resource utilization information.
 * It supports metrics export for integration with monitoring systems.
 */
class FrameworkMetrics {
    
    private static final Logger logger = LoggerFactory.getLogger(FrameworkMetrics.class);
    
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean trackingActive = new AtomicBoolean(false);
    
    // Command execution metrics
    private volatile long totalCommandsExecuted = 0;
    private volatile long successfulCommands = 0;
    private volatile long failedCommands = 0;
    private volatile double averageExecutionTime = 0.0;
    private volatile Duration totalExecutionTime = Duration.ZERO;
    
    // Framework performance metrics
    private volatile long frameworInitializations = 0;
    private volatile Duration averageInitializationTime = Duration.ZERO;
    private volatile long moduleRegistrations = 0;
    private volatile long observerNotifications = 0;
    
    // Tracking collections
    private final ConcurrentHashMap<String, CommandMetric> commandMetrics = new ConcurrentHashMap<>();
    private final Queue<Duration> recentExecutionTimes = new LinkedList<>();
    private final Object metricsLock = new Object();
    
    /**
     * Initializes the metrics collection system.
     */
    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            logger.debug("FrameworkMetrics initialized");
        }
    }
    
    /**
     * Starts performance tracking for real-time metrics collection.
     */
    public void startPerformanceTracking() {
        if (trackingActive.compareAndSet(false, true)) {
            logger.debug("Performance tracking started");
        }
    }
    
    /**
     * Stops performance tracking.
     */
    public void stopPerformanceTracking() {
        if (trackingActive.compareAndSet(true, false)) {
            logger.debug("Performance tracking stopped");
        }
    }
    
    /**
     * Records command execution metrics.
     * 
     * @param commandId Unique command identifier
     * @param executionTime Duration of command execution
     * @param success true if command was successful, false otherwise
     */
    public void recordCommandExecution(String commandId, Duration executionTime, boolean success) {
        if (!trackingActive.get()) {
            return;
        }
        
        synchronized (metricsLock) {
            totalCommandsExecuted++;
            if (success) {
                successfulCommands++;
            } else {
                failedCommands++;
            }
            
            // Update total execution time
            totalExecutionTime = totalExecutionTime.plus(executionTime);
            
            // Calculate average execution time
            averageExecutionTime = totalExecutionTime.toMillis() / (double) totalCommandsExecuted;
            
            // Update recent execution times for trend analysis
            recentExecutionTimes.offer(executionTime);
            if (recentExecutionTimes.size() > 100) {
                recentExecutionTimes.poll();
            }
            
            // Update command-specific metrics
            commandMetrics.compute(commandId, (key, metric) -> {
                if (metric == null) {
                    return new CommandMetric(commandId, executionTime, success);
                } else {
                    metric.update(executionTime, success);
                    return metric;
                }
            });
        }
    }
    
    /**
     * Records framework initialization metrics.
     * 
     * @param initializationTime Duration of framework initialization
     */
    public void recordInitialization(Duration initializationTime) {
        if (!trackingActive.get()) {
            return;
        }
        
        synchronized (metricsLock) {
            frameworInitializations++;
            
            // Calculate average initialization time
            Duration totalInitTime = averageInitializationTime.multipliedBy(frameworInitializations - 1)
                .plus(initializationTime);
            averageInitializationTime = totalInitTime.dividedBy(frameworInitializations);
        }
    }
    
    /**
     * Records module registration event.
     */
    public void recordModuleRegistration() {
        if (trackingActive.get()) {
            moduleRegistrations++;
        }
    }
    
    /**
     * Records observer notification event.
     */
    public void recordObserverNotification() {
        if (trackingActive.get()) {
            observerNotifications++;
        }
    }
    
    /**
     * Gets the total number of commands executed.
     * 
     * @return Total command count
     */
    public long getTotalCommandsExecuted() {
        return totalCommandsExecuted;
    }
    
    /**
     * Gets the number of successful commands.
     * 
     * @return Successful command count
     */
    public long getSuccessfulCommands() {
        return successfulCommands;
    }
    
    /**
     * Gets the number of failed commands.
     * 
     * @return Failed command count
     */
    public long getFailedCommands() {
        return failedCommands;
    }
    
    /**
     * Gets the command success rate as a percentage.
     * 
     * @return Success rate between 0.0 and 100.0
     */
    public double getSuccessRate() {
        if (totalCommandsExecuted == 0) {
            return 0.0;
        }
        return (successfulCommands / (double) totalCommandsExecuted) * 100.0;
    }
    
    /**
     * Gets the average command execution time in milliseconds.
     * 
     * @return Average execution time
     */
    public double getAverageExecutionTime() {
        return averageExecutionTime;
    }
    
    /**
     * Gets the average framework initialization time.
     * 
     * @return Average initialization duration
     */
    public Duration getAverageInitializationTime() {
        return averageInitializationTime;
    }
    
    /**
     * Gets the total number of module registrations.
     * 
     * @return Module registration count
     */
    public long getModuleRegistrations() {
        return moduleRegistrations;
    }
    
    /**
     * Gets the total number of observer notifications sent.
     * 
     * @return Observer notification count
     */
    public long getObserverNotifications() {
        return observerNotifications;
    }
    
    /**
     * Exports all metrics in a structured format.
     * 
     * @return Map containing all metrics data
     */
    public Map<String, Object> exportMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        synchronized (metricsLock) {
            metrics.put("totalCommandsExecuted", totalCommandsExecuted);
            metrics.put("successfulCommands", successfulCommands);
            metrics.put("failedCommands", failedCommands);
            metrics.put("successRate", getSuccessRate());
            metrics.put("averageExecutionTimeMs", averageExecutionTime);
            metrics.put("averageInitializationTimeMs", averageInitializationTime.toMillis());
            metrics.put("moduleRegistrations", moduleRegistrations);
            metrics.put("observerNotifications", observerNotifications);
            metrics.put("timestamp", Instant.now().toEpochMilli());
            metrics.put("trackingActive", trackingActive.get());
        }
        
        return metrics;
    }
    
    /**
     * Resets all metrics to initial values.
     */
    public void resetMetrics() {
        synchronized (metricsLock) {
            totalCommandsExecuted = 0;
            successfulCommands = 0;
            failedCommands = 0;
            averageExecutionTime = 0.0;
            totalExecutionTime = Duration.ZERO;
            frameworInitializations = 0;
            averageInitializationTime = Duration.ZERO;
            moduleRegistrations = 0;
            observerNotifications = 0;
            commandMetrics.clear();
            recentExecutionTimes.clear();
        }
        
        logger.debug("Framework metrics reset");
    }
    
    /**
     * Gets a formatted summary of all current metrics.
     * 
     * @return Formatted metrics summary string
     */
    public String getMetricsSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Framework Metrics Summary ===\n");
        summary.append("Total Commands Executed: ").append(totalCommandsExecuted).append("\n");
        summary.append("Successful Commands: ").append(successfulCommands).append("\n");
        summary.append("Failed Commands: ").append(failedCommands).append("\n");
        summary.append("Success Rate: ").append(String.format("%.2f%%", getSuccessRate())).append("\n");
        summary.append("Average Execution Time: ").append(String.format("%.2fms", averageExecutionTime)).append("\n");
        summary.append("Average Initialization Time: ").append(averageInitializationTime.toMillis()).append("ms\n");
        summary.append("Module Registrations: ").append(moduleRegistrations).append("\n");
        summary.append("Observer Notifications: ").append(observerNotifications).append("\n");
        summary.append("Tracking Active: ").append(trackingActive.get()).append("\n");
        return summary.toString();
    }
}

/**
 * CommandMetric class represents performance metrics for individual commands.
 * 
 * This class tracks execution statistics for specific command types,
 * enabling detailed performance analysis and optimization.
 */
class CommandMetric {
    
    private final String commandId;
    private volatile long executionCount = 0;
    private volatile long successCount = 0;
    private volatile Duration totalExecutionTime = Duration.ZERO;
    private volatile Duration minExecutionTime = Duration.ofMillis(Long.MAX_VALUE);
    private volatile Duration maxExecutionTime = Duration.ZERO;
    private final Instant firstExecution;
    private volatile Instant lastExecution;
    
    /**
     * Creates a new CommandMetric for the specified command.
     * 
     * @param commandId Unique command identifier
     * @param executionTime Initial execution time
     * @param success true if initial execution was successful
     */
    public CommandMetric(String commandId, Duration executionTime, boolean success) {
        this.commandId = commandId;
        this.firstExecution = Instant.now();
        update(executionTime, success);
    }
    
    /**
     * Updates the metrics with a new execution result.
     * 
     * @param executionTime Duration of the execution
     * @param success true if execution was successful
     */
    public void update(Duration executionTime, boolean success) {
        executionCount++;
        if (success) {
            successCount++;
        }
        
        totalExecutionTime = totalExecutionTime.plus(executionTime);
        
        if (executionTime.compareTo(minExecutionTime) < 0) {
            minExecutionTime = executionTime;
        }
        
        if (executionTime.compareTo(maxExecutionTime) > 0) {
            maxExecutionTime = executionTime;
        }
        
        lastExecution = Instant.now();
    }
    
    /**
     * Gets the command identifier.
     * 
     * @return Command ID string
     */
    public String getCommandId() {
        return commandId;
    }
    
    /**
     * Gets the total number of executions for this command.
     * 
     * @return Execution count
     */
    public long getExecutionCount() {
        return executionCount;
    }
    
    /**
     * Gets the success rate for this command as a percentage.
     * 
     * @return Success rate between 0.0 and 100.0
     */
    public double getSuccessRate() {
        if (executionCount == 0) {
            return 0.0;
        }
        return (successCount / (double) executionCount) * 100.0;
    }
    
    /**
     * Gets the average execution time for this command.
     * 
     * @return Average execution duration
     */
    public Duration getAverageExecutionTime() {
        if (executionCount == 0) {
            return Duration.ZERO;
        }
        return totalExecutionTime.dividedBy(executionCount);
    }
    
    /**
     * Gets the minimum execution time recorded for this command.
     * 
     * @return Minimum execution duration
     */
    public Duration getMinExecutionTime() {
        return executionCount > 0 ? minExecutionTime : Duration.ZERO;
    }
    
    /**
     * Gets the maximum execution time recorded for this command.
     * 
     * @return Maximum execution duration
     */
    public Duration getMaxExecutionTime() {
        return maxExecutionTime;
    }
    
    /**
     * Gets the timestamp of the first execution.
     * 
     * @return First execution instant
     */
    public Instant getFirstExecution() {
        return firstExecution;
    }
    
    /**
     * Gets the timestamp of the last execution.
     * 
     * @return Last execution instant
     */
    public Instant getLastExecution() {
        return lastExecution;
    }
    
    @Override
    public String toString() {
        return String.format("CommandMetric{commandId='%s', executions=%d, successRate=%.2f%%, avgTime=%dms}", 
                           commandId, executionCount, getSuccessRate(), getAverageExecutionTime().toMillis());
    }
}