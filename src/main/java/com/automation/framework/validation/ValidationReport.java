package com.automation.framework.validation;

import java.time.Instant;

/**
 * Represents a comprehensive validation report with metrics and summary.
 * Provides detailed insights into validation performance, error rates, and system status.
 */
public class ValidationReport {
    
    private final long totalValidations;
    private final long totalErrors;
    private final long totalWarnings;
    private final long timeoutThreshold;
    private final boolean strictModeEnabled;
    private final ValidationMode validationMode;
    private final Instant reportTimestamp;
    private final int cacheSize;
    private final String errorMessage;
    
    /**
     * Creates a comprehensive validation report.
     * 
     * @param totalValidations Total number of validations performed
     * @param totalErrors Total number of errors encountered
     * @param totalWarnings Total number of warnings encountered
     * @param timeoutThreshold Timeout threshold in milliseconds
     * @param strictModeEnabled Whether strict mode is enabled
     * @param validationMode Current validation mode
     * @param reportTimestamp When this report was generated
     * @param cacheSize Current validation cache size
     */
    public ValidationReport(long totalValidations, long totalErrors, long totalWarnings, 
                          long timeoutThreshold, boolean strictModeEnabled, ValidationMode validationMode,
                          Instant reportTimestamp, int cacheSize) {
        this.totalValidations = totalValidations;
        this.totalErrors = totalErrors;
        this.totalWarnings = totalWarnings;
        this.timeoutThreshold = timeoutThreshold;
        this.strictModeEnabled = strictModeEnabled;
        this.validationMode = validationMode;
        this.reportTimestamp = reportTimestamp;
        this.cacheSize = cacheSize;
        this.errorMessage = null;
    }
    
    /**
     * Creates an error report indicating report generation failure.
     * 
     * @param errorMessage The error message describing the failure
     */
    private ValidationReport(String errorMessage) {
        this.totalValidations = 0;
        this.totalErrors = 0;
        this.totalWarnings = 0;
        this.timeoutThreshold = 0;
        this.strictModeEnabled = false;
        this.validationMode = ValidationMode.LENIENT;
        this.reportTimestamp = Instant.now();
        this.cacheSize = 0;
        this.errorMessage = errorMessage;
    }
    
    /**
     * Creates an error report when report generation fails.
     * 
     * @param errorMessage The error message
     * @return Error validation report
     */
    public static ValidationReport createErrorReport(String errorMessage) {
        return new ValidationReport(errorMessage);
    }
    
    /**
     * Gets the total number of validations performed.
     * 
     * @return Total validations count
     */
    public long getTotalValidations() {
        return totalValidations;
    }
    
    /**
     * Gets the total number of errors encountered.
     * 
     * @return Total errors count
     */
    public long getTotalErrors() {
        return totalErrors;
    }
    
    /**
     * Gets the total number of warnings encountered.
     * 
     * @return Total warnings count
     */
    public long getTotalWarnings() {
        return totalWarnings;
    }
    
    /**
     * Gets the timeout threshold in milliseconds.
     * 
     * @return Timeout threshold
     */
    public long getTimeoutThreshold() {
        return timeoutThreshold;
    }
    
    /**
     * Checks if strict mode is enabled.
     * 
     * @return true if strict mode is enabled
     */
    public boolean isStrictModeEnabled() {
        return strictModeEnabled;
    }
    
    /**
     * Gets the current validation mode.
     * 
     * @return Validation mode
     */
    public ValidationMode getValidationMode() {
        return validationMode;
    }
    
    /**
     * Gets the report generation timestamp.
     * 
     * @return Report timestamp
     */
    public Instant getReportTimestamp() {
        return reportTimestamp;
    }
    
    /**
     * Gets the current validation cache size.
     * 
     * @return Cache size
     */
    public int getCacheSize() {
        return cacheSize;
    }
    
    /**
     * Checks if this report indicates an error condition.
     * 
     * @return true if report generation failed
     */
    public boolean hasError() {
        return errorMessage != null;
    }
    
    /**
     * Gets the error message if report generation failed.
     * 
     * @return Error message or null if no error
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * Calculates the error rate as a percentage.
     * 
     * @return Error rate (0.0 to 1.0)
     */
    public double getErrorRate() {
        return totalValidations > 0 ? (double) totalErrors / totalValidations : 0.0;
    }
    
    /**
     * Calculates the warning rate as a percentage.
     * 
     * @return Warning rate (0.0 to 1.0)
     */
    public double getWarningRate() {
        return totalValidations > 0 ? (double) totalWarnings / totalValidations : 0.0;
    }
    
    /**
     * Calculates the success rate as a percentage.
     * 
     * @return Success rate (0.0 to 1.0)
     */
    public double getSuccessRate() {
        if (totalValidations == 0) {
            return 1.0; // No validations means 100% success
        }
        long successfulValidations = totalValidations - totalErrors;
        return (double) successfulValidations / totalValidations;
    }
    
    /**
     * Checks if the validation system is healthy (low error rate).
     * 
     * @return true if error rate is below 5%
     */
    public boolean isHealthy() {
        return getErrorRate() < 0.05; // Less than 5% error rate
    }
    
    /**
     * Gets a summary status of the validation system.
     * 
     * @return Status string (HEALTHY, WARNING, CRITICAL)
     */
    public String getSystemStatus() {
        if (hasError()) {
            return "ERROR";
        }
        
        double errorRate = getErrorRate();
        if (errorRate < 0.01) {
            return "HEALTHY";
        } else if (errorRate < 0.05) {
            return "WARNING";
        } else {
            return "CRITICAL";
        }
    }
    
    @Override
    public String toString() {
        if (errorMessage != null) {
            return "ValidationReport[ERROR: " + errorMessage + "]";
        }
        
        return String.format("ValidationReport[validations=%d, errors=%d, warnings=%d, errorRate=%.2f%%, " +
                           "warningRate=%.2f%%, successRate=%.2f%%, status=%s, timeout=%dms, strict=%s, mode=%s, cache=%d]",
                           totalValidations, totalErrors, totalWarnings, 
                           getErrorRate() * 100, getWarningRate() * 100, getSuccessRate() * 100,
                           getSystemStatus(), timeoutThreshold, strictModeEnabled, validationMode, cacheSize);
    }
}