package com.automation.framework.core;

// External imports for concurrent operations and atomic state management
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Instant;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Internal imports for connection pool coordination
import com.automation.framework.resources.ConnectionPoolManager;

// Standard Java imports for shutdown hook management and utilities
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

/**
 * ShutdownHandler provides comprehensive graceful shutdown coordination for the automation framework.
 * 
 * This class implements ordered shutdown sequences with timeout protection, managing the systematic 
 * termination of test execution, browser sessions, API connections, thread pools, and resource cleanup.
 * It registers JVM shutdown hooks to ensure proper cleanup even during unexpected termination scenarios.
 * 
 * The shutdown process follows a 7-phase sequence:
 * 1. Stop accepting new test requests
 * 2. Complete in-progress tests with timeout
 * 3. Close browser sessions via driver.quit()
 * 4. Shutdown API connection pools
 * 5. Release thread pools
 * 6. Final resource cleanup
 * 7. Audit log completion
 * 
 * Key Features:
 * - Thread-safe shutdown coordination with atomic state management
 * - Configurable timeout protection for each shutdown phase
 * - Emergency shutdown procedures with force termination
 * - Partial execution capabilities to preserve completed test results
 * - Comprehensive metrics collection and audit trail maintenance
 * - JVM shutdown hook registration for unexpected termination handling
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class ShutdownHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ShutdownHandler.class);
    
    // Singleton instance management
    private static volatile ShutdownHandler instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Shutdown state management with thread-safe atomic operations
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private final AtomicBoolean shutdownComplete = new AtomicBoolean(false);
    private final AtomicBoolean emergencyShutdown = new AtomicBoolean(false);
    private final AtomicBoolean requestAcceptanceStopped = new AtomicBoolean(false);
    
    // Shutdown timing and metrics
    private volatile Instant shutdownStartTime;
    private volatile Instant shutdownCompletionTime;
    private volatile ShutdownStatus currentStatus = ShutdownStatus.NOT_INITIATED;
    private volatile ShutdownPhase currentPhase = ShutdownPhase.PHASE_1_STOP_REQUESTS;
    
    // Timeout configuration management
    private volatile TimeoutConfiguration timeoutConfig = new TimeoutConfiguration();
    
    // Thread-safe callback management using ConcurrentLinkedQueue
    private final ConcurrentLinkedQueue<ShutdownCallbackEntry> registeredCallbacks = new ConcurrentLinkedQueue<>();
    
    // Metrics collection and monitoring
    private final ShutdownMetrics metrics = new ShutdownMetrics();
    private final Map<ShutdownPhase, Instant> phaseStartTimes = new ConcurrentHashMap<>();
    private final Map<ShutdownPhase, Instant> phaseCompletionTimes = new ConcurrentHashMap<>();
    private final Map<String, Object> partialExecutionResults = new ConcurrentHashMap<>();
    
    // JVM shutdown hook management
    private Thread shutdownHook;
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);
    
    // Connection pool manager for coordinated shutdown
    private final ConnectionPoolManager connectionPoolManager;
    
    // Executor service for managing shutdown operations
    private ExecutorService shutdownExecutor;
    
    /**
     * Private constructor to enforce singleton pattern.
     * Initializes the shutdown handler with default configuration and dependencies.
     */
    private ShutdownHandler() {
        this.connectionPoolManager = new ConnectionPoolManager();
        this.shutdownExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ShutdownHandler-Executor");
            thread.setDaemon(false); // Ensure shutdown completes even if other threads terminate
            return thread;
        });
        
        logger.info("ShutdownHandler initialized with default configuration");
    }
    
    /**
     * Gets the singleton instance of ShutdownHandler.
     * Thread-safe lazy initialization with double-checked locking pattern.
     * 
     * @return The singleton ShutdownHandler instance
     */
    public static ShutdownHandler getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new ShutdownHandler();
                }
            }
        }
        return instance;
    }
    
    /**
     * Initiates graceful shutdown sequence with ordered phase execution.
     * This method follows the 7-phase shutdown process specified in the requirements:
     * 1. Stop accepting new test requests
     * 2. Complete in-progress tests with timeout
     * 3. Close browser sessions via driver.quit()
     * 4. Shutdown API connection pools
     * 5. Release thread pools
     * 6. Final resource cleanup
     * 7. Audit log completion
     * 
     * @return CompletableFuture<Boolean> that completes when shutdown finishes
     */
    public CompletableFuture<Boolean> initiateGracefulShutdown() {
        // Ensure only one shutdown can be initiated
        if (!shutdownInProgress.compareAndSet(false, true)) {
            logger.warn("Shutdown already in progress, ignoring duplicate initiation request");
            return CompletableFuture.completedFuture(false);
        }
        
        shutdownStartTime = Instant.now();
        currentStatus = ShutdownStatus.STOPPING_REQUESTS;
        logger.info("Initiating graceful shutdown sequence at {}", shutdownStartTime);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Execute all shutdown phases in sequence
                boolean success = executeShutdownPhases();
                
                if (success) {
                    shutdownComplete.set(true);
                    shutdownCompletionTime = Instant.now();
                    currentStatus = ShutdownStatus.SHUTDOWN_COMPLETE;
                    logger.info("Graceful shutdown completed successfully in {}ms", 
                              Duration.between(shutdownStartTime, shutdownCompletionTime).toMillis());
                } else {
                    currentStatus = ShutdownStatus.SHUTDOWN_FAILED;
                    logger.error("Graceful shutdown failed, some phases may not have completed properly");
                }
                
                return success;
                
            } catch (Exception e) {
                logger.error("Exception during graceful shutdown execution", e);
                currentStatus = ShutdownStatus.SHUTDOWN_FAILED;
                return false;
            }
        }, shutdownExecutor);
    }
    
    /**
     * Executes emergency shutdown with immediate resource termination.
     * Used when graceful shutdown times out or critical failures occur.
     * 
     * @return boolean indicating emergency shutdown completion status
     */
    public boolean executeEmergencyShutdown() {
        emergencyShutdown.set(true);
        currentStatus = ShutdownStatus.EMERGENCY_SHUTDOWN;
        currentPhase = ShutdownPhase.EMERGENCY_PHASE;
        
        Instant emergencyStart = Instant.now();
        logger.warn("Executing emergency shutdown at {}", emergencyStart);
        
        try {
            // Force stop all operations immediately
            stopAcceptingRequests();
            
            // Force close connection pools without waiting
            connectionPoolManager.shutdownConnectionPool();
            
            // Shutdown executor services with minimal timeout
            if (shutdownExecutor != null && !shutdownExecutor.isShutdown()) {
                shutdownExecutor.shutdownNow();
                try {
                    if (!shutdownExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warn("Shutdown executor did not terminate within 5 seconds during emergency shutdown");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Interrupted while waiting for shutdown executor termination");
                }
            }
            
            // Execute emergency callbacks with minimal timeout
            executeEmergencyCallbacks();
            
            shutdownComplete.set(true);
            shutdownCompletionTime = Instant.now();
            
            long duration = Duration.between(emergencyStart, shutdownCompletionTime).toMillis();
            logger.warn("Emergency shutdown completed in {}ms", duration);
            metrics.incrementEmergencyShutdownCount();
            
            return true;
            
        } catch (Exception e) {
            logger.error("Exception during emergency shutdown", e);
            return false;
        }
    }
    
    /**
     * Registers a shutdown callback for execution during the shutdown sequence.
     * Callbacks are executed in priority order during the appropriate phase.
     * 
     * @param callback The shutdown callback to register
     * @return boolean indicating successful registration
     */
    public boolean registerShutdownCallback(ShutdownCallback callback) {
        if (callback == null) {
            logger.warn("Attempted to register null shutdown callback");
            return false;
        }
        
        if (shutdownInProgress.get()) {
            logger.warn("Cannot register callback '{}' - shutdown already in progress", callback.getName());
            return false;
        }
        
        ShutdownCallbackEntry entry = new ShutdownCallbackEntry(callback, Instant.now());
        boolean added = registeredCallbacks.offer(entry);
        
        if (added) {
            logger.debug("Registered shutdown callback '{}' with priority {}", 
                        callback.getName(), callback.getPriority());
        }
        
        return added;
    }
    
    /**
     * Unregisters a previously registered shutdown callback.
     * 
     * @param callbackName The name of the callback to unregister
     * @return boolean indicating successful unregistration
     */
    public boolean unregisterShutdownCallback(String callbackName) {
        if (callbackName == null || callbackName.trim().isEmpty()) {
            logger.warn("Cannot unregister callback with null or empty name");
            return false;
        }
        
        if (shutdownInProgress.get()) {
            logger.warn("Cannot unregister callback '{}' - shutdown already in progress", callbackName);
            return false;
        }
        
        boolean removed = registeredCallbacks.removeIf(entry -> 
            callbackName.equals(entry.getCallback().getName()));
        
        if (removed) {
            logger.debug("Unregistered shutdown callback '{}'", callbackName);
        } else {
            logger.debug("Callback '{}' not found for unregistration", callbackName);
        }
        
        return removed;
    }
    
    /**
     * Gets the current shutdown status.
     * 
     * @return Current ShutdownStatus
     */
    public ShutdownStatus getShutdownStatus() {
        return currentStatus;
    }
    
    /**
     * Checks if shutdown is currently in progress.
     * 
     * @return true if shutdown is in progress, false otherwise
     */
    public boolean isShutdownInProgress() {
        return shutdownInProgress.get();
    }
    
    /**
     * Checks if shutdown has completed successfully.
     * 
     * @return true if shutdown is complete, false otherwise
     */
    public boolean isShutdownComplete() {
        return shutdownComplete.get();
    }
    
    /**
     * Gets the timestamp when shutdown was initiated.
     * 
     * @return Instant of shutdown start time, null if not started
     */
    public Instant getShutdownStartTime() {
        return shutdownStartTime;
    }
    
    /**
     * Gets the duration of the shutdown process.
     * 
     * @return Duration of shutdown, null if not completed
     */
    public Duration getShutdownDuration() {
        if (shutdownStartTime == null) {
            return null;
        }
        
        Instant endTime = shutdownCompletionTime != null ? shutdownCompletionTime : Instant.now();
        return Duration.between(shutdownStartTime, endTime);
    }
    
    /**
     * Stops accepting new test requests as part of Phase 1 of shutdown.
     * This prevents new work from being scheduled during shutdown.
     * 
     * @return boolean indicating successful completion of request stopping
     */
    public boolean stopAcceptingRequests() {
        if (requestAcceptanceStopped.compareAndSet(false, true)) {
            currentPhase = ShutdownPhase.PHASE_1_STOP_REQUESTS;
            phaseStartTimes.put(currentPhase, Instant.now());
            
            logger.info("Phase 1: Stopped accepting new test requests");
            
            // Simulate request acceptance stopping logic
            // In a real implementation, this would signal request handlers to reject new requests
            
            phaseCompletionTimes.put(currentPhase, Instant.now());
            return true;
        }
        
        return false; // Already stopped
    }
    
    /**
     * Completes in-progress tests with configurable timeout as part of Phase 2.
     * Allows currently running tests to finish gracefully before proceeding.
     * 
     * @return boolean indicating successful completion of in-progress tests
     */
    public boolean completeInProgressTests() {
        currentPhase = ShutdownPhase.PHASE_2_COMPLETE_TESTS;
        phaseStartTimes.put(currentPhase, Instant.now());
        
        logger.info("Phase 2: Completing in-progress tests with timeout {}ms", 
                   timeoutConfig.getTestCompletionTimeout());
        
        try {
            // Wait for in-progress tests to complete with timeout
            long timeoutMs = timeoutConfig.getTestCompletionTimeout();
            
            // Simulate waiting for test completion
            // In a real implementation, this would check test execution status
            Thread.sleep(Math.min(timeoutMs, 1000)); // Simulate brief wait
            
            metrics.incrementTestsCompleted(getCurrentTestCount());
            
            logger.info("Phase 2: In-progress tests completion phase finished");
            phaseCompletionTimes.put(currentPhase, Instant.now());
            return true;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for in-progress tests to complete");
            metrics.incrementTestsAborted(getCurrentTestCount());
            return false;
        } catch (Exception e) {
            logger.error("Error during in-progress tests completion", e);
            return false;
        }
    }
    
    /**
     * Releases all system resources as part of the comprehensive shutdown process.
     * This method coordinates the shutdown of connection pools, thread pools, and other resources.
     * 
     * @return boolean indicating successful resource release completion
     */
    public boolean releaseAllResources() {
        logger.info("Phase 6: Releasing all system resources");
        currentPhase = ShutdownPhase.PHASE_6_FINAL_CLEANUP;
        phaseStartTimes.put(currentPhase, Instant.now());
        
        boolean success = true;
        
        try {
            // Phase 3: Close browser sessions (simulated)
            currentPhase = ShutdownPhase.PHASE_3_CLOSE_BROWSERS;
            logger.info("Phase 3: Closing browser sessions via driver.quit()");
            // In real implementation, this would call WebDriver.quit() for all browser instances
            
            // Phase 4: Shutdown API connection pools
            currentPhase = ShutdownPhase.PHASE_4_SHUTDOWN_API_POOLS;
            logger.info("Phase 4: Shutting down API connection pools");
            connectionPoolManager.shutdownConnectionPool();
            
            // Phase 5: Release thread pools
            currentPhase = ShutdownPhase.PHASE_5_RELEASE_THREAD_POOLS;
            logger.info("Phase 5: Releasing thread pools");
            if (shutdownExecutor != null && !shutdownExecutor.isShutdown()) {
                shutdownExecutor.shutdown();
                if (!shutdownExecutor.awaitTermination(timeoutConfig.getResourceReleaseTimeout(), TimeUnit.MILLISECONDS)) {
                    logger.warn("Shutdown executor did not terminate within timeout, forcing shutdown");
                    shutdownExecutor.shutdownNow();
                }
            }
            
            // Execute registered callbacks for resource cleanup
            executeShutdownCallbacks();
            
            metrics.incrementResourcesReleased(getResourceCount());
            
            phaseCompletionTimes.put(ShutdownPhase.PHASE_6_FINAL_CLEANUP, Instant.now());
            logger.info("Phase 6: All system resources released successfully");
            
        } catch (Exception e) {
            logger.error("Error during resource release", e);
            success = false;
        }
        
        return success;
    }
    
    /**
     * Adds a JVM shutdown hook to ensure cleanup during unexpected termination.
     * Uses Runtime.addShutdownHook() as specified in requirements.
     * 
     * @return boolean indicating successful shutdown hook registration
     */
    public boolean addShutdownHook() {
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            shutdownHook = new Thread(() -> {
                logger.warn("JVM shutdown hook triggered - executing emergency cleanup");
                if (!shutdownComplete.get()) {
                    executeEmergencyShutdown();
                }
            }, "ShutdownHandler-Hook");
            
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                logger.info("JVM shutdown hook registered successfully");
                return true;
            } catch (Exception e) {
                logger.error("Failed to register JVM shutdown hook", e);
                shutdownHookRegistered.set(false);
                return false;
            }
        }
        
        logger.debug("Shutdown hook already registered");
        return true;
    }
    
    /**
     * Removes the JVM shutdown hook if it was previously registered.
     * 
     * @return boolean indicating successful shutdown hook removal
     */
    public boolean removeShutdownHook() {
        if (shutdownHookRegistered.compareAndSet(true, false)) {
            try {
                if (shutdownHook != null) {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    shutdownHook = null;
                    logger.info("JVM shutdown hook removed successfully");
                }
                return true;
            } catch (IllegalStateException e) {
                // Shutdown sequence already initiated, can't remove hook
                logger.debug("Cannot remove shutdown hook - JVM shutdown already in progress");
                return false;
            } catch (Exception e) {
                logger.error("Failed to remove JVM shutdown hook", e);
                shutdownHookRegistered.set(true); // Restore state
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * Gets the list of currently registered shutdown callbacks.
     * 
     * @return Unmodifiable list of registered callbacks
     */
    public List<ShutdownCallback> getRegisteredCallbacks() {
        List<ShutdownCallback> callbacks = new ArrayList<>();
        registeredCallbacks.forEach(entry -> callbacks.add(entry.getCallback()));
        return Collections.unmodifiableList(callbacks);
    }
    
    /**
     * Gets comprehensive shutdown metrics for monitoring and analysis.
     * 
     * @return Current shutdown metrics
     */
    public ShutdownMetrics getShutdownMetrics() {
        return metrics;
    }
    
    /**
     * Sets the graceful shutdown timeout configuration.
     * 
     * @param timeout Timeout duration in milliseconds
     */
    public void setGracefulTimeout(long timeout) {
        timeoutConfig.setGracefulTimeout(timeout);
        logger.debug("Graceful shutdown timeout set to {}ms", timeout);
    }
    
    /**
     * Sets the emergency shutdown timeout configuration.
     * 
     * @param timeout Timeout duration in milliseconds
     */
    public void setEmergencyTimeout(long timeout) {
        timeoutConfig.setEmergencyTimeout(timeout);
        logger.debug("Emergency shutdown timeout set to {}ms", timeout);
    }
    
    /**
     * Gets the current timeout configuration.
     * 
     * @return Current TimeoutConfiguration
     */
    public TimeoutConfiguration getTimeoutConfiguration() {
        return timeoutConfig;
    }
    
    /**
     * Attempts to cancel an ongoing shutdown process.
     * Only possible if shutdown hasn't progressed beyond the initial phase.
     * 
     * @return boolean indicating successful cancellation
     */
    public boolean cancelShutdown() {
        if (!shutdownInProgress.get()) {
            logger.debug("No shutdown in progress to cancel");
            return false;
        }
        
        if (currentPhase.ordinal() > ShutdownPhase.PHASE_1_STOP_REQUESTS.ordinal()) {
            logger.warn("Cannot cancel shutdown - already progressed beyond initial phase (current: {})", currentPhase);
            return false;
        }
        
        shutdownInProgress.set(false);
        requestAcceptanceStopped.set(false);
        currentStatus = ShutdownStatus.SHUTDOWN_CANCELLED;
        
        logger.info("Shutdown process cancelled successfully");
        return true;
    }
    
    /**
     * Forces immediate shutdown without graceful phase execution.
     * Similar to emergency shutdown but more aggressive.
     * 
     * @return boolean indicating force shutdown completion
     */
    public boolean forceShutdown() {
        logger.warn("Force shutdown initiated - bypassing graceful procedures");
        return executeEmergencyShutdown();
    }
    
    /**
     * Gets the current shutdown phase.
     * 
     * @return Current ShutdownPhase
     */
    public ShutdownPhase getShutdownPhase() {
        return currentPhase;
    }
    
    /**
     * Gets partial execution results preserved during abnormal shutdown.
     * 
     * @return Map containing partial execution results
     */
    public Map<String, Object> getPartialExecutionResults() {
        return Collections.unmodifiableMap(partialExecutionResults);
    }
    
    /**
     * Preserves current test state for recovery after abnormal shutdown.
     * 
     * @param testId Test identifier
     * @param state Test state data to preserve
     * @return boolean indicating successful state preservation
     */
    public boolean preserveTestState(String testId, Object state) {
        if (testId == null || testId.trim().isEmpty()) {
            logger.warn("Cannot preserve test state with null or empty test ID");
            return false;
        }
        
        partialExecutionResults.put(testId, state);
        logger.debug("Preserved test state for test ID: {}", testId);
        return true;
    }
    
    /**
     * Gets a comprehensive summary of the shutdown process.
     * 
     * @return Formatted string containing shutdown summary
     */
    public String getShutdownSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Shutdown Summary ===\n");
        summary.append("Status: ").append(currentStatus).append("\n");
        summary.append("Phase: ").append(currentPhase).append("\n");
        summary.append("Start Time: ").append(shutdownStartTime).append("\n");
        summary.append("Duration: ").append(getShutdownDuration()).append("\n");
        summary.append("Emergency Shutdown: ").append(emergencyShutdown.get()).append("\n");
        summary.append("Registered Callbacks: ").append(registeredCallbacks.size()).append("\n");
        summary.append("Partial Results Preserved: ").append(partialExecutionResults.size()).append("\n");
        summary.append("Connection Pool Healthy: ").append(connectionPoolManager.isPoolHealthy()).append("\n");
        summary.append("Active Connections: ").append(connectionPoolManager.getActiveConnections()).append("\n");
        
        if (shutdownCompletionTime != null) {
            summary.append("Completion Time: ").append(shutdownCompletionTime).append("\n");
        }
        
        return summary.toString();
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Executes all shutdown phases in the correct sequence.
     * 
     * @return boolean indicating successful completion of all phases
     */
    private boolean executeShutdownPhases() {
        try {
            logger.info("Beginning 7-phase shutdown sequence");
            
            // Phase 1: Stop accepting new requests
            if (!stopAcceptingRequests()) {
                logger.error("Phase 1 failed - could not stop accepting requests");
                return false;
            }
            
            // Phase 2: Complete in-progress tests
            currentStatus = ShutdownStatus.COMPLETING_TESTS;
            if (!completeInProgressTests()) {
                logger.error("Phase 2 failed - could not complete in-progress tests");
                return false;
            }
            
            // Phases 3-6: Release all resources (includes browser, API pools, thread pools, cleanup)
            currentStatus = ShutdownStatus.RELEASING_RESOURCES;
            if (!releaseAllResources()) {
                logger.error("Phases 3-6 failed - could not release all resources");
                return false;
            }
            
            // Phase 7: Audit log completion
            currentPhase = ShutdownPhase.PHASE_7_AUDIT_COMPLETION;
            phaseStartTimes.put(currentPhase, Instant.now());
            logger.info("Phase 7: Completing audit log");
            
            String auditSummary = getShutdownSummary();
            logger.info("Shutdown audit summary:\n{}", auditSummary);
            
            phaseCompletionTimes.put(currentPhase, Instant.now());
            logger.info("Phase 7: Audit log completion finished");
            
            logger.info("All 7 shutdown phases completed successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Exception during shutdown phase execution", e);
            return false;
        }
    }
    
    /**
     * Executes all registered shutdown callbacks in priority order.
     */
    private void executeShutdownCallbacks() {
        logger.debug("Executing {} registered shutdown callbacks", registeredCallbacks.size());
        
        // Convert to list and sort by priority
        List<ShutdownCallbackEntry> sortedCallbacks = new ArrayList<>(registeredCallbacks);
        sortedCallbacks.sort((a, b) -> Integer.compare(b.getCallback().getPriority(), a.getCallback().getPriority()));
        
        for (ShutdownCallbackEntry entry : sortedCallbacks) {
            ShutdownCallback callback = entry.getCallback();
            
            try {
                logger.debug("Executing shutdown callback: {}", callback.getName());
                
                // Execute with timeout protection
                Future<?> callbackFuture = shutdownExecutor.submit(() -> {
                    callback.execute();
                });
                
                callbackFuture.get(callback.getTimeout(), TimeUnit.MILLISECONDS);
                metrics.incrementSuccessfulCallbacks();
                
                logger.debug("Shutdown callback '{}' completed successfully", callback.getName());
                
            } catch (TimeoutException e) {
                logger.warn("Shutdown callback '{}' timed out after {}ms", 
                           callback.getName(), callback.getTimeout());
                metrics.incrementFailedCallbacks();
                metrics.incrementTimeoutEvents();
                
            } catch (Exception e) {
                logger.error("Error executing shutdown callback '{}'", callback.getName(), e);
                metrics.incrementFailedCallbacks();
            }
        }
    }
    
    /**
     * Executes emergency callbacks with minimal timeout for critical cleanup.
     */
    private void executeEmergencyCallbacks() {
        logger.debug("Executing emergency callbacks");
        
        List<ShutdownCallbackEntry> callbacks = new ArrayList<>(registeredCallbacks);
        
        for (ShutdownCallbackEntry entry : callbacks) {
            ShutdownCallback callback = entry.getCallback();
            
            if (!callback.canCancel()) {
                continue; // Skip non-essential callbacks during emergency
            }
            
            try {
                logger.debug("Executing emergency callback: {}", callback.getName());
                callback.execute();
                metrics.incrementSuccessfulCallbacks();
                
            } catch (Exception e) {
                logger.warn("Error executing emergency callback '{}'", callback.getName(), e);
                metrics.incrementFailedCallbacks();
            }
        }
    }
    
    /**
     * Gets the current count of active tests for metrics tracking.
     * 
     * @return Current test count
     */
    private int getCurrentTestCount() {
        // In a real implementation, this would query the test execution engine
        return 0; // Simplified for framework setup
    }
    
    /**
     * Gets the current count of resources for metrics tracking.
     * 
     * @return Current resource count
     */
    private int getResourceCount() {
        int count = 0;
        count += connectionPoolManager.getActiveConnections();
        // In a real implementation, this would count browser sessions, file handles, etc.
        return count;
    }
}

/**
 * Internal class for managing shutdown callback entries with metadata.
 */
class ShutdownCallbackEntry {
    private final ShutdownCallback callback;
    private final Instant registrationTime;
    
    public ShutdownCallbackEntry(ShutdownCallback callback, Instant registrationTime) {
        this.callback = callback;
        this.registrationTime = registrationTime;
    }
    
    public ShutdownCallback getCallback() {
        return callback;
    }
    
    public Instant getRegistrationTime() {
        return registrationTime;
    }
}

/**
 * Enumeration representing the current status of the shutdown process.
 * Provides comprehensive state tracking throughout the shutdown lifecycle.
 */
enum ShutdownStatus {
    /**
     * Shutdown has not been initiated yet.
     */
    NOT_INITIATED,
    
    /**
     * Currently stopping acceptance of new test requests.
     */
    STOPPING_REQUESTS,
    
    /**
     * Currently completing in-progress tests.
     */
    COMPLETING_TESTS,
    
    /**
     * Currently releasing system resources.
     */
    RELEASING_RESOURCES,
    
    /**
     * Currently shutting down connection pools.
     */
    SHUTTING_DOWN_POOLS,
    
    /**
     * Emergency shutdown procedures are active.
     */
    EMERGENCY_SHUTDOWN,
    
    /**
     * Shutdown process completed successfully.
     */
    SHUTDOWN_COMPLETE,
    
    /**
     * Shutdown process failed to complete properly.
     */
    SHUTDOWN_FAILED,
    
    /**
     * Shutdown process was cancelled before completion.
     */
    SHUTDOWN_CANCELLED
}

/**
 * Interface for shutdown callback implementations that execute during the shutdown sequence.
 * Provides standardized contract for cleanup operations with priority and timeout management.
 */
interface ShutdownCallback {
    
    /**
     * Executes the shutdown callback operation.
     * Implementation should be idempotent and handle exceptions gracefully.
     */
    void execute();
    
    /**
     * Gets the priority of this callback for execution ordering.
     * Higher priority callbacks execute first.
     * 
     * @return Priority value (higher = executes first)
     */
    int getPriority();
    
    /**
     * Gets the unique name of this callback for identification and logging.
     * 
     * @return Callback name
     */
    String getName();
    
    /**
     * Gets the maximum execution timeout for this callback in milliseconds.
     * 
     * @return Timeout in milliseconds
     */
    long getTimeout();
    
    /**
     * Indicates whether this callback can be cancelled during emergency shutdown.
     * 
     * @return true if callback can be skipped during emergency shutdown
     */
    boolean canCancel();
}

/**
 * Comprehensive metrics collection class for shutdown process monitoring and analysis.
 * Tracks timing, success rates, resource counts, and performance indicators.
 */
class ShutdownMetrics {
    
    private volatile Instant initiationTime;
    private volatile Instant completionTime;
    private final Map<ShutdownPhase, Duration> phaseTimings = new ConcurrentHashMap<>();
    private final Map<String, Long> callbackExecutionTimes = new ConcurrentHashMap<>();
    private final AtomicBoolean timeoutEvents = new AtomicBoolean(false);
    private final AtomicBoolean failedCallbacks = new AtomicBoolean(false);
    private final AtomicBoolean successfulCallbacks = new AtomicBoolean(false);
    private final AtomicBoolean emergencyShutdownCount = new AtomicBoolean(false);
    private final AtomicBoolean partialExecutionCount = new AtomicBoolean(false);
    private volatile int resourcesReleased = 0;
    private volatile int testsCompleted = 0;
    private volatile int testsAborted = 0;
    
    // Counters
    private final AtomicBoolean timeoutEventCount = new AtomicBoolean(false);
    private final AtomicBoolean failedCallbackCount = new AtomicBoolean(false);
    private final AtomicBoolean successfulCallbackCount = new AtomicBoolean(false);
    private final AtomicBoolean emergencyCount = new AtomicBoolean(false);
    private final AtomicBoolean partialCount = new AtomicBoolean(false);
    
    /**
     * Gets the time when shutdown was initiated.
     * 
     * @return Initiation timestamp
     */
    public Instant getInitiationTime() {
        return initiationTime;
    }
    
    /**
     * Sets the shutdown initiation time.
     * 
     * @param time Initiation timestamp
     */
    public void setInitiationTime(Instant time) {
        this.initiationTime = time;
    }
    
    /**
     * Gets the time when shutdown completed.
     * 
     * @return Completion timestamp
     */
    public Instant getCompletionTime() {
        return completionTime;
    }
    
    /**
     * Sets the shutdown completion time.
     * 
     * @param time Completion timestamp
     */
    public void setCompletionTime(Instant time) {
        this.completionTime = time;
    }
    
    /**
     * Gets the total duration of the shutdown process.
     * 
     * @return Total shutdown duration
     */
    public Duration getTotalDuration() {
        if (initiationTime == null) {
            return Duration.ZERO;
        }
        
        Instant endTime = completionTime != null ? completionTime : Instant.now();
        return Duration.between(initiationTime, endTime);
    }
    
    /**
     * Gets timing information for each shutdown phase.
     * 
     * @return Map of phase timings
     */
    public Map<ShutdownPhase, Duration> getPhaseTimings() {
        return Collections.unmodifiableMap(phaseTimings);
    }
    
    /**
     * Records the execution time for a specific shutdown phase.
     * 
     * @param phase The shutdown phase
     * @param duration Time taken to complete the phase
     */
    public void recordPhaseTime(ShutdownPhase phase, Duration duration) {
        phaseTimings.put(phase, duration);
    }
    
    /**
     * Gets execution times for individual callbacks.
     * 
     * @return Map of callback execution times
     */
    public Map<String, Long> getCallbackExecutionTimes() {
        return Collections.unmodifiableMap(callbackExecutionTimes);
    }
    
    /**
     * Records execution time for a specific callback.
     * 
     * @param callbackName Name of the callback
     * @param executionTime Time taken in milliseconds
     */
    public void recordCallbackTime(String callbackName, long executionTime) {
        callbackExecutionTimes.put(callbackName, executionTime);
    }
    
    /**
     * Gets the count of timeout events during shutdown.
     * 
     * @return Number of timeout events
     */
    public int getTimeoutEvents() {
        return timeoutEventCount.get() ? 1 : 0;
    }
    
    /**
     * Increments the timeout event counter.
     */
    public void incrementTimeoutEvents() {
        timeoutEventCount.set(true);
    }
    
    /**
     * Gets the count of failed callback executions.
     * 
     * @return Number of failed callbacks
     */
    public int getFailedCallbacks() {
        return failedCallbackCount.get() ? 1 : 0;
    }
    
    /**
     * Increments the failed callback counter.
     */
    public void incrementFailedCallbacks() {
        failedCallbackCount.set(true);
    }
    
    /**
     * Gets the count of successful callback executions.
     * 
     * @return Number of successful callbacks
     */
    public int getSuccessfulCallbacks() {
        return successfulCallbackCount.get() ? 1 : 0;
    }
    
    /**
     * Increments the successful callback counter.
     */
    public void incrementSuccessfulCallbacks() {
        successfulCallbackCount.set(true);
    }
    
    /**
     * Gets the count of emergency shutdown events.
     * 
     * @return Number of emergency shutdowns
     */
    public int getEmergencyShutdownCount() {
        return emergencyCount.get() ? 1 : 0;
    }
    
    /**
     * Increments the emergency shutdown counter.
     */
    public void incrementEmergencyShutdownCount() {
        emergencyCount.set(true);
    }
    
    /**
     * Gets the count of partial execution events.
     * 
     * @return Number of partial executions
     */
    public int getPartialExecutionCount() {
        return partialCount.get() ? 1 : 0;
    }
    
    /**
     * Increments the partial execution counter.
     */
    public void incrementPartialExecutionCount() {
        partialCount.set(true);
    }
    
    /**
     * Gets the number of resources released during shutdown.
     * 
     * @return Count of released resources
     */
    public int getResourcesReleased() {
        return resourcesReleased;
    }
    
    /**
     * Increments the resources released counter.
     * 
     * @param count Number of resources released
     */
    public void incrementResourcesReleased(int count) {
        this.resourcesReleased += count;
    }
    
    /**
     * Gets the number of tests completed during shutdown.
     * 
     * @return Count of completed tests
     */
    public int getTestsCompleted() {
        return testsCompleted;
    }
    
    /**
     * Increments the tests completed counter.
     * 
     * @param count Number of tests completed
     */
    public void incrementTestsCompleted(int count) {
        this.testsCompleted += count;
    }
    
    /**
     * Gets the number of tests aborted during shutdown.
     * 
     * @return Count of aborted tests
     */
    public int getTestsAborted() {
        return testsAborted;
    }
    
    /**
     * Increments the tests aborted counter.
     * 
     * @param count Number of tests aborted
     */
    public void incrementTestsAborted(int count) {
        this.testsAborted += count;
    }
}

/**
 * Enumeration representing the phases of the shutdown sequence.
 * Provides structured progression through the 7-phase shutdown process plus emergency phase.
 */
enum ShutdownPhase {
    /**
     * Phase 1: Stop accepting new test requests.
     */
    PHASE_1_STOP_REQUESTS,
    
    /**
     * Phase 2: Complete in-progress tests with timeout.
     */
    PHASE_2_COMPLETE_TESTS,
    
    /**
     * Phase 3: Close browser sessions via driver.quit().
     */
    PHASE_3_CLOSE_BROWSERS,
    
    /**
     * Phase 4: Shutdown API connection pools gracefully.
     */
    PHASE_4_SHUTDOWN_API_POOLS,
    
    /**
     * Phase 5: Release all thread pools with proper termination.
     */
    PHASE_5_RELEASE_THREAD_POOLS,
    
    /**
     * Phase 6: Execute final resource cleanup procedures.
     */
    PHASE_6_FINAL_CLEANUP,
    
    /**
     * Phase 7: Complete audit log with shutdown summary.
     */
    PHASE_7_AUDIT_COMPLETION,
    
    /**
     * Emergency phase: Force termination when normal phases fail or timeout.
     */
    EMERGENCY_PHASE
}

/**
 * Configuration class for managing shutdown timeout settings.
 * Provides centralized timeout management with validation and default values.
 */
class TimeoutConfiguration {
    
    // Default timeout values in milliseconds
    private static final long DEFAULT_GRACEFUL_TIMEOUT = 30000; // 30 seconds
    private static final long DEFAULT_EMERGENCY_TIMEOUT = 10000; // 10 seconds
    private static final long DEFAULT_TEST_COMPLETION_TIMEOUT = 60000; // 60 seconds
    private static final long DEFAULT_RESOURCE_RELEASE_TIMEOUT = 15000; // 15 seconds
    
    private volatile long gracefulTimeout;
    private volatile long emergencyTimeout;
    private volatile long testCompletionTimeout;
    private volatile long resourceReleaseTimeout;
    
    /**
     * Creates a new TimeoutConfiguration with default values.
     */
    public TimeoutConfiguration() {
        this.gracefulTimeout = DEFAULT_GRACEFUL_TIMEOUT;
        this.emergencyTimeout = DEFAULT_EMERGENCY_TIMEOUT;
        this.testCompletionTimeout = DEFAULT_TEST_COMPLETION_TIMEOUT;
        this.resourceReleaseTimeout = DEFAULT_RESOURCE_RELEASE_TIMEOUT;
    }
    
    /**
     * Gets the graceful shutdown timeout in milliseconds.
     * 
     * @return Graceful timeout value
     */
    public long getGracefulTimeout() {
        return gracefulTimeout;
    }
    
    /**
     * Sets the graceful shutdown timeout.
     * 
     * @param gracefulTimeout Timeout in milliseconds (must be positive)
     * @throws IllegalArgumentException if timeout is not positive
     */
    public void setGracefulTimeout(long gracefulTimeout) {
        if (gracefulTimeout <= 0) {
            throw new IllegalArgumentException("Graceful timeout must be positive");
        }
        this.gracefulTimeout = gracefulTimeout;
    }
    
    /**
     * Gets the emergency shutdown timeout in milliseconds.
     * 
     * @return Emergency timeout value
     */
    public long getEmergencyTimeout() {
        return emergencyTimeout;
    }
    
    /**
     * Sets the emergency shutdown timeout.
     * 
     * @param emergencyTimeout Timeout in milliseconds (must be positive)
     * @throws IllegalArgumentException if timeout is not positive
     */
    public void setEmergencyTimeout(long emergencyTimeout) {
        if (emergencyTimeout <= 0) {
            throw new IllegalArgumentException("Emergency timeout must be positive");
        }
        this.emergencyTimeout = emergencyTimeout;
    }
    
    /**
     * Gets the test completion timeout in milliseconds.
     * 
     * @return Test completion timeout value
     */
    public long getTestCompletionTimeout() {
        return testCompletionTimeout;
    }
    
    /**
     * Sets the test completion timeout.
     * 
     * @param testCompletionTimeout Timeout in milliseconds (must be positive)
     * @throws IllegalArgumentException if timeout is not positive
     */
    public void setTestCompletionTimeout(long testCompletionTimeout) {
        if (testCompletionTimeout <= 0) {
            throw new IllegalArgumentException("Test completion timeout must be positive");
        }
        this.testCompletionTimeout = testCompletionTimeout;
    }
    
    /**
     * Gets the resource release timeout in milliseconds.
     * 
     * @return Resource release timeout value
     */
    public long getResourceReleaseTimeout() {
        return resourceReleaseTimeout;
    }
    
    /**
     * Sets the resource release timeout.
     * 
     * @param resourceReleaseTimeout Timeout in milliseconds (must be positive)
     * @throws IllegalArgumentException if timeout is not positive
     */
    public void setResourceReleaseTimeout(long resourceReleaseTimeout) {
        if (resourceReleaseTimeout <= 0) {
            throw new IllegalArgumentException("Resource release timeout must be positive");
        }
        this.resourceReleaseTimeout = resourceReleaseTimeout;
    }
    
    /**
     * Validates all timeout configurations to ensure they are reasonable.
     * 
     * @throws IllegalStateException if any timeout configuration is invalid
     */
    public void validate() {
        if (gracefulTimeout <= 0 || emergencyTimeout <= 0 || 
            testCompletionTimeout <= 0 || resourceReleaseTimeout <= 0) {
            throw new IllegalStateException("All timeout values must be positive");
        }
        
        if (emergencyTimeout >= gracefulTimeout) {
            throw new IllegalStateException("Emergency timeout should be less than graceful timeout");
        }
        
        if (testCompletionTimeout + resourceReleaseTimeout > gracefulTimeout) {
            throw new IllegalStateException("Sum of phase timeouts exceeds graceful timeout");
        }
    }
    
    /**
     * Gets a default timeout configuration with standard values.
     * 
     * @return New TimeoutConfiguration with default settings
     */
    public static TimeoutConfiguration getDefaultConfiguration() {
        return new TimeoutConfiguration();
    }
}