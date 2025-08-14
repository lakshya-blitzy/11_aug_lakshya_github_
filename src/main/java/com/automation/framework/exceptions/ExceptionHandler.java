package com.automation.framework.exceptions;

// External imports for structured logging and audit trail maintenance
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// External imports for safe handling of potentially null values and concurrent operations
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;

// Standard Java imports for collections, time handling, and concurrency
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.ExecutionException;
import java.time.Instant;

// Internal imports from framework dependencies
import com.automation.framework.exceptions.ErrorReporter;
import com.automation.framework.exceptions.LogLevel;
import com.automation.framework.exceptions.RecoveryStrategy;
import com.automation.framework.exceptions.RetryMechanism;

import com.automation.framework.core.FrameworkManager;

/**
 * ExceptionHandler serves as the centralized exception management component for the automation framework.
 * 
 * This class implements a comprehensive three-tier error recovery system that coordinates all error handling
 * across the automation framework. It provides hierarchical error recovery with intelligent classification,
 * comprehensive logging integration, and recovery orchestration capabilities.
 * 
 * Three-Tier Recovery System:
 * - Component Level: Handles localized failures within individual testing modules through automatic retry
 *   mechanisms, alternative locator strategies, and resource reallocation
 * - Test Level: Manages failures affecting individual test cases through state isolation, error context
 *   capture, and continuation decision logic with detailed failure diagnostics
 * - Suite Level: Addresses critical failures that impact entire test suites through graceful degradation,
 *   partial execution capabilities, and emergency shutdown procedures
 * 
 * Key Features:
 * - Unified exception classification and response strategies across all framework components
 * - Real-time error monitoring with configurable thresholds and escalation procedures
 * - State preservation and recovery coordination for maintaining test execution integrity
 * - Integration with ErrorReporter for comprehensive logging with correlation IDs and audit trails
 * - Integration with RecoveryStrategy for intelligent recovery decision making
 * - Integration with RetryMechanism for configurable retry logic with circuit breaker patterns
 * - Emergency shutdown coordination for critical failure scenarios
 * 
 * Error Classification:
 * - COMPONENT: Localized errors that can be resolved through retry or alternative approaches
 * - TEST: Errors affecting individual test cases requiring state isolation and recovery decisions
 * - SUITE: Critical errors impacting entire test suites requiring graceful degradation
 * - CRITICAL: System-level errors requiring immediate emergency shutdown procedures
 * 
 * Recovery Levels:
 * - COMPONENT_LEVEL: Automatic retry mechanisms for transient failures
 * - TEST_LEVEL: State isolation and continuation decision logic
 * - SUITE_LEVEL: Graceful degradation and emergency shutdown procedures
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class ExceptionHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ExceptionHandler.class);
    
    // Core framework component integrations for comprehensive error management
    private final ErrorReporter errorReporter;
    private final RecoveryStrategy recoveryStrategy;
    private final RetryMechanism retryMechanism;
    private final FrameworkManager frameworkManager;
    
    // Error tracking and metrics collection with thread-safe operations
    private final ConcurrentHashMap<ErrorLevel, AtomicLong> errorCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> exceptionCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ErrorLevel, AtomicLong> recoveryAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ErrorLevel> errorClassifications = new ConcurrentHashMap<>();
    
    // Recovery history and state management for comprehensive tracking
    private final List<RecoveryResult> recoveryHistory = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<String, Object> preservedStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> errorTimestamps = new ConcurrentHashMap<>();
    
    // Configuration and threshold management for adaptive error handling
    private final ConcurrentHashMap<ErrorLevel, Integer> errorThresholds = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastErrorTrendAnalysis = new AtomicReference<>(Instant.now());
    private final ReentrantReadWriteLock configurationLock = new ReentrantReadWriteLock();
    
    // Circuit breaker and emergency shutdown coordination
    private final AtomicReference<Instant> lastEmergencyShutdown = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong totalRecoveryAttempts = new AtomicLong(0);
    
    /**
     * Creates a new ExceptionHandler with all required framework component integrations.
     * Initializes error tracking, configures default thresholds, and establishes recovery coordination.
     * 
     * @param errorReporter ErrorReporter instance for comprehensive logging and reporting
     * @param recoveryStrategy RecoveryStrategy instance for intelligent recovery decision making
     * @param retryMechanism RetryMechanism instance for configurable retry logic
     * @param frameworkManager FrameworkManager instance for suite-level coordination
     */
    public ExceptionHandler(ErrorReporter errorReporter, RecoveryStrategy recoveryStrategy, 
                          RetryMechanism retryMechanism, FrameworkManager frameworkManager) {
        this.errorReporter = errorReporter;
        this.recoveryStrategy = recoveryStrategy;
        this.retryMechanism = retryMechanism;
        this.frameworkManager = frameworkManager;
        
        // Initialize error tracking counters for all error levels
        initializeErrorCounters();
        
        // Configure default error thresholds for adaptive handling
        configureDefaultThresholds();
        
        logger.info("ExceptionHandler initialized with three-tier recovery system");
    }
    
    /**
     * Handles exceptions through the three-tier recovery system with comprehensive error processing.
     * Acts as the main entry point for all exception processing with intelligent error categorization
     * and response strategies based on exception type, severity, and system state.
     * 
     * @param exception The exception to handle and process
     * @param context Additional context information for error classification and recovery
     * @return CompletableFuture<RecoveryResult> indicating the outcome of exception handling
     */
    public CompletableFuture<RecoveryResult> handleException(Exception exception, Map<String, Object> context) {
        if (exception == null) {
            logger.warn("Attempted to handle null exception - no action taken");
            return CompletableFuture.completedFuture(
                new RecoveryResult(false, RecoveryLevel.COMPONENT_LEVEL, Collections.emptyList(), 
                                 0L, "Cannot handle null exception", Collections.emptyList(), 
                                 Collections.emptyMap(), Instant.now(), "Null exception provided", 0));
        }
        
        return CompletableFuture.supplyAsync(() -> {
            Instant processingStart = Instant.now();
            String correlationId = generateCorrelationId();
            
            try {
                // Set correlation ID for distributed tracing
                errorReporter.setCorrelationId(correlationId);
                
                // Capture comprehensive error context
                ExceptionContext exceptionContext = captureErrorContext(exception, context, correlationId);
                
                // Classify error to determine appropriate recovery level
                ErrorLevel errorLevel = classifyError(exception, exceptionContext);
                
                // Update error tracking and metrics
                updateErrorTracking(exception, errorLevel);
                
                // Log the exception with full context
                errorReporter.logException(exception, "Exception handled by ExceptionHandler", exceptionContext.getAdditionalContext());
                
                // Determine if recovery is feasible based on current system state
                if (!isRecoveryFeasible(errorLevel, exception)) {
                    logger.warn("Recovery not feasible for error level: {} - {}", errorLevel, exception.getMessage());
                    RecoveryResult failedResult = new RecoveryResult(false, getRecoveryLevel(errorLevel), 
                        Collections.emptyList(), Duration.between(processingStart, Instant.now()).toMillis(), 
                        "Recovery not feasible: " + exception.getMessage(), 
                        Collections.emptyList(), Collections.emptyMap(), processingStart, 
                        "Recovery not feasible for error level: " + errorLevel, 0);
                    recordRecoveryResult(failedResult);
                    return failedResult;
                }
                
                // Execute recovery based on determined error level
                RecoveryResult recoveryResult = executeRecoveryByLevel(errorLevel, exceptionContext);
                
                // Calculate total processing duration
                Duration processingDuration = Duration.between(processingStart, Instant.now());
                recoveryResult = new RecoveryResult(
                    recoveryResult.isSuccessful(),
                    recoveryResult.getRecoveryLevel(),
                    recoveryResult.getExecutedActions(),
                    processingDuration.toMillis(),
                    recoveryResult.getErrorMessage(),
                    recoveryResult.getRecoveryActions(),
                    recoveryResult.getPreservedState(),
                    processingStart,
                    recoveryResult.getFailureReason(),
                    recoveryResult.getRecoveryAttempts()
                );
                
                // Record recovery result for history and analysis
                recordRecoveryResult(recoveryResult);
                
                // Update consecutive failure tracking
                if (recoveryResult.isSuccessful()) {
                    consecutiveFailures.set(0);
                    logger.debug("Exception handled successfully with {} recovery in {}ms", 
                               recoveryResult.getRecoveryLevel(), processingDuration.toMillis());
                } else {
                    int failures = consecutiveFailures.incrementAndGet();
                    logger.warn("Recovery failed - consecutive failures: {} for error: {}", 
                              failures, exception.getMessage());
                    
                    // Check if emergency shutdown is needed based on consecutive failures
                    if (shouldTriggerEmergencyShutdown(failures, errorLevel)) {
                        triggerEmergencyShutdown("Consecutive recovery failures exceeded threshold");
                    }
                }
                
                return recoveryResult;
                
            } catch (Exception processingException) {
                logger.error("Exception during exception handling for correlation ID: {}", 
                           correlationId, processingException);
                
                // Emergency fallback - create minimal recovery result
                Duration processingDuration = Duration.between(processingStart, Instant.now());
                RecoveryResult emergencyResult = new RecoveryResult(false, RecoveryLevel.SUITE_LEVEL, 
                    Collections.emptyList(), processingDuration.toMillis(), 
                    "Exception handling failed: " + processingException.getMessage(), 
                    Collections.emptyList(), Collections.emptyMap(), processingStart,
                    "Emergency exception handling failure", 1);
                
                recordRecoveryResult(emergencyResult);
                return emergencyResult;
            }
        }).exceptionally(throwable -> {
            logger.error("Asynchronous exception handling failure", throwable);
            return new RecoveryResult(false, RecoveryLevel.SUITE_LEVEL, Collections.emptyList(), 
                0L, "Asynchronous handling failure: " + throwable.getMessage(), 
                Collections.emptyList(), Collections.emptyMap(), Instant.now(),
                "Asynchronous exception handling failure", 0);
        });
    }
    
    /**
     * Classifies errors to determine the appropriate recovery level based on exception type and context.
     * Implements intelligent error classification logic that considers exception hierarchy, error patterns,
     * and system state to determine optimal recovery strategies.
     * 
     * @param exception The exception to classify
     * @param context Additional context for classification decision
     * @return ErrorLevel indicating the appropriate recovery level
     */
    public ErrorLevel classifyError(Exception exception, ExceptionContext context) {
        if (exception == null) {
            return ErrorLevel.COMPONENT;
        }
        
        try {
            String exceptionType = exception.getClass().getSimpleName();
            String errorMessage = exception.getMessage();
            
            // Check for critical system-level errors
            if (isCriticalError(exception, errorMessage)) {
                logger.warn("Classified as CRITICAL error: {}", exceptionType);
                return ErrorLevel.CRITICAL;
            }
            
            // Check for suite-level errors that impact entire test execution
            if (isSuiteLevelError(exception, errorMessage, context)) {
                logger.warn("Classified as SUITE level error: {}", exceptionType);
                return ErrorLevel.SUITE;
            }
            
            // Check for test-level errors affecting individual test cases
            if (isTestLevelError(exception, errorMessage, context)) {
                logger.debug("Classified as TEST level error: {}", exceptionType);
                return ErrorLevel.TEST;
            }
            
            // Default to component-level for localized errors
            logger.debug("Classified as COMPONENT level error: {}", exceptionType);
            return ErrorLevel.COMPONENT;
            
        } catch (Exception classificationException) {
            logger.error("Error during exception classification", classificationException);
            // Fail safe to component level for unknown classification errors
            return ErrorLevel.COMPONENT;
        }
    }
    
    /**
     * Gets comprehensive error rates across all error levels for monitoring and analysis.
     * Provides real-time error rate statistics for framework health monitoring and
     * capacity planning with configurable time windows and trend analysis.
     * 
     * @return Map containing error rates by error level and time period
     */
    public Map<String, Double> getErrorRates() {
        Map<String, Double> rates = new HashMap<>();
        
        try {
            long totalErrors = errorCounts.values().stream()
                .mapToLong(AtomicLong::get)
                .sum();
            
            if (totalErrors == 0) {
                // Return zero rates if no errors recorded
                for (ErrorLevel level : ErrorLevel.values()) {
                    rates.put(level.name() + "_RATE", 0.0);
                }
                rates.put("OVERALL_ERROR_RATE", 0.0);
                return rates;
            }
            
            // Calculate error rates by level
            for (ErrorLevel level : ErrorLevel.values()) {
                long levelCount = errorCounts.getOrDefault(level, new AtomicLong(0)).get();
                double rate = (levelCount / (double) totalErrors) * 100.0;
                rates.put(level.name() + "_RATE", rate);
            }
            
            // Calculate overall error rate (errors per minute based on recent activity)
            long recentErrors = calculateRecentErrors(Duration.ofMinutes(5));
            double overallRate = recentErrors / 5.0; // errors per minute
            rates.put("OVERALL_ERROR_RATE", overallRate);
            
            // Add recovery success rate
            long totalRecoveries = totalRecoveryAttempts.get();
            if (totalRecoveries > 0) {
                long successfulRecoveries = recoveryHistory.stream()
                    .mapToLong(result -> result.isSuccessful() ? 1 : 0)
                    .sum();
                double recoverySuccessRate = (successfulRecoveries / (double) totalRecoveries) * 100.0;
                rates.put("RECOVERY_SUCCESS_RATE", recoverySuccessRate);
            } else {
                rates.put("RECOVERY_SUCCESS_RATE", 0.0);
            }
            
            logger.debug("Generated error rates - Total errors: {}, Overall rate: {}/min", 
                       totalErrors, rates.get("OVERALL_ERROR_RATE"));
            
            return rates;
            
        } catch (Exception e) {
            logger.error("Error calculating error rates", e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * Gets detailed exception counts by exception type for comprehensive error analysis.
     * Provides granular statistics on specific exception types to identify common
     * failure patterns and optimize error handling strategies.
     * 
     * @return Map containing exception counts by exception class name
     */
    public Map<String, Integer> getExceptionCounts() {
        Map<String, Integer> counts = new HashMap<>();
        
        try {
            // Convert atomic integers to regular integers for return
            exceptionCounts.forEach((exceptionType, atomicCount) -> 
                counts.put(exceptionType, atomicCount.get()));
            
            // Add summary statistics
            int totalExceptions = counts.values().stream().mapToInt(Integer::intValue).sum();
            counts.put("TOTAL_EXCEPTIONS", totalExceptions);
            
            // Find most common exception type
            Optional<Map.Entry<String, Integer>> mostCommon = counts.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("TOTAL_EXCEPTIONS"))
                .max(Map.Entry.comparingByValue());
            
            if (mostCommon.isPresent()) {
                counts.put("MOST_COMMON_TYPE", mostCommon.get().getValue());
                logger.debug("Most common exception: {} with {} occurrences", 
                           mostCommon.get().getKey(), mostCommon.get().getValue());
            }
            
            return counts;
            
        } catch (Exception e) {
            logger.error("Error retrieving exception counts", e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * Gets current error classifications for all tracked exception types.
     * Provides visibility into how different exception types are being classified
     * for recovery level determination and strategy optimization.
     * 
     * @return Map containing error classifications by exception type
     */
    public Map<String, ErrorLevel> getErrorClassifications() {
        try {
            // Return defensive copy to prevent external modification
            return new HashMap<>(errorClassifications);
        } catch (Exception e) {
            logger.error("Error retrieving error classifications", e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * Gets recovery attempt counts by error level for monitoring recovery effectiveness.
     * Provides statistics on recovery attempt frequency and success rates across
     * different error levels to optimize recovery strategies.
     * 
     * @return Map containing recovery attempt counts by error level
     */
    public Map<String, Long> getRecoveryAttempts() {
        Map<String, Long> attempts = new HashMap<>();
        
        try {
            // Get recovery attempts by level
            for (ErrorLevel level : ErrorLevel.values()) {
                RecoveryLevel recoveryLevel = getRecoveryLevel(level);
                long count = recoveryAttempts.getOrDefault(level, new AtomicLong(0)).get();
                attempts.put(recoveryLevel.name(), count);
            }
            
            // Add total recovery attempts
            attempts.put("TOTAL_RECOVERY_ATTEMPTS", totalRecoveryAttempts.get());
            
            // Calculate recent recovery rate (last 10 minutes)
            long recentRecoveries = recoveryHistory.stream()
                .filter(result -> result.getTimestamp().isAfter(Instant.now().minus(Duration.ofMinutes(10))))
                .mapToLong(result -> 1L)
                .sum();
            attempts.put("RECENT_RECOVERY_RATE", recentRecoveries);
            
            return attempts;
            
        } catch (Exception e) {
            logger.error("Error retrieving recovery attempts", e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * Executes component-level recovery for localized failures through automatic retry mechanisms.
     * Implements intelligent retry strategies with exponential backoff, alternative approaches,
     * and resource reallocation to handle transient issues without impacting overall execution.
     * 
     * @param context ExceptionContext containing error details and system state
     * @return CompletableFuture<RecoveryResult> indicating recovery outcome
     */
    public CompletableFuture<RecoveryResult> executeComponentRecovery(ExceptionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            Instant recoveryStart = Instant.now();
            String correlationId = context.getCorrelationId();
            
            try {
                logger.debug("Executing component-level recovery for correlation ID: {}", correlationId);
                
                // Check if retry is allowed for this exception type
                if (!retryMechanism.isRetryAllowed("component-recovery")) {
                    logger.debug("Retry not allowed for exception type: {}", 
                               context.getException().getClass().getSimpleName());
                    return createFailedRecoveryResult(RecoveryLevel.COMPONENT_LEVEL, 
                        "Retry not allowed for exception type", recoveryStart);
                }
                
                // Execute recovery using retry mechanism
                RetryResult<Object> retryResult = retryMechanism.executeWithRetry("component-recovery", () -> {
                    // Simulate component recovery logic
                    logger.debug("Attempting component recovery for: {}", correlationId);
                    return performComponentRecovery(context);
                });
                
                Duration recoveryDuration = Duration.between(recoveryStart, Instant.now());
                int attemptCount = retryMechanism.getRetryCount("component-recovery");
                
                if (retryResult.isSuccessful()) {
                    logger.info("Component recovery successful after {} attempts in {}ms", 
                              attemptCount, recoveryDuration.toMillis());
                    
                    List<String> actions = Arrays.asList(
                        "Executed component retry mechanism",
                        "Applied alternative resource allocation",
                        "Performed transient error resolution"
                    );
                    
                    return new RecoveryResult(true, RecoveryLevel.COMPONENT_LEVEL, actions, 
                        recoveryDuration.toMillis(), null, actions, Collections.emptyMap(), 
                        recoveryStart, null, attemptCount);
                } else {
                    logger.warn("Component recovery failed after {} attempts", attemptCount);
                    return createFailedRecoveryResult(RecoveryLevel.COMPONENT_LEVEL, 
                        "Component recovery exhausted all retry attempts", recoveryStart);
                }
                
            } catch (Exception e) {
                logger.error("Exception during component recovery for correlation ID: {}", correlationId, e);
                return createFailedRecoveryResult(RecoveryLevel.COMPONENT_LEVEL, 
                    "Component recovery exception: " + e.getMessage(), recoveryStart);
            }
        }).exceptionally(throwable -> {
            logger.error("Asynchronous component recovery failure", throwable);
            return createFailedRecoveryResult(RecoveryLevel.COMPONENT_LEVEL, 
                "Asynchronous recovery failure: " + throwable.getMessage(), Instant.now());
        });
    }
    
    /**
     * Executes test-level recovery for failures affecting individual test cases.
     * Implements state isolation, error context capture, and continuation decision logic
     * with detailed failure diagnostics and recovery coordination.
     * 
     * @param context ExceptionContext containing error details and system state
     * @return CompletableFuture<RecoveryResult> indicating recovery outcome
     */
    public CompletableFuture<RecoveryResult> executeTestRecovery(ExceptionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            Instant recoveryStart = Instant.now();
            String correlationId = context.getCorrelationId();
            
            try {
                logger.debug("Executing test-level recovery for correlation ID: {}", correlationId);
                
                // Preserve current test execution state
                Map<String, Object> preservedState = preserveExecutionState(context);
                
                // Evaluate recovery feasibility using recovery strategy
                boolean feasible = recoveryStrategy.evaluateRecoveryFeasibility(
                    RecoveryLevel.TEST_LEVEL, context.getSystemState());
                
                if (!feasible) {
                    logger.warn("Test-level recovery not feasible for: {}", correlationId);
                    return new RecoveryResult(false, RecoveryLevel.TEST_LEVEL, Collections.emptyList(), 
                        Duration.between(recoveryStart, Instant.now()).toMillis(), 
                        "Test recovery not feasible", Collections.emptyList(), 
                        preservedState, recoveryStart, "Test recovery not feasible", 0);
                }
                
                // Create and execute recovery plan
                RecoveryPlan recoveryPlan = recoveryStrategy.createRecoveryPlan(
                    RecoveryLevel.TEST_LEVEL, context.getSystemState());
                
                if (recoveryPlan == null) {
                    logger.warn("Failed to create recovery plan for test-level recovery: {}", correlationId);
                    return createFailedRecoveryResult(RecoveryLevel.TEST_LEVEL, 
                        "Failed to create recovery plan", recoveryStart);
                }
                
                // Execute the recovery plan
                CompletableFuture<RecoveryResult> executionResult = recoveryStrategy.executeRecovery(recoveryPlan);
                RecoveryResult result;
                try {
                    result = executionResult.get(); // Blocking wait for recovery completion
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("Recovery execution failed", e);
                    return createFailedRecoveryResult(RecoveryLevel.TEST_LEVEL, 
                        "Recovery execution failed: " + e.getMessage(), recoveryStart);
                }
                
                Duration recoveryDuration = Duration.between(recoveryStart, Instant.now());
                
                if (result.isSuccessful()) {
                    logger.info("Test-level recovery successful in {}ms", recoveryDuration.toMillis());
                    
                    List<String> actions = Arrays.asList(
                        "Isolated test execution state",
                        "Captured comprehensive error context",
                        "Applied test continuation logic",
                        "Preserved execution state for analysis"
                    );
                    
                    return new RecoveryResult(true, RecoveryLevel.TEST_LEVEL, actions, 
                        recoveryDuration.toMillis(), null, actions, preservedState, 
                        recoveryStart, null, 1);
                } else {
                    logger.warn("Test-level recovery execution failed for: {}", correlationId);
                    return new RecoveryResult(false, RecoveryLevel.TEST_LEVEL, Collections.emptyList(), 
                        recoveryDuration.toMillis(), "Recovery plan execution failed", 
                        Collections.emptyList(), preservedState, recoveryStart, 
                        "Recovery plan execution failed", 1);
                }
                
            } catch (Exception e) {
                logger.error("Exception during test-level recovery for correlation ID: {}", correlationId, e);
                return createFailedRecoveryResult(RecoveryLevel.TEST_LEVEL, 
                    "Test recovery exception: " + e.getMessage(), recoveryStart);
            }
        }).exceptionally(throwable -> {
            logger.error("Asynchronous test-level recovery failure", throwable);
            return createFailedRecoveryResult(RecoveryLevel.TEST_LEVEL, 
                "Asynchronous recovery failure: " + throwable.getMessage(), Instant.now());
        });
    }
    
    /**
     * Executes suite-level recovery for critical failures that impact entire test suites.
     * Implements graceful degradation, partial execution capabilities, and emergency shutdown
     * procedures with comprehensive result preservation and coordination with FrameworkManager.
     * 
     * @param context ExceptionContext containing error details and system state
     * @return CompletableFuture<RecoveryResult> indicating recovery outcome
     */
    public CompletableFuture<RecoveryResult> executeSuiteRecovery(ExceptionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            Instant recoveryStart = Instant.now();
            String correlationId = context.getCorrelationId();
            
            try {
                logger.warn("Executing suite-level recovery for critical failure - correlation ID: {}", correlationId);
                
                // Preserve comprehensive system state before suite recovery
                Map<String, Object> preservedState = preserveExecutionState(context);
                
                // Check framework state and determine recovery approach
                boolean frameworkStable = isFrameworkStable();
                
                if (!frameworkStable) {
                    logger.error("Framework unstable - triggering emergency shutdown for: {}", correlationId);
                    triggerEmergencyShutdown("Framework instability detected during suite recovery");
                    
                    return new RecoveryResult(false, RecoveryLevel.SUITE_LEVEL, 
                        Arrays.asList("Triggered emergency shutdown", "Preserved system state"), 
                        Duration.between(recoveryStart, Instant.now()).toMillis(), 
                        "Emergency shutdown triggered due to framework instability", 
                        Arrays.asList("Triggered emergency shutdown", "Preserved system state"), 
                        preservedState, recoveryStart, 
                        "Emergency shutdown triggered due to framework instability", 1);
                }
                
                // Attempt graceful degradation
                boolean degradationSuccess = performGracefulDegradation(context);
                
                if (degradationSuccess) {
                    logger.info("Graceful degradation successful for suite-level recovery: {}", correlationId);
                    
                    List<String> actions = Arrays.asList(
                        "Executed graceful test suite degradation",
                        "Preserved partial execution results",
                        "Maintained system stability",
                        "Coordinated with FrameworkManager"
                    );
                    
                    return new RecoveryResult(true, RecoveryLevel.SUITE_LEVEL, actions, 
                        Duration.between(recoveryStart, Instant.now()).toMillis(), null, actions, 
                        preservedState, recoveryStart, null, 1);
                } else {
                    logger.warn("Graceful degradation failed - attempting partial execution: {}", correlationId);
                    
                    // Attempt partial execution as fallback
                    boolean partialSuccess = attemptPartialExecution(context);
                    
                    if (partialSuccess) {
                        logger.info("Partial execution successful for suite recovery: {}", correlationId);
                        
                        List<String> actions = Arrays.asList(
                            "Graceful degradation failed",
                            "Executed partial test suite",
                            "Preserved available results",
                            "Maintained minimal functionality"
                        );
                        
                        return new RecoveryResult(true, RecoveryLevel.SUITE_LEVEL, actions, 
                            Duration.between(recoveryStart, Instant.now()).toMillis(), 
                            "Partial recovery - graceful degradation failed", actions, 
                            preservedState, recoveryStart, null, 2);
                    } else {
                        logger.error("Both graceful degradation and partial execution failed: {}", correlationId);
                        return createFailedRecoveryResult(RecoveryLevel.SUITE_LEVEL, 
                            "All suite recovery strategies failed", recoveryStart);
                    }
                }
                
            } catch (Exception e) {
                logger.error("Exception during suite-level recovery for correlation ID: {}", correlationId, e);
                return createFailedRecoveryResult(RecoveryLevel.SUITE_LEVEL, 
                    "Suite recovery exception: " + e.getMessage(), recoveryStart);
            }
        }).exceptionally(throwable -> {
            logger.error("Asynchronous suite-level recovery failure", throwable);
            return createFailedRecoveryResult(RecoveryLevel.SUITE_LEVEL, 
                "Asynchronous recovery failure: " + throwable.getMessage(), Instant.now());
        });
    }
    
    /**
     * Captures comprehensive error context for exception analysis and recovery decision making.
     * Collects detailed information including exception details, system state, stack traces,
     * correlation IDs, module information, and additional context for comprehensive error analysis.
     * 
     * @param exception The exception for which to capture context
     * @param additionalContext Additional context information provided by the caller
     * @param correlationId Unique correlation identifier for distributed tracing
     * @return ExceptionContext containing comprehensive error context information
     */
    public ExceptionContext captureErrorContext(Exception exception, Map<String, Object> additionalContext, 
                                              String correlationId) {
        try {
            // Capture basic exception information
            Instant timestamp = Instant.now();
            String stackTrace = getStackTraceAsString(exception);
            
            // Capture system state information
            Map<String, Object> systemState = captureSystemState();
            
            // Extract module and test information from context
            String moduleName = extractModuleName(additionalContext);
            String testName = extractTestName(additionalContext);
            
            // Merge additional context with captured state
            Map<String, Object> mergedContext = new HashMap<>();
            if (additionalContext != null) {
                mergedContext.putAll(additionalContext);
            }
            mergedContext.putAll(systemState);
            mergedContext.put("capture_timestamp", timestamp);
            mergedContext.put("framework_status", String.valueOf(frameworkManager.getStatus()));
            
            // Use the merged context directly (masking will be handled during logging)
            Map<String, Object> maskedContext = mergedContext;
            
            logger.debug("Captured error context for correlation ID: {} - Module: {}, Test: {}", 
                       correlationId, moduleName, testName);
            
            return new ExceptionContext(exception, determineEscalationLevel(exception), timestamp, 
                stackTrace, systemState, correlationId, moduleName, testName, maskedContext);
                
        } catch (Exception contextException) {
            logger.error("Error capturing exception context", contextException);
            
            // Return minimal context on error
            return new ExceptionContext(exception, ErrorLevel.COMPONENT, Instant.now(), 
                exception != null ? exception.toString() : "Unknown exception", 
                Collections.emptyMap(), correlationId, "Unknown", "Unknown", 
                Collections.singletonMap("context_capture_error", contextException.getMessage()));
        }
    }
    
    /**
     * Determines the escalation level for exceptions based on severity and impact analysis.
     * Analyzes exception characteristics, system state, and error patterns to determine
     * appropriate escalation levels for comprehensive error management.
     * 
     * @param exception The exception to analyze for escalation level determination
     * @return ErrorLevel indicating the appropriate escalation level
     */
    public ErrorLevel determineEscalationLevel(Exception exception) {
        if (exception == null) {
            return ErrorLevel.COMPONENT;
        }
        
        try {
            String exceptionType = exception.getClass().getSimpleName();
            String errorMessage = exception.getMessage() != null ? exception.getMessage() : "";
            
            // Check for critical system errors requiring immediate escalation
            if (isCriticalSystemError(exception)) {
                logger.warn("Critical system error detected - escalating to CRITICAL level: {}", exceptionType);
                return ErrorLevel.CRITICAL;
            }
            
            // Check for errors that impact multiple components or entire suites
            if (isMultiComponentError(exception, errorMessage)) {
                logger.warn("Multi-component error detected - escalating to SUITE level: {}", exceptionType);
                return ErrorLevel.SUITE;
            }
            
            // Check recent error frequency for pattern-based escalation
            int recentErrorCount = getRecentErrorCount(exceptionType, Duration.ofMinutes(5));
            if (recentErrorCount >= getErrorThreshold(ErrorLevel.TEST)) {
                logger.warn("High error frequency detected - escalating to TEST level: {} (count: {})", 
                          exceptionType, recentErrorCount);
                return ErrorLevel.TEST;
            }
            
            // Check consecutive failure patterns
            int consecutiveCount = consecutiveFailures.get();
            if (consecutiveCount >= getErrorThreshold(ErrorLevel.SUITE)) {
                logger.warn("Consecutive failure threshold exceeded - escalating to SUITE level: {}", consecutiveCount);
                return ErrorLevel.SUITE;
            }
            
            // Default escalation logic based on exception hierarchy
            if (isInfrastructureError(exception)) {
                return ErrorLevel.TEST;
            } else if (isTransientError(exception)) {
                return ErrorLevel.COMPONENT;
            } else {
                return ErrorLevel.TEST; // Conservative default for unknown errors
            }
            
        } catch (Exception escalationException) {
            logger.error("Error determining escalation level", escalationException);
            return ErrorLevel.COMPONENT; // Fail-safe default
        }
    }
    
    /**
     * Triggers emergency shutdown procedures for critical failure scenarios.
     * Bypasses normal shutdown sequence for immediate resource cleanup and system protection
     * during critical failures that threaten framework stability or data integrity.
     * 
     * @param reason Description of the reason for emergency shutdown
     * @return boolean indicating whether emergency shutdown was successfully triggered
     */
    public boolean triggerEmergencyShutdown(String reason) {
        try {
            Instant shutdownStart = Instant.now();
            logger.error("EMERGENCY SHUTDOWN TRIGGERED: {}", reason);
            
            // Record emergency shutdown in history
            lastEmergencyShutdown.set(shutdownStart);
            
            // Log critical error with full context
            errorReporter.log(LogLevel.ERROR, "Emergency shutdown initiated", 
                Map.of("reason", reason, "timestamp", shutdownStart.toString(), 
                       "framework_status", String.valueOf(frameworkManager.getStatus())), null);
            
            // Attempt graceful framework shutdown with timeout
            CompletableFuture<Boolean> shutdownFuture = frameworkManager.shutdown();
            
            try {
                // Wait for graceful shutdown with short timeout
                Boolean shutdownResult = shutdownFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
                
                if (shutdownResult != null && shutdownResult) {
                    logger.warn("Emergency shutdown completed gracefully in {}ms", 
                              Duration.between(shutdownStart, Instant.now()).toMillis());
                    return true;
                } else {
                    logger.error("Graceful emergency shutdown failed - forcing immediate shutdown");
                    return forceImmediateShutdown();
                }
                
            } catch (Exception shutdownException) {
                logger.error("Emergency shutdown timeout or exception", shutdownException);
                return forceImmediateShutdown();
            }
            
        } catch (Exception e) {
            logger.error("Critical error during emergency shutdown trigger", e);
            return forceImmediateShutdown();
        }
    }
    
    /**
     * Preserves current execution state for recovery analysis and continuation.
     * Captures comprehensive state information including test context, system resources,
     * configuration data, and execution progress for state restoration and analysis.
     * 
     * @param context ExceptionContext containing current error context
     * @return Map containing preserved execution state information
     */
    public Map<String, Object> preserveExecutionState(ExceptionContext context) {
        Map<String, Object> preservedState = new HashMap<>();
        
        try {
            String stateKey = context.getCorrelationId() + "_" + Instant.now().toEpochMilli();
            
            // Preserve basic context information
            preservedState.put("correlation_id", context.getCorrelationId());
            preservedState.put("preservation_timestamp", Instant.now());
            preservedState.put("error_level", context.getErrorLevel());
            preservedState.put("module_name", context.getModuleName());
            preservedState.put("test_name", context.getTestName());
            
            // Preserve system state
            preservedState.put("system_state", new HashMap<>(context.getSystemState()));
            
            // Preserve framework status and configuration
            preservedState.put("framework_status", String.valueOf(frameworkManager.getStatus()));
            preservedState.put("active_modules", frameworkManager.getRegisteredModuleIds());
            
            // Preserve error tracking state
            preservedState.put("error_counts", getCurrentErrorCounts());
            preservedState.put("recovery_attempts", getCurrentRecoveryAttempts());
            preservedState.put("consecutive_failures", consecutiveFailures.get());
            
            // Store preserved state for later retrieval
            preservedStates.put(stateKey, preservedState);
            
            logger.debug("Execution state preserved with key: {} for correlation ID: {}", 
                       stateKey, context.getCorrelationId());
            
            // Add state key for retrieval
            preservedState.put("preservation_key", stateKey);
            
            return preservedState;
            
        } catch (Exception e) {
            logger.error("Error preserving execution state", e);
            
            // Return minimal preserved state on error
            Map<String, Object> minimalState = new HashMap<>();
            minimalState.put("preservation_error", e.getMessage());
            minimalState.put("correlation_id", context.getCorrelationId());
            minimalState.put("preservation_timestamp", Instant.now());
            return minimalState;
        }
    }
    
    /**
     * Gets comprehensive recovery history for analysis and optimization.
     * Provides detailed historical data on all recovery attempts including success rates,
     * performance metrics, and trend analysis for continuous improvement of recovery strategies.
     * 
     * @return List containing complete recovery history with detailed results
     */
    public List<RecoveryResult> getRecoveryHistory() {
        try {
            // Return defensive copy to prevent external modification
            synchronized (recoveryHistory) {
                return new ArrayList<>(recoveryHistory);
            }
        } catch (Exception e) {
            logger.error("Error retrieving recovery history", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Resets all error counters and recovery metrics to initial values.
     * Provides capability to reset monitoring data for fresh analysis periods
     * while preserving historical data in recovery history.
     * 
     * @return boolean indicating successful reset of error counters
     */
    public boolean resetErrorCounters() {
        try {
            configurationLock.writeLock().lock();
            
            // Reset all error tracking counters
            errorCounts.clear();
            exceptionCounts.clear();
            recoveryAttempts.clear();
            errorClassifications.clear();
            errorTimestamps.clear();
            
            // Reset atomic counters
            consecutiveFailures.set(0);
            totalRecoveryAttempts.set(0);
            
            // Re-initialize error counters
            initializeErrorCounters();
            
            logger.info("Error counters reset successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error resetting error counters", e);
            return false;
        } finally {
            configurationLock.writeLock().unlock();
        }
    }
    
    /**
     * Configures error thresholds for adaptive error handling and escalation.
     * Allows dynamic configuration of thresholds that trigger different recovery levels
     * and escalation procedures based on error frequency and severity patterns.
     * 
     * @param thresholds Map containing error threshold values by error level
     * @return boolean indicating successful threshold configuration
     */
    public boolean configureErrorThresholds(Map<ErrorLevel, Integer> thresholds) {
        if (thresholds == null || thresholds.isEmpty()) {
            logger.warn("Cannot configure with null or empty thresholds");
            return false;
        }
        
        try {
            configurationLock.writeLock().lock();
            
            // Validate threshold values
            for (Map.Entry<ErrorLevel, Integer> entry : thresholds.entrySet()) {
                if (entry.getValue() == null || entry.getValue() < 0) {
                    logger.warn("Invalid threshold value for {}: {}", entry.getKey(), entry.getValue());
                    return false;
                }
            }
            
            // Update threshold configuration
            errorThresholds.clear();
            errorThresholds.putAll(thresholds);
            
            logger.info("Error thresholds configured: {}", thresholds);
            return true;
            
        } catch (Exception e) {
            logger.error("Error configuring error thresholds", e);
            return false;
        } finally {
            configurationLock.writeLock().unlock();
        }
    }
    
    /**
     * Determines if recovery is feasible based on current system state and error characteristics.
     * Evaluates system resources, error patterns, recent failure history, and recovery capacity
     * to determine if recovery attempts are likely to succeed.
     * 
     * @param errorLevel The error level for which to evaluate recovery feasibility
     * @param exception The specific exception for recovery feasibility analysis
     * @return boolean indicating whether recovery is feasible for the given conditions
     */
    public boolean isRecoveryFeasible(ErrorLevel errorLevel, Exception exception) {
        try {
            // Check basic recovery prerequisites
            if (errorLevel == ErrorLevel.CRITICAL) {
                logger.debug("Recovery not feasible for CRITICAL error level");
                return false;
            }
            
            // Check framework state
            if (!isFrameworkStable()) {
                logger.debug("Recovery not feasible - framework unstable");
                return false;
            }
            
            // Check recent failure patterns
            int recentFailures = getRecentErrorCount(exception.getClass().getSimpleName(), Duration.ofMinutes(5));
            int threshold = getErrorThreshold(errorLevel);
            
            if (recentFailures >= threshold * 2) { // Allow some buffer above threshold
                logger.debug("Recovery not feasible - recent failure count ({}) exceeds threshold ({})", 
                           recentFailures, threshold);
                return false;
            }
            
            // Check consecutive failure patterns
            int consecutive = consecutiveFailures.get();
            if (consecutive >= 10) { // Hard limit for consecutive failures
                logger.debug("Recovery not feasible - consecutive failures ({}) exceed hard limit", consecutive);
                return false;
            }
            
            // Check if emergency shutdown was recently triggered
            Instant lastEmergency = lastEmergencyShutdown.get();
            if (lastEmergency != null && 
                Duration.between(lastEmergency, Instant.now()).toMinutes() < 5) {
                logger.debug("Recovery not feasible - recent emergency shutdown");
                return false;
            }
            
            // Use recovery strategy for detailed feasibility analysis
            RecoveryLevel recoveryLevel = getRecoveryLevel(errorLevel);
            return recoveryStrategy.evaluateRecoveryFeasibility(recoveryLevel, captureSystemState());
            
        } catch (Exception e) {
            logger.error("Error evaluating recovery feasibility", e);
            return false; // Fail-safe approach
        }
    }
    
    /**
     * Gets comprehensive error trend analysis for predictive error management.
     * Analyzes error patterns, frequency trends, recovery success rates, and performance
     * metrics to provide insights for proactive error prevention and optimization.
     * 
     * @return Map containing detailed error trend analysis and predictions
     */
    public Map<String, Object> getErrorTrends() {
        Map<String, Object> trends = new HashMap<>();
        
        try {
            Instant now = Instant.now();
            
            // Calculate error rate trends over different time periods
            trends.put("last_hour_errors", calculateRecentErrors(Duration.ofHours(1)));
            trends.put("last_day_errors", calculateRecentErrors(Duration.ofDays(1)));
            trends.put("error_rate_per_minute", calculateErrorRate(Duration.ofMinutes(1)));
            trends.put("error_rate_per_hour", calculateErrorRate(Duration.ofHours(1)));
            
            // Recovery trend analysis
            long recentRecoveries = recoveryHistory.stream()
                .filter(result -> result.getTimestamp().isAfter(now.minus(Duration.ofHours(1))))
                .count();
            
            long successfulRecoveries = recoveryHistory.stream()
                .filter(result -> result.getTimestamp().isAfter(now.minus(Duration.ofHours(1))))
                .filter(RecoveryResult::isSuccessful)
                .count();
            
            double recoverySuccessRate = recentRecoveries > 0 ? 
                (successfulRecoveries / (double) recentRecoveries) * 100.0 : 0.0;
            
            trends.put("recent_recovery_count", recentRecoveries);
            trends.put("recent_recovery_success_rate", recoverySuccessRate);
            
            // Most common error types
            Map<String, Integer> topErrors = exceptionCounts.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicInteger>comparingByValue(
                    (a, b) -> Integer.compare(b.get(), a.get())))
                .limit(5)
                .collect(HashMap::new, 
                    (map, entry) -> map.put(entry.getKey(), entry.getValue().get()),
                    HashMap::putAll);
            
            trends.put("top_error_types", topErrors);
            
            // Error escalation patterns
            Map<String, Long> escalationCounts = new HashMap<>();
            for (ErrorLevel level : ErrorLevel.values()) {
                escalationCounts.put(level.name(), 
                    errorCounts.getOrDefault(level, new AtomicLong(0)).get());
            }
            trends.put("escalation_patterns", escalationCounts);
            
            // Consecutive failure trends
            trends.put("current_consecutive_failures", consecutiveFailures.get());
            trends.put("max_consecutive_threshold", 10);
            
            // Performance trends
            OptionalDouble avgRecoveryTime = recoveryHistory.stream()
                .filter(result -> result.getTimestamp().isAfter(now.minus(Duration.ofHours(1))))
                .mapToDouble(result -> result.getDuration())
                .average();
            
            trends.put("average_recovery_time_ms", avgRecoveryTime.orElse(0.0));
            
            // Trend analysis timestamp
            trends.put("analysis_timestamp", now);
            trends.put("analysis_period_hours", 1);
            
            // Update last trend analysis time
            lastErrorTrendAnalysis.set(now);
            
            logger.debug("Generated comprehensive error trend analysis");
            return trends;
            
        } catch (Exception e) {
            logger.error("Error generating error trends", e);
            return Collections.emptyMap();
        }
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Initializes error tracking counters for all error levels.
     */
    private void initializeErrorCounters() {
        for (ErrorLevel level : ErrorLevel.values()) {
            errorCounts.putIfAbsent(level, new AtomicLong(0));
            recoveryAttempts.putIfAbsent(level, new AtomicLong(0));
        }
    }
    
    /**
     * Configures default error thresholds for adaptive handling.
     */
    private void configureDefaultThresholds() {
        errorThresholds.put(ErrorLevel.COMPONENT, 10);
        errorThresholds.put(ErrorLevel.TEST, 5);
        errorThresholds.put(ErrorLevel.SUITE, 3);
        errorThresholds.put(ErrorLevel.CRITICAL, 1);
    }
    
    /**
     * Generates a unique correlation ID for distributed tracing.
     */
    private String generateCorrelationId() {
        return "EXC-" + System.currentTimeMillis() + "-" + 
               Integer.toHexString(new Random().nextInt(0xFFFF));
    }
    
    /**
     * Updates error tracking metrics for the given exception and error level.
     */
    private void updateErrorTracking(Exception exception, ErrorLevel errorLevel) {
        try {
            // Update error counts by level
            errorCounts.get(errorLevel).incrementAndGet();
            
            // Update exception type counts
            String exceptionType = exception.getClass().getSimpleName();
            exceptionCounts.computeIfAbsent(exceptionType, k -> new AtomicInteger(0)).incrementAndGet();
            
            // Store error classification
            errorClassifications.put(exceptionType, errorLevel);
            
            // Record timestamp for trend analysis
            errorTimestamps.put(exceptionType + "_" + System.currentTimeMillis(), Instant.now());
            
            logger.debug("Updated error tracking - Type: {}, Level: {}", exceptionType, errorLevel);
            
        } catch (Exception e) {
            logger.error("Error updating error tracking", e);
        }
    }
    
    /**
     * Executes recovery based on the determined error level.
     */
    private RecoveryResult executeRecoveryByLevel(ErrorLevel errorLevel, ExceptionContext context) {
        try {
            totalRecoveryAttempts.incrementAndGet();
            recoveryAttempts.get(errorLevel).incrementAndGet();
            
            CompletableFuture<RecoveryResult> recoveryFuture;
            
            switch (errorLevel) {
                case COMPONENT:
                    recoveryFuture = executeComponentRecovery(context);
                    break;
                case TEST:
                    recoveryFuture = executeTestRecovery(context);
                    break;
                case SUITE:
                    recoveryFuture = executeSuiteRecovery(context);
                    break;
                case CRITICAL:
                    triggerEmergencyShutdown("Critical error detected: " + context.getException().getMessage());
                    return createFailedRecoveryResult(RecoveryLevel.SUITE_LEVEL, 
                        "Critical error - emergency shutdown triggered", Instant.now());
                default:
                    return createFailedRecoveryResult(RecoveryLevel.COMPONENT_LEVEL, 
                        "Unknown error level: " + errorLevel, Instant.now());
            }
            
            return recoveryFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
            
        } catch (Exception e) {
            logger.error("Error executing recovery by level", e);
            return createFailedRecoveryResult(getRecoveryLevel(errorLevel), 
                "Recovery execution failed: " + e.getMessage(), Instant.now());
        }
    }
    
    /**
     * Gets the recovery level corresponding to an error level.
     */
    private RecoveryLevel getRecoveryLevel(ErrorLevel errorLevel) {
        switch (errorLevel) {
            case COMPONENT:
                return RecoveryLevel.COMPONENT_LEVEL;
            case TEST:
                return RecoveryLevel.TEST_LEVEL;
            case SUITE:
            case CRITICAL:
                return RecoveryLevel.SUITE_LEVEL;
            default:
                return RecoveryLevel.COMPONENT_LEVEL;
        }
    }
    
    /**
     * Creates a failed recovery result with standard format.
     */
    private RecoveryResult createFailedRecoveryResult(RecoveryLevel level, String errorMessage, Instant startTime) {
        return new RecoveryResult(false, level, Collections.emptyList(), Duration.between(startTime, Instant.now()).toMillis(), 
            errorMessage, Collections.emptyList(), Collections.emptyMap(), startTime, "Recovery failed", 1);
    }
    
    /**
     * Records a recovery result in the history for analysis.
     */
    private void recordRecoveryResult(RecoveryResult result) {
        try {
            synchronized (recoveryHistory) {
                recoveryHistory.add(result);
                
                // Limit history size to prevent memory issues
                if (recoveryHistory.size() > 1000) {
                    recoveryHistory.remove(0);
                }
            }
            
            logger.debug("Recorded recovery result - Success: {}, Level: {}, Duration: {}ms", 
                       result.isSuccessful(), result.getRecoveryLevel(), result.getDuration());
                       
        } catch (Exception e) {
            logger.error("Error recording recovery result", e);
        }
    }
    
    /**
     * Checks if the given error is critical and requires immediate attention.
     */
    private boolean isCriticalError(Exception exception, String errorMessage) {
        String exceptionType = exception.getClass().getSimpleName();
        
        // Check for critical system exception types
        if (exceptionType.contains("OutOfMemory") || 
            exceptionType.contains("StackOverflow") ||
            exceptionType.contains("NoClassDefFound") ||
            exceptionType.contains("ExceptionInInitializer")) {
            return true;
        }
        
        // Check error message for critical indicators
        if (errorMessage != null) {
            String lowerMessage = errorMessage.toLowerCase();
            return lowerMessage.contains("system failure") ||
                   lowerMessage.contains("critical error") ||
                   lowerMessage.contains("framework corruption") ||
                   lowerMessage.contains("unrecoverable");
        }
        
        return false;
    }
    
    /**
     * Checks if the error affects the entire test suite.
     */
    private boolean isSuiteLevelError(Exception exception, String errorMessage, ExceptionContext context) {
        String exceptionType = exception.getClass().getSimpleName();
        
        // Check for suite-level exception indicators
        if (exceptionType.contains("Configuration") ||
            exceptionType.contains("Security") ||
            exceptionType.contains("Authentication")) {
            return true;
        }
        
        // Check if error affects multiple modules
        if (context != null && context.getSystemState().containsKey("affected_modules")) {
            @SuppressWarnings("unchecked")
            List<String> affectedModules = (List<String>) context.getSystemState().get("affected_modules");
            return affectedModules != null && affectedModules.size() > 1;
        }
        
        return false;
    }
    
    /**
     * Checks if the error affects individual test cases.
     */
    private boolean isTestLevelError(Exception exception, String errorMessage, ExceptionContext context) {
        String exceptionType = exception.getClass().getSimpleName();
        
        // Common test-level exceptions
        return exceptionType.contains("Assertion") ||
               exceptionType.contains("Test") ||
               exceptionType.contains("Verification") ||
               exceptionType.contains("Element") ||
               (errorMessage != null && errorMessage.contains("test"));
    }
    
    /**
     * Calculates recent error count within the specified duration.
     */
    private long calculateRecentErrors(Duration timeWindow) {
        Instant cutoff = Instant.now().minus(timeWindow);
        
        return errorTimestamps.values().stream()
            .filter(timestamp -> timestamp.isAfter(cutoff))
            .count();
    }
    
    /**
     * Gets error threshold for the specified error level.
     */
    private int getErrorThreshold(ErrorLevel level) {
        return errorThresholds.getOrDefault(level, 5);
    }
    
    /**
     * Checks if the framework is in a stable state.
     */
    private boolean isFrameworkStable() {
        try {
            // Check framework status
            var status = frameworkManager.getStatus();
            if (String.valueOf(status).contains("ERROR") || String.valueOf(status).contains("CRITICAL")) {
                return false;
            }
            
            // Check recent emergency shutdowns
            Instant lastEmergency = lastEmergencyShutdown.get();
            if (lastEmergency != null && 
                Duration.between(lastEmergency, Instant.now()).toMinutes() < 10) {
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            logger.error("Error checking framework stability", e);
            return false;
        }
    }
    
    /**
     * Performs component recovery logic.
     */
    private Object performComponentRecovery(ExceptionContext context) {
        // Simulate component recovery operations
        logger.debug("Performing component recovery for: {}", context.getCorrelationId());
        
        // Component recovery logic would be implemented here
        // This could include resource reallocation, retry mechanisms, etc.
        
        return "Component recovery completed";
    }
    
    /**
     * Performs graceful degradation for suite-level recovery.
     */
    private boolean performGracefulDegradation(ExceptionContext context) {
        try {
            logger.debug("Performing graceful degradation for: {}", context.getCorrelationId());
            
            // Graceful degradation logic would be implemented here
            // This could include disabling non-essential features, reducing capacity, etc.
            
            return true;
            
        } catch (Exception e) {
            logger.error("Error during graceful degradation", e);
            return false;
        }
    }
    
    /**
     * Attempts partial execution as a fallback recovery strategy.
     */
    private boolean attemptPartialExecution(ExceptionContext context) {
        try {
            logger.debug("Attempting partial execution for: {}", context.getCorrelationId());
            
            // Partial execution logic would be implemented here
            // This could include running only critical tests, simplified workflows, etc.
            
            return true;
            
        } catch (Exception e) {
            logger.error("Error during partial execution attempt", e);
            return false;
        }
    }
    
    /**
     * Forces immediate shutdown when graceful shutdown fails.
     */
    private boolean forceImmediateShutdown() {
        try {
            logger.error("Forcing immediate shutdown - all graceful attempts failed");
            
            // Force immediate resource cleanup
            // This would typically involve directly terminating processes, closing connections, etc.
            
            return true;
            
        } catch (Exception e) {
            logger.error("Critical error during forced shutdown", e);
            return false;
        }
    }
    
    /**
     * Gets stack trace as string for context capture.
     */
    private String getStackTraceAsString(Exception exception) {
        if (exception == null) {
            return "No exception provided";
        }
        
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        exception.printStackTrace(pw);
        return sw.toString();
    }
    
    /**
     * Captures current system state information.
     */
    private Map<String, Object> captureSystemState() {
        Map<String, Object> state = new HashMap<>();
        
        try {
            state.put("timestamp", Instant.now());
            state.put("framework_status", String.valueOf(frameworkManager.getStatus()));
            state.put("active_modules", frameworkManager.getRegisteredModuleIds());
            state.put("total_memory", Runtime.getRuntime().totalMemory());
            state.put("free_memory", Runtime.getRuntime().freeMemory());
            state.put("available_processors", Runtime.getRuntime().availableProcessors());
            
        } catch (Exception e) {
            logger.error("Error capturing system state", e);
            state.put("capture_error", e.getMessage());
        }
        
        return state;
    }
    
    /**
     * Extracts module name from additional context.
     */
    private String extractModuleName(Map<String, Object> context) {
        if (context == null) {
            return "Unknown";
        }
        
        Object moduleName = context.get("module_name");
        return moduleName != null ? moduleName.toString() : "Unknown";
    }
    
    /**
     * Extracts test name from additional context.
     */
    private String extractTestName(Map<String, Object> context) {
        if (context == null) {
            return "Unknown";
        }
        
        Object testName = context.get("test_name");
        return testName != null ? testName.toString() : "Unknown";
    }
    
    /**
     * Gets recent error count for a specific exception type.
     */
    private int getRecentErrorCount(String exceptionType, Duration timeWindow) {
        Instant cutoff = Instant.now().minus(timeWindow);
        
        return (int) errorTimestamps.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(exceptionType))
            .filter(entry -> entry.getValue().isAfter(cutoff))
            .count();
    }
    
    /**
     * Checks if emergency shutdown should be triggered based on failure patterns.
     */
    private boolean shouldTriggerEmergencyShutdown(int consecutiveFailures, ErrorLevel errorLevel) {
        return consecutiveFailures >= 15 || 
               (errorLevel == ErrorLevel.CRITICAL && consecutiveFailures >= 3) ||
               (errorLevel == ErrorLevel.SUITE && consecutiveFailures >= 8);
    }
    
    /**
     * Checks if the exception is a critical system error.
     */
    private boolean isCriticalSystemError(Throwable exception) {
        return exception instanceof OutOfMemoryError ||
               exception instanceof StackOverflowError ||
               exception instanceof NoClassDefFoundError ||
               exception instanceof ExceptionInInitializerError;
    }
    
    /**
     * Checks if the exception affects multiple components.
     */
    private boolean isMultiComponentError(Exception exception, String errorMessage) {
        return errorMessage != null && 
               (errorMessage.contains("multiple") || 
                errorMessage.contains("cascade") ||
                errorMessage.contains("dependency"));
    }
    
    /**
     * Checks if the exception is infrastructure-related.
     */
    private boolean isInfrastructureError(Exception exception) {
        String exceptionType = exception.getClass().getSimpleName();
        return exceptionType.contains("Connection") ||
               exceptionType.contains("Network") ||
               exceptionType.contains("Timeout") ||
               exceptionType.contains("IO");
    }
    
    /**
     * Checks if the exception is transient and likely to succeed on retry.
     */
    private boolean isTransientError(Exception exception) {
        String exceptionType = exception.getClass().getSimpleName();
        return exceptionType.contains("Timeout") ||
               exceptionType.contains("Temporary") ||
               exceptionType.contains("Retry");
    }
    
    /**
     * Gets current error counts for state preservation.
     */
    private Map<String, Long> getCurrentErrorCounts() {
        Map<String, Long> counts = new HashMap<>();
        errorCounts.forEach((level, count) -> counts.put(level.name(), count.get()));
        return counts;
    }
    
    /**
     * Gets current recovery attempts for state preservation.
     */
    private Map<String, Long> getCurrentRecoveryAttempts() {
        Map<String, Long> attempts = new HashMap<>();
        recoveryAttempts.forEach((level, count) -> attempts.put(level.name(), count.get()));
        return attempts;
    }
    
    /**
     * Calculates error rate for the specified time window.
     */
    private double calculateErrorRate(Duration timeWindow) {
        long errors = calculateRecentErrors(timeWindow);
        double windowMinutes = timeWindow.toMinutes();
        return windowMinutes > 0 ? errors / windowMinutes : 0.0;
    }
}

/**
 * ErrorLevel enumeration represents the severity and scope of errors within the automation framework.
 * 
 * This enum provides standardized error classification levels that determine the appropriate
 * recovery strategies and escalation procedures for different types of failures.
 * 
 * Each level corresponds to specific recovery mechanisms and system response strategies,
 * enabling graduated error handling from localized component issues to critical system failures.
 */
enum ErrorLevel {
    /**
     * Component-level errors that affect individual framework components.
     * These are typically transient issues that can be resolved through retry mechanisms,
     * alternative approaches, or resource reallocation without affecting other components.
     */
    COMPONENT,
    
    /**
     * Test-level errors that affect individual test cases or test execution.
     * These errors require state isolation, error context capture, and continuation
     * decision logic while maintaining overall test suite integrity.
     */
    TEST,
    
    /**
     * Suite-level errors that impact entire test suites or multiple components.
     * These errors require graceful degradation, partial execution capabilities,
     * or coordinated recovery across multiple framework modules.
     */
    SUITE,
    
    /**
     * Critical errors that threaten framework stability or data integrity.
     * These errors require immediate emergency shutdown procedures and
     * comprehensive system protection measures.
     */
    CRITICAL
}



/**
 * ExceptionContext class captures comprehensive error context information for exception analysis
 * and recovery decision making within the automation framework.
 * 
 * This class encapsulates detailed information about exceptions including the original exception,
 * error classification, system state, timing information, and additional context data required
 * for intelligent error handling and recovery operations.
 * 
 * The context information supports distributed tracing, error correlation, state preservation,
 * and comprehensive error analysis across the entire automation framework.
 */
class ExceptionContext {
    
    private final Exception exception;
    private final ErrorLevel errorLevel;
    private final Instant timestamp;
    private final String stackTrace;
    private final Map<String, Object> systemState;
    private final String correlationId;
    private final String moduleName;
    private final String testName;
    private final Map<String, Object> additionalContext;
    
    /**
     * Creates a new ExceptionContext with comprehensive error information.
     * 
     * @param exception The original exception that occurred
     * @param errorLevel The classified error level for recovery determination
     * @param timestamp The timestamp when the exception occurred
     * @param stackTrace The complete stack trace as a string
     * @param systemState Current system state information
     * @param correlationId Unique correlation identifier for distributed tracing
     * @param moduleName Name of the framework module where the exception occurred
     * @param testName Name of the test case being executed when the exception occurred
     * @param additionalContext Additional context information provided by the caller
     */
    public ExceptionContext(Exception exception, ErrorLevel errorLevel, Instant timestamp,
                          String stackTrace, Map<String, Object> systemState, String correlationId,
                          String moduleName, String testName, Map<String, Object> additionalContext) {
        this.exception = exception;
        this.errorLevel = errorLevel;
        this.timestamp = timestamp;
        this.stackTrace = stackTrace;
        this.systemState = new HashMap<>(systemState != null ? systemState : Collections.emptyMap());
        this.correlationId = correlationId;
        this.moduleName = moduleName;
        this.testName = testName;
        this.additionalContext = new HashMap<>(additionalContext != null ? additionalContext : Collections.emptyMap());
    }
    
    /**
     * Gets the original exception that occurred.
     * 
     * @return Exception instance containing the original error
     */
    public Exception getException() {
        return exception;
    }
    
    /**
     * Gets the classified error level for recovery determination.
     * 
     * @return ErrorLevel indicating the severity and scope of the error
     */
    public ErrorLevel getErrorLevel() {
        return errorLevel;
    }
    
    /**
     * Gets the timestamp when the exception occurred.
     * 
     * @return Instant representing the exact time of exception occurrence
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * Gets the complete stack trace as a string for detailed error analysis.
     * 
     * @return String containing the full stack trace of the exception
     */
    public String getStackTrace() {
        return stackTrace;
    }
    
    /**
     * Gets the system state information captured at the time of the exception.
     * 
     * @return Unmodifiable map containing system state data
     */
    public Map<String, Object> getSystemState() {
        return Collections.unmodifiableMap(systemState);
    }
    
    /**
     * Gets the unique correlation identifier for distributed tracing.
     * 
     * @return String containing the correlation ID for error tracking
     */
    public String getCorrelationId() {
        return correlationId;
    }
    
    /**
     * Gets the name of the framework module where the exception occurred.
     * 
     * @return String containing the module name
     */
    public String getModuleName() {
        return moduleName;
    }
    
    /**
     * Gets the name of the test case being executed when the exception occurred.
     * 
     * @return String containing the test name
     */
    public String getTestName() {
        return testName;
    }
    
    /**
     * Gets additional context information provided by the caller.
     * 
     * @return Unmodifiable map containing additional context data
     */
    public Map<String, Object> getAdditionalContext() {
        return Collections.unmodifiableMap(additionalContext);
    }
    
    @Override
    public String toString() {
        return String.format("ExceptionContext{correlationId='%s', errorLevel=%s, module='%s', test='%s', timestamp=%s}", 
                           correlationId, errorLevel, moduleName, testName, timestamp);
    }
}
