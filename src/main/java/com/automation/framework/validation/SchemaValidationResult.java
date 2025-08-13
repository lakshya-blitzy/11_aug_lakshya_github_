package com.automation.framework.validation;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * SchemaValidationResult encapsulates the results of schema validation operations.
 * 
 * This immutable result class provides comprehensive validation feedback including
 * success status, detailed error messages with JSON path locations, warning information,
 * severity classification, and performance timing data for monitoring and debugging.
 * 
 * Features:
 * - Immutable result object ensuring thread safety and data integrity
 * - Detailed error messages with JSON path locations for precise error identification
 * - Warning collection for non-critical validation issues in relaxed mode
 * - Severity classification (VALID, WARNING, ERROR, CRITICAL) for appropriate response handling
 * - Performance timing data for validation operation monitoring and optimization
 * - Builder pattern support for flexible result construction with optional parameters
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class SchemaValidationResult {
    
    private final boolean valid;
    private final List<String> errors;
    private final List<String> warnings;
    private final String jsonPath;
    private final String errorMessage;
    private final String severity;
    private final long validationTime;
    
    /**
     * Creates a new SchemaValidationResult with comprehensive validation feedback.
     * 
     * @param valid Whether the validation passed successfully
     * @param errors List of validation error messages
     * @param warnings List of validation warning messages
     * @param jsonPath JSON path location of the primary validation issue
     * @param errorMessage Primary error message for the validation failure
     * @param severity Severity level of the validation result (VALID, WARNING, ERROR, CRITICAL)
     * @param validationTime Time taken for validation operation in milliseconds
     */
    public SchemaValidationResult(boolean valid, List<String> errors, List<String> warnings,
                           String jsonPath, String errorMessage, String severity, long validationTime) {
        this.valid = valid;
        this.errors = errors != null ? Collections.unmodifiableList(new ArrayList<>(errors)) : Collections.emptyList();
        this.warnings = warnings != null ? Collections.unmodifiableList(new ArrayList<>(warnings)) : Collections.emptyList();
        this.jsonPath = jsonPath != null ? jsonPath : "";
        this.errorMessage = errorMessage != null ? errorMessage : "";
        this.severity = severity != null ? severity : "UNKNOWN";
        this.validationTime = validationTime;
    }
    
    /**
     * Checks if the validation was successful.
     * 
     * @return true if validation passed, false if validation failed
     */
    public boolean isValid() {
        return valid;
    }
    
    /**
     * Gets the list of validation errors.
     * 
     * @return Immutable list of error messages
     */
    public List<String> getErrors() {
        return errors;
    }
    
    /**
     * Gets the list of validation warnings.
     * 
     * @return Immutable list of warning messages
     */
    public List<String> getWarnings() {
        return warnings;
    }
    
    /**
     * Gets the JSON path location of the primary validation issue.
     * 
     * @return JSON path string indicating location of validation issue
     */
    public String getJsonPath() {
        return jsonPath;
    }
    
    /**
     * Gets the primary error message for validation failure.
     * 
     * @return Primary error message describing the validation failure
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * Gets the severity level of the validation result.
     * 
     * @return Severity classification (VALID, WARNING, ERROR, CRITICAL)
     */
    public String getSeverity() {
        return severity;
    }
    
    /**
     * Gets the time taken for the validation operation.
     * 
     * @return Validation time in milliseconds
     */
    public long getValidationTime() {
        return validationTime;
    }
    
    /**
     * Checks if the result has any errors.
     * 
     * @return true if errors are present, false otherwise
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    /**
     * Checks if the result has any warnings.
     * 
     * @return true if warnings are present, false otherwise
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    /**
     * Gets the total count of issues (errors + warnings).
     * 
     * @return Total number of validation issues
     */
    public int getTotalIssueCount() {
        return errors.size() + warnings.size();
    }
    
    /**
     * Creates a formatted summary of the validation result.
     * 
     * @return String summary of validation result with status and issue counts
     */
    public String getSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("Validation ").append(valid ? "PASSED" : "FAILED");
        summary.append(" [").append(severity).append("]");
        
        if (!errors.isEmpty()) {
            summary.append(" - ").append(errors.size()).append(" error(s)");
        }
        
        if (!warnings.isEmpty()) {
            summary.append(" - ").append(warnings.size()).append(" warning(s)");
        }
        
        summary.append(" (").append(validationTime).append("ms)");
        
        return summary.toString();
    }
    
    @Override
    public String toString() {
        return "SchemaValidationResult{" +
               "valid=" + valid +
               ", errors=" + errors.size() +
               ", warnings=" + warnings.size() +
               ", severity='" + severity + '\'' +
               ", validationTime=" + validationTime +
               '}';
    }
}