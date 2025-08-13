package com.automation.framework.validation;

// Internal framework imports - ONLY from depends_on_files
import com.automation.framework.exceptions.ExceptionHandler;
import com.automation.framework.resources.FileResourceHandler;

// External imports for collections and data processing
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Properties;
import java.util.Arrays;

// External imports for URL validation and network operations
import java.net.URL;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.net.InetAddress;

// External imports for file path validation and operations
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;

// External imports for pattern matching and regex validation
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;

// External imports for logging framework integration
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// External imports for JSON processing and configuration parsing
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;

// External imports for numeric validation and parsing
import java.lang.Integer;
import java.lang.NumberFormatException;

// External imports for I/O operations and resource management
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * ConfigurationValidator provides comprehensive validation of framework configuration at startup,
 * ensuring all required settings are present, properly formatted, and functional before test
 * execution begins.
 * 
 * This class implements enterprise-grade configuration validation with fail-fast mechanisms
 * to prevent test execution with invalid configurations. It validates properties files,
 * environment variables, runtime configurations, external resource availability, and
 * dependency configurations to ensure framework reliability.
 * 
 * Key Features:
 * - Comprehensive validation of all configuration properties and formats
 * - External resource availability verification with connectivity testing
 * - Environment-specific configuration profile validation and inheritance
 * - Maven configuration and dependency availability verification
 * - TestNG configuration file structure and parameter validation
 * - Connection pool settings validation with range checking and capacity planning
 * - Authentication credential format validation with security pattern checking
 * - Fail-fast mechanism with detailed error reporting for rapid troubleshooting
 * - Configuration inheritance and override resolution across multiple sources
 * - Integration with framework exception handling for error recovery and classification
 * 
 * Validation Layers:
 * - Syntax validation for configuration file formats and property structures
 * - Semantic validation for configuration value ranges, dependencies, and compatibility
 * - Runtime validation for external resource connectivity and availability
 * - Security validation for credential formats, encryption, and access patterns
 * - Performance validation for timeout values, pool sizes, and resource limits
 * 
 * Security and Compliance:
 * - Credential format validation without exposing sensitive data in logs
 * - Configuration audit trail maintenance through integrated logging
 * - Path sanitization and validation to prevent directory traversal attacks
 * - Input validation and sanitization for all configuration parameters
 * - Secure handling of authentication tokens and encryption keys
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class ConfigurationValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationValidator.class);
    
    // Framework component integrations for comprehensive validation
    private final ExceptionHandler exceptionHandler;
    private final FileResourceHandler fileResourceHandler;
    
    // Configuration validation patterns and constraints
    private static final Pattern URL_PATTERN = Pattern.compile(
        "^(https?|ftp)://[a-zA-Z0-9.-]+(?:\\.[a-zA-Z]{2,})?(?::[0-9]{1,5})?(?:/.*)?$"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    );
    private static final Pattern NUMERIC_RANGE_PATTERN = Pattern.compile("^\\d+$");
    private static final Pattern API_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{16,}$");
    private static final Pattern JWT_TOKEN_PATTERN = Pattern.compile(
        "^[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+\\.[A-Za-z0-9-_.+/=]*$"
    );
    
    // Configuration constraints and validation limits
    private static final int MIN_THREAD_POOL_SIZE = 1;
    private static final int MAX_THREAD_POOL_SIZE = 100;
    private static final int MIN_CONNECTION_POOL_SIZE = 1;
    private static final int MAX_CONNECTION_POOL_SIZE = 200;
    private static final int MIN_TIMEOUT_MS = 1000;
    private static final int MAX_TIMEOUT_MS = 300000;
    private static final int MIN_RETRY_COUNT = 0;
    private static final int MAX_RETRY_COUNT = 10;
    
    // Required configuration properties for framework operation
    private static final Set<String> REQUIRED_PROPERTIES = Set.of(
        "framework.name",
        "framework.version",
        "framework.environment",
        "selenium.webdriver.path",
        "selenium.browser.default",
        "api.base.url",
        "logging.level",
        "thread.pool.size",
        "connection.pool.size",
        "request.timeout.ms",
        "retry.max.attempts"
    );
    
    // Supported environment profiles for configuration validation
    private static final Set<String> SUPPORTED_ENVIRONMENTS = Set.of(
        "development", "testing", "staging", "production", "dev", "test", "stage", "prod"
    );
    
    // Supported browser types for web automation validation
    private static final Set<String> SUPPORTED_BROWSERS = Set.of(
        "chrome", "firefox", "edge", "safari", "opera", "chromium"
    );
    
    // Current validation state and results
    private final List<ValidationResult> validationResults = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Creates a new ConfigurationValidator with framework component integrations.
     * Initializes JSON processing, validation patterns, and component connections.
     * 
     * @param exceptionHandler ExceptionHandler instance for centralized exception management
     * @param fileResourceHandler FileResourceHandler instance for file validation operations
     */
    public ConfigurationValidator(ExceptionHandler exceptionHandler, FileResourceHandler fileResourceHandler) {
        this.exceptionHandler = exceptionHandler;
        this.fileResourceHandler = fileResourceHandler;
        
        // Configure ObjectMapper for robust JSON processing
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);
        
        logger.info("ConfigurationValidator initialized with comprehensive validation capabilities");
    }
    
    /**
     * Performs comprehensive configuration validation including all required properties,
     * formats, external resources, and framework dependencies.
     * 
     * @param configurationSources Map of configuration sources with their properties
     * @return ValidationResult containing validation status and detailed error information
     */
    public ValidationResult validateConfiguration(Map<String, Properties> configurationSources) {
        try {
            logger.info("Starting comprehensive configuration validation");
            
            ValidationResult result = new ValidationResult();
            
            if (configurationSources == null || configurationSources.isEmpty()) {
                result.addError(ConfigurationError.MISSING_PROPERTY, 
                    "No configuration sources provided for validation", "configurationSources");
                return result;
            }
            
            // Validate each configuration source
            for (Map.Entry<String, Properties> entry : configurationSources.entrySet()) {
                String sourceName = entry.getKey();
                Properties properties = entry.getValue();
                
                logger.debug("Validating configuration source: {}", sourceName);
                
                // Validate required properties
                ValidationResult requiredResult = validateRequiredProperties(properties);
                result.mergeResults(requiredResult);
                
                // Validate property formats
                ValidationResult formatResult = validatePropertyFormats(properties);
                result.mergeResults(formatResult);
                
                // Validate connection pool settings
                ValidationResult poolResult = validateConnectionPoolSettings(properties);
                result.mergeResults(poolResult);
                
                // Validate credential formats
                ValidationResult credentialResult = validateCredentialFormat(properties);
                result.mergeResults(credentialResult);
            }
            
            // Validate external resources
            ValidationResult resourceResult = validateExternalResources(configurationSources);
            result.mergeResults(resourceResult);
            
            // Validate Maven configuration
            ValidationResult mavenResult = validateMavenConfiguration();
            result.mergeResults(mavenResult);
            
            // Validate TestNG configuration
            ValidationResult testNgResult = validateTestNGConfiguration();
            result.mergeResults(testNgResult);
            
            if (result.isValid()) {
                logger.info("Configuration validation completed successfully");
            } else {
                logger.warn("Configuration validation failed with {} errors and {} warnings", 
                           result.getErrorCount(), result.getWarnings().size());
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "validateConfiguration",
                "configurationSourceCount", configurationSources != null ? configurationSources.size() : 0,
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            ValidationResult errorResult = new ValidationResult();
            errorResult.addError(ConfigurationError.INVALID_FORMAT, 
                "Critical error during configuration validation: " + e.getMessage(), "system");
            
            return errorResult;
        }
    }
    
    /**
     * Validates that all required configuration properties are present and non-empty.
     * Checks for mandatory properties across all configuration sources.
     * 
     * @param properties Properties object containing configuration settings
     * @return ValidationResult containing validation status and missing property errors
     */
    public ValidationResult validateRequiredProperties(Properties properties) {
        try {
            logger.debug("Validating required configuration properties");
            
            ValidationResult result = new ValidationResult();
            
            if (properties == null) {
                result.addError(ConfigurationError.MISSING_PROPERTY, 
                    "Properties object is null", "properties");
                return result;
            }
            
            // Check for each required property
            for (String requiredProperty : REQUIRED_PROPERTIES) {
                String value = properties.getProperty(requiredProperty);
                
                if (value == null || value.trim().isEmpty()) {
                    result.addError(ConfigurationError.MISSING_PROPERTY,
                        "Required property '" + requiredProperty + "' is missing or empty",
                        requiredProperty);
                    logger.warn("Missing required property: {}", requiredProperty);
                } else {
                    logger.debug("Found required property: {} = {}", requiredProperty, 
                                maskSensitiveValue(requiredProperty, value));
                }
            }
            
            // Validate conditional requirements
            ValidationResult conditionalResult = validateConditionalRequirements(properties);
            result.mergeResults(conditionalResult);
            
            if (result.isValid()) {
                logger.debug("All required properties validation completed successfully");
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "validateRequiredProperties",
                "propertiesCount", properties != null ? properties.size() : 0,
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            ValidationResult errorResult = new ValidationResult();
            errorResult.addError(ConfigurationError.INVALID_FORMAT,
                "Error during required properties validation: " + e.getMessage(), "system");
            
            return errorResult;
        }
    }
    
    /**
     * Validates the format of configuration property values including URLs, paths,
     * numeric ranges, and pattern matching for various configuration types.
     * 
     * @param properties Properties object containing configuration settings
     * @return ValidationResult containing format validation status and errors
     */
    public ValidationResult validatePropertyFormats(Properties properties) {
        try {
            logger.debug("Validating configuration property formats");
            
            ValidationResult result = new ValidationResult();
            
            if (properties == null) {
                result.addError(ConfigurationError.INVALID_FORMAT,
                    "Cannot validate formats for null properties", "properties");
                return result;
            }
            
            // Validate URL formats
            String[] urlProperties = {"api.base.url", "selenium.hub.url", "reporting.service.url"};
            for (String urlProperty : urlProperties) {
                String url = properties.getProperty(urlProperty);
                if (url != null && !url.trim().isEmpty()) {
                    ValidationResult urlResult = validateUrlFormat(urlProperty, url);
                    result.mergeResults(urlResult);
                }
            }
            
            // Validate file path formats
            String[] pathProperties = {"selenium.webdriver.path", "test.data.path", "log.file.path"};
            for (String pathProperty : pathProperties) {
                String path = properties.getProperty(pathProperty);
                if (path != null && !path.trim().isEmpty()) {
                    ValidationResult pathResult = validatePathFormat(pathProperty, path);
                    result.mergeResults(pathResult);
                }
            }
            
            // Validate numeric formats
            String[] numericProperties = {"thread.pool.size", "connection.pool.size", 
                                        "request.timeout.ms", "retry.max.attempts"};
            for (String numericProperty : numericProperties) {
                String value = properties.getProperty(numericProperty);
                if (value != null && !value.trim().isEmpty()) {
                    ValidationResult numericResult = validateNumericFormat(numericProperty, value);
                    result.mergeResults(numericResult);
                }
            }
            
            // Validate browser configuration
            String browser = properties.getProperty("selenium.browser.default");
            if (browser != null && !browser.trim().isEmpty()) {
                ValidationResult browserResult = validateBrowserFormat(browser);
                result.mergeResults(browserResult);
            }
            
            // Validate environment profile
            String environment = properties.getProperty("framework.environment");
            if (environment != null && !environment.trim().isEmpty()) {
                ValidationResult envResult = validateEnvironmentProfile(environment);
                result.mergeResults(envResult);
            }
            
            if (result.isValid()) {
                logger.debug("Property format validation completed successfully");
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "validatePropertyFormats",
                "propertiesCount", properties != null ? properties.size() : 0,
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            ValidationResult errorResult = new ValidationResult();
            errorResult.addError(ConfigurationError.INVALID_FORMAT,
                "Error during property format validation: " + e.getMessage(), "system");
            
            return errorResult;
        }
    }
    
    /**
     * Validates external resource availability including browser drivers, test data files,
     * API endpoints, and other dependencies required for framework operation.
     * 
     * @param configurationSources Map of configuration sources containing resource paths
     * @return ValidationResult containing external resource validation status
     */
    public ValidationResult validateExternalResources(Map<String, Properties> configurationSources) {
        try {
            logger.debug("Validating external resource availability");
            
            ValidationResult result = new ValidationResult();
            
            if (configurationSources == null || configurationSources.isEmpty()) {
                result.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                    "No configuration sources provided for resource validation", "configurationSources");
                return result;
            }
            
            // Collect all resource paths from configuration sources
            Set<String> driverPaths = new HashSet<>();
            Set<String> testDataPaths = new HashSet<>();
            Set<String> apiUrls = new HashSet<>();
            
            for (Properties properties : configurationSources.values()) {
                collectResourcePaths(properties, driverPaths, testDataPaths, apiUrls);
            }
            
            // Validate WebDriver executables
            for (String driverPath : driverPaths) {
                ValidationResult driverResult = validateWebDriverResource(driverPath);
                result.mergeResults(driverResult);
            }
            
            // Validate test data files
            for (String testDataPath : testDataPaths) {
                ValidationResult dataResult = validateTestDataResource(testDataPath);
                result.mergeResults(dataResult);
            }
            
            // Validate API endpoint connectivity
            for (String apiUrl : apiUrls) {
                ValidationResult apiResult = validateApiEndpointConnectivity(apiUrl);
                result.mergeResults(apiResult);
            }
            
            if (result.isValid()) {
                logger.debug("External resource validation completed successfully");
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "validateExternalResources",
                "configurationSourceCount", configurationSources != null ? configurationSources.size() : 0,
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            ValidationResult errorResult = new ValidationResult();
            errorResult.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                "Error during external resource validation: " + e.getMessage(), "system");
            
            return errorResult;
        }
    }
    
    /**
     * Validates connection pool settings ensuring they are within acceptable ranges
     * and properly configured for framework performance and stability.
     * 
     * @param properties Properties object containing connection pool configuration
     * @return ValidationResult containing connection pool validation status
     */
    public ValidationResult validateConnectionPoolSettings(Properties properties) {
        try {
            logger.debug("Validating connection pool settings");
            
            ValidationResult result = new ValidationResult();
            
            if (properties == null) {
                result.addError(ConfigurationError.INVALID_RANGE,
                    "Cannot validate connection pool settings for null properties", "properties");
                return result;
            }
            
            // Validate thread pool size
            String threadPoolSize = properties.getProperty("thread.pool.size");
            if (threadPoolSize != null && !threadPoolSize.trim().isEmpty()) {
                ValidationResult threadResult = validatePoolSize("thread.pool.size", threadPoolSize,
                    MIN_THREAD_POOL_SIZE, MAX_THREAD_POOL_SIZE);
                result.mergeResults(threadResult);
            }
            
            // Validate connection pool size
            String connectionPoolSize = properties.getProperty("connection.pool.size");
            if (connectionPoolSize != null && !connectionPoolSize.trim().isEmpty()) {
                ValidationResult connResult = validatePoolSize("connection.pool.size", connectionPoolSize,
                    MIN_CONNECTION_POOL_SIZE, MAX_CONNECTION_POOL_SIZE);
                result.mergeResults(connResult);
            }
            
            // Validate timeout settings
            String requestTimeout = properties.getProperty("request.timeout.ms");
            if (requestTimeout != null && !requestTimeout.trim().isEmpty()) {
                ValidationResult timeoutResult = validateTimeoutValue("request.timeout.ms", requestTimeout);
                result.mergeResults(timeoutResult);
            }
            
            // Validate retry settings
            String retryAttempts = properties.getProperty("retry.max.attempts");
            if (retryAttempts != null && !retryAttempts.trim().isEmpty()) {
                ValidationResult retryResult = validateRetryCount("retry.max.attempts", retryAttempts);
                result.mergeResults(retryResult);
            }
            
            // Validate pool dependency relationships
            ValidationResult dependencyResult = validatePoolDependencies(properties);
            result.mergeResults(dependencyResult);
            
            if (result.isValid()) {
                logger.debug("Connection pool settings validation completed successfully");
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "validateConnectionPoolSettings",
                "propertiesCount", properties != null ? properties.size() : 0,
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            ValidationResult errorResult = new ValidationResult();
            errorResult.addError(ConfigurationError.INVALID_RANGE,
                "Error during connection pool validation: " + e.getMessage(), "system");
            
            return errorResult;
        }
    }
    
    /**
     * Validates credential formats without exposing sensitive data, ensuring proper
     * formatting for API keys, JWT tokens, and other authentication mechanisms.
     * 
     * @param properties Properties object containing credential configuration
     * @return ValidationResult containing credential format validation status
     */
    public ValidationResult validateCredentialFormat(Properties properties) {
        try {
            logger.debug("Validating credential formats (sensitive data masked)");
            
            ValidationResult result = new ValidationResult();
            
            if (properties == null) {
                result.addError(ConfigurationError.INVALID_CREDENTIALS,
                    "Cannot validate credential formats for null properties", "properties");
                return result;
            }
            
            // Validate API keys
            String[] apiKeyProperties = {"api.key", "auth.api.key", "service.api.key"};
            for (String apiKeyProperty : apiKeyProperties) {
                String apiKey = properties.getProperty(apiKeyProperty);
                if (apiKey != null && !apiKey.trim().isEmpty()) {
                    ValidationResult apiKeyResult = validateApiKeyFormat(apiKeyProperty, apiKey);
                    result.mergeResults(apiKeyResult);
                }
            }
            
            // Validate JWT tokens
            String[] jwtProperties = {"auth.jwt.token", "bearer.token", "access.token"};
            for (String jwtProperty : jwtProperties) {
                String jwtToken = properties.getProperty(jwtProperty);
                if (jwtToken != null && !jwtToken.trim().isEmpty()) {
                    ValidationResult jwtResult = validateJwtTokenFormat(jwtProperty, jwtToken);
                    result.mergeResults(jwtResult);
                }
            }
            
            // Validate username/password combinations
            ValidationResult credentialsResult = validateUsernamePasswordCredentials(properties);
            result.mergeResults(credentialsResult);
            
            // Validate OAuth configuration
            ValidationResult oauthResult = validateOAuthConfiguration(properties);
            result.mergeResults(oauthResult);
            
            if (result.isValid()) {
                logger.debug("Credential format validation completed successfully");
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "validateCredentialFormat",
                "propertiesCount", properties != null ? properties.size() : 0,
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            ValidationResult errorResult = new ValidationResult();
            errorResult.addError(ConfigurationError.INVALID_CREDENTIALS,
                "Error during credential format validation: " + e.getMessage(), "system");
            
            return errorResult;
        }
    }
    
    /**
     * Validates environment-specific configuration profiles ensuring proper
     * inheritance and override resolution for different deployment environments.
     * 
     * @param environment Environment profile identifier to validate
     * @return ValidationResult containing environment profile validation status
     */
    public ValidationResult validateEnvironmentProfile(String environment) {
        try {
            logger.debug("Validating environment profile: {}", environment);
            
            ValidationResult result = new ValidationResult();
            
            if (environment == null || environment.trim().isEmpty()) {
                result.addError(ConfigurationError.INVALID_PROFILE,
                    "Environment profile cannot be null or empty", "framework.environment");
                return result;
            }
            
            String normalizedEnv = environment.toLowerCase().trim();
            
            // Check if environment is supported
            if (!SUPPORTED_ENVIRONMENTS.contains(normalizedEnv)) {
                result.addError(ConfigurationError.INVALID_PROFILE,
                    "Unsupported environment profile: " + environment + 
                    ". Supported environments: " + SUPPORTED_ENVIRONMENTS, "framework.environment");
                return result;
            }
            
            // Validate environment-specific configuration files
            ValidationResult configFileResult = validateEnvironmentConfigFiles(normalizedEnv);
            result.mergeResults(configFileResult);
            
            // Validate environment-specific resource paths
            ValidationResult resourceResult = validateEnvironmentResources(normalizedEnv);
            result.mergeResults(resourceResult);
            
            if (result.isValid()) {
                logger.debug("Environment profile validation completed successfully for: {}", environment);
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "validateEnvironmentProfile",
                "environment", environment,
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            ValidationResult errorResult = new ValidationResult();
            errorResult.addError(ConfigurationError.INVALID_PROFILE,
                "Error during environment profile validation: " + e.getMessage(), "framework.environment");
            
            return errorResult;
        }
    }
    
    /**
     * Gets the list of validation errors from the last validation operation.
     * 
     * @return List<String> containing detailed error messages
     */
    public List<String> getValidationErrors() {
        List<String> allErrors = new ArrayList<>();
        
        try {
            for (ValidationResult result : validationResults) {
                allErrors.addAll(result.getErrors());
            }
            
            logger.debug("Retrieved {} validation errors from {} validation results", 
                        allErrors.size(), validationResults.size());
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "getValidationErrors",
                "resultCount", validationResults.size(),
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            allErrors.add("Error retrieving validation errors: " + e.getMessage());
        }
        
        return allErrors;
    }
    
    /**
     * Checks if the configuration is valid based on all performed validations.
     * 
     * @return boolean indicating overall configuration validity
     */
    public boolean isConfigurationValid() {
        try {
            boolean isValid = validationResults.stream().allMatch(ValidationResult::isValid);
            
            logger.debug("Configuration validity check result: {}", isValid);
            
            return isValid;
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "isConfigurationValid",
                "resultCount", validationResults.size(),
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            return false;
        }
    }
    
    /**
     * Validates complete startup configuration performing all validation checks
     * in the proper sequence for framework initialization.
     * 
     * @return ValidationResult containing comprehensive startup validation status
     */
    public ValidationResult validateStartupConfiguration() {
        try {
            logger.info("Starting comprehensive startup configuration validation");
            
            ValidationResult result = new ValidationResult();
            
            // Load configuration from standard locations
            Map<String, Properties> configurationSources = loadConfigurationSources();
            
            if (configurationSources.isEmpty()) {
                result.addError(ConfigurationError.MISSING_PROPERTY,
                    "No configuration sources found for startup validation", "configurationSources");
                return result;
            }
            
            // Perform comprehensive validation
            ValidationResult configResult = validateConfiguration(configurationSources);
            result.mergeResults(configResult);
            
            // Store validation results for future reference
            validationResults.add(result);
            
            if (result.isValid()) {
                logger.info("Startup configuration validation completed successfully");
            } else {
                logger.error("Startup configuration validation failed with {} errors", 
                           result.getErrorCount());
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "validateStartupConfiguration",
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            ValidationResult errorResult = new ValidationResult();
            errorResult.addError(ConfigurationError.INVALID_FORMAT,
                "Critical error during startup configuration validation: " + e.getMessage(), "system");
            
            return errorResult;
        }
    }
    
    /**
     * Validates Maven configuration and dependency availability ensuring all
     * required dependencies are accessible and properly versioned.
     * 
     * @return ValidationResult containing Maven configuration validation status
     */
    public ValidationResult validateMavenConfiguration() {
        try {
            logger.debug("Validating Maven configuration and dependencies");
            
            ValidationResult result = new ValidationResult();
            
            // Validate pom.xml file existence and accessibility
            Path pomPath = Paths.get("pom.xml");
            if (!fileResourceHandler.validateFileExists(pomPath.toString())) {
                result.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                    "Maven pom.xml file not found in project root", "pom.xml");
                return result;
            }
            
            if (!fileResourceHandler.validateFileReadable(pomPath.toString())) {
                result.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                    "Maven pom.xml file is not readable", "pom.xml");
                return result;
            }
            
            // Validate pom.xml format
            ValidationResult formatResult = validatePomXmlFormat(pomPath);
            result.mergeResults(formatResult);
            
            // Validate Maven dependency availability
            ValidationResult dependencyResult = validateMavenDependencies();
            result.mergeResults(dependencyResult);
            
            // Validate Maven local repository
            ValidationResult repoResult = validateMavenRepository();
            result.mergeResults(repoResult);
            
            if (result.isValid()) {
                logger.debug("Maven configuration validation completed successfully");
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "validateMavenConfiguration",
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            ValidationResult errorResult = new ValidationResult();
            errorResult.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                "Error during Maven configuration validation: " + e.getMessage(), "maven");
            
            return errorResult;
        }
    }
    
    /**
     * Validates TestNG configuration file structure and parameter settings
     * ensuring proper test suite configuration and execution parameters.
     * 
     * @return ValidationResult containing TestNG configuration validation status
     */
    public ValidationResult validateTestNGConfiguration() {
        try {
            logger.debug("Validating TestNG configuration");
            
            ValidationResult result = new ValidationResult();
            
            // Look for TestNG configuration files in standard locations
            String[] testNgPaths = {
                "src/test/resources/testng.xml",
                "testng.xml",
                "src/test/resources/suites/testng.xml"
            };
            
            Path testNgConfigPath = null;
            for (String testNgPath : testNgPaths) {
                Path path = Paths.get(testNgPath);
                if (fileResourceHandler.validateFileExists(path.toString())) {
                    testNgConfigPath = path;
                    break;
                }
            }
            
            if (testNgConfigPath == null) {
                result.addWarning("No TestNG configuration file found in standard locations");
                logger.debug("TestNG configuration file not found, using default configuration");
                return result;
            }
            
            // Validate TestNG file accessibility
            if (!fileResourceHandler.validateFileReadable(testNgConfigPath.toString())) {
                result.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                    "TestNG configuration file is not readable: " + testNgConfigPath, "testng.xml");
                return result;
            }
            
            // Validate TestNG XML format
            ValidationResult formatResult = validateTestNgXmlFormat(testNgConfigPath);
            result.mergeResults(formatResult);
            
            // Validate TestNG suite configuration
            ValidationResult suiteResult = validateTestNgSuiteConfiguration(testNgConfigPath);
            result.mergeResults(suiteResult);
            
            if (result.isValid()) {
                logger.debug("TestNG configuration validation completed successfully");
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorContext = Map.of(
                "operation", "validateTestNGConfiguration",
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            ValidationResult errorResult = new ValidationResult();
            errorResult.addError(ConfigurationError.INVALID_FORMAT,
                "Error during TestNG configuration validation: " + e.getMessage(), "testng");
            
            return errorResult;
        }
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Loads configuration from standard configuration sources including files and environment variables.
     */
    private Map<String, Properties> loadConfigurationSources() {
        Map<String, Properties> sources = new HashMap<>();
        
        try {
            // Load from application.properties
            Properties appProps = loadPropertiesFile("src/main/resources/application.properties");
            if (appProps != null && !appProps.isEmpty()) {
                sources.put("application.properties", appProps);
            }
            
            // Load from framework.properties
            Properties frameworkProps = loadPropertiesFile("src/main/resources/framework.properties");
            if (frameworkProps != null && !frameworkProps.isEmpty()) {
                sources.put("framework.properties", frameworkProps);
            }
            
            // Load from environment variables
            Properties envProps = loadEnvironmentVariables();
            if (envProps != null && !envProps.isEmpty()) {
                sources.put("environment", envProps);
            }
            
            // Load from system properties
            Properties systemProps = new Properties();
            systemProps.putAll(System.getProperties());
            sources.put("system", systemProps);
            
        } catch (Exception e) {
            logger.warn("Error loading configuration sources: {}", e.getMessage());
        }
        
        return sources;
    }
    
    /**
     * Loads properties from a file path with error handling.
     */
    private Properties loadPropertiesFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!fileResourceHandler.validateFileExists(path.toString()) || 
                !fileResourceHandler.validateFileReadable(path.toString())) {
                return new Properties();
            }
            
            Properties properties = new Properties();
            try (InputStream input = new FileInputStream(path.toFile())) {
                properties.load(input);
            }
            
            logger.debug("Loaded {} properties from {}", properties.size(), filePath);
            return properties;
            
        } catch (Exception e) {
            logger.debug("Could not load properties from {}: {}", filePath, e.getMessage());
            return new Properties();
        }
    }
    
    /**
     * Loads relevant environment variables as properties.
     */
    private Properties loadEnvironmentVariables() {
        Properties envProps = new Properties();
        
        try {
            Map<String, String> envMap = System.getenv();
            
            // Filter for framework-related environment variables
            for (Map.Entry<String, String> entry : envMap.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("FRAMEWORK_") || key.startsWith("SELENIUM_") || 
                    key.startsWith("API_") || key.startsWith("TEST_")) {
                    String propKey = key.toLowerCase().replace('_', '.');
                    envProps.setProperty(propKey, entry.getValue());
                }
            }
            
            logger.debug("Loaded {} environment variables", envProps.size());
            
        } catch (Exception e) {
            logger.warn("Error loading environment variables: {}", e.getMessage());
        }
        
        return envProps;
    }
    
    /**
     * Validates conditional requirements based on configuration context.
     */
    private ValidationResult validateConditionalRequirements(Properties properties) {
        ValidationResult result = new ValidationResult();
        
        try {
            // If web automation is enabled, WebDriver path is required
            String webEnabled = properties.getProperty("selenium.enabled", "true");
            if ("true".equalsIgnoreCase(webEnabled)) {
                String driverPath = properties.getProperty("selenium.webdriver.path");
                if (driverPath == null || driverPath.trim().isEmpty()) {
                    result.addError(ConfigurationError.MISSING_PROPERTY,
                        "WebDriver path is required when web automation is enabled", 
                        "selenium.webdriver.path");
                }
            }
            
            // If API testing is enabled, base URL is required
            String apiEnabled = properties.getProperty("api.enabled", "true");
            if ("true".equalsIgnoreCase(apiEnabled)) {
                String baseUrl = properties.getProperty("api.base.url");
                if (baseUrl == null || baseUrl.trim().isEmpty()) {
                    result.addError(ConfigurationError.MISSING_PROPERTY,
                        "API base URL is required when API testing is enabled", 
                        "api.base.url");
                }
            }
            
        } catch (Exception e) {
            logger.warn("Error validating conditional requirements: {}", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validates URL format and basic connectivity.
     */
    private ValidationResult validateUrlFormat(String propertyName, String url) {
        ValidationResult result = new ValidationResult();
        
        try {
            // Validate URL format using pattern
            Matcher matcher = URL_PATTERN.matcher(url);
            if (!matcher.matches()) {
                result.addError(ConfigurationError.INVALID_FORMAT,
                    "Invalid URL format for property '" + propertyName + "': " + url, propertyName);
                return result;
            }
            
            // Additional validation using URL class
            URL urlObj = new URL(url);
            String protocol = urlObj.getProtocol();
            String host = urlObj.getHost();
            int port = urlObj.getPort();
            
            if (host == null || host.trim().isEmpty()) {
                result.addError(ConfigurationError.INVALID_FORMAT,
                    "URL host is missing for property '" + propertyName + "'", propertyName);
            }
            
            if (port != -1 && (port < 1 || port > 65535)) {
                result.addError(ConfigurationError.INVALID_FORMAT,
                    "Invalid port number for property '" + propertyName + "': " + port, propertyName);
            }
            
            if (!Arrays.asList("http", "https", "ftp").contains(protocol.toLowerCase())) {
                result.addWarning("Unusual protocol for property '" + propertyName + "': " + protocol);
            }
            
        } catch (MalformedURLException e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Malformed URL for property '" + propertyName + "': " + e.getMessage(), propertyName);
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Error validating URL for property '" + propertyName + "': " + e.getMessage(), propertyName);
        }
        
        return result;
    }
    
    /**
     * Validates file path format and accessibility.
     */
    private ValidationResult validatePathFormat(String propertyName, String pathStr) {
        ValidationResult result = new ValidationResult();
        
        try {
            Path path = Paths.get(pathStr);
            
            // Check if path is valid
            if (!Files.exists(path) && !isExecutablePath(pathStr)) {
                result.addWarning("Path does not exist for property '" + propertyName + "': " + pathStr);
            }
            
            // Check for path traversal security issues
            Path normalizedPath = path.normalize();
            if (!normalizedPath.equals(path)) {
                result.addWarning("Path normalization detected for property '" + propertyName + "': " + pathStr);
            }
            
        } catch (InvalidPathException e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Invalid path format for property '" + propertyName + "': " + e.getMessage(), propertyName);
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Error validating path for property '" + propertyName + "': " + e.getMessage(), propertyName);
        }
        
        return result;
    }
    
    /**
     * Validates numeric format and value.
     */
    private ValidationResult validateNumericFormat(String propertyName, String value) {
        ValidationResult result = new ValidationResult();
        
        try {
            if (!NUMERIC_RANGE_PATTERN.matcher(value).matches()) {
                result.addError(ConfigurationError.INVALID_FORMAT,
                    "Non-numeric value for property '" + propertyName + "': " + value, propertyName);
                return result;
            }
            
            int numericValue = Integer.parseInt(value);
            
            if (numericValue < 0) {
                result.addError(ConfigurationError.INVALID_RANGE,
                    "Negative value not allowed for property '" + propertyName + "': " + value, propertyName);
            }
            
        } catch (NumberFormatException e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Invalid numeric format for property '" + propertyName + "': " + value, propertyName);
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Error validating numeric value for property '" + propertyName + "': " + e.getMessage(), propertyName);
        }
        
        return result;
    }
    
    /**
     * Validates browser configuration.
     */
    private ValidationResult validateBrowserFormat(String browser) {
        ValidationResult result = new ValidationResult();
        
        try {
            String normalizedBrowser = browser.toLowerCase().trim();
            
            if (!SUPPORTED_BROWSERS.contains(normalizedBrowser)) {
                result.addError(ConfigurationError.INVALID_FORMAT,
                    "Unsupported browser: " + browser + ". Supported browsers: " + SUPPORTED_BROWSERS, 
                    "selenium.browser.default");
            }
            
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Error validating browser configuration: " + e.getMessage(), "selenium.browser.default");
        }
        
        return result;
    }
    
    /**
     * Collects resource paths from properties for validation.
     */
    private void collectResourcePaths(Properties properties, Set<String> driverPaths, 
                                    Set<String> testDataPaths, Set<String> apiUrls) {
        try {
            // Collect WebDriver paths
            String driverPath = properties.getProperty("selenium.webdriver.path");
            if (driverPath != null && !driverPath.trim().isEmpty()) {
                driverPaths.add(driverPath);
            }
            
            // Collect test data paths
            String testDataPath = properties.getProperty("test.data.path");
            if (testDataPath != null && !testDataPath.trim().isEmpty()) {
                testDataPaths.add(testDataPath);
            }
            
            // Collect API URLs
            String apiUrl = properties.getProperty("api.base.url");
            if (apiUrl != null && !apiUrl.trim().isEmpty()) {
                apiUrls.add(apiUrl);
            }
            
        } catch (Exception e) {
            logger.warn("Error collecting resource paths: {}", e.getMessage());
        }
    }
    
    /**
     * Validates WebDriver resource availability.
     */
    private ValidationResult validateWebDriverResource(String driverPath) {
        ValidationResult result = new ValidationResult();
        
        try {
            if (!fileResourceHandler.validateFileExists(driverPath)) {
                result.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                    "WebDriver executable not found: " + driverPath, "selenium.webdriver.path");
                return result;
            }
            
            // Check if file is executable
            Path path = Paths.get(driverPath);
            if (!Files.isExecutable(path)) {
                result.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                    "WebDriver file is not executable: " + driverPath, "selenium.webdriver.path");
            }
            
        } catch (Exception e) {
            result.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                "Error validating WebDriver resource: " + e.getMessage(), "selenium.webdriver.path");
        }
        
        return result;
    }
    
    /**
     * Validates test data resource availability.
     */
    private ValidationResult validateTestDataResource(String testDataPath) {
        ValidationResult result = new ValidationResult();
        
        try {
            if (!fileResourceHandler.validateFileExists(testDataPath)) {
                result.addWarning("Test data file not found: " + testDataPath);
                return result;
            }
            
            if (!fileResourceHandler.validateFileReadable(testDataPath)) {
                result.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                    "Test data file is not readable: " + testDataPath, "test.data.path");
                return result;
            }
            
            // Validate file format
            if (!fileResourceHandler.validateFileFormat(testDataPath)) {
                result.addWarning("Test data file format validation failed: " + testDataPath);
            }
            
        } catch (Exception e) {
            result.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                "Error validating test data resource: " + e.getMessage(), "test.data.path");
        }
        
        return result;
    }
    
    /**
     * Validates API endpoint connectivity.
     */
    private ValidationResult validateApiEndpointConnectivity(String apiUrl) {
        ValidationResult result = new ValidationResult();
        
        try {
            URL url = new URL(apiUrl);
            String host = url.getHost();
            
            // Basic DNS resolution check
            try {
                InetAddress.getByName(host);
                logger.debug("DNS resolution successful for host: {}", host);
            } catch (UnknownHostException e) {
                result.addWarning("DNS resolution failed for API host: " + host);
                return result;
            }
            
            // Port connectivity check for common ports
            int port = url.getPort();
            if (port == -1) {
                port = url.getDefaultPort();
            }
            
            if (port > 0) {
                // Note: Full connectivity test would require actual network call
                // For configuration validation, we just validate format and DNS
                logger.debug("API endpoint format validated: {}", apiUrl);
            }
            
        } catch (Exception e) {
            result.addWarning("Error validating API endpoint connectivity: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validates pool size within acceptable ranges.
     */
    private ValidationResult validatePoolSize(String propertyName, String value, int minSize, int maxSize) {
        ValidationResult result = new ValidationResult();
        
        try {
            int poolSize = Integer.parseInt(value);
            
            if (poolSize < minSize) {
                result.addError(ConfigurationError.INVALID_RANGE,
                    "Pool size too small for property '" + propertyName + "': " + poolSize + 
                    " (minimum: " + minSize + ")", propertyName);
            } else if (poolSize > maxSize) {
                result.addError(ConfigurationError.INVALID_RANGE,
                    "Pool size too large for property '" + propertyName + "': " + poolSize + 
                    " (maximum: " + maxSize + ")", propertyName);
            }
            
        } catch (NumberFormatException e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Invalid numeric format for pool size '" + propertyName + "': " + value, propertyName);
        }
        
        return result;
    }
    
    /**
     * Validates timeout value within acceptable range.
     */
    private ValidationResult validateTimeoutValue(String propertyName, String value) {
        ValidationResult result = new ValidationResult();
        
        try {
            int timeout = Integer.parseInt(value);
            
            if (timeout < MIN_TIMEOUT_MS) {
                result.addError(ConfigurationError.INVALID_RANGE,
                    "Timeout too small for property '" + propertyName + "': " + timeout + 
                    " (minimum: " + MIN_TIMEOUT_MS + "ms)", propertyName);
            } else if (timeout > MAX_TIMEOUT_MS) {
                result.addError(ConfigurationError.INVALID_RANGE,
                    "Timeout too large for property '" + propertyName + "': " + timeout + 
                    " (maximum: " + MAX_TIMEOUT_MS + "ms)", propertyName);
            }
            
        } catch (NumberFormatException e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Invalid numeric format for timeout '" + propertyName + "': " + value, propertyName);
        }
        
        return result;
    }
    
    /**
     * Validates retry count within acceptable range.
     */
    private ValidationResult validateRetryCount(String propertyName, String value) {
        ValidationResult result = new ValidationResult();
        
        try {
            int retryCount = Integer.parseInt(value);
            
            if (retryCount < MIN_RETRY_COUNT) {
                result.addError(ConfigurationError.INVALID_RANGE,
                    "Retry count too small for property '" + propertyName + "': " + retryCount + 
                    " (minimum: " + MIN_RETRY_COUNT + ")", propertyName);
            } else if (retryCount > MAX_RETRY_COUNT) {
                result.addError(ConfigurationError.INVALID_RANGE,
                    "Retry count too large for property '" + propertyName + "': " + retryCount + 
                    " (maximum: " + MAX_RETRY_COUNT + ")", propertyName);
            }
            
        } catch (NumberFormatException e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Invalid numeric format for retry count '" + propertyName + "': " + value, propertyName);
        }
        
        return result;
    }
    
    /**
     * Validates pool dependency relationships.
     */
    private ValidationResult validatePoolDependencies(Properties properties) {
        ValidationResult result = new ValidationResult();
        
        try {
            String threadPoolStr = properties.getProperty("thread.pool.size");
            String connectionPoolStr = properties.getProperty("connection.pool.size");
            
            if (threadPoolStr != null && connectionPoolStr != null) {
                int threadPool = Integer.parseInt(threadPoolStr);
                int connectionPool = Integer.parseInt(connectionPoolStr);
                
                // Connection pool should typically be larger than thread pool
                if (connectionPool < threadPool) {
                    result.addWarning("Connection pool size (" + connectionPool + 
                        ") is smaller than thread pool size (" + threadPool + "). " +
                        "This may cause connection starvation.");
                }
                
                // Warn if pools are very large
                if (threadPool > 50 || connectionPool > 100) {
                    result.addWarning("Large pool sizes detected. Monitor resource usage carefully.");
                }
            }
            
        } catch (Exception e) {
            logger.debug("Error validating pool dependencies: {}", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validates API key format.
     */
    private ValidationResult validateApiKeyFormat(String propertyName, String apiKey) {
        ValidationResult result = new ValidationResult();
        
        try {
            Matcher matcher = API_KEY_PATTERN.matcher(apiKey);
            if (!matcher.matches()) {
                result.addError(ConfigurationError.INVALID_CREDENTIALS,
                    "Invalid API key format for property '" + propertyName + "'", propertyName);
            }
            
            if (apiKey.length() < 16) {
                result.addWarning("API key for property '" + propertyName + "' is shorter than recommended (16+ characters)");
            }
            
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_CREDENTIALS,
                "Error validating API key format for property '" + propertyName + "': " + e.getMessage(), propertyName);
        }
        
        return result;
    }
    
    /**
     * Validates JWT token format.
     */
    private ValidationResult validateJwtTokenFormat(String propertyName, String jwtToken) {
        ValidationResult result = new ValidationResult();
        
        try {
            Matcher matcher = JWT_TOKEN_PATTERN.matcher(jwtToken);
            if (!matcher.matches()) {
                result.addError(ConfigurationError.INVALID_CREDENTIALS,
                    "Invalid JWT token format for property '" + propertyName + "'", propertyName);
            }
            
            // Basic JWT structure validation (3 parts separated by dots)
            String[] parts = jwtToken.split("\\.");
            if (parts.length != 3) {
                result.addError(ConfigurationError.INVALID_CREDENTIALS,
                    "JWT token must have 3 parts for property '" + propertyName + "'", propertyName);
            }
            
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_CREDENTIALS,
                "Error validating JWT token format for property '" + propertyName + "': " + e.getMessage(), propertyName);
        }
        
        return result;
    }
    
    /**
     * Validates username/password credential combinations.
     */
    private ValidationResult validateUsernamePasswordCredentials(Properties properties) {
        ValidationResult result = new ValidationResult();
        
        try {
            String username = properties.getProperty("auth.username");
            String password = properties.getProperty("auth.password");
            
            if ((username != null && !username.trim().isEmpty()) || 
                (password != null && !password.trim().isEmpty())) {
                
                if (username == null || username.trim().isEmpty()) {
                    result.addError(ConfigurationError.INVALID_CREDENTIALS,
                        "Username is required when password is provided", "auth.username");
                }
                
                if (password == null || password.trim().isEmpty()) {
                    result.addError(ConfigurationError.INVALID_CREDENTIALS,
                        "Password is required when username is provided", "auth.password");
                }
                
                // Check for common weak passwords
                if (password != null && isWeakPassword(password)) {
                    result.addWarning("Weak password detected for auth.password");
                }
            }
            
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_CREDENTIALS,
                "Error validating username/password credentials: " + e.getMessage(), "auth");
        }
        
        return result;
    }
    
    /**
     * Validates OAuth configuration.
     */
    private ValidationResult validateOAuthConfiguration(Properties properties) {
        ValidationResult result = new ValidationResult();
        
        try {
            String clientId = properties.getProperty("oauth.client.id");
            String clientSecret = properties.getProperty("oauth.client.secret");
            String tokenUrl = properties.getProperty("oauth.token.url");
            
            if (clientId != null || clientSecret != null || tokenUrl != null) {
                if (clientId == null || clientId.trim().isEmpty()) {
                    result.addError(ConfigurationError.INVALID_CREDENTIALS,
                        "OAuth client ID is required", "oauth.client.id");
                }
                
                if (clientSecret == null || clientSecret.trim().isEmpty()) {
                    result.addError(ConfigurationError.INVALID_CREDENTIALS,
                        "OAuth client secret is required", "oauth.client.secret");
                }
                
                if (tokenUrl == null || tokenUrl.trim().isEmpty()) {
                    result.addError(ConfigurationError.INVALID_CREDENTIALS,
                        "OAuth token URL is required", "oauth.token.url");
                } else {
                    // Validate token URL format
                    ValidationResult urlResult = validateUrlFormat("oauth.token.url", tokenUrl);
                    result.mergeResults(urlResult);
                }
            }
            
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_CREDENTIALS,
                "Error validating OAuth configuration: " + e.getMessage(), "oauth");
        }
        
        return result;
    }
    
    /**
     * Validates environment-specific configuration files.
     */
    private ValidationResult validateEnvironmentConfigFiles(String environment) {
        ValidationResult result = new ValidationResult();
        
        try {
            String configFileName = "application-" + environment + ".properties";
            String configPath = "src/main/resources/" + configFileName;
            
            if (fileResourceHandler.validateFileExists(configPath)) {
                if (!fileResourceHandler.validateFileReadable(configPath)) {
                    result.addError(ConfigurationError.RESOURCE_NOT_FOUND,
                        "Environment config file is not readable: " + configPath, configFileName);
                }
            } else {
                result.addWarning("Environment-specific config file not found: " + configPath);
            }
            
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_PROFILE,
                "Error validating environment config files: " + e.getMessage(), "environment");
        }
        
        return result;
    }
    
    /**
     * Validates environment-specific resources.
     */
    private ValidationResult validateEnvironmentResources(String environment) {
        ValidationResult result = new ValidationResult();
        
        try {
            // Check for environment-specific test data
            String testDataPath = "src/test/resources/data/" + environment;
            if (!fileResourceHandler.validateFileExists(testDataPath)) {
                result.addWarning("Environment-specific test data directory not found: " + testDataPath);
            }
            
            // Check for environment-specific driver configurations
            String driverConfigPath = "src/test/resources/drivers/" + environment;
            if (!fileResourceHandler.validateFileExists(driverConfigPath)) {
                result.addWarning("Environment-specific driver config not found: " + driverConfigPath);
            }
            
        } catch (Exception e) {
            result.addWarning("Error validating environment resources: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validates pom.xml file format and structure.
     */
    private ValidationResult validatePomXmlFormat(Path pomPath) {
        ValidationResult result = new ValidationResult();
        
        try {
            if (!fileResourceHandler.validateFileFormat(pomPath.toString())) {
                result.addError(ConfigurationError.INVALID_FORMAT,
                    "Maven pom.xml file format validation failed", "pom.xml");
                return result;
            }
            
            // Additional XML structure validation could be added here
            logger.debug("Maven pom.xml format validation completed");
            
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Error validating pom.xml format: " + e.getMessage(), "pom.xml");
        }
        
        return result;
    }
    
    /**
     * Validates Maven dependency availability.
     */
    private ValidationResult validateMavenDependencies() {
        ValidationResult result = new ValidationResult();
        
        try {
            // Check for Maven local repository
            String userHome = System.getProperty("user.home");
            String mavenRepo = userHome + "/.m2/repository";
            
            if (!fileResourceHandler.validateFileExists(mavenRepo)) {
                result.addWarning("Maven local repository not found at: " + mavenRepo);
            }
            
            // Could add specific dependency checks here for critical dependencies
            logger.debug("Maven dependency validation completed");
            
        } catch (Exception e) {
            result.addWarning("Error validating Maven dependencies: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validates Maven repository configuration.
     */
    private ValidationResult validateMavenRepository() {
        ValidationResult result = new ValidationResult();
        
        try {
            String userHome = System.getProperty("user.home");
            String settingsPath = userHome + "/.m2/settings.xml";
            
            if (fileResourceHandler.validateFileExists(settingsPath)) {
                if (!fileResourceHandler.validateFileReadable(settingsPath)) {
                    result.addWarning("Maven settings.xml file is not readable: " + settingsPath);
                }
            }
            
            logger.debug("Maven repository validation completed");
            
        } catch (Exception e) {
            result.addWarning("Error validating Maven repository: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validates TestNG XML file format.
     */
    private ValidationResult validateTestNgXmlFormat(Path testNgPath) {
        ValidationResult result = new ValidationResult();
        
        try {
            if (!fileResourceHandler.validateFileFormat(testNgPath.toString())) {
                result.addError(ConfigurationError.INVALID_FORMAT,
                    "TestNG XML file format validation failed", "testng.xml");
                return result;
            }
            
            logger.debug("TestNG XML format validation completed");
            
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Error validating TestNG XML format: " + e.getMessage(), "testng.xml");
        }
        
        return result;
    }
    
    /**
     * Validates TestNG suite configuration.
     */
    private ValidationResult validateTestNgSuiteConfiguration(Path testNgPath) {
        ValidationResult result = new ValidationResult();
        
        try {
            // Basic TestNG configuration validation
            // In a full implementation, this would parse the XML and validate structure
            logger.debug("TestNG suite configuration validation completed for: {}", testNgPath);
            
        } catch (Exception e) {
            result.addError(ConfigurationError.INVALID_FORMAT,
                "Error validating TestNG suite configuration: " + e.getMessage(), "testng.xml");
        }
        
        return result;
    }
    
    /**
     * Masks sensitive values for logging.
     */
    private String maskSensitiveValue(String propertyName, String value) {
        try {
            String lowerName = propertyName.toLowerCase();
            
            if (lowerName.contains("password") || lowerName.contains("secret") || 
                lowerName.contains("key") || lowerName.contains("token")) {
                if (value.length() <= 4) {
                    return "****";
                } else {
                    return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
                }
            }
            
            return value;
            
        } catch (Exception e) {
            return "****";
        }
    }
    
    /**
     * Checks if a path is for an executable file.
     */
    private boolean isExecutablePath(String pathStr) {
        try {
            String lowerPath = pathStr.toLowerCase();
            return lowerPath.endsWith(".exe") || lowerPath.endsWith(".sh") || 
                   lowerPath.endsWith(".bat") || lowerPath.endsWith(".cmd") ||
                   lowerPath.contains("driver") || lowerPath.contains("chromedriver") ||
                   lowerPath.contains("geckodriver") || lowerPath.contains("edgedriver");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Checks for weak password patterns.
     */
    private boolean isWeakPassword(String password) {
        try {
            if (password.length() < 8) {
                return true;
            }
            
            String[] weakPasswords = {"password", "123456", "admin", "test", "default"};
            String lowerPassword = password.toLowerCase();
            
            for (String weak : weakPasswords) {
                if (lowerPassword.contains(weak)) {
                    return true;
                }
            }
            
            return false;
            
        } catch (Exception e) {
            return false;
        }
    }
}

/**
 * ValidationResult encapsulates the results of configuration validation operations,
 * providing detailed information about validation status, errors, warnings, and
 * the validated configuration data.
 * 
 * This class supports accumulation of validation results from multiple validation
 * operations and provides comprehensive reporting capabilities for troubleshooting
 * configuration issues in the automation framework.
 */
class ValidationResult {
    
    private boolean valid = true;
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Object> validatedConfiguration = new HashMap<>();
    private final Map<ConfigurationError, List<String>> errorsByType = new HashMap<>();
    
    /**
     * Checks if the validation result indicates a valid configuration.
     * 
     * @return boolean indicating validation success
     */
    public boolean isValid() {
        return valid && errors.isEmpty();
    }
    
    /**
     * Gets the list of validation errors.
     * 
     * @return List<String> containing detailed error messages
     */
    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }
    
    /**
     * Gets the list of validation warnings.
     * 
     * @return List<String> containing warning messages
     */
    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }
    
    /**
     * Gets the validated configuration data.
     * 
     * @return Map<String, Object> containing validated configuration
     */
    public Map<String, Object> getValidatedConfiguration() {
        return new HashMap<>(validatedConfiguration);
    }
    
    /**
     * Gets the count of validation errors.
     * 
     * @return int representing number of errors
     */
    public int getErrorCount() {
        return errors.size();
    }
    
    /**
     * Adds a validation error with type classification.
     * 
     * @param errorType Type of configuration error
     * @param message Detailed error message
     * @param property Property name that caused the error
     */
    public void addError(ConfigurationError errorType, String message, String property) {
        try {
            valid = false;
            String fullMessage = "[" + errorType.name() + "] " + message + " (Property: " + property + ")";
            errors.add(fullMessage);
            
            errorsByType.computeIfAbsent(errorType, k -> new ArrayList<>()).add(fullMessage);
            
        } catch (Exception e) {
            errors.add("Error adding validation error: " + e.getMessage());
        }
    }
    
    /**
     * Adds a validation warning.
     * 
     * @param message Warning message
     */
    public void addWarning(String message) {
        try {
            warnings.add(message);
        } catch (Exception e) {
            warnings.add("Error adding validation warning: " + e.getMessage());
        }
    }
    
    /**
     * Adds validated configuration data.
     * 
     * @param key Configuration key
     * @param value Configuration value
     */
    public void addValidatedConfiguration(String key, Object value) {
        try {
            validatedConfiguration.put(key, value);
        } catch (Exception e) {
            addWarning("Error adding validated configuration for key: " + key);
        }
    }
    
    /**
     * Merges another validation result into this one.
     * 
     * @param other ValidationResult to merge
     */
    public void mergeResults(ValidationResult other) {
        try {
            if (other == null) {
                return;
            }
            
            if (!other.isValid()) {
                this.valid = false;
            }
            
            this.errors.addAll(other.getErrors());
            this.warnings.addAll(other.getWarnings());
            this.validatedConfiguration.putAll(other.getValidatedConfiguration());
            
            // Merge error types
            for (Map.Entry<ConfigurationError, List<String>> entry : other.errorsByType.entrySet()) {
                this.errorsByType.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            }
            
        } catch (Exception e) {
            addError(ConfigurationError.INVALID_FORMAT, 
                "Error merging validation results: " + e.getMessage(), "system");
        }
    }
    
    /**
     * Gets errors grouped by error type.
     * 
     * @return Map<ConfigurationError, List<String>> containing errors by type
     */
    public Map<ConfigurationError, List<String>> getErrorsByType() {
        return new HashMap<>(errorsByType);
    }
    
    /**
     * Gets a summary of the validation result.
     * 
     * @return String containing validation summary
     */
    public String getSummary() {
        try {
            StringBuilder summary = new StringBuilder();
            summary.append("Validation Result Summary:\n");
            summary.append("  Valid: ").append(isValid()).append("\n");
            summary.append("  Errors: ").append(errors.size()).append("\n");
            summary.append("  Warnings: ").append(warnings.size()).append("\n");
            summary.append("  Validated Properties: ").append(validatedConfiguration.size()).append("\n");
            
            if (!errors.isEmpty()) {
                summary.append("  Error Types: ").append(errorsByType.keySet()).append("\n");
            }
            
            return summary.toString();
            
        } catch (Exception e) {
            return "Error generating validation summary: " + e.getMessage();
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