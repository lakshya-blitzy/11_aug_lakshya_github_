package com.automation.framework.exceptions;

/**
 * Enumeration of logging levels in hierarchical order.
 * Each level represents increasing severity and importance.
 */
public enum LogLevel {
    /**
     * Trace level - most detailed logging for debugging purposes
     */
    TRACE,
    
    /**
     * Debug level - detailed information for troubleshooting
     */
    DEBUG,
    
    /**
     * Info level - general information about application flow
     */
    INFO,
    
    /**
     * Warning level - potentially harmful situations
     */
    WARN,
    
    /**
     * Error level - serious problems that don't stop execution
     */
    ERROR,
    
    /**
     * Fatal level - critical errors that may cause application termination
     */
    FATAL
}