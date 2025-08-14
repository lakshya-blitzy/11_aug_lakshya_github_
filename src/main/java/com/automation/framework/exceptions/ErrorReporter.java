package com.automation.framework.exceptions;

import com.automation.framework.core.ConfigurationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import java.lang.ThreadLocal;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

/**
 * ErrorReporter provides comprehensive error logging and reporting capabilities for the automation framework.
 * 
 * Features:
 * - Hierarchical structured logging with configurable levels (TRACE, DEBUG, INFO, WARN, ERROR, FATAL)
 * - Unique correlation identifiers for distributed tracing across framework components
 * - Automatic sensitive data masking for security compliance
 * - Comprehensive audit logs with tamper-evident formatting
 * - Error context capture including stack traces, screenshots, and API data
 * - Real-time error metrics and reporting
 * - Thread-safe concurrent operation support
 * 
 * This class integrates with the automation framework's configuration management
 * and monitoring systems to provide enterprise-grade error handling capabilities.
 */
public class ErrorReporter {
    
    private static final Logger logger = LoggerFactory.getLogger(ErrorReporter.class);
    
    // Configuration and dependency management
    private final ConfigurationManager configManager;
    private final ObjectMapper objectMapper;
    
    // Thread-local correlation context
    private static final ThreadLocal<String> correlationContext = new ThreadLocal<>();
    
    // Error tracking and metrics
    private final ConcurrentHashMap<String, ErrorContext> errorContexts;
    private final AtomicLong totalErrorCount = new AtomicLong(0);
    private final ConcurrentHashMap<LogLevel, AtomicLong> errorCountsByLevel;
    private final ConcurrentHashMap<ErrorSeverity, AtomicLong> errorCountsBySeverity;
    private final AtomicReference<Instant> lastErrorTime = new AtomicReference<>(Instant.now());
    
    // Sensitive data masking patterns
    private final List<Pattern> sensitiveDataPatterns;
    private volatile boolean sensitiveDataMaskingEnabled = true;
    
    // Current log level and configuration
    private volatile LogLevel currentLogLevel = LogLevel.INFO;
    private volatile boolean errorReportingActive = false;
    
    // Thread safety for configuration changes
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();
    
    // Constants for error reporting
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String MASKED_VALUE = "[MASKED]";
    private static final int MAX_STACK_TRACE_LINES = 50;
    private static final int MAX_CONTEXT_SIZE = 10000; // characters
    
    /**
     * Creates a new ErrorReporter instance.
     * Initializes all required components and loads configuration settings.
     */
    public ErrorReporter() {
        this.configManager = ConfigurationManager.getInstance();
        this.objectMapper = new ObjectMapper();
        this.errorContexts = new ConcurrentHashMap<>();
        this.errorCountsByLevel = new ConcurrentHashMap<>();
        this.errorCountsBySeverity = new ConcurrentHashMap<>();
        this.sensitiveDataPatterns = new ArrayList<>();
        
        // Initialize error counters
        for (LogLevel level : LogLevel.values()) {
            errorCountsByLevel.put(level, new AtomicLong(0));
        }
        for (ErrorSeverity severity : ErrorSeverity.values()) {
            errorCountsBySeverity.put(severity, new AtomicLong(0));
        }
        
        // Configure Jackson for structured logging
        configureObjectMapper();
        
        // Initialize sensitive data patterns
        initializeSensitiveDataPatterns();
        
        // Load configuration settings
        loadConfiguration();
        
        logger.info("ErrorReporter initialized with log level: {}, masking enabled: {}", 
                   currentLogLevel, sensitiveDataMaskingEnabled);
    }
    
    /**
     * Logs a message at the specified level with optional correlation ID and context.
     * This is the primary logging method used by all other logging methods.
     * 
     * @param level The log level
     * @param message The message to log
     * @param context Additional context information
     * @param throwable Optional exception associated with this log entry
     */
    public void log(LogLevel level, String message, Map<String, Object> context, Throwable throwable) {
        if (!isLoggingEnabled(level)) {
            return;
        }
        
        try {
            String correlationId = getCurrentCorrelationId();
            ErrorContext errorContext = createErrorContext(level, message, throwable, context);
            
            // Store error context for metrics and retrieval
            if (correlationId != null) {
                errorContexts.put(correlationId, errorContext);
            }
            
            // Update metrics
            updateErrorMetrics(level, errorContext.getErrorSeverity());
            
            // Format and log the structured message
            String structuredLog = formatStructuredLog(errorContext);
            
            // Log using SLF4J based on level
            logToSlf4j(level, structuredLog, throwable);
            
            // Update last error time
            lastErrorTime.set(errorContext.getTimestamp());
            
        } catch (Exception e) {
            // Fallback logging to prevent error reporting from failing
            logger.error("Failed to process error log: {}", message, e);
        }
    }
    
    /**
     * Logs a TRACE level message.
     * 
     * @param message The message to log
     */
    public void trace(String message) {
        log(LogLevel.TRACE, message, null, null);
    }
    
    /**
     * Logs a DEBUG level message.
     * 
     * @param message The message to log
     */
    public void debug(String message) {
        log(LogLevel.DEBUG, message, null, null);
    }
    
    /**
     * Logs an INFO level message.
     * 
     * @param message The message to log
     */
    public void info(String message) {
        log(LogLevel.INFO, message, null, null);
    }
    
    /**
     * Logs a WARN level message.
     * 
     * @param message The message to log
     */
    public void warn(String message) {
        log(LogLevel.WARN, message, null, null);
    }
    
    /**
     * Logs an ERROR level message.
     * 
     * @param message The message to log
     */
    public void error(String message) {
        log(LogLevel.ERROR, message, null, null);
    }
    
    /**
     * Logs a FATAL level message.
     * 
     * @param message The message to log
     */
    public void fatal(String message) {
        log(LogLevel.FATAL, message, null, null);
    }
    
    /**
     * Logs an exception with comprehensive context capture.
     * Automatically determines appropriate log level based on exception type.
     * 
     * @param exception The exception to log
     * @param contextMessage Additional context message
     * @param additionalContext Additional context data
     */
    public void logException(Throwable exception, String contextMessage, Map<String, Object> additionalContext) {
        if (exception == null) {
            return;
        }
        
        // Determine appropriate log level based on exception type
        LogLevel level = determineLogLevelForException(exception);
        
        // Enhance context with exception details
        Map<String, Object> enhancedContext = new HashMap<>();
        if (additionalContext != null) {
            enhancedContext.putAll(additionalContext);
        }
        
        enhancedContext.put("exceptionType", exception.getClass().getName());
        enhancedContext.put("exceptionMessage", exception.getMessage());
        enhancedContext.put("stackTrace", captureStackTrace(exception));
        
        // Add system state information
        enhancedContext.put("systemState", captureSystemState());
        
        String message = contextMessage != null ? contextMessage : 
                        "Exception occurred: " + exception.getClass().getSimpleName();
        
        log(level, message, enhancedContext, exception);
    }
    
    /**
     * Sets the correlation ID for the current thread.
     * This correlation ID will be used for all subsequent log entries in this thread.
     * 
     * @param correlationId The correlation ID to set
     */
    public void setCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.trim().isEmpty()) {
            correlationContext.set(correlationId.trim());
            logger.debug("Set correlation ID: {}", correlationId);
        } else {
            correlationContext.remove();
            logger.debug("Removed correlation ID from thread context");
        }
    }
    
    /**
     * Gets the current correlation ID for this thread.
     * 
     * @return The current correlation ID or null if none is set
     */
    public String getCorrelationId() {
        String correlationId = correlationContext.get();
        if (correlationId == null) {
            correlationId = generateCorrelationId();
            correlationContext.set(correlationId);
        }
        return correlationId;
    }
    
    /**
     * Masks sensitive data in the provided text using configured patterns.
     * This method identifies and replaces sensitive information like passwords,
     * tokens, and personally identifiable information with masked values.
     * 
     * @param data The data to mask
     * @return The data with sensitive information masked
     */
    public String maskSensitiveData(String data) {
        if (!sensitiveDataMaskingEnabled || data == null || data.isEmpty()) {
            return data;
        }
        
        String maskedData = data;
        
        // Password patterns - handle both JSON and key=value formats
        maskedData = Pattern.compile("(?i)(\"password\"|\"pwd\"|\"pass\"|password|pwd|pass)(\\s*[:=]\\s*[\"']?)([^\\s\"',}]+)[\"']?", Pattern.CASE_INSENSITIVE)
                            .matcher(maskedData).replaceAll("$1$2\"" + MASKED_VALUE + "\"");
        
        // Token patterns - handle both JSON and key=value formats
        maskedData = Pattern.compile("(?i)(\"token\"|\"bearer\"|\"auth\"|\"key\"|token|bearer|auth|key)(\\s*[:=]\\s*[\"']?)([^\\s\"',}]+)[\"']?", Pattern.CASE_INSENSITIVE)
                            .matcher(maskedData).replaceAll("$1$2\"" + MASKED_VALUE + "\"");
        
        // Credit card patterns - full replacement
        maskedData = Pattern.compile("\\b(?:\\d{4}[-\\s]?){3}\\d{4}\\b")
                            .matcher(maskedData).replaceAll(MASKED_VALUE);
        
        // Social Security Number patterns - full replacement
        maskedData = Pattern.compile("\\b\\d{3}-?\\d{2}-?\\d{4}\\b")
                            .matcher(maskedData).replaceAll(MASKED_VALUE);
        
        // Email patterns - full replacement
        maskedData = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b")
                            .matcher(maskedData).replaceAll(MASKED_VALUE);
        
        // API key patterns - handle both JSON and key=value formats
        maskedData = Pattern.compile("(?i)(\"api[_-]?key\"|\"secret[_-]?key\"|api[_-]?key|secret[_-]?key)(\\s*[:=]\\s*[\"']?)([A-Za-z0-9\\-_]+)[\"']?", Pattern.CASE_INSENSITIVE)
                            .matcher(maskedData).replaceAll("$1$2\"" + MASKED_VALUE + "\"");
        
        return maskedData;
    }
    
    /**
     * Captures a screenshot for web automation errors.
     * This method attempts to capture the current browser state when a web-related error occurs.
     * 
     * @return Screenshot data as base64 encoded string, or null if capture fails
     */
    public String captureScreenshot() {
        try {
            // This would typically interact with WebDriver
            // For now, return a placeholder indicating screenshot capability
            String timestamp = Instant.now().toString();
            Map<String, Object> screenshotMetadata = new HashMap<>();
            screenshotMetadata.put("timestamp", timestamp);
            screenshotMetadata.put("correlationId", getCurrentCorrelationId());
            screenshotMetadata.put("status", "screenshot_capture_attempted");
            
            return objectMapper.writeValueAsString(screenshotMetadata);
        } catch (Exception e) {
            logger.warn("Failed to capture screenshot", e);
            return null;
        }
    }
    
    /**
     * Captures API context information for API testing errors.
     * Includes request/response data, headers, and timing information.
     * 
     * @param request The API request data
     * @param response The API response data
     * @return Formatted API context information
     */
    public String captureAPIContext(Object request, Object response) {
        try {
            Map<String, Object> apiContext = new HashMap<>();
            apiContext.put("timestamp", Instant.now().toString());
            apiContext.put("correlationId", getCurrentCorrelationId());
            
            if (request != null) {
                String requestData = objectMapper.writeValueAsString(request);
                apiContext.put("request", maskSensitiveData(requestData));
            }
            
            if (response != null) {
                String responseData = objectMapper.writeValueAsString(response);
                apiContext.put("response", maskSensitiveData(responseData));
            }
            
            return objectMapper.writeValueAsString(apiContext);
        } catch (Exception e) {
            logger.warn("Failed to capture API context", e);
            return "API context capture failed: " + e.getMessage();
        }
    }
    
    /**
     * Captures stack trace information from an exception.
     * Limits the stack trace size to prevent excessive log volume.
     * 
     * @param throwable The exception to capture stack trace from
     * @return Formatted stack trace string
     */
    public String captureStackTrace(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        
        try {
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            throwable.printStackTrace(printWriter);
            
            String fullStackTrace = stringWriter.toString();
            String[] lines = fullStackTrace.split("\n");
            
            // Limit stack trace lines to prevent excessive log volume
            int maxLines = Math.min(lines.length, MAX_STACK_TRACE_LINES);
            StringBuilder limitedStackTrace = new StringBuilder();
            
            for (int i = 0; i < maxLines; i++) {
                limitedStackTrace.append(lines[i]).append("\n");
            }
            
            if (lines.length > MAX_STACK_TRACE_LINES) {
                limitedStackTrace.append("... (")
                                 .append(lines.length - MAX_STACK_TRACE_LINES)
                                 .append(" more lines truncated)\n");
            }
            
            return limitedStackTrace.toString().trim();
        } catch (Exception e) {
            logger.warn("Failed to capture stack trace", e);
            return "Stack trace capture failed: " + e.getMessage();
        }
    }
    
    /**
     * Creates a comprehensive error context object.
     * This includes all relevant information about the error occurrence.
     * 
     * @param level The log level
     * @param message The error message
     * @param throwable Optional exception
     * @param additionalContext Additional context data
     * @return Complete ErrorContext object
     */
    public ErrorContext createErrorContext(LogLevel level, String message, Throwable throwable, Map<String, Object> additionalContext) {
        String errorId = UUID.randomUUID().toString();
        String correlationId = getCurrentCorrelationId();
        Instant timestamp = Instant.now();
        ErrorSeverity severity = determineSeverityFromLevel(level);
        
        // Capture stack trace if exception is provided
        String stackTrace = throwable != null ? captureStackTrace(throwable) : null;
        
        // Determine module name from current thread or call stack
        String moduleName = determineModuleName();
        
        // Determine test name from context or thread name
        String testName = determineTestName(additionalContext);
        
        // Capture system state
        Map<String, Object> systemState = captureSystemState();
        
        // Mask sensitive data in additional context
        Map<String, Object> maskedContext = null;
        if (additionalContext != null) {
            maskedContext = maskSensitiveDataInMap(additionalContext);
        }
        
        // Create error signature for deduplication
        String errorSignature = generateErrorSignature(message, throwable, stackTrace);
        
        return new ErrorContext(
            errorId,
            level,
            timestamp,
            maskSensitiveData(message),
            stackTrace,
            correlationId,
            moduleName,
            testName,
            null, // screenshot data - would be populated for web errors
            null, // API request data - would be populated for API errors
            null, // API response data - would be populated for API errors
            systemState,
            maskedContext,
            errorSignature,
            severity,
            throwable
        );
    }
    
    /**
     * Reports an error to external monitoring systems.
     * This method can be extended to integrate with various monitoring platforms.
     * 
     * @param errorContext The error context to report
     */
    public void reportError(ErrorContext errorContext) {
        if (errorContext == null) {
            return;
        }
        
        try {
            // Log the error using standard logging
            String structuredLog = formatStructuredLog(errorContext);
            logger.error("Error reported: {}", structuredLog);
            
            // Here you could add integrations with external monitoring systems:
            // - Send to metrics collection system
            // - Alert management systems
            // - Error tracking platforms (Sentry, Rollbar, etc.)
            
            // For now, update internal metrics
            updateErrorMetrics(errorContext.getErrorLevel(), errorContext.getErrorSeverity());
            
        } catch (Exception e) {
            logger.error("Failed to report error", e);
        }
    }
    
    /**
     * Formats an error context into a structured log entry.
     * Creates JSON-formatted log entries for easy parsing by log analysis systems.
     * 
     * @param errorContext The error context to format
     * @return Formatted JSON log entry
     */
    public String formatStructuredLog(ErrorContext errorContext) {
        if (errorContext == null) {
            return "{}";
        }
        
        try {
            return errorContext.toStructuredFormat();
        } catch (Exception e) {
            logger.warn("Failed to format structured log, falling back to simple format", e);
            
            // Fallback to simple format
            return String.format("{\"errorId\":\"%s\",\"level\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}", 
                                errorContext.getErrorId(),
                                errorContext.getErrorLevel(),
                                maskSensitiveData(errorContext.getException() != null ? 
                                    errorContext.getException().getMessage() : "Unknown error"),
                                errorContext.getTimestamp());
        }
    }
    
    /**
     * Generates a unique correlation ID for distributed tracing.
     * Uses UUID format with timestamp prefix for better chronological sorting.
     * 
     * @return A new unique correlation ID
     */
    public String generateCorrelationId() {
        long timestamp = System.currentTimeMillis();
        String uuid = UUID.randomUUID().toString();
        return String.format("%d-%s", timestamp, uuid);
    }
    
    /**
     * Validates the integrity of a log entry using cryptographic hashing.
     * Ensures log entries haven't been tampered with for compliance requirements.
     * 
     * @param logEntry The log entry to validate
     * @param expectedHash The expected hash value
     * @return true if the log entry is valid, false otherwise
     */
    public boolean validateLogIntegrity(String logEntry, String expectedHash) {
        if (logEntry == null || expectedHash == null) {
            return false;
        }
        
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(logEntry.getBytes(StandardCharsets.UTF_8));
            String actualHash = Base64.getEncoder().encodeToString(hash);
            
            return actualHash.equals(expectedHash);
        } catch (NoSuchAlgorithmException e) {
            logger.error("Failed to validate log integrity", e);
            return false;
        }
    }
    
    /**
     * Gets comprehensive error metrics for monitoring and reporting.
     * 
     * @return ErrorMetrics object containing all current metrics
     */
    public ErrorMetrics getErrorMetrics() {
        configLock.readLock().lock();
        try {
            Map<LogLevel, Long> errorsByLevel = new HashMap<>();
            errorCountsByLevel.forEach((level, count) -> errorsByLevel.put(level, count.get()));
            
            Map<ErrorSeverity, Long> errorsBySeverity = new HashMap<>();
            errorCountsBySeverity.forEach((severity, count) -> errorsBySeverity.put(severity, count.get()));
            
            return new ErrorMetrics(
                totalErrorCount.get(),
                calculateErrorRate(),
                calculateAverageErrorsPerMinute(),
                lastErrorTime.get(),
                Instant.now(), // timeWindow
                Duration.ofMinutes(60), // timeWindowDuration
                errorsByLevel,
                errorsBySeverity,
                generateErrorTrends(),
                generateCorrelationIdMetrics(),
                generateSensitiveDataMaskingMetrics(),
                generateStructuredLogMetrics()
            );
        } finally {
            configLock.readLock().unlock();
        }
    }
    
    /**
     * Resets all error counters and metrics.
     * Used for testing or when starting fresh monitoring periods.
     */
    public void resetErrorCounters() {
        configLock.writeLock().lock();
        try {
            totalErrorCount.set(0);
            errorCountsByLevel.values().forEach(counter -> counter.set(0));
            errorCountsBySeverity.values().forEach(counter -> counter.set(0));
            lastErrorTime.set(Instant.now());
            
            logger.info("Error counters reset");
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Enables sensitive data masking with configured patterns.
     */
    public void enableSensitiveDataMasking() {
        configLock.writeLock().lock();
        try {
            sensitiveDataMaskingEnabled = true;
            logger.info("Sensitive data masking enabled");
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Disables sensitive data masking.
     * Warning: This may expose sensitive data in logs - use with caution.
     */
    public void disableSensitiveDataMasking() {
        configLock.writeLock().lock();
        try {
            sensitiveDataMaskingEnabled = false;
            logger.warn("Sensitive data masking disabled - sensitive data may be exposed in logs");
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Sets the current log level.
     * Only messages at or above this level will be logged.
     * 
     * @param level The new log level
     */
    public void setLogLevel(LogLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("Log level cannot be null");
        }
        
        configLock.writeLock().lock();
        try {
            LogLevel previousLevel = this.currentLogLevel;
            this.currentLogLevel = level;
            logger.info("Log level changed from {} to {}", previousLevel, level);
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Gets the current log level.
     * 
     * @return The current log level
     */
    public LogLevel getLogLevel() {
        configLock.readLock().lock();
        try {
            return currentLogLevel;
        } finally {
            configLock.readLock().unlock();
        }
    }
    
    /**
     * Starts the error reporting system.
     * Initializes all necessary components and begins accepting error reports.
     */
    public void startErrorReporting() {
        configLock.writeLock().lock();
        try {
            if (!errorReportingActive) {
                errorReportingActive = true;
                
                // Initialize correlation ID for main thread if not set
                if (getCurrentCorrelationId() == null) {
                    setCorrelationId(generateCorrelationId());
                }
                
                logger.info("Error reporting started with correlation ID: {}", getCurrentCorrelationId());
            }
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Stops the error reporting system.
     * Performs cleanup and finalizes any pending error reports.
     */
    public void stopErrorReporting() {
        configLock.writeLock().lock();
        try {
            if (errorReportingActive) {
                errorReportingActive = false;
                
                // Cleanup thread-local data
                correlationContext.remove();
                
                // Clear error contexts to free memory
                errorContexts.clear();
                
                logger.info("Error reporting stopped and cleanup completed");
            }
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Configures the ObjectMapper for structured logging output.
     */
    private void configureObjectMapper() {
        objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
    
    /**
     * Initializes patterns for detecting sensitive data.
     */
    private void initializeSensitiveDataPatterns() {
        // Password patterns
        sensitiveDataPatterns.add(Pattern.compile("(?i)(password|pwd|pass)\\s*[:=]\\s*[\"']?([^\\s\"',}]+)", Pattern.CASE_INSENSITIVE));
        
        // Token patterns
        sensitiveDataPatterns.add(Pattern.compile("(?i)(token|bearer|auth|key)\\s*[:=]\\s*[\"']?([^\\s\"',}]+)", Pattern.CASE_INSENSITIVE));
        
        // Credit card patterns
        sensitiveDataPatterns.add(Pattern.compile("\\b(?:\\d{4}[-\\s]?){3}\\d{4}\\b"));
        
        // Social Security Number patterns
        sensitiveDataPatterns.add(Pattern.compile("\\b\\d{3}-?\\d{2}-?\\d{4}\\b"));
        
        // Email patterns (partial masking)
        sensitiveDataPatterns.add(Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"));
        
        // API key patterns
        sensitiveDataPatterns.add(Pattern.compile("(?i)(api[_-]?key|secret[_-]?key)\\s*[:=]\\s*[\"']?([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE));
    }
    
    /**
     * Loads configuration settings from ConfigurationManager.
     */
    private void loadConfiguration() {
        try {
            // Load log level
            String logLevelStr = configManager.getProperty("error.reporting.log.level");
            if (logLevelStr != null) {
                try {
                    currentLogLevel = LogLevel.valueOf(logLevelStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid log level in configuration: {}, using default: {}", logLevelStr, currentLogLevel);
                }
            }
            
            // Load masking configuration
            String maskingEnabledStr = configManager.getProperty("error.reporting.masking.enabled");
            if (maskingEnabledStr != null) {
                sensitiveDataMaskingEnabled = Boolean.parseBoolean(maskingEnabledStr);
            }
            
            logger.debug("Configuration loaded: log level={}, masking enabled={}", currentLogLevel, sensitiveDataMaskingEnabled);
            
        } catch (Exception e) {
            logger.warn("Failed to load error reporting configuration, using defaults", e);
        }
    }
    
    /**
     * Gets the current correlation ID, generating one if none exists.
     */
    private String getCurrentCorrelationId() {
        String correlationId = correlationContext.get();
        if (correlationId == null) {
            correlationId = generateCorrelationId();
            correlationContext.set(correlationId);
        }
        return correlationId;
    }
    
    /**
     * Checks if logging is enabled for the specified level.
     */
    private boolean isLoggingEnabled(LogLevel level) {
        return errorReportingActive && level.ordinal() >= currentLogLevel.ordinal();
    }
    
    /**
     * Logs to SLF4J using the appropriate method for the log level.
     */
    private void logToSlf4j(LogLevel level, String message, Throwable throwable) {
        switch (level) {
            case TRACE:
                if (throwable != null) {
                    logger.trace(message, throwable);
                } else {
                    logger.trace(message);
                }
                break;
            case DEBUG:
                if (throwable != null) {
                    logger.debug(message, throwable);
                } else {
                    logger.debug(message);
                }
                break;
            case INFO:
                if (throwable != null) {
                    logger.info(message, throwable);
                } else {
                    logger.info(message);
                }
                break;
            case WARN:
                if (throwable != null) {
                    logger.warn(message, throwable);
                } else {
                    logger.warn(message);
                }
                break;
            case ERROR:
            case FATAL:
                if (throwable != null) {
                    logger.error(message, throwable);
                } else {
                    logger.error(message);
                }
                break;
        }
    }
    
    /**
     * Determines appropriate log level for an exception.
     */
    private LogLevel determineLogLevelForException(Throwable exception) {
        if (exception instanceof RuntimeException) {
            return LogLevel.ERROR;
        } else if (exception instanceof Error) {
            return LogLevel.FATAL;
        } else {
            return LogLevel.WARN;
        }
    }
    
    /**
     * Updates error metrics counters.
     */
    private void updateErrorMetrics(LogLevel level, ErrorSeverity severity) {
        totalErrorCount.incrementAndGet();
        errorCountsByLevel.get(level).incrementAndGet();
        errorCountsBySeverity.get(severity).incrementAndGet();
    }
    
    /**
     * Determines error severity from log level.
     */
    private ErrorSeverity determineSeverityFromLevel(LogLevel level) {
        switch (level) {
            case TRACE:
            case DEBUG:
                return ErrorSeverity.LOW;
            case INFO:
                return ErrorSeverity.LOW;
            case WARN:
                return ErrorSeverity.MEDIUM;
            case ERROR:
                return ErrorSeverity.HIGH;
            case FATAL:
                return ErrorSeverity.CRITICAL;
            default:
                return ErrorSeverity.MEDIUM;
        }
    }
    
    /**
     * Captures current system state information.
     */
    private Map<String, Object> captureSystemState() {
        Map<String, Object> systemState = new HashMap<>();
        
        try {
            Runtime runtime = Runtime.getRuntime();
            systemState.put("freeMemory", runtime.freeMemory());
            systemState.put("totalMemory", runtime.totalMemory());
            systemState.put("maxMemory", runtime.maxMemory());
            systemState.put("availableProcessors", runtime.availableProcessors());
            systemState.put("timestamp", Instant.now().toString());
            systemState.put("threadName", Thread.currentThread().getName());
            systemState.put("threadId", Thread.currentThread().getId());
        } catch (Exception e) {
            systemState.put("error", "Failed to capture system state: " + e.getMessage());
        }
        
        return systemState;
    }
    
    /**
     * Masks sensitive data in a map of context data.
     */
    private Map<String, Object> maskSensitiveDataInMap(Map<String, Object> context) {
        if (context == null || !sensitiveDataMaskingEnabled) {
            return context;
        }
        
        Map<String, Object> maskedContext = new HashMap<>();
        context.forEach((key, value) -> {
            if (value instanceof String) {
                maskedContext.put(key, maskSensitiveData((String) value));
            } else {
                maskedContext.put(key, value);
            }
        });
        
        return maskedContext;
    }
    
    /**
     * Determines the module name from call stack or thread context.
     */
    private String determineModuleName() {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                if (className.contains("com.automation.framework") && !className.contains("ErrorReporter")) {
                    return className.substring(className.lastIndexOf('.') + 1);
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to determine module name", e);
        }
        return "Unknown";
    }
    
    /**
     * Determines the test name from context or thread name.
     */
    private String determineTestName(Map<String, Object> context) {
        if (context != null && context.containsKey("testName")) {
            return context.get("testName").toString();
        }
        
        String threadName = Thread.currentThread().getName();
        if (threadName.contains("test") || threadName.contains("Test")) {
            return threadName;
        }
        
        return "Unknown";
    }
    
    /**
     * Generates an error signature for deduplication.
     */
    private String generateErrorSignature(String message, Throwable throwable, String stackTrace) {
        try {
            StringBuilder signature = new StringBuilder();
            
            if (message != null) {
                signature.append(message);
            }
            
            if (throwable != null) {
                signature.append(throwable.getClass().getName());
                if (throwable.getMessage() != null) {
                    signature.append(throwable.getMessage());
                }
            }
            
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signature.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 16); // Use first 16 characters
            
        } catch (Exception e) {
            return UUID.randomUUID().toString().substring(0, 16);
        }
    }
    
    /**
     * Calculates current error rate (errors per minute).
     */
    private double calculateErrorRate() {
        // Simple implementation - could be enhanced with time windows
        return totalErrorCount.get() / 60.0; // Approximate rate
    }
    
    /**
     * Calculates average errors per minute.
     */
    private double calculateAverageErrorsPerMinute() {
        return calculateErrorRate(); // Simplified for now
    }
    
    /**
     * Generates error trend data.
     */
    private Map<String, Object> generateErrorTrends() {
        Map<String, Object> trends = new HashMap<>();
        trends.put("totalErrors", totalErrorCount.get());
        trends.put("timestamp", Instant.now().toString());
        return trends;
    }
    
    /**
     * Generates correlation ID metrics.
     */
    private Map<String, Object> generateCorrelationIdMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("activeCorrelationIds", errorContexts.size());
        metrics.put("currentCorrelationId", getCurrentCorrelationId());
        return metrics;
    }
    
    /**
     * Generates sensitive data masking metrics.
     */
    private Map<String, Object> generateSensitiveDataMaskingMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("maskingEnabled", sensitiveDataMaskingEnabled);
        metrics.put("maskingPatterns", sensitiveDataPatterns.size());
        return metrics;
    }
    
    /**
     * Generates structured log metrics.
     */
    private Map<String, Object> generateStructuredLogMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("structuredLoggingEnabled", true);
        metrics.put("jsonFormat", true);
        return metrics;
    }
}
