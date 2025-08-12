package com.automation.framework.validation;

/**
 * Enumeration of validation modes for comprehensive API and response validation.
 * Each mode provides different validation behaviors and error handling strategies.
 */
public enum ValidationMode {
    /**
     * Strict validation mode - all issues treated as errors, no tolerance for deviations
     */
    STRICT("Strict validation mode - all issues treated as errors"),
    
    /**
     * Lenient validation mode - non-critical issues treated as warnings, allows some flexibility
     */
    LENIENT("Lenient validation mode - non-critical issues treated as warnings"),
    
    /**
     * Fail-fast mode - stops validation on first error for quick feedback
     */
    FAIL_FAST("Fail-fast mode - stops validation on first error"),
    
    /**
     * Collect all errors mode - continues validation to find all issues before reporting
     */
    COLLECT_ALL_ERRORS("Collect all errors mode - continues validation to find all issues"),
    
    /**
     * Partial validation mode - validates only specified fields or sections
     */
    PARTIAL_VALIDATION("Partial validation mode - validates only specified fields"),
    
    /**
     * Streaming validation mode - validates large responses in chunks for memory efficiency
     */
    STREAMING_VALIDATION("Streaming validation mode - validates large responses in chunks"),
    
    /**
     * Performance-focused mode - optimized for speed with minimal overhead
     */
    PERFORMANCE_FOCUSED("Performance-focused mode - optimized for speed"),
    
    /**
     * Compliance-focused mode - enhanced validation for regulatory and security requirements
     */
    COMPLIANCE_FOCUSED("Compliance-focused mode - enhanced validation for regulatory requirements"),
    
    /**
     * Security-focused mode - emphasizes security validation and threat detection
     */
    SECURITY_FOCUSED("Security-focused mode - emphasizes security validation and threat detection");
    
    private final String description;
    
    ValidationMode(String description) {
        this.description = description;
    }
    
    /**
     * Gets the description of this validation mode.
     * 
     * @return Human-readable description
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Checks if this mode is strict (treats warnings as errors).
     * 
     * @return true if strict mode
     */
    public boolean isStrict() {
        return this == STRICT || this == COMPLIANCE_FOCUSED || this == SECURITY_FOCUSED;
    }
    
    /**
     * Checks if this mode should fail fast on first error.
     * 
     * @return true if fail-fast mode
     */
    public boolean isFailFast() {
        return this == FAIL_FAST;
    }
    
    /**
     * Checks if this mode supports partial validation.
     * 
     * @return true if partial validation is supported
     */
    public boolean supportsPartialValidation() {
        return this == PARTIAL_VALIDATION || this == STREAMING_VALIDATION;
    }
    
    /**
     * Checks if this mode is optimized for performance.
     * 
     * @return true if performance-optimized
     */
    public boolean isPerformanceOptimized() {
        return this == PERFORMANCE_FOCUSED;
    }
    
    @Override
    public String toString() {
        return name() + ": " + description;
    }
}