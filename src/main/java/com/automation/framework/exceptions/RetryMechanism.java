package com.automation.framework.exceptions;

// External imports
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Internal imports
import com.automation.framework.exceptions.ErrorReporter;
import com.automation.framework.resources.ConnectionPoolManager;
import com.automation.framework.core.ConfigurationManager;
import com.automation.framework.resources.ThreadPoolManager;

/**
 * RetryMechanism provides configurable retry logic implementation with automatic retry capabilities
 * for transient failures. Features exponential backoff strategies, maximum retry limits, and circuit
 * breaker patterns to prevent retry storms.
 * 
 * Key Features:
 * - Configurable retry policies (exponential backoff, linear backoff, fixed delay, immediate)
 * - Circuit breaker pattern to prevent connection exhaustion
 * - Detailed logging and metrics collection for retry attempts
 * - Different retry strategies for different failure types
 * - Maximum concurrent retry limits to prevent retry storms
 * - Connection pool monitoring integration
 * 
 * Implementation follows enterprise-grade retry patterns with comprehensive error handling
 * and monitoring capabilities.
 */
public class RetryMechanism {
    
    private static final Logger logger = LoggerFactory.getLogger(RetryMechanism.class);
    
    // Singleton instance management
    private static volatile RetryMechanism instance;
    private static final Object instanceLock = new Object();
    
    // Framework integration components
    private final ErrorReporter errorReporter;
    private final ConnectionPoolManager connectionPoolManager;
    private final ConfigurationManager configurationManager;
    private final ThreadPoolManager threadPoolManager;
    
    // Retry configuration and state management
    private final Map<String, RetryConfiguration> operationConfigurations;
    private final Map<String, CircuitBreakerState> circuitBreakerStates;
    private final Map<String, Instant> circuitBreakerOpenTimes;
    private final Map<String, AtomicInteger> retryCounters;
    private final Map<String, Instant> lastRetryTimes;
    private final Map<String, RetryMetrics> operationMetrics;
    
    // Global retry control
    private final AtomicInteger globalRetryCount;
    private final AtomicInteger concurrentRetries;
    private final ScheduledExecutorService retryExecutor;
    
    // Default configuration values from specification
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long[] DEFAULT_BACKOFF_DELAYS = {1000, 2000, 4000}; // 1s, 2s, 4s
    private static final int DEFAULT_CONNECTION_TIMEOUT = 2000; // 2 seconds
    private static final double DEFAULT_CIRCUIT_BREAKER_THRESHOLD = 0.8; // 80%
    private static final int DEFAULT_MAX_CONCURRENT_RETRIES = 10;
    
    // Circuit breaker configuration
    private static final Duration CIRCUIT_BREAKER_OPEN_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration CIRCUIT_BREAKER_HALF_OPEN_TIMEOUT = Duration.ofSeconds(30);
    
    /**
     * Private constructor for singleton pattern.
     * Initializes all retry management components and framework integrations.
     */
    private RetryMechanism() {
        // Initialize framework integration components
        this.errorReporter = ErrorReporter.getInstance();
        this.connectionPoolManager = ConnectionPoolManager.getInstance();
        this.configurationManager = ConfigurationManager.getInstance();
        this.threadPoolManager = ThreadPoolManager.getInstance();
        
        // Initialize retry state management
        this.operationConfigurations = new ConcurrentHashMap<>();
        this.circuitBreakerStates = new ConcurrentHashMap<>();
        this.circuitBreakerOpenTimes = new ConcurrentHashMap<>();
        this.retryCounters = new ConcurrentHashMap<>();
        this.lastRetryTimes = new ConcurrentHashMap<>();
        this.operationMetrics = new ConcurrentHashMap<>();
        
        // Initialize global controls
        this.globalRetryCount = new AtomicInteger(0);
        this.concurrentRetries = new AtomicInteger(0);
        
        // Initialize retry executor with daemon threads
        this.retryExecutor = Executors.newScheduledThreadPool(5, r -> {
            Thread t = new Thread(r, "RetryMechanism-Executor");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) -> 
                logger.error("Uncaught exception in retry executor thread", ex));
            return t;
        });
        
        // Initialize default configurations
        initializeDefaultConfigurations();
        
        logger.info("RetryMechanism instance created successfully");
    }
    
    /**
     * Returns the singleton instance of RetryMechanism.
     * Thread-safe lazy initialization with double-checked locking.
     * 
     * @return RetryMechanism instance
     */
    public static RetryMechanism getInstance() {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new RetryMechanism();
                }
            }
        }
        return instance;
    }
    
    /**
     * Executes an operation with retry logic based on the specified or default configuration.
     * Implements exponential backoff and circuit breaker patterns for resilient operation execution.
     * 
     * @param <T> the return type of the operation
     * @param operationName unique identifier for the operation
     * @param operation the operation to execute with retry
     * @return RetryResult containing the execution outcome
     */
    public <T> RetryResult<T> executeWithRetry(String operationName, Supplier<T> operation) {
        return executeWithRetry(operationName, operation, getRetryConfiguration(operationName));
    }
    
    /**
     * Executes an operation with retry logic using the specified configuration.
     * 
     * @param <T> the return type of the operation
     * @param operationName unique identifier for the operation
     * @param operation the operation to execute with retry
     * @param config the retry configuration to use
     * @return RetryResult containing the execution outcome
     */
    public <T> RetryResult<T> executeWithRetry(String operationName, Supplier<T> operation, RetryConfiguration config) {
        // Set correlation ID for distributed tracing
        String correlationId = generateCorrelationId(operationName);
        errorReporter.setCorrelationId(correlationId);
        
        Instant startTime = Instant.now();
        int attemptCount = 0;
        Exception lastException = null;
        
        logger.info("Starting retry operation: {} with correlationId: {}", operationName, correlationId);
        
        try {
            // Check if operation is allowed (circuit breaker and concurrent retry limits)
            if (!isRetryAllowed(operationName)) {
                String reason = getRetryBlockedReason(operationName);
                logger.warn("Retry operation {} blocked: {}", operationName, reason);
                return createFailedResult(operationName, config.getRetryPolicy(), 
                    new RuntimeException("Retry operation blocked: " + reason), 
                    0, startTime, reason);
            }
            
            // Execute retry loop
            while (attemptCount <= config.getMaxRetries()) {
                attemptCount++;
                concurrentRetries.incrementAndGet();
                
                try {
                    logger.debug("Attempt {} for operation: {}", attemptCount, operationName);
                    
                    // Execute the operation
                    T result = operation.get();
                    
                    // Success - update metrics and return
                    Duration totalDuration = Duration.between(startTime, Instant.now());
                    updateSuccessMetrics(operationName, attemptCount, totalDuration);
                    resetCircuitBreaker(operationName);
                    
                    errorReporter.info("Operation {} succeeded on attempt {} in {}ms", 
                        operationName, attemptCount, totalDuration.toMillis());
                    
                    return createSuccessResult(operationName, config.getRetryPolicy(), result, 
                        attemptCount, startTime);
                        
                } catch (Exception e) {
                    lastException = e;
                    
                    // Log the attempt failure with detailed context
                    errorReporter.logException("Operation attempt failed", e);
                    logger.warn("Attempt {} failed for operation {}: {}", 
                        attemptCount, operationName, e.getMessage());
                    
                    // Update failure metrics
                    updateFailureMetrics(operationName);
                    
                    // Check if we should continue retrying
                    if (attemptCount > config.getMaxRetries()) {
                        break; // Exceeded max retries
                    }
                    
                    // Check if failure type is retryable
                    if (!isRetryableException(e)) {
                        logger.info("Non-retryable exception for operation {}: {}", 
                            operationName, e.getClass().getSimpleName());
                        break;
                    }
                    
                    // Calculate and apply backoff delay
                    long delayMs = calculateBackoffDelay(config, attemptCount - 1);
                    if (delayMs > 0) {
                        logger.debug("Applying backoff delay of {}ms before next attempt", delayMs);
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            logger.warn("Retry operation {} interrupted during backoff", operationName);
                            break;
                        }
                    }
                    
                    // Update last retry time
                    lastRetryTimes.put(operationName, Instant.now());
                    
                } finally {
                    concurrentRetries.decrementAndGet();
                }
            }
            
            // All retry attempts failed
            Duration totalDuration = Duration.between(startTime, Instant.now());
            updateCircuitBreakerOnFailure(operationName, attemptCount);
            
            String failureReason = String.format("All %d retry attempts failed. Last exception: %s", 
                attemptCount, lastException != null ? lastException.getMessage() : "Unknown");
            
            errorReporter.error("Retry operation {} failed after {} attempts in {}ms: {}", 
                operationName, attemptCount, totalDuration.toMillis(), failureReason);
            
            return createFailedResult(operationName, config.getRetryPolicy(), lastException, 
                attemptCount, startTime, failureReason);
                
        } finally {
            // Always decrement concurrent counter and clean up
            concurrentRetries.set(Math.max(0, concurrentRetries.get() - 1));
            globalRetryCount.incrementAndGet();
        }
    }
    
    /**
     * Gets the retry configuration for the specified operation.
     * Returns default configuration if no specific configuration exists.
     * 
     * @param operationName the operation name
     * @return RetryConfiguration for the operation
     */
    public RetryConfiguration getRetryConfiguration(String operationName) {
        return operationConfigurations.getOrDefault(operationName, getDefaultConfiguration());
    }
    
    /**
     * Resets the retry counter for the specified operation.
     * Useful for clearing retry state after successful execution or configuration changes.
     * 
     * @param operationName the operation name
     */
    public void resetRetryCounter(String operationName) {
        retryCounters.remove(operationName);
        lastRetryTimes.remove(operationName);
        resetCircuitBreaker(operationName);
        
        logger.debug("Reset retry counter for operation: {}", operationName);
    }
    
    /**
     * Returns comprehensive retry metrics for the specified operation.
     * 
     * @param operationName the operation name
     * @return RetryMetrics for the operation
     */
    public RetryMetrics getRetryMetrics(String operationName) {
        return operationMetrics.computeIfAbsent(operationName, k -> new RetryMetrics(k));
    }
    
    /**
     * Configures the retry policy for the specified operation.
     * 
     * @param operationName the operation name
     * @param config the retry configuration to apply
     */
    public void configureRetryPolicy(String operationName, RetryConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("Retry configuration cannot be null");
        }
        
        operationConfigurations.put(operationName, config);
        
        // Reset circuit breaker state when configuration changes
        resetCircuitBreaker(operationName);
        
        logger.info("Configured retry policy for operation {} - maxRetries: {}, policy: {}", 
            operationName, config.getMaxRetries(), config.getRetryPolicy());
    }
    
    /**
     * Sets the maximum number of retries for the specified operation.
     * 
     * @param operationName the operation name
     * @param maxRetries the maximum retry count
     */
    public void setMaxRetries(String operationName, int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries cannot be negative");
        }
        
        RetryConfiguration currentConfig = getRetryConfiguration(operationName);
        RetryConfiguration newConfig = new RetryConfiguration.Builder(currentConfig)
            .maxRetries(maxRetries)
            .build();
        
        configureRetryPolicy(operationName, newConfig);
    }
    
    /**
     * Sets the backoff strategy for the specified operation.
     * 
     * @param operationName the operation name
     * @param strategy the backoff strategy to use
     */
    public void setBackoffStrategy(String operationName, RetryPolicy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("Backoff strategy cannot be null");
        }
        
        RetryConfiguration currentConfig = getRetryConfiguration(operationName);
        RetryConfiguration newConfig = new RetryConfiguration.Builder(currentConfig)
            .retryPolicy(strategy)
            .build();
        
        configureRetryPolicy(operationName, newConfig);
    }
    
    /**
     * Checks if retry is allowed for the specified operation.
     * Considers circuit breaker state, concurrent retry limits, and pool utilization.
     * 
     * @param operationName the operation name
     * @return true if retry is allowed, false otherwise
     */
    public boolean isRetryAllowed(String operationName) {
        // Check circuit breaker state
        CircuitBreakerState circuitState = getCircuitBreakerState(operationName);
        if (circuitState == CircuitBreakerState.OPEN) {
            return false;
        }
        
        // Check concurrent retry limits
        int currentConcurrent = concurrentRetries.get();
        int maxConcurrent = getMaxConcurrentRetries();
        if (currentConcurrent >= maxConcurrent) {
            logger.warn("Maximum concurrent retries reached: {}/{}", currentConcurrent, maxConcurrent);
            return false;
        }
        
        // Check thread pool health
        if (!threadPoolManager.isThreadPoolHealthy()) {
            logger.warn("Thread pool unhealthy - blocking new retries");
            return false;
        }
        
        // Check connection pool utilization
        double poolUtilization = connectionPoolManager.getPoolUtilization();
        double threshold = DEFAULT_CIRCUIT_BREAKER_THRESHOLD;
        if (poolUtilization > threshold) {
            logger.warn("Connection pool utilization too high: {:.2f}% > {:.2f}%", 
                poolUtilization, threshold * 100);
            return false;
        }
        
        return true;
    }
    
    /**
     * Returns the timestamp of the last retry attempt for the specified operation.
     * 
     * @param operationName the operation name
     * @return Optional containing the last retry time, or empty if no retries have occurred
     */
    public Optional<Instant> getLastRetryTime(String operationName) {
        return Optional.ofNullable(lastRetryTimes.get(operationName));
    }
    
    /**
     * Returns the current retry count for the specified operation.
     * 
     * @param operationName the operation name
     * @return current retry count
     */
    public int getRetryCount(String operationName) {
        AtomicInteger counter = retryCounters.get(operationName);
        return counter != null ? counter.get() : 0;
    }
    
    // ================= PRIVATE HELPER METHODS =================
    
    /**
     * Initializes default retry configurations for common operation types.
     */
    private void initializeDefaultConfigurations() {
        // Default configuration based on specification requirements
        RetryConfiguration defaultConfig = new RetryConfiguration.Builder()
            .maxRetries(DEFAULT_MAX_RETRIES)
            .initialDelay(Duration.ofMillis(DEFAULT_BACKOFF_DELAYS[0]))
            .maxDelay(Duration.ofMillis(DEFAULT_BACKOFF_DELAYS[2]))
            .retryPolicy(RetryPolicy.EXPONENTIAL_BACKOFF)
            .circuitBreakerThreshold(DEFAULT_CIRCUIT_BREAKER_THRESHOLD)
            .timeoutDuration(Duration.ofMillis(DEFAULT_CONNECTION_TIMEOUT))
            .enabled(true)
            .backoffMultiplier(2.0)
            .build();
        
        // Web automation specific configuration
        RetryConfiguration webConfig = new RetryConfiguration.Builder(defaultConfig)
            .maxRetries(2) // Lower retries for web operations
            .retryPolicy(RetryPolicy.LINEAR_BACKOFF)
            .build();
        
        // API testing specific configuration
        RetryConfiguration apiConfig = new RetryConfiguration.Builder(defaultConfig)
            .maxRetries(DEFAULT_MAX_RETRIES)
            .retryPolicy(RetryPolicy.EXPONENTIAL_BACKOFF)
            .build();
        
        // Store configurations
        operationConfigurations.put("default", defaultConfig);
        operationConfigurations.put("web", webConfig);
        operationConfigurations.put("api", apiConfig);
        
        logger.debug("Initialized default retry configurations");
    }
    
    /**
     * Gets the default retry configuration.
     * 
     * @return default RetryConfiguration
     */
    private RetryConfiguration getDefaultConfiguration() {
        return operationConfigurations.get("default");
    }
    
    /**
     * Generates a unique correlation ID for operation tracking.
     * 
     * @param operationName the operation name
     * @return unique correlation ID
     */
    private String generateCorrelationId(String operationName) {
        return String.format("retry-%s-%d-%s", 
            operationName, 
            System.currentTimeMillis(), 
            UUID.randomUUID().toString().substring(0, 8));
    }
    
    /**
     * Determines if an exception is retryable based on its type and characteristics.
     * 
     * @param exception the exception to evaluate
     * @return true if the exception is retryable
     */
    private boolean isRetryableException(Exception exception) {
        // Non-retryable exceptions
        if (exception instanceof IllegalArgumentException ||
            exception instanceof SecurityException ||
            exception instanceof UnsupportedOperationException ||
            exception instanceof NullPointerException) {
            return false;
        }
        
        // Network and connection related exceptions are typically retryable
        String exceptionMessage = exception.getMessage();
        if (exceptionMessage != null) {
            String lowerMessage = exceptionMessage.toLowerCase();
            if (lowerMessage.contains("connection") ||
                lowerMessage.contains("timeout") ||
                lowerMessage.contains("socket") ||
                lowerMessage.contains("network") ||
                lowerMessage.contains("temporarily unavailable") ||
                lowerMessage.contains("service unavailable")) {
                return true;
            }
        }
        
        // Default to retryable for most runtime exceptions
        return exception instanceof RuntimeException;
    }
    
    /**
     * Calculates the backoff delay based on the retry policy and attempt number.
     * 
     * @param config the retry configuration
     * @param attemptNumber the current attempt number (0-based)
     * @return delay in milliseconds
     */
    private long calculateBackoffDelay(RetryConfiguration config, int attemptNumber) {
        switch (config.getRetryPolicy()) {
            case EXPONENTIAL_BACKOFF:
                return calculateExponentialBackoff(config, attemptNumber);
            case LINEAR_BACKOFF:
                return calculateLinearBackoff(config, attemptNumber);
            case FIXED_DELAY:
                return config.getInitialDelay().toMillis();
            case IMMEDIATE:
                return 0;
            default:
                return config.getInitialDelay().toMillis();
        }
    }
    
    /**
     * Calculates exponential backoff delay with jitter.
     * 
     * @param config the retry configuration
     * @param attemptNumber the current attempt number
     * @return delay in milliseconds
     */
    private long calculateExponentialBackoff(RetryConfiguration config, int attemptNumber) {
        if (attemptNumber < DEFAULT_BACKOFF_DELAYS.length) {
            // Use predefined delays for first few attempts (1s, 2s, 4s)
            return DEFAULT_BACKOFF_DELAYS[attemptNumber];
        }
        
        // Calculate exponential backoff for additional attempts
        long baseDelay = config.getInitialDelay().toMillis();
        double multiplier = config.getBackoffMultiplier();
        long calculatedDelay = (long) (baseDelay * Math.pow(multiplier, attemptNumber));
        
        // Apply maximum delay limit
        long maxDelay = config.getMaxDelay().toMillis();
        long delay = Math.min(calculatedDelay, maxDelay);
        
        // Add jitter to prevent thundering herd
        double jitter = 0.1 * ThreadLocalRandom.current().nextDouble();
        delay = (long) (delay * (1.0 + jitter));
        
        return delay;
    }
    
    /**
     * Calculates linear backoff delay.
     * 
     * @param config the retry configuration
     * @param attemptNumber the current attempt number
     * @return delay in milliseconds
     */
    private long calculateLinearBackoff(RetryConfiguration config, int attemptNumber) {
        long baseDelay = config.getInitialDelay().toMillis();
        long calculatedDelay = baseDelay * (attemptNumber + 1);
        
        // Apply maximum delay limit
        long maxDelay = config.getMaxDelay().toMillis();
        return Math.min(calculatedDelay, maxDelay);
    }
    
    /**
     * Gets the current circuit breaker state for an operation.
     * 
     * @param operationName the operation name
     * @return current circuit breaker state
     */
    private CircuitBreakerState getCircuitBreakerState(String operationName) {
        CircuitBreakerState state = circuitBreakerStates.getOrDefault(operationName, CircuitBreakerState.CLOSED);
        
        // Check if circuit breaker should transition from OPEN to HALF_OPEN
        if (state == CircuitBreakerState.OPEN) {
            Instant openTime = circuitBreakerOpenTimes.get(operationName);
            if (openTime != null && 
                Duration.between(openTime, Instant.now()).compareTo(CIRCUIT_BREAKER_OPEN_TIMEOUT) > 0) {
                // Transition to HALF_OPEN
                circuitBreakerStates.put(operationName, CircuitBreakerState.HALF_OPEN);
                logger.info("Circuit breaker for {} transitioned from OPEN to HALF_OPEN", operationName);
                return CircuitBreakerState.HALF_OPEN;
            }
        }
        
        return state;
    }
    
    /**
     * Updates circuit breaker state on operation failure.
     * 
     * @param operationName the operation name
     * @param attemptCount the number of attempts made
     */
    private void updateCircuitBreakerOnFailure(String operationName, int attemptCount) {
        RetryMetrics metrics = getRetryMetrics(operationName);
        
        // Calculate failure rate
        double failureRate = metrics.getFailureRate();
        double threshold = getRetryConfiguration(operationName).getCircuitBreakerThreshold();
        
        // Check if circuit breaker should open
        if (failureRate > threshold && metrics.getTotalAttempts() > 5) {
            circuitBreakerStates.put(operationName, CircuitBreakerState.OPEN);
            circuitBreakerOpenTimes.put(operationName, Instant.now());
            metrics.incrementCircuitBreakerTriggers();
            
            logger.warn("Circuit breaker OPENED for operation {} - failure rate: {:.2f}%", 
                operationName, failureRate * 100);
            
            errorReporter.error("Circuit breaker opened for operation {} due to high failure rate", operationName);
        }
    }
    
    /**
     * Resets the circuit breaker for an operation to CLOSED state.
     * 
     * @param operationName the operation name
     */
    private void resetCircuitBreaker(String operationName) {
        CircuitBreakerState previousState = circuitBreakerStates.put(operationName, CircuitBreakerState.CLOSED);
        circuitBreakerOpenTimes.remove(operationName);
        
        if (previousState != null && previousState != CircuitBreakerState.CLOSED) {
            logger.info("Circuit breaker for {} reset to CLOSED state", operationName);
        }
    }
    
    /**
     * Updates success metrics for an operation.
     * 
     * @param operationName the operation name
     * @param attemptCount the number of attempts made
     * @param totalDuration the total duration of the operation
     */
    private void updateSuccessMetrics(String operationName, int attemptCount, Duration totalDuration) {
        RetryMetrics metrics = getRetryMetrics(operationName);
        metrics.recordSuccess(attemptCount, totalDuration);
        
        // Update global success counter
        retryCounters.computeIfAbsent(operationName, k -> new AtomicInteger(0)).set(0);
    }
    
    /**
     * Updates failure metrics for an operation.
     * 
     * @param operationName the operation name
     */
    private void updateFailureMetrics(String operationName) {
        RetryMetrics metrics = getRetryMetrics(operationName);
        metrics.recordFailure();
        
        // Update retry counter
        retryCounters.computeIfAbsent(operationName, k -> new AtomicInteger(0)).incrementAndGet();
    }
    
    /**
     * Creates a successful retry result.
     * 
     * @param <T> the result type
     * @param operationName the operation name
     * @param policy the retry policy used
     * @param result the operation result
     * @param attemptCount the number of attempts made
     * @param startTime the operation start time
     * @return successful RetryResult
     */
    private <T> RetryResult<T> createSuccessResult(String operationName, RetryPolicy policy, 
                                                  T result, int attemptCount, Instant startTime) {
        Duration totalDuration = Duration.between(startTime, Instant.now());
        return new RetryResult<>(true, result, null, attemptCount, totalDuration, 
            null, policy, Instant.now());
    }
    
    /**
     * Creates a failed retry result.
     * 
     * @param <T> the result type
     * @param operationName the operation name
     * @param policy the retry policy used
     * @param exception the final exception
     * @param attemptCount the number of attempts made
     * @param startTime the operation start time
     * @param failureReason the failure reason
     * @return failed RetryResult
     */
    private <T> RetryResult<T> createFailedResult(String operationName, RetryPolicy policy, 
                                                 Exception exception, int attemptCount, 
                                                 Instant startTime, String failureReason) {
        Duration totalDuration = Duration.between(startTime, Instant.now());
        return new RetryResult<>(false, null, exception, attemptCount, totalDuration, 
            failureReason, policy, Instant.now());
    }
    
    /**
     * Gets the reason why retry is blocked for an operation.
     * 
     * @param operationName the operation name
     * @return reason string
     */
    private String getRetryBlockedReason(String operationName) {
        CircuitBreakerState circuitState = getCircuitBreakerState(operationName);
        if (circuitState == CircuitBreakerState.OPEN) {
            return "Circuit breaker is OPEN";
        }
        
        int currentConcurrent = concurrentRetries.get();
        int maxConcurrent = getMaxConcurrentRetries();
        if (currentConcurrent >= maxConcurrent) {
            return String.format("Maximum concurrent retries reached: %d/%d", currentConcurrent, maxConcurrent);
        }
        
        if (!threadPoolManager.isThreadPoolHealthy()) {
            return "Thread pool is unhealthy";
        }
        
        double poolUtilization = connectionPoolManager.getPoolUtilization();
        if (poolUtilization > DEFAULT_CIRCUIT_BREAKER_THRESHOLD) {
            return String.format("Connection pool utilization too high: %.2f%%", poolUtilization);
        }
        
        return "Unknown reason";
    }
    
    /**
     * Gets the maximum concurrent retries allowed.
     * 
     * @return maximum concurrent retries
     */
    private int getMaxConcurrentRetries() {
        try {
            String configValue = configurationManager.getProperty("retry.max.concurrent");
            return configValue != null ? Integer.parseInt(configValue) : DEFAULT_MAX_CONCURRENT_RETRIES;
        } catch (Exception e) {
            logger.warn("Failed to get max concurrent retries from configuration, using default", e);
            return DEFAULT_MAX_CONCURRENT_RETRIES;
        }
    }
}

/**
 * Enumeration of retry policies for different backoff strategies.
 * Provides various approaches to handling retry delays based on failure patterns.
 */
enum RetryPolicy {
    /**
     * Exponential backoff with increasing delays (1s, 2s, 4s, 8s, ...)
     * Recommended for network and external service failures
     */
    EXPONENTIAL_BACKOFF,
    
    /**
     * Fixed delay between retry attempts
     * Useful for operations with consistent failure recovery times
     */
    FIXED_DELAY,
    
    /**
     * Linear backoff with proportionally increasing delays
     * Suitable for operations with predictable recovery patterns
     */
    LINEAR_BACKOFF,
    
    /**
     * Immediate retry without delay
     * Used for transient failures that resolve quickly
     */
    IMMEDIATE
}

/**
 * Enumeration of circuit breaker states for failure management.
 * Implements the circuit breaker pattern to prevent cascading failures.
 */
enum CircuitBreakerState {
    /**
     * Circuit breaker is closed - operations are allowed
     */
    CLOSED,
    
    /**
     * Circuit breaker is open - operations are blocked due to high failure rate
     */
    OPEN,
    
    /**
     * Circuit breaker is half-open - limited operations allowed to test recovery
     */
    HALF_OPEN
}

/**
 * Result wrapper for retry operations containing execution outcome and metadata.
 * Provides comprehensive information about the retry execution including timing,
 * attempt counts, and failure details.
 * 
 * @param <T> the type of the operation result
 */
class RetryResult<T> {
    
    private final boolean successful;
    private final T result;
    private final Exception exception;
    private final int attemptCount;
    private final Duration totalDuration;
    private final String failureReason;
    private final RetryPolicy retryPolicy;
    private final Instant timestamp;
    
    /**
     * Constructor for RetryResult.
     * 
     * @param successful whether the operation was successful
     * @param result the operation result (null if failed)
     * @param exception the exception that occurred (null if successful)
     * @param attemptCount the number of attempts made
     * @param totalDuration the total duration of all attempts
     * @param failureReason the reason for failure (null if successful)
     * @param retryPolicy the retry policy used
     * @param timestamp the timestamp when the result was created
     */
    public RetryResult(boolean successful, T result, Exception exception, int attemptCount,
                      Duration totalDuration, String failureReason, RetryPolicy retryPolicy, 
                      Instant timestamp) {
        this.successful = successful;
        this.result = result;
        this.exception = exception;
        this.attemptCount = attemptCount;
        this.totalDuration = totalDuration;
        this.failureReason = failureReason;
        this.retryPolicy = retryPolicy;
        this.timestamp = timestamp;
    }
    
    /**
     * Returns whether the operation was successful.
     * 
     * @return true if the operation succeeded
     */
    public boolean isSuccessful() {
        return successful;
    }
    
    /**
     * Returns the operation result.
     * 
     * @return the result, or null if the operation failed
     */
    public T getResult() {
        return result;
    }
    
    /**
     * Returns the exception that caused the operation to fail.
     * 
     * @return the exception, or null if the operation succeeded
     */
    public Exception getException() {
        return exception;
    }
    
    /**
     * Returns the number of attempts made.
     * 
     * @return the attempt count
     */
    public int getAttemptCount() {
        return attemptCount;
    }
    
    /**
     * Returns the total duration of all retry attempts.
     * 
     * @return the total duration
     */
    public Duration getTotalDuration() {
        return totalDuration;
    }
    
    /**
     * Returns the reason for failure.
     * 
     * @return the failure reason, or null if successful
     */
    public String getFailureReason() {
        return failureReason;
    }
    
    /**
     * Returns the retry policy used for this operation.
     * 
     * @return the retry policy
     */
    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }
    
    /**
     * Returns the timestamp when this result was created.
     * 
     * @return the timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return String.format("RetryResult{successful=%s, attemptCount=%d, duration=%dms, policy=%s%s}",
            successful, attemptCount, totalDuration.toMillis(), retryPolicy,
            successful ? "" : ", reason=" + failureReason);
    }
}

/**
 * Configuration class for retry behavior and circuit breaker settings.
 * Provides immutable configuration with builder pattern for easy construction
 * and comprehensive retry policy management.
 */
class RetryConfiguration {
    
    private final int maxRetries;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final RetryPolicy retryPolicy;
    private final double circuitBreakerThreshold;
    private final Duration timeoutDuration;
    private final boolean enabled;
    private final double backoffMultiplier;
    
    /**
     * Private constructor for builder pattern.
     * 
     * @param builder the configuration builder
     */
    private RetryConfiguration(Builder builder) {
        this.maxRetries = builder.maxRetries;
        this.initialDelay = builder.initialDelay;
        this.maxDelay = builder.maxDelay;
        this.retryPolicy = builder.retryPolicy;
        this.circuitBreakerThreshold = builder.circuitBreakerThreshold;
        this.timeoutDuration = builder.timeoutDuration;
        this.enabled = builder.enabled;
        this.backoffMultiplier = builder.backoffMultiplier;
    }
    
    /**
     * Returns the maximum number of retries allowed.
     * 
     * @return maximum retry count
     */
    public int getMaxRetries() {
        return maxRetries;
    }
    
    /**
     * Returns the initial delay for the first retry attempt.
     * 
     * @return initial delay duration
     */
    public Duration getInitialDelay() {
        return initialDelay;
    }
    
    /**
     * Returns the maximum delay allowed between retry attempts.
     * 
     * @return maximum delay duration
     */
    public Duration getMaxDelay() {
        return maxDelay;
    }
    
    /**
     * Returns the retry policy for backoff calculation.
     * 
     * @return retry policy
     */
    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }
    
    /**
     * Returns the circuit breaker threshold (failure rate) for opening the circuit.
     * 
     * @return circuit breaker threshold (0.0 to 1.0)
     */
    public double getCircuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }
    
    /**
     * Returns the timeout duration for individual operation attempts.
     * 
     * @return timeout duration
     */
    public Duration getTimeoutDuration() {
        return timeoutDuration;
    }
    
    /**
     * Returns whether retry is enabled for this configuration.
     * 
     * @return true if retry is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Returns the backoff multiplier for exponential backoff calculations.
     * 
     * @return backoff multiplier
     */
    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }
    
    @Override
    public String toString() {
        return String.format("RetryConfiguration{maxRetries=%d, initialDelay=%dms, maxDelay=%dms, " +
            "policy=%s, circuitThreshold=%.2f, timeout=%dms, enabled=%s, multiplier=%.1f}",
            maxRetries, initialDelay.toMillis(), maxDelay.toMillis(), retryPolicy,
            circuitBreakerThreshold, timeoutDuration.toMillis(), enabled, backoffMultiplier);
    }
    
    /**
     * Builder for RetryConfiguration with fluent interface and validation.
     * Provides flexible configuration construction with sensible defaults.
     */
    public static class Builder {
        private int maxRetries = 3;
        private Duration initialDelay = Duration.ofSeconds(1);
        private Duration maxDelay = Duration.ofSeconds(4);
        private RetryPolicy retryPolicy = RetryPolicy.EXPONENTIAL_BACKOFF;
        private double circuitBreakerThreshold = 0.8;
        private Duration timeoutDuration = Duration.ofSeconds(2);
        private boolean enabled = true;
        private double backoffMultiplier = 2.0;
        
        /**
         * Default constructor with framework defaults.
         */
        public Builder() {}
        
        /**
         * Copy constructor to create a builder from existing configuration.
         * 
         * @param config the configuration to copy
         */
        public Builder(RetryConfiguration config) {
            this.maxRetries = config.maxRetries;
            this.initialDelay = config.initialDelay;
            this.maxDelay = config.maxDelay;
            this.retryPolicy = config.retryPolicy;
            this.circuitBreakerThreshold = config.circuitBreakerThreshold;
            this.timeoutDuration = config.timeoutDuration;
            this.enabled = config.enabled;
            this.backoffMultiplier = config.backoffMultiplier;
        }
        
        /**
         * Sets the maximum number of retries.
         * 
         * @param maxRetries maximum retry count (must be >= 0)
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("Max retries cannot be negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }
        
        /**
         * Sets the initial delay for retry attempts.
         * 
         * @param initialDelay initial delay duration (must be positive)
         * @return this builder
         */
        public Builder initialDelay(Duration initialDelay) {
            if (initialDelay == null || initialDelay.isNegative()) {
                throw new IllegalArgumentException("Initial delay must be positive");
            }
            this.initialDelay = initialDelay;
            return this;
        }
        
        /**
         * Sets the maximum delay between retry attempts.
         * 
         * @param maxDelay maximum delay duration (must be positive)
         * @return this builder
         */
        public Builder maxDelay(Duration maxDelay) {
            if (maxDelay == null || maxDelay.isNegative()) {
                throw new IllegalArgumentException("Max delay must be positive");
            }
            this.maxDelay = maxDelay;
            return this;
        }
        
        /**
         * Sets the retry policy for backoff calculation.
         * 
         * @param retryPolicy retry policy (cannot be null)
         * @return this builder
         */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            if (retryPolicy == null) {
                throw new IllegalArgumentException("Retry policy cannot be null");
            }
            this.retryPolicy = retryPolicy;
            return this;
        }
        
        /**
         * Sets the circuit breaker threshold.
         * 
         * @param threshold failure rate threshold (0.0 to 1.0)
         * @return this builder
         */
        public Builder circuitBreakerThreshold(double threshold) {
            if (threshold < 0.0 || threshold > 1.0) {
                throw new IllegalArgumentException("Circuit breaker threshold must be between 0.0 and 1.0");
            }
            this.circuitBreakerThreshold = threshold;
            return this;
        }
        
        /**
         * Sets the timeout duration for individual operations.
         * 
         * @param timeoutDuration timeout duration (must be positive)
         * @return this builder
         */
        public Builder timeoutDuration(Duration timeoutDuration) {
            if (timeoutDuration == null || timeoutDuration.isNegative()) {
                throw new IllegalArgumentException("Timeout duration must be positive");
            }
            this.timeoutDuration = timeoutDuration;
            return this;
        }
        
        /**
         * Sets whether retry is enabled.
         * 
         * @param enabled true to enable retry
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        /**
         * Sets the backoff multiplier for exponential backoff.
         * 
         * @param multiplier backoff multiplier (must be >= 1.0)
         * @return this builder
         */
        public Builder backoffMultiplier(double multiplier) {
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("Backoff multiplier must be >= 1.0");
            }
            this.backoffMultiplier = multiplier;
            return this;
        }
        
        /**
         * Builds the retry configuration with validation.
         * 
         * @return validated RetryConfiguration instance
         */
        public RetryConfiguration build() {
            // Validation
            if (maxDelay.compareTo(initialDelay) < 0) {
                throw new IllegalArgumentException("Max delay cannot be less than initial delay");
            }
            
            return new RetryConfiguration(this);
        }
    }
}

/**
 * Metrics collection and analysis class for retry operations.
 * Provides comprehensive tracking of retry performance, success rates,
 * and circuit breaker behavior with real-time statistics.
 */
class RetryMetrics {
    
    private static final Logger logger = LoggerFactory.getLogger(RetryMetrics.class);
    
    private final String operationName;
    private final AtomicLong totalAttempts;
    private final AtomicLong successfulOperations;
    private final AtomicLong failedOperations;
    private final AtomicLong totalRetryAttempts;
    private final AtomicLong circuitBreakerTriggers;
    private final AtomicLong totalDurationMs;
    private volatile Instant lastRetryTime;
    private volatile Instant creationTime;
    
    // Histogram for retry count distribution
    private final Map<Integer, AtomicLong> retryHistogram;
    
    // Rolling window for recent success rate calculation
    private final Queue<Boolean> recentResults;
    private static final int ROLLING_WINDOW_SIZE = 100;
    
    /**
     * Constructor for RetryMetrics.
     * 
     * @param operationName the name of the operation being tracked
     */
    public RetryMetrics(String operationName) {
        this.operationName = operationName;
        this.totalAttempts = new AtomicLong(0);
        this.successfulOperations = new AtomicLong(0);
        this.failedOperations = new AtomicLong(0);
        this.totalRetryAttempts = new AtomicLong(0);
        this.circuitBreakerTriggers = new AtomicLong(0);
        this.totalDurationMs = new AtomicLong(0);
        this.lastRetryTime = Instant.now();
        this.creationTime = Instant.now();
        this.retryHistogram = new ConcurrentHashMap<>();
        this.recentResults = new LinkedList<>();
        
        logger.debug("Created RetryMetrics for operation: {}", operationName);
    }
    
    /**
     * Records a successful operation.
     * 
     * @param attemptCount the number of attempts made
     * @param duration the total duration of the operation
     */
    public synchronized void recordSuccess(int attemptCount, Duration duration) {
        successfulOperations.incrementAndGet();
        totalAttempts.incrementAndGet();
        totalRetryAttempts.addAndGet(attemptCount - 1); // Subtract 1 for the initial attempt
        totalDurationMs.addAndGet(duration.toMillis());
        lastRetryTime = Instant.now();
        
        // Update histogram
        retryHistogram.computeIfAbsent(attemptCount, k -> new AtomicLong(0)).incrementAndGet();
        
        // Update rolling window
        updateRollingWindow(true);
        
        logger.trace("Recorded success for {} - attempts: {}, duration: {}ms", 
            operationName, attemptCount, duration.toMillis());
    }
    
    /**
     * Records a failed operation attempt.
     */
    public synchronized void recordFailure() {
        failedOperations.incrementAndGet();
        totalAttempts.incrementAndGet();
        lastRetryTime = Instant.now();
        
        // Update rolling window
        updateRollingWindow(false);
        
        logger.trace("Recorded failure for {}", operationName);
    }
    
    /**
     * Gets the success rate as a percentage (0.0 to 1.0).
     * 
     * @return success rate
     */
    public double getSuccessRate() {
        long total = totalAttempts.get();
        if (total == 0) {
            return 1.0; // No attempts yet, assume success
        }
        return (double) successfulOperations.get() / total;
    }
    
    /**
     * Gets the failure rate as a percentage (0.0 to 1.0).
     * 
     * @return failure rate
     */
    public double getFailureRate() {
        return 1.0 - getSuccessRate();
    }
    
    /**
     * Gets the total number of operation attempts.
     * 
     * @return total attempts
     */
    public long getTotalAttempts() {
        return totalAttempts.get();
    }
    
    /**
     * Gets the average number of retries per operation.
     * 
     * @return average retry count
     */
    public double getAverageRetryCount() {
        long totalOps = successfulOperations.get() + failedOperations.get();
        if (totalOps == 0) {
            return 0.0;
        }
        return (double) totalRetryAttempts.get() / totalOps;
    }
    
    /**
     * Gets the number of times the circuit breaker was triggered.
     * 
     * @return circuit breaker trigger count
     */
    public long getCircuitBreakerTriggers() {
        return circuitBreakerTriggers.get();
    }
    
    /**
     * Increments the circuit breaker trigger count.
     */
    public void incrementCircuitBreakerTriggers() {
        circuitBreakerTriggers.incrementAndGet();
        logger.debug("Circuit breaker triggered for operation: {}, total triggers: {}", 
            operationName, circuitBreakerTriggers.get());
    }
    
    /**
     * Gets the timestamp of the last retry attempt.
     * 
     * @return last retry time
     */
    public Instant getLastRetryTime() {
        return lastRetryTime;
    }
    
    /**
     * Gets the retry count histogram showing distribution of retry attempts.
     * 
     * @return map of retry count to frequency
     */
    public Map<Integer, Long> getRetryHistogram() {
        return retryHistogram.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().get()
            ));
    }
    
    /**
     * Resets all metrics to initial state.
     */
    public synchronized void resetMetrics() {
        totalAttempts.set(0);
        successfulOperations.set(0);
        failedOperations.set(0);
        totalRetryAttempts.set(0);
        circuitBreakerTriggers.set(0);
        totalDurationMs.set(0);
        lastRetryTime = Instant.now();
        creationTime = Instant.now();
        retryHistogram.clear();
        recentResults.clear();
        
        logger.info("Reset metrics for operation: {}", operationName);
    }
    
    /**
     * Gets comprehensive metrics summary as formatted string.
     * 
     * @return metrics summary
     */
    public String getMetricsSummary() {
        long totalOps = successfulOperations.get() + failedOperations.get();
        double avgDuration = totalOps > 0 ? (double) totalDurationMs.get() / totalOps : 0.0;
        Duration uptime = Duration.between(creationTime, Instant.now());
        
        return String.format(
            "RetryMetrics[%s]: Operations=%d, Success=%.2f%%, Failures=%d, " +
            "AvgRetries=%.2f, AvgDuration=%.1fms, CircuitBreakers=%d, Uptime=%s",
            operationName, totalOps, getSuccessRate() * 100, failedOperations.get(),
            getAverageRetryCount(), avgDuration, circuitBreakerTriggers.get(), 
            formatDuration(uptime));
    }
    
    /**
     * Gets the rolling success rate based on recent operations.
     * 
     * @return rolling success rate (0.0 to 1.0)
     */
    public double getRollingSuccessRate() {
        synchronized (recentResults) {
            if (recentResults.isEmpty()) {
                return 1.0;
            }
            long successes = recentResults.stream().mapToLong(success -> success ? 1 : 0).sum();
            return (double) successes / recentResults.size();
        }
    }
    
    /**
     * Gets the operation throughput in operations per second.
     * 
     * @return throughput in ops/sec
     */
    public double getThroughput() {
        Duration uptime = Duration.between(creationTime, Instant.now());
        if (uptime.toMillis() <= 0) {
            return 0.0;
        }
        long totalOps = successfulOperations.get() + failedOperations.get();
        return (double) totalOps / uptime.toSeconds();
    }
    
    /**
     * Updates the rolling window of recent results.
     * 
     * @param success whether the operation was successful
     */
    private void updateRollingWindow(boolean success) {
        synchronized (recentResults) {
            recentResults.offer(success);
            if (recentResults.size() > ROLLING_WINDOW_SIZE) {
                recentResults.poll();
            }
        }
    }
    
    /**
     * Formats a duration for human-readable display.
     * 
     * @param duration the duration to format
     * @return formatted duration string
     */
    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
    
    @Override
    public String toString() {
        return getMetricsSummary();
    }
}