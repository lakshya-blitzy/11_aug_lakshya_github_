package com.automation.framework.exceptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comprehensive error metrics collection and analysis.
 * Provides detailed statistics about error rates, patterns, and trends across the framework.
 */
public class ErrorMetrics {
    
    private final long totalErrors;
    private final double errorRate;
    private final double averageErrorsPerMinute;
    private final Instant lastErrorTime;
    private final Instant timeWindow;
    private Duration timeWindowDuration;
    private final Map<LogLevel, Long> errorsByLevel;
    private final Map<ErrorSeverity, Long> errorsBySeverity;
    private final Map<String, Object> errorTrends;
    private final Map<String, Object> correlationIdMetrics;
    private final Map<String, Object> sensitiveDataMaskingMetrics;
    private final Map<String, Object> structuredLogMetrics;
    
    /**
     * Creates a new ErrorMetrics instance with the provided data.
     */
    public ErrorMetrics(long totalErrors, double errorRate, double averageErrorsPerMinute,
                       Instant lastErrorTime, Instant timeWindow, Duration timeWindowDuration,
                       Map<LogLevel, Long> errorsByLevel, Map<ErrorSeverity, Long> errorsBySeverity,
                       Map<String, Object> errorTrends, Map<String, Object> correlationIdMetrics,
                       Map<String, Object> sensitiveDataMaskingMetrics,
                       Map<String, Object> structuredLogMetrics) {
        this.totalErrors = totalErrors;
        this.errorRate = errorRate;
        this.averageErrorsPerMinute = averageErrorsPerMinute;
        this.lastErrorTime = lastErrorTime;
        this.timeWindow = timeWindow;
        this.timeWindowDuration = timeWindowDuration;
        this.errorsByLevel = new ConcurrentHashMap<>(errorsByLevel != null ? errorsByLevel : new HashMap<>());
        this.errorsBySeverity = new ConcurrentHashMap<>(errorsBySeverity != null ? errorsBySeverity : new HashMap<>());
        this.errorTrends = new HashMap<>(errorTrends != null ? errorTrends : new HashMap<>());
        this.correlationIdMetrics = new HashMap<>(correlationIdMetrics != null ? correlationIdMetrics : new HashMap<>());
        this.sensitiveDataMaskingMetrics = new HashMap<>(sensitiveDataMaskingMetrics != null ? sensitiveDataMaskingMetrics : new HashMap<>());
        this.structuredLogMetrics = new HashMap<>(structuredLogMetrics != null ? structuredLogMetrics : new HashMap<>());
    }
    
    /**
     * Gets the total number of errors recorded.
     * 
     * @return Total error count
     */
    public long getTotalErrors() {
        return totalErrors;
    }
    
    /**
     * Gets the current error rate (errors per second).
     * 
     * @return Error rate as double
     */
    public double getErrorRate() {
        return errorRate;
    }
    
    /**
     * Gets the average number of errors per minute over the time window.
     * 
     * @return Average errors per minute
     */
    public double getAverageErrorsPerMinute() {
        return averageErrorsPerMinute;
    }
    
    /**
     * Gets the timestamp of the most recent error.
     * 
     * @return Last error timestamp, or null if no errors have occurred
     */
    public Instant getLastErrorTime() {
        return lastErrorTime;
    }
    
    /**
     * Gets the breakdown of errors by log level.
     * 
     * @return Map of log levels to error counts
     */
    public Map<LogLevel, Long> getErrorsByLevel() {
        return new HashMap<>(errorsByLevel);
    }
    
    /**
     * Gets the breakdown of errors by severity level.
     * 
     * @return Map of severity levels to error counts
     */
    public Map<ErrorSeverity, Long> getErrorsBySeverity() {
        return new HashMap<>(errorsBySeverity);
    }
    
    /**
     * Gets error trend analysis over time.
     * 
     * @return Map containing trend analysis data
     */
    public Map<String, Object> getErrorTrends() {
        return new HashMap<>(errorTrends);
    }
    
    /**
     * Gets metrics related to correlation ID usage and tracking.
     * 
     * @return Map containing correlation ID metrics
     */
    public Map<String, Object> getCorrelationIdMetrics() {
        return new HashMap<>(correlationIdMetrics);
    }
    
    /**
     * Gets metrics about sensitive data masking operations.
     * 
     * @return Map containing masking metrics
     */
    public Map<String, Object> getSensitiveDataMaskingMetrics() {
        return new HashMap<>(sensitiveDataMaskingMetrics);
    }
    
    /**
     * Gets metrics about structured logging performance and usage.
     * 
     * @return Map containing structured log metrics
     */
    public Map<String, Object> getStructuredLogMetrics() {
        return new HashMap<>(structuredLogMetrics);
    }
    
    /**
     * Resets all metrics to zero.
     * Used for starting fresh monitoring periods.
     */
    public void resetMetrics() {
        // Note: This implementation creates a new instance rather than modifying in place
        // since this class is designed to be immutable. In practice, the ErrorReporter
        // would create a new ErrorMetrics instance to represent reset metrics.
    }
    
    /**
     * Gets the current time window for metric calculations.
     * 
     * @return The time window start time
     */
    public Instant getTimeWindow() {
        return timeWindow;
    }
    
    /**
     * Sets the time window duration for metric calculations.
     * 
     * @param duration The new time window duration
     */
    public void setTimeWindow(Duration duration) {
        if (duration != null && !duration.isNegative() && !duration.isZero()) {
            this.timeWindowDuration = duration;
        }
    }
    
    /**
     * Generates a comprehensive metrics report.
     * 
     * @return JSON formatted metrics report
     */
    public String generateMetricsReport() {
        try {
            Map<String, Object> report = new HashMap<>();
            
            // Basic counts
            report.put("total_errors", totalErrors);
            report.put("error_rate", errorRate);
            report.put("average_errors_per_minute", averageErrorsPerMinute);
            report.put("last_error_time", lastErrorTime != null ? lastErrorTime.toString() : null);
            report.put("time_window", timeWindow.toString());
            report.put("time_window_duration_minutes", timeWindowDuration.toMinutes());
            
            // Level breakdown
            Map<String, Long> levelBreakdown = new HashMap<>();
            errorsByLevel.forEach((level, count) -> levelBreakdown.put(level.toString(), count));
            report.put("errors_by_level", levelBreakdown);
            
            // Severity breakdown
            Map<String, Long> severityBreakdown = new HashMap<>();
            errorsBySeverity.forEach((severity, count) -> severityBreakdown.put(severity.toString(), count));
            report.put("errors_by_severity", severityBreakdown);
            
            // Additional metrics
            report.put("error_trends", errorTrends);
            report.put("correlation_id_metrics", correlationIdMetrics);
            report.put("sensitive_data_masking_metrics", sensitiveDataMaskingMetrics);
            report.put("structured_log_metrics", structuredLogMetrics);
            
            // Summary statistics
            Map<String, Object> summary = new HashMap<>();
            summary.put("high_severity_percentage", calculateHighSeverityPercentage());
            summary.put("critical_errors", errorsBySeverity.getOrDefault(ErrorSeverity.CRITICAL, 0L));
            summary.put("most_common_level", findMostCommonLevel());
            summary.put("most_common_severity", findMostCommonSeverity());
            report.put("summary", summary);
            
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
            mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            
            return mapper.writeValueAsString(report);
            
        } catch (Exception e) {
            return String.format("{\"error\":\"Failed to generate metrics report: %s\"}", e.getMessage());
        }
    }
    
    /**
     * Calculates the percentage of high and critical severity errors.
     */
    private double calculateHighSeverityPercentage() {
        if (totalErrors == 0) {
            return 0.0;
        }
        
        long highSeverityCount = errorsBySeverity.getOrDefault(ErrorSeverity.HIGH, 0L) + 
                                errorsBySeverity.getOrDefault(ErrorSeverity.CRITICAL, 0L);
        
        return (double) highSeverityCount / totalErrors * 100.0;
    }
    
    /**
     * Finds the most common log level.
     */
    private String findMostCommonLevel() {
        return errorsByLevel.entrySet().stream()
                           .max(Map.Entry.comparingByValue())
                           .map(entry -> entry.getKey().toString())
                           .orElse("NONE");
    }
    
    /**
     * Finds the most common error severity.
     */
    private String findMostCommonSeverity() {
        return errorsBySeverity.entrySet().stream()
                              .max(Map.Entry.comparingByValue())
                              .map(entry -> entry.getKey().toString())
                              .orElse("NONE");
    }
    
    @Override
    public String toString() {
        return String.format("ErrorMetrics{totalErrors=%d, errorRate=%.2f, lastErrorTime=%s}", 
                           totalErrors, errorRate, lastErrorTime);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ErrorMetrics that = (ErrorMetrics) obj;
        return totalErrors == that.totalErrors &&
               Double.compare(that.errorRate, errorRate) == 0 &&
               Objects.equals(timeWindow, that.timeWindow);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(totalErrors, errorRate, timeWindow);
    }
}