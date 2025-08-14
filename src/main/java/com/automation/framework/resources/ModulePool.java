package com.automation.framework.resources;

/**
 * Enumeration of module thread pools for bulkhead isolation pattern.
 * 
 * Each module has its own dedicated thread pool to prevent cascading failures
 * and ensure proper resource isolation across the automation framework.
 * 
 * This design allows for:
 * - Independent scaling per module
 * - Failure isolation boundaries
 * - Specialized thread pool configurations per use case
 * - Clear resource allocation tracking
 */
public enum ModulePool {
    /**
     * Web automation module pool for browser-based testing
     */
    WEB,
    
    /**
     * API automation module pool for RESTful API testing
     */
    API,
    
    /**
     * Reporting module pool for test result processing and report generation
     */
    REPORTING,
    
    /**
     * Monitoring module pool for framework health and performance monitoring
     */
    MONITORING,
    
    /**
     * Core services module pool for framework orchestration and coordination
     */
    CORE
}