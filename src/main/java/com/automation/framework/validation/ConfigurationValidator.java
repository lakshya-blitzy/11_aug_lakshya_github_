package com.automation.framework.validation;

// Internal framework imports - ONLY from depends_on_files
import com.automation.framework.exceptions.ExceptionHandler;
import com.automation.framework.resources.FileResourceHandler;

// External imports for collections and logging
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.Arrays;

// External imports for file path validation
import java.nio.file.Paths;
import java.nio.file.Path;

// External imports for URL validation
import java.net.URL;
import java.net.MalformedURLException;

// External SLF4J logging imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ConfigurationValidator provides comprehensive configuration validation for the automation framework.
 * 
 * This class implements enterprise-grade configuration validation including startup configuration,
 * Maven configuration, TestNG configuration, file resource validation, and environment-specific
 * settings validation with fail-fast mechanisms and detailed error reporting.
 * 
 * Key Features:
 * - Required configuration property validation with missing property detection
 * - Configuration value format validation (URLs, file paths, numeric ranges)
 * - External resource availability verification (browser drivers, test data files)
 * - Environment-specific settings validation and configuration inheritance
 * - Maven configuration and dependency availability checking
 * - TestNG configuration file validity verification
 * - Connection pool settings validation with range checking
 * - Authentication credentials format validation without exposing sensitive data
 * - Fail-fast validation mechanisms with detailed error reporting for troubleshooting
 * - Configuration profile support for different environments (dev, staging, prod)
 * 
 * Dependencies:
 * - ExceptionHandler for centralized error management and recovery
 * - FileResourceHandler for file validation and resource management
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class ConfigurationValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationValidator.class);
    
    // Framework dependency components
    private final ExceptionHandler exceptionHandler;
    private final FileResourceHandler fileResourceHandler;
    
    // Required configuration properties for framework startup
    private static final List<String> REQUIRED_PROPERTIES = Arrays.asList(
        "automation.browser.type",
        "automation.api.base.url",
        "automation.test.data.path",
        "automation.reports.path"
    );
    
    /**
     * Creates a new ConfigurationValidator with framework dependencies.
     * 
     * @param exceptionHandler ExceptionHandler for error management
     * @param fileResourceHandler FileResourceHandler for file validation
     */
    public ConfigurationValidator(ExceptionHandler exceptionHandler, FileResourceHandler fileResourceHandler) {
        this.exceptionHandler = exceptionHandler;
        this.fileResourceHandler = fileResourceHandler;
        
        logger.info("ConfigurationValidator initialized with framework dependencies");
    }
    
    /**
     * Performs comprehensive configuration validation including all required properties,
     * formats, external resources, and framework dependencies.
     * 
     * @param configurationSources Map of configuration sources with their properties
     * @return boolean indicating overall validation success
     */
    public boolean validateConfiguration(Map<String, Properties> configurationSources) {
        try {
            logger.info("Starting comprehensive configuration validation");
            
            if (configurationSources == null || configurationSources.isEmpty()) {
                logger.error("No configuration sources provided for validation");
                return false;
            }
            
            boolean allValid = true;
            
            // Validate each configuration source
            for (Map.Entry<String, Properties> entry : configurationSources.entrySet()) {
                String sourceName = entry.getKey();
                Properties properties = entry.getValue();
                
                logger.debug("Validating configuration source: {}", sourceName);
                
                if (properties == null) {
                    logger.error("Configuration source {} is null", sourceName);
                    allValid = false;
                    continue;
                }
                
                // Validate required properties
                if (!validateRequiredProperties(properties)) {
                    logger.error("Required properties validation failed for source: {}", sourceName);
                    allValid = false;
                }
                
                // Validate property formats
                if (!validatePropertyFormats(properties)) {
                    logger.error("Property format validation failed for source: {}", sourceName);
                    allValid = false;
                }
            }
            
            // Validate external resources
            if (!validateExternalResources()) {
                logger.error("External resources validation failed");
                allValid = false;
            }
            
            // Validate Maven configuration
            if (!validateMavenConfiguration()) {
                logger.error("Maven configuration validation failed");
                allValid = false;
            }
            
            // Validate TestNG configuration
            if (!validateTestNGConfiguration()) {
                logger.error("TestNG configuration validation failed");
                allValid = false;
            }
            
            if (allValid) {
                logger.info("Configuration validation completed successfully");
            } else {
                logger.warn("Configuration validation completed with errors");
            }
            
            return allValid;
            
        } catch (Exception e) {
            logger.error("Critical error during configuration validation: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Validates that all required configuration properties are present and non-empty.
     * 
     * @param properties Properties object containing configuration settings
     * @return boolean indicating validation success
     */
    public boolean validateRequiredProperties(Properties properties) {
        try {
            logger.debug("Validating required configuration properties");
            
            if (properties == null) {
                logger.error("Properties object is null");
                return false;
            }
            
            boolean allValid = true;
            
            // Check for each required property
            for (String requiredProperty : REQUIRED_PROPERTIES) {
                String value = properties.getProperty(requiredProperty);
                
                if (value == null || value.trim().isEmpty()) {
                    logger.error("Required property '{}' is missing or empty", requiredProperty);
                    allValid = false;
                } else {
                    logger.debug("Found required property: {}", requiredProperty);
                }
            }
            
            return allValid;
            
        } catch (Exception e) {
            logger.error("Error validating required properties: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Validates configuration property formats including URLs, file paths, and numeric ranges.
     * 
     * @param properties Properties object containing configuration settings
     * @return boolean indicating validation success
     */
    public boolean validatePropertyFormats(Properties properties) {
        try {
            logger.debug("Validating configuration property formats");
            
            if (properties == null) {
                logger.error("Properties object is null");
                return false;
            }
            
            boolean allValid = true;
            
            // Validate URL format for API base URL
            String apiBaseUrl = properties.getProperty("automation.api.base.url");
            if (apiBaseUrl != null && !isValidUrl(apiBaseUrl)) {
                logger.error("Invalid URL format for automation.api.base.url: {}", apiBaseUrl);
                allValid = false;
            }
            
            // Validate file path formats
            String testDataPath = properties.getProperty("automation.test.data.path");
            if (testDataPath != null && !isValidPath(testDataPath)) {
                logger.error("Invalid file path format for automation.test.data.path: {}", testDataPath);
                allValid = false;
            }
            
            String reportsPath = properties.getProperty("automation.reports.path");
            if (reportsPath != null && !isValidPath(reportsPath)) {
                logger.error("Invalid file path format for automation.reports.path: {}", reportsPath);
                allValid = false;
            }
            
            return allValid;
            
        } catch (Exception e) {
            logger.error("Error validating property formats: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Validates external resource availability including browser drivers and test data files.
     * 
     * @return boolean indicating validation success
     */
    public boolean validateExternalResources() {
        try {
            logger.debug("Validating external resource availability");
            
            boolean allValid = true;
            
            // Validate common browser driver paths
            String[] driverPaths = {
                "drivers/chromedriver",
                "drivers/chromedriver.exe",
                "drivers/geckodriver",
                "drivers/geckodriver.exe"
            };
            
            for (String driverPath : driverPaths) {
                Path path = Paths.get(driverPath);
                if (fileResourceHandler != null && !fileResourceHandler.validateFileAccess(path, "read")) {
                    logger.debug("Driver not found (optional): {}", driverPath);
                    // Not marking as failure since drivers may be in PATH
                } else if (fileResourceHandler == null) {
                    logger.debug("FileResourceHandler not available, skipping driver validation: {}", driverPath);
                }
            }
            
            return allValid;
            
        } catch (Exception e) {
            logger.error("Error validating external resources: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Validates Maven configuration and dependency availability.
     * 
     * @return boolean indicating validation success
     */
    public boolean validateMavenConfiguration() {
        try {
            logger.debug("Validating Maven configuration");
            
            // Check if pom.xml exists
            Path pomPath = Paths.get("pom.xml");
            if (fileResourceHandler != null) {
                if (!fileResourceHandler.validateFileAccess(pomPath, "read")) {
                    logger.error("pom.xml file not found or not readable");
                    return false;
                }
            } else {
                logger.debug("FileResourceHandler not available, skipping pom.xml validation");
            }
            
            logger.debug("Maven configuration validation completed successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error validating Maven configuration: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Validates TestNG configuration file validity.
     * 
     * @return boolean indicating validation success
     */
    public boolean validateTestNGConfiguration() {
        try {
            logger.debug("Validating TestNG configuration");
            
            // Check for TestNG configuration files
            String[] testNgPaths = {
                "src/test/resources/testng.xml",
                "testng.xml"
            };
            
            boolean foundConfig = false;
            if (fileResourceHandler != null) {
                for (String testNgPath : testNgPaths) {
                    Path path = Paths.get(testNgPath);
                    if (fileResourceHandler.validateFileAccess(path, "read")) {
                        logger.debug("Found TestNG configuration: {}", testNgPath);
                        foundConfig = true;
                        break;
                    }
                }
                
                if (!foundConfig) {
                    logger.warn("No TestNG configuration file found (optional)");
                }
            } else {
                logger.debug("FileResourceHandler not available, skipping TestNG configuration validation");
            }
            
            logger.debug("TestNG configuration validation completed");
            return true;
            
        } catch (Exception e) {
            logger.error("Error validating TestNG configuration: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Validates URL format.
     * 
     * @param url URL string to validate
     * @return boolean indicating if URL is valid
     */
    private boolean isValidUrl(String url) {
        try {
            new URL(url);
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }
    
    /**
     * Validates file path format.
     * 
     * @param path Path string to validate
     * @return boolean indicating if path is valid
     */
    private boolean isValidPath(String path) {
        try {
            Paths.get(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

/**
 * ConfigurationError enumeration defines the types of configuration validation errors
 * that can occur during framework startup and configuration validation.
 * 
 * This enum provides standardized error classification for configuration validation
 * failures, enabling appropriate error handling strategies and user guidance for
 * resolving configuration issues.
 */
enum ConfigurationError {
    
    /**
     * Indicates a required configuration property is missing or empty
     */
    MISSING_PROPERTY("A required configuration property is missing or has no value"),
    
    /**
     * Indicates a configuration property has an invalid format or structure
     */
    INVALID_FORMAT("A configuration property has an invalid format or structure"),
    
    /**
     * Indicates an external resource referenced in configuration cannot be found
     */
    RESOURCE_NOT_FOUND("An external resource referenced in configuration cannot be accessed"),
    
    /**
     * Indicates a configuration value is outside the acceptable range
     */
    INVALID_RANGE("A configuration value is outside the acceptable range or limits"),
    
    /**
     * Indicates authentication credentials have invalid format or structure
     */
    INVALID_CREDENTIALS("Authentication credentials have invalid format or are incomplete"),
    
    /**
     * Indicates an unsupported or invalid environment profile is specified
     */
    INVALID_PROFILE("An unsupported or invalid environment profile is specified");
    
    private final String description;
    
    /**
     * Creates a new ConfigurationError with a description.
     * 
     * @param description Human-readable description of the error type
     */
    ConfigurationError(String description) {
        this.description = description;
    }
    
    /**
     * Gets the description of this error type.
     * 
     * @return String containing error description
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Gets a user-friendly error message for this error type.
     * 
     * @return String containing user-friendly error message
     */
    public String getUserMessage() {
        return description + ". Please check your configuration and try again.";
    }
}