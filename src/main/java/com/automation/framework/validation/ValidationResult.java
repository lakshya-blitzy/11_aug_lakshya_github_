package com.automation.framework.validation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ValidationResult encapsulates the results of state validation operations.
 * 
 * This class provides comprehensive information about validation outcomes,
 * including success/failure status, error messages, warnings, and detailed
 * validation information for debugging and reporting purposes.
 * 
 * The class supports both successful and failed validation scenarios with
 * appropriate error reporting and metadata collection.
 */
public class ValidationResult {
    
    private final boolean valid;
    private final List<String> errors;
    private final List<String> warnings;
    private final Map<String, Object> validationDetails;
    private final Instant validationTime;
    private final ElementState elementState;
    private final StateValidationType validationType;
    private final String errorMessage;
    private final String validationSummary;
    
    /**
     * Creates a successful ValidationResult.
     * 
     * @param validationType The type of validation performed
     * @param message Success message
     * @param details Validation details
     * @return ValidationResult indicating success
     */
    public static ValidationResult success(StateValidationType validationType, String message, Map<String, Object> details) {
        return new ValidationResult(true, Collections.emptyList(), Collections.emptyList(),
            details, Instant.now(), ElementState.READY, validationType, null, message);
    }
    
    /**
     * Creates a failed ValidationResult.
     * 
     * @param validationType The type of validation performed
     * @param errorMessage Error message
     * @param details Validation details
     * @return ValidationResult indicating failure
     */
    public static ValidationResult failure(StateValidationType validationType, String errorMessage, Map<String, Object> details) {
        return new ValidationResult(false, Collections.singletonList(errorMessage), Collections.emptyList(),
            details, Instant.now(), ElementState.ERROR_STATE, validationType, errorMessage, "Validation failed");
    }
    
    /**
     * Creates a ValidationResult with complete information.
     * 
     * @param valid Whether validation was successful
     * @param errors List of error messages
     * @param warnings List of warning messages
     * @param validationDetails Detailed validation information
     * @param validationTime Timestamp of validation
     * @param elementState Current element state
     * @param validationType Type of validation performed
     * @param errorMessage Primary error message
     * @param validationSummary Summary of validation results
     */
    public ValidationResult(boolean valid, List<String> errors, List<String> warnings,
                          Map<String, Object> validationDetails, Instant validationTime,
                          ElementState elementState, StateValidationType validationType,
                          String errorMessage, String validationSummary) {
        this.valid = valid;
        this.errors = new ArrayList<>(errors != null ? errors : Collections.emptyList());
        this.warnings = new ArrayList<>(warnings != null ? warnings : Collections.emptyList());
        this.validationDetails = new HashMap<>(validationDetails != null ? validationDetails : Collections.emptyMap());
        this.validationTime = validationTime != null ? validationTime : Instant.now();
        this.elementState = elementState != null ? elementState : ElementState.READY;
        this.validationType = validationType;
        this.errorMessage = errorMessage;
        this.validationSummary = validationSummary;
    }
    
    /**
     * Returns whether the validation was successful.
     * 
     * @return true if validation passed, false otherwise
     */
    public boolean isValid() {
        return valid;
    }
    
    /**
     * Returns the list of validation errors.
     * 
     * @return Unmodifiable list of error messages
     */
    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }
    
    /**
     * Returns the list of validation warnings.
     * 
     * @return Unmodifiable list of warning messages
     */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }
    
    /**
     * Returns detailed validation information.
     * 
     * @return Unmodifiable map of validation details
     */
    public Map<String, Object> getValidationDetails() {
        return Collections.unmodifiableMap(validationDetails);
    }
    
    /**
     * Returns the validation timestamp.
     * 
     * @return Instant when validation was performed
     */
    public Instant getValidationTime() {
        return validationTime;
    }
    
    /**
     * Returns the element state at time of validation.
     * 
     * @return ElementState enum value
     */
    public ElementState getElementState() {
        return elementState;
    }
    
    /**
     * Returns the type of validation performed.
     * 
     * @return StateValidationType enum value
     */
    public StateValidationType getValidationType() {
        return validationType;
    }
    
    /**
     * Returns the primary error message.
     * 
     * @return Error message string, or null if no errors
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * Returns the validation timestamp as milliseconds since epoch.
     * 
     * @return Timestamp in milliseconds
     */
    public long getTimestamp() {
        return validationTime.toEpochMilli();
    }
    
    /**
     * Returns whether there are any validation errors.
     * 
     * @return true if errors exist, false otherwise
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    /**
     * Returns whether there are any validation warnings.
     * 
     * @return true if warnings exist, false otherwise
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    /**
     * Returns a summary of the validation results.
     * 
     * @return Validation summary string
     */
    public String getValidationSummary() {
        return validationSummary;
    }
    
    /**
     * Adds an error message to the validation result.
     * 
     * @param error Error message to add
     */
    public void addError(String error) {
        if (error != null && !error.trim().isEmpty()) {
            errors.add(error.trim());
        }
    }
    
    /**
     * Adds a warning message to the validation result.
     * 
     * @param warning Warning message to add
     */
    public void addWarning(String warning) {
        if (warning != null && !warning.trim().isEmpty()) {
            warnings.add(warning.trim());
        }
    }
    
    @Override
    public String toString() {
        return String.format("ValidationResult{valid=%s, type=%s, errors=%d, warnings=%d, time=%s}",
            valid, validationType, errors.size(), warnings.size(), validationTime);
    }
}