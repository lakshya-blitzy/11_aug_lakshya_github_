package com.automation.framework.exceptions;

/**
 * Enumeration of error severity levels for classification and prioritization.
 * Used for error routing, alerting, and escalation decisions.
 */
public enum ErrorSeverity {
    /**
     * Low severity - informational errors with minimal impact
     */
    LOW,
    
    /**
     * Medium severity - warnings and non-critical errors
     */
    MEDIUM,
    
    /**
     * High severity - serious errors requiring attention
     */
    HIGH,
    
    /**
     * Critical severity - urgent errors requiring immediate action
     */
    CRITICAL
}