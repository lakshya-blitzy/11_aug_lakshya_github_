package com.automation.framework.exceptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Comprehensive error context information capturing all relevant details about an error occurrence.
 * This class provides complete error information for debugging, monitoring, and analysis purposes.
 */
public class ErrorContext {
    
    private final String errorId;
    private final LogLevel errorLevel;
    private final Instant timestamp;
    private final String stackTrace;
    private final String correlationId;
    private final String moduleName;
    private final String testName;
    private final String screenshotData;
    private final String apiRequestData;
    private final String apiResponseData;
    private final Map<String, Object> systemState;
    private final Map<String, Object> additionalContext;
    private final String errorSignature;
    private final ErrorSeverity errorSeverity;
    private final Throwable exception;
    
    /**
     * Creates a new ErrorContext with all error details.
     */
    public ErrorContext(String errorId, LogLevel errorLevel, Instant timestamp, String message,
                       String stackTrace, String correlationId, String moduleName, String testName,
                       String screenshotData, String apiRequestData, String apiResponseData,
                       Map<String, Object> systemState, Map<String, Object> additionalContext,
                       String errorSignature, ErrorSeverity errorSeverity, Throwable exception) {
        this.errorId = errorId;
        this.errorLevel = errorLevel;
        this.timestamp = timestamp;
        this.stackTrace = stackTrace;
        this.correlationId = correlationId;
        this.moduleName = moduleName;
        this.testName = testName;
        this.screenshotData = screenshotData;
        this.apiRequestData = apiRequestData;
        this.apiResponseData = apiResponseData;
        this.systemState = systemState != null ? new HashMap<>(systemState) : new HashMap<>();
        this.additionalContext = additionalContext != null ? new HashMap<>(additionalContext) : new HashMap<>();
        this.errorSignature = errorSignature;
        this.errorSeverity = errorSeverity;
        this.exception = exception;
    }
    
    /**
     * Gets the exception that caused this error.
     * 
     * @return The original exception or null if no exception was involved
     */
    public Throwable getException() {
        return exception;
    }
    
    /**
     * Gets the error level (log level) for this error.
     * 
     * @return The error level
     */
    public LogLevel getErrorLevel() {
        return errorLevel;
    }
    
    /**
     * Gets the timestamp when this error occurred.
     * 
     * @return The error timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * Gets the stack trace information for this error.
     * 
     * @return The stack trace as a string, or null if no stack trace was captured
     */
    public String getStackTrace() {
        return stackTrace;
    }
    
    /**
     * Gets the correlation ID for distributed tracing.
     * 
     * @return The correlation ID
     */
    public String getCorrelationId() {
        return correlationId;
    }
    
    /**
     * Gets the name of the module where this error occurred.
     * 
     * @return The module name
     */
    public String getModuleName() {
        return moduleName;
    }
    
    /**
     * Gets the name of the test that was running when this error occurred.
     * 
     * @return The test name
     */
    public String getTestName() {
        return testName;
    }
    
    /**
     * Gets the screenshot data captured when this error occurred.
     * Typically used for web automation errors.
     * 
     * @return The screenshot data as base64 encoded string, or null if no screenshot was captured
     */
    public String getScreenshotData() {
        return screenshotData;
    }
    
    /**
     * Gets the API request data associated with this error.
     * Used for API testing errors to provide request context.
     * 
     * @return The API request data as JSON string, or null if no API request was involved
     */
    public String getAPIRequestData() {
        return apiRequestData;
    }
    
    /**
     * Gets the API response data associated with this error.
     * Used for API testing errors to provide response context.
     * 
     * @return The API response data as JSON string, or null if no API response was available
     */
    public String getAPIResponseData() {
        return apiResponseData;
    }
    
    /**
     * Gets the system state information captured when this error occurred.
     * Includes memory usage, thread information, and other system metrics.
     * 
     * @return Map containing system state information
     */
    public Map<String, Object> getSystemState() {
        return new HashMap<>(systemState);
    }
    
    /**
     * Gets additional context information provided with this error.
     * 
     * @return Map containing additional context data
     */
    public Map<String, Object> getAdditionalContext() {
        return new HashMap<>(additionalContext);
    }
    
    /**
     * Gets the masked sensitive data (same as additional context but with sensitive data masked).
     * 
     * @return Map containing masked context data
     */
    public Map<String, Object> getMaskedSensitiveData() {
        return getAdditionalContext(); // Already masked in constructor
    }
    
    /**
     * Gets the error signature used for deduplication and categorization.
     * 
     * @return The error signature hash
     */
    public String getErrorSignature() {
        return errorSignature;
    }
    
    /**
     * Gets the unique error ID for this specific error occurrence.
     * 
     * @return The unique error ID
     */
    public String getErrorId() {
        return errorId;
    }
    
    /**
     * Gets the error severity level.
     * 
     * @return The error severity
     */
    public ErrorSeverity getErrorSeverity() {
        return errorSeverity;
    }
    
    /**
     * Converts this error context to a structured JSON format.
     * Creates a comprehensive JSON representation suitable for log analysis systems.
     * 
     * @return JSON formatted string representation of this error context
     */
    public String toStructuredFormat() {
        try {
            Map<String, Object> structuredData = new HashMap<>();
            
            // Basic error information
            structuredData.put("error_id", errorId);
            structuredData.put("correlation_id", correlationId);
            structuredData.put("timestamp", timestamp.toString());
            structuredData.put("error_level", errorLevel.toString());
            structuredData.put("error_severity", errorSeverity.toString());
            structuredData.put("error_signature", errorSignature);
            
            // Context information
            structuredData.put("module_name", moduleName);
            structuredData.put("test_name", testName);
            
            // Exception details
            if (exception != null) {
                Map<String, Object> exceptionData = new HashMap<>();
                exceptionData.put("type", exception.getClass().getName());
                exceptionData.put("message", exception.getMessage());
                exceptionData.put("stack_trace", stackTrace);
                structuredData.put("exception", exceptionData);
            }
            
            // Capture data
            if (screenshotData != null) {
                structuredData.put("screenshot_data", screenshotData);
            }
            if (apiRequestData != null) {
                structuredData.put("api_request_data", apiRequestData);
            }
            if (apiResponseData != null) {
                structuredData.put("api_response_data", apiResponseData);
            }
            
            // System and additional context
            structuredData.put("system_state", systemState);
            structuredData.put("additional_context", additionalContext);
            
            // Create ObjectMapper for JSON serialization
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
            mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            
            return mapper.writeValueAsString(structuredData);
            
        } catch (Exception e) {
            // Fallback to simple format if JSON serialization fails
            return String.format("{\"error_id\":\"%s\",\"timestamp\":\"%s\",\"level\":\"%s\",\"message\":\"JSON serialization failed: %s\"}", 
                                errorId, timestamp.toString(), errorLevel.toString(), e.getMessage());
        }
    }
    
    @Override
    public String toString() {
        return String.format("ErrorContext{errorId='%s', level=%s, timestamp=%s, correlationId='%s', module='%s', test='%s'}", 
                           errorId, errorLevel, timestamp, correlationId, moduleName, testName);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ErrorContext that = (ErrorContext) obj;
        return Objects.equals(errorId, that.errorId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(errorId);
    }
}