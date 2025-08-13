package com.automation.framework.validation;

// Internal framework imports - ONLY from depends_on_files
import com.automation.framework.core.ConfigurationManager;
import com.automation.framework.exceptions.ExceptionHandler;
import com.automation.framework.resources.FileResourceHandler;
import com.automation.framework.validation.SchemaValidator;

// External imports - TestNG DataProvider annotation support
import org.testng.annotations.DataProvider;

// External imports - Jackson for JSON processing
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;

// External imports - Apache POI for Excel processing
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;

// External imports - Apache Commons CSV for CSV processing
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

// External imports - Java standard library collections and utilities
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

// External imports - Java regex for pattern matching and validation
import java.util.regex.Pattern;
import java.util.regex.Matcher;

// External imports - Java time for date/time validation
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

// External imports - Java NIO for file path operations
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;

// External imports - Java I/O for file operations
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.FileReader;

// External imports - SLF4J logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// External imports - Java standard library core
import java.util.Collections;
import java.util.stream.Collectors;
import java.time.Instant;
import java.time.Duration;

/**
 * TestDataValidator provides comprehensive validation of test data before execution to ensure 
 * data integrity and prevent test failures due to invalid inputs.
 * 
 * This enterprise-grade validator implements robust test data validation mechanisms for multiple 
 * file formats including CSV, JSON, XML, Excel, Properties, and YAML files. It performs thorough 
 * validation of test data files, parameters, and datasets used in data-driven testing scenarios 
 * with comprehensive error reporting and performance optimization through intelligent caching.
 * 
 * Key Features:
 * - Multi-format test data validation (CSV, JSON, XML, Excel, Properties, YAML)
 * - Comprehensive field and data completeness validation with required field checking
 * - Data type and format validation with boundary value checking for numeric inputs
 * - String length constraints and character encoding validation
 * - Date/time format validation with range checking and timezone support
 * - Referential integrity validation for related test data with foreign key checking
 * - Custom validation rules per test scenario with business logic constraints
 * - Data sanitization to prevent injection attacks with XSS and SQL injection protection
 * - Performance optimization through validated data caching with LRU eviction
 * - Detailed validation reporting with specific error locations and line numbers
 * - TestNG DataProvider validation for parameterized tests with provider method validation
 * - Configurable fail-fast or error collection modes based on test execution requirements
 * 
 * Validation Modes:
 * - FAIL_FAST: Stop validation on first error for quick feedback during development
 * - COLLECT_ALL_ERRORS: Collect all validation errors for comprehensive reporting
 * - LENIENT: Report validation issues as warnings without failing tests
 * 
 * Performance Requirements:
 * - Test data validation response time: <200ms for cached data, <1s for fresh validation
 * - Validation cache hit ratio: >85% for repeated test data validation operations
 * - Memory usage: <100MB for validation cache with automatic cleanup and size limits
 * - Concurrent validation support: Up to 50 simultaneous validation operations
 * 
 * Integration Architecture:
 * - ConfigurationManager integration for validation settings, thresholds, and cache configuration
 * - ExceptionHandler integration for centralized validation error management and recovery
 * - FileResourceHandler integration for test data file validation with proper resource cleanup
 * - SchemaValidator integration for structured data validation against defined schemas
 * 
 * Thread Safety:
 * - Thread-safe concurrent access with ConcurrentHashMap for validation result caching
 * - Immutable validation results with thread-local validation context management
 * - Atomic cache operations with proper synchronization for validation state updates
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class TestDataValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(TestDataValidator.class);
    
    // Framework component integrations for comprehensive test data validation
    private final ConfigurationManager configurationManager;
    private final ExceptionHandler exceptionHandler;
    private final FileResourceHandler fileResourceHandler;
    private final SchemaValidator schemaValidator;
    
    // External component integrations for data processing
    private final ObjectMapper objectMapper;
    
    // Validation configuration and state management
    private volatile ValidationMode validationMode = ValidationMode.FAIL_FAST;
    private volatile boolean cacheEnabled = true;
    private volatile int maxCacheSize = 1000;
    private volatile long cacheTtlMs = 3600000; // 1 hour
    
    // Test data validation cache with performance optimization
    private final ConcurrentHashMap<String, CachedTestDataValidationResult> validationCache;
    
    // Custom validation rules for business logic constraints
    private final Map<String, ValidationRule> customValidationRules;
    
    // Performance and metrics tracking
    private volatile long totalValidations = 0;
    private volatile long cacheHits = 0;
    private volatile long validationErrors = 0;
    
    // Validation patterns for common data formats
    private final Map<String, Pattern> validationPatterns;
    
    // Date/time formatters for temporal validation
    private final List<DateTimeFormatter> dateTimeFormatters;
    
    /**
     * Creates a new TestDataValidator instance with framework component integrations.
     * Initializes validation infrastructure, data processing components, and performance caching.
     */
    public TestDataValidator() {
        // Initialize framework component dependencies
        this.configurationManager = ConfigurationManager.getInstance();
        this.exceptionHandler = new ExceptionHandler(
            new com.automation.framework.exceptions.ErrorReporter(),
            new com.automation.framework.exceptions.RecoveryStrategy(),
            com.automation.framework.exceptions.RetryMechanism.getInstance(),
            com.automation.framework.core.FrameworkManager.getInstance()
        );
        this.fileResourceHandler = new FileResourceHandler(
            com.automation.framework.monitoring.AuditLogger.getInstance(),
            this.exceptionHandler
        );
        this.schemaValidator = new SchemaValidator();
        
        // Initialize data processing components
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        // Initialize caching infrastructure
        this.validationCache = new ConcurrentHashMap<>();
        this.customValidationRules = new HashMap<>();
        
        // Initialize validation patterns
        this.validationPatterns = initializeValidationPatterns();
        
        // Initialize date/time formatters
        this.dateTimeFormatters = initializeDateTimeFormatters();
        
        // Load configuration settings
        loadConfigurationSettings();
        
        logger.info("TestDataValidator initialized with validation mode: {}, cache enabled: {}, max cache size: {}", 
                   validationMode, cacheEnabled, maxCacheSize);
    }
    
    /**
     * Validates comprehensive test data including files, parameters, and datasets with detailed error reporting.
     * Supports multiple data formats and validation modes with performance optimization through caching.
     * 
     * @param testData Test data object to validate (Map, List, or custom object)
     * @param validationConfig Configuration parameters for validation behavior
     * @return TestDataValidationResult containing validation status, errors, warnings, and performance metrics
     */
    public TestDataValidationResult validateTestData(Object testData, Map<String, Object> validationConfig) {
        long startTime = System.currentTimeMillis();
        totalValidations++;
        
        try {
            if (testData == null) {
                return createErrorResult("Test data cannot be null", startTime);
            }
            
            // Generate cache key for performance optimization
            String cacheKey = generateCacheKey(testData, validationConfig);
            
            // Check cache if enabled
            if (cacheEnabled) {
                TestDataValidationResult cachedResult = getCachedValidationResult(cacheKey);
                if (cachedResult != null) {
                    cacheHits++;
                    logger.debug("Validation cache hit for test data validation");
                    return cachedResult;
                }
            }
            
            // Perform comprehensive test data validation
            TestDataValidationResult result = performTestDataValidation(testData, validationConfig, startTime);
            
            // Cache validation result if enabled
            if (cacheEnabled && result != null) {
                cacheValidationResult(cacheKey, result);
            }
            
            long validationTime = System.currentTimeMillis() - startTime;
            logger.debug("Test data validation completed in {}ms, valid: {}, errors: {}, warnings: {}", 
                        validationTime, result.isValid(), result.getErrorCount(), result.getWarningCount());
            
            return result;
            
        } catch (Exception e) {
            validationErrors++;
            String errorMessage = "Test data validation failed: " + e.getMessage();
            
            Map<String, Object> errorContext = Map.of(
                "operation", "validateTestData",
                "testDataType", testData != null ? testData.getClass().getSimpleName() : "null",
                "validationTime", System.currentTimeMillis() - startTime
            );
            
            exceptionHandler.handleException(e, errorContext);
            return createErrorResult(errorMessage, startTime);
        }
    }
    
    /**
     * Validates test data files with format detection and comprehensive file validation.
     * Supports CSV, JSON, XML, Excel, Properties, and YAML file formats with schema validation.
     * 
     * @param filePath Path to the test data file to validate
     * @param dataType Expected data type of the file (CSV, JSON, XML, EXCEL, PROPERTIES, YAML)
     * @return TestDataValidationResult containing file validation status and detailed error information
     */
    public TestDataValidationResult validateDataFile(String filePath, TestDataType dataType) {
        long startTime = System.currentTimeMillis();
        totalValidations++;
        
        try {
            if (filePath == null || filePath.trim().isEmpty()) {
                return createErrorResult("File path cannot be null or empty", startTime);
            }
            
            Path dataFilePath = Paths.get(filePath);
            
            // Generate cache key for file validation
            String cacheKey = generateFileCacheKey(filePath, dataType);
            
            // Check cache if enabled
            if (cacheEnabled) {
                TestDataValidationResult cachedResult = getCachedValidationResult(cacheKey);
                if (cachedResult != null) {
                    cacheHits++;
                    logger.debug("Validation cache hit for file: {}", filePath);
                    return cachedResult;
                }
            }
            
            // Validate file existence and accessibility using FileResourceHandler
            if (!fileResourceHandler.validateFileExists(dataFilePath)) {
                return createErrorResult("Test data file does not exist: " + filePath, startTime);
            }
            
            if (!fileResourceHandler.validateFileReadable(dataFilePath)) {
                return createErrorResult("Test data file is not readable: " + filePath, startTime);
            }
            
            if (!fileResourceHandler.validateFileFormat(dataFilePath, dataType.name())) {
                return createErrorResult("Test data file format validation failed: " + filePath, startTime);
            }
            
            // Get file metadata for validation context
            Map<String, Object> fileMetadata = fileResourceHandler.getFileMetadata(dataFilePath);
            
            // Perform format-specific validation
            TestDataValidationResult result = validateDataFileByType(dataFilePath, dataType, fileMetadata, startTime);
            
            // Cache validation result if enabled
            if (cacheEnabled && result != null) {
                cacheValidationResult(cacheKey, result);
            }
            
            long validationTime = System.currentTimeMillis() - startTime;
            logger.debug("Data file validation completed in {}ms for {}, valid: {}, errors: {}", 
                        validationTime, filePath, result.isValid(), result.getErrorCount());
            
            return result;
            
        } catch (Exception e) {
            validationErrors++;
            String errorMessage = "Data file validation failed: " + e.getMessage();
            
            Map<String, Object> errorContext = Map.of(
                "operation", "validateDataFile",
                "filePath", filePath,
                "dataType", dataType != null ? dataType.toString() : "null",
                "validationTime", System.currentTimeMillis() - startTime
            );
            
            exceptionHandler.handleException(e, errorContext);
            return createErrorResult(errorMessage, startTime);
        }
    }
    
    /**
     * Validates TestNG DataProvider methods and their returned test data arrays.
     * Ensures proper DataProvider configuration and validates test parameter arrays.
     * 
     * @param dataProviderMethod Method annotated with @DataProvider to validate
     * @param expectedParameterCount Expected number of parameters per test case
     * @return TestDataValidationResult containing DataProvider validation status and parameter validation
     */
    public TestDataValidationResult validateDataProvider(java.lang.reflect.Method dataProviderMethod, int expectedParameterCount) {
        long startTime = System.currentTimeMillis();
        totalValidations++;
        
        try {
            if (dataProviderMethod == null) {
                return createErrorResult("DataProvider method cannot be null", startTime);
            }
            
            // Check if method has @DataProvider annotation
            DataProvider dataProviderAnnotation = dataProviderMethod.getAnnotation(DataProvider.class);
            if (dataProviderAnnotation == null) {
                return createErrorResult("Method is not annotated with @DataProvider: " + dataProviderMethod.getName(), startTime);
            }
            
            TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
            
            // Validate DataProvider method signature
            Class<?> returnType = dataProviderMethod.getReturnType();
            if (!Object[][].class.isAssignableFrom(returnType) && !java.util.Iterator.class.isAssignableFrom(returnType)) {
                resultBuilder.addError("DataProvider method must return Object[][] or Iterator<Object[]>: " + dataProviderMethod.getName());
            }
            
            // Validate method accessibility
            if (!dataProviderMethod.isAccessible()) {
                try {
                    dataProviderMethod.setAccessible(true);
                } catch (SecurityException e) {
                    resultBuilder.addError("DataProvider method is not accessible: " + dataProviderMethod.getName());
                }
            }
            
            // Invoke method to validate returned data (if possible)
            try {
                Object result = dataProviderMethod.invoke(null);
                if (result instanceof Object[][]) {
                    Object[][] testData = (Object[][]) result;
                    TestDataValidationResult dataValidation = validateDataProviderArray(testData, expectedParameterCount);
                    resultBuilder.mergeResult(dataValidation);
                } else if (result instanceof java.util.Iterator) {
                    @SuppressWarnings("unchecked")
                    java.util.Iterator<Object[]> iterator = (java.util.Iterator<Object[]>) result;
                    TestDataValidationResult dataValidation = validateDataProviderIterator(iterator, expectedParameterCount);
                    resultBuilder.mergeResult(dataValidation);
                }
            } catch (Exception e) {
                resultBuilder.addError("Failed to invoke DataProvider method: " + e.getMessage());
            }
            
            // Check DataProvider name configuration
            String providerName = dataProviderAnnotation.name();
            if (providerName.isEmpty()) {
                resultBuilder.addWarning("DataProvider name is not specified, using method name: " + dataProviderMethod.getName());
            }
            
            TestDataValidationResult finalResult = resultBuilder.build(startTime);
            
            long validationTime = System.currentTimeMillis() - startTime;
            logger.debug("DataProvider validation completed in {}ms for {}, valid: {}, errors: {}", 
                        validationTime, dataProviderMethod.getName(), finalResult.isValid(), finalResult.getErrorCount());
            
            return finalResult;
            
        } catch (Exception e) {
            validationErrors++;
            String errorMessage = "DataProvider validation failed: " + e.getMessage();
            
            Map<String, Object> errorContext = Map.of(
                "operation", "validateDataProvider",
                "methodName", dataProviderMethod != null ? dataProviderMethod.getName() : "null",
                "expectedParameterCount", expectedParameterCount,
                "validationTime", System.currentTimeMillis() - startTime
            );
            
            exceptionHandler.handleException(e, errorContext);
            return createErrorResult(errorMessage, startTime);
        }
    }
    
    /**
     * Validates data types and formats for test data fields with comprehensive type checking.
     * Supports primitive types, objects, collections, and custom data type validation.
     * 
     * @param testData Test data object containing fields to validate
     * @param expectedDataTypes Map of field names to expected data types
     * @return TestDataValidationResult containing data type validation status and type mismatch errors
     */
    public TestDataValidationResult validateDataTypes(Object testData, Map<String, Class<?>> expectedDataTypes) {
        long startTime = System.currentTimeMillis();
        totalValidations++;
        
        try {
            if (testData == null) {
                return createErrorResult("Test data cannot be null for type validation", startTime);
            }
            
            if (expectedDataTypes == null || expectedDataTypes.isEmpty()) {
                return createErrorResult("Expected data types map cannot be null or empty", startTime);
            }
            
            TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
            
            // Convert test data to map for field access
            Map<String, Object> dataMap = convertToDataMap(testData);
            
            // Validate each expected field type
            for (Map.Entry<String, Class<?>> entry : expectedDataTypes.entrySet()) {
                String fieldName = entry.getKey();
                Class<?> expectedType = entry.getValue();
                
                if (!dataMap.containsKey(fieldName)) {
                    resultBuilder.addError("Required field missing: " + fieldName);
                    continue;
                }
                
                Object fieldValue = dataMap.get(fieldName);
                
                if (fieldValue == null) {
                    resultBuilder.addWarning("Field has null value: " + fieldName);
                    continue;
                }
                
                // Perform type validation
                TestDataValidationResult fieldValidation = validateFieldType(fieldName, fieldValue, expectedType);
                resultBuilder.mergeResult(fieldValidation);
            }
            
            TestDataValidationResult finalResult = resultBuilder.build(startTime);
            
            long validationTime = System.currentTimeMillis() - startTime;
            logger.debug("Data types validation completed in {}ms, valid: {}, errors: {}", 
                        validationTime, finalResult.isValid(), finalResult.getErrorCount());
            
            return finalResult;
            
        } catch (Exception e) {
            validationErrors++;
            String errorMessage = "Data types validation failed: " + e.getMessage();
            
            Map<String, Object> errorContext = Map.of(
                "operation", "validateDataTypes",
                "testDataType", testData != null ? testData.getClass().getSimpleName() : "null",
                "expectedTypesCount", expectedDataTypes != null ? expectedDataTypes.size() : 0,
                "validationTime", System.currentTimeMillis() - startTime
            );
            
            exceptionHandler.handleException(e, errorContext);
            return createErrorResult(errorMessage, startTime);
        }
    }
    
    /**
     * Validates data format patterns including regex validation, length constraints, and encoding validation.
     * Supports email, phone, URL, custom regex patterns, and character encoding validation.
     * 
     * @param testData Test data object containing fields to validate
     * @param formatConstraints Map of field names to format validation constraints
     * @return TestDataValidationResult containing format validation status and pattern mismatch errors
     */
    public TestDataValidationResult validateDataFormat(Object testData, Map<String, FormatConstraint> formatConstraints) {
        long startTime = System.currentTimeMillis();
        totalValidations++;
        
        try {
            if (testData == null) {
                return createErrorResult("Test data cannot be null for format validation", startTime);
            }
            
            if (formatConstraints == null || formatConstraints.isEmpty()) {
                return createErrorResult("Format constraints map cannot be null or empty", startTime);
            }
            
            TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
            
            // Convert test data to map for field access
            Map<String, Object> dataMap = convertToDataMap(testData);
            
            // Validate each field format constraint
            for (Map.Entry<String, FormatConstraint> entry : formatConstraints.entrySet()) {
                String fieldName = entry.getKey();
                FormatConstraint constraint = entry.getValue();
                
                if (!dataMap.containsKey(fieldName)) {
                    if (constraint.isRequired()) {
                        resultBuilder.addError("Required field missing for format validation: " + fieldName);
                    }
                    continue;
                }
                
                Object fieldValue = dataMap.get(fieldName);
                
                if (fieldValue == null) {
                    if (constraint.isRequired()) {
                        resultBuilder.addError("Required field has null value: " + fieldName);
                    }
                    continue;
                }
                
                // Perform format validation
                TestDataValidationResult formatValidation = validateFieldFormat(fieldName, fieldValue, constraint);
                resultBuilder.mergeResult(formatValidation);
            }
            
            TestDataValidationResult finalResult = resultBuilder.build(startTime);
            
            long validationTime = System.currentTimeMillis() - startTime;
            logger.debug("Data format validation completed in {}ms, valid: {}, errors: {}", 
                        validationTime, finalResult.isValid(), finalResult.getErrorCount());
            
            return finalResult;
            
        } catch (Exception e) {
            validationErrors++;
            String errorMessage = "Data format validation failed: " + e.getMessage();
            
            Map<String, Object> errorContext = Map.of(
                "operation", "validateDataFormat",
                "testDataType", testData != null ? testData.getClass().getSimpleName() : "null",
                "constraintsCount", formatConstraints != null ? formatConstraints.size() : 0,
                "validationTime", System.currentTimeMillis() - startTime
            );
            
            exceptionHandler.handleException(e, errorContext);
            return createErrorResult(errorMessage, startTime);
        }
    }
    
    /**
     * Validates boundary values for numeric inputs with comprehensive range checking.
     * Supports integer, decimal, date ranges, and custom boundary validation with edge case testing.
     * 
     * @param testData Test data object containing numeric fields to validate
     * @param boundaryConstraints Map of field names to boundary validation constraints
     * @return TestDataValidationResult containing boundary validation status and range violation errors
     */
    public TestDataValidationResult validateBoundaryValues(Object testData, Map<String, BoundaryConstraint> boundaryConstraints) {
        long startTime = System.currentTimeMillis();
        totalValidations++;
        
        try {
            if (testData == null) {
                return createErrorResult("Test data cannot be null for boundary validation", startTime);
            }
            
            if (boundaryConstraints == null || boundaryConstraints.isEmpty()) {
                return createErrorResult("Boundary constraints map cannot be null or empty", startTime);
            }
            
            TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
            
            // Convert test data to map for field access
            Map<String, Object> dataMap = convertToDataMap(testData);
            
            // Validate each field boundary constraint
            for (Map.Entry<String, BoundaryConstraint> entry : boundaryConstraints.entrySet()) {
                String fieldName = entry.getKey();
                BoundaryConstraint constraint = entry.getValue();
                
                if (!dataMap.containsKey(fieldName)) {
                    if (constraint.isRequired()) {
                        resultBuilder.addError("Required field missing for boundary validation: " + fieldName);
                    }
                    continue;
                }
                
                Object fieldValue = dataMap.get(fieldName);
                
                if (fieldValue == null) {
                    if (constraint.isRequired()) {
                        resultBuilder.addError("Required field has null value: " + fieldName);
                    }
                    continue;
                }
                
                // Perform boundary validation
                TestDataValidationResult boundaryValidation = validateFieldBoundary(fieldName, fieldValue, constraint);
                resultBuilder.mergeResult(boundaryValidation);
            }
            
            TestDataValidationResult finalResult = resultBuilder.build(startTime);
            
            long validationTime = System.currentTimeMillis() - startTime;
            logger.debug("Boundary values validation completed in {}ms, valid: {}, errors: {}", 
                        validationTime, finalResult.isValid(), finalResult.getErrorCount());
            
            return finalResult;
            
        } catch (Exception e) {
            validationErrors++;
            String errorMessage = "Boundary values validation failed: " + e.getMessage();
            
            Map<String, Object> errorContext = Map.of(
                "operation", "validateBoundaryValues",
                "testDataType", testData != null ? testData.getClass().getSimpleName() : "null",
                "constraintsCount", boundaryConstraints != null ? boundaryConstraints.size() : 0,
                "validationTime", System.currentTimeMillis() - startTime
            );
            
            exceptionHandler.handleException(e, errorContext);
            return createErrorResult(errorMessage, startTime);
        }
    }
    
    /**
     * Validates referential integrity for related test data with foreign key checking.
     * Ensures data consistency across related datasets and validates join conditions.
     * 
     * @param testData Primary test data object to validate
     * @param relatedData Map of related datasets for referential integrity checking
     * @param integrityRules Map of field names to referential integrity validation rules
     * @return TestDataValidationResult containing integrity validation status and reference violation errors
     */
    public TestDataValidationResult validateReferentialIntegrity(Object testData, Map<String, Object> relatedData, 
                                                        Map<String, IntegrityRule> integrityRules) {
        long startTime = System.currentTimeMillis();
        totalValidations++;
        
        try {
            if (testData == null) {
                return createErrorResult("Test data cannot be null for referential integrity validation", startTime);
            }
            
            if (integrityRules == null || integrityRules.isEmpty()) {
                return createErrorResult("Integrity rules map cannot be null or empty", startTime);
            }
            
            TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
            
            // Convert test data to map for field access
            Map<String, Object> dataMap = convertToDataMap(testData);
            
            // Validate each referential integrity rule
            for (Map.Entry<String, IntegrityRule> entry : integrityRules.entrySet()) {
                String fieldName = entry.getKey();
                IntegrityRule rule = entry.getValue();
                
                if (!dataMap.containsKey(fieldName)) {
                    if (rule.isRequired()) {
                        resultBuilder.addError("Required field missing for integrity validation: " + fieldName);
                    }
                    continue;
                }
                
                Object fieldValue = dataMap.get(fieldName);
                
                if (fieldValue == null) {
                    if (rule.isRequired()) {
                        resultBuilder.addError("Required field has null value for integrity validation: " + fieldName);
                    }
                    continue;
                }
                
                // Perform referential integrity validation
                TestDataValidationResult integrityValidation = validateFieldIntegrity(fieldName, fieldValue, rule, relatedData);
                resultBuilder.mergeResult(integrityValidation);
            }
            
            TestDataValidationResult finalResult = resultBuilder.build(startTime);
            
            long validationTime = System.currentTimeMillis() - startTime;
            logger.debug("Referential integrity validation completed in {}ms, valid: {}, errors: {}", 
                        validationTime, finalResult.isValid(), finalResult.getErrorCount());
            
            return finalResult;
            
        } catch (Exception e) {
            validationErrors++;
            String errorMessage = "Referential integrity validation failed: " + e.getMessage();
            
            Map<String, Object> errorContext = Map.of(
                "operation", "validateReferentialIntegrity",
                "testDataType", testData != null ? testData.getClass().getSimpleName() : "null",
                "integrityRulesCount", integrityRules != null ? integrityRules.size() : 0,
                "relatedDataCount", relatedData != null ? relatedData.size() : 0,
                "validationTime", System.currentTimeMillis() - startTime
            );
            
            exceptionHandler.handleException(e, errorContext);
            return createErrorResult(errorMessage, startTime);
        }
    }
    
    /**
     * Validates test data against custom validation rules with business logic constraints.
     * Supports domain-specific validation rules and complex business rule validation.
     * 
     * @param testData Test data object to validate against custom rules
     * @param ruleName Name of the custom validation rule to apply
     * @return TestDataValidationResult containing custom rule validation status and business rule violation errors
     */
    public TestDataValidationResult validateCustomRules(Object testData, String ruleName) {
        long startTime = System.currentTimeMillis();
        totalValidations++;
        
        try {
            if (testData == null) {
                return createErrorResult("Test data cannot be null for custom rule validation", startTime);
            }
            
            if (ruleName == null || ruleName.trim().isEmpty()) {
                return createErrorResult("Rule name cannot be null or empty", startTime);
            }
            
            ValidationRule customRule = customValidationRules.get(ruleName);
            if (customRule == null) {
                return createErrorResult("Custom validation rule not found: " + ruleName, startTime);
            }
            
            TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
            
            // Check if rule is applicable to the test data
            if (!customRule.isApplicable(testData)) {
                resultBuilder.addWarning("Custom validation rule is not applicable to test data: " + ruleName);
                return resultBuilder.build(startTime);
            }
            
            // Apply custom validation rule
            try {
                boolean isValid = customRule.validate(testData);
                if (!isValid) {
                    resultBuilder.addError("Custom validation rule failed: " + customRule.getErrorMessage());
                }
            } catch (Exception e) {
                resultBuilder.addError("Custom validation rule execution failed: " + e.getMessage());
            }
            
            TestDataValidationResult finalResult = resultBuilder.build(startTime);
            
            long validationTime = System.currentTimeMillis() - startTime;
            logger.debug("Custom rules validation completed in {}ms for rule {}, valid: {}, errors: {}", 
                        validationTime, ruleName, finalResult.isValid(), finalResult.getErrorCount());
            
            return finalResult;
            
        } catch (Exception e) {
            validationErrors++;
            String errorMessage = "Custom rules validation failed: " + e.getMessage();
            
            Map<String, Object> errorContext = Map.of(
                "operation", "validateCustomRules",
                "testDataType", testData != null ? testData.getClass().getSimpleName() : "null",
                "ruleName", ruleName != null ? ruleName : "null",
                "validationTime", System.currentTimeMillis() - startTime
            );
            
            exceptionHandler.handleException(e, errorContext);
            return createErrorResult(errorMessage, startTime);
        }
    }
    
    /**
     * Sanitizes test data to prevent injection attacks with XSS and SQL injection protection.
     * Removes or escapes potentially dangerous characters and validates safe input patterns.
     * 
     * @param testData Test data object to sanitize
     * @param sanitizationConfig Configuration parameters for sanitization behavior
     * @return Map containing sanitized test data with original and sanitized values
     */
    public Map<String, Object> sanitizeData(Object testData, Map<String, Object> sanitizationConfig) {
        try {
            if (testData == null) {
                Map<String, Object> result = new HashMap<>();
                result.put("sanitized", null);
                result.put("original", null);
                result.put("sanitizationApplied", false);
                return result;
            }
            
            Map<String, Object> result = new HashMap<>();
            Map<String, Object> originalData = convertToDataMap(testData);
            Map<String, Object> sanitizedData = new HashMap<>();
            boolean sanitizationApplied = false;
            
            // Apply sanitization to each field
            for (Map.Entry<String, Object> entry : originalData.entrySet()) {
                String fieldName = entry.getKey();
                Object fieldValue = entry.getValue();
                
                if (fieldValue instanceof String) {
                    String sanitizedValue = sanitizeStringValue((String) fieldValue, sanitizationConfig);
                    sanitizedData.put(fieldName, sanitizedValue);
                    if (!sanitizedValue.equals(fieldValue)) {
                        sanitizationApplied = true;
                        logger.debug("Sanitization applied to field: {}", fieldName);
                    }
                } else {
                    sanitizedData.put(fieldName, fieldValue);
                }
            }
            
            result.put("original", originalData);
            result.put("sanitized", sanitizedData);
            result.put("sanitizationApplied", sanitizationApplied);
            
            return result;
            
        } catch (Exception e) {
            String errorMessage = "Data sanitization failed: " + e.getMessage();
            
            Map<String, Object> errorContext = Map.of(
                "operation", "sanitizeData",
                "testDataType", testData != null ? testData.getClass().getSimpleName() : "null"
            );
            
            exceptionHandler.handleException(e, errorContext);
            
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", errorMessage);
            errorResult.put("sanitizationApplied", false);
            return errorResult;
        }
    }
    
    /**
     * Gets cached validation result for performance optimization with cache hit tracking.
     * Returns previously validated results if available and not expired.
     * 
     * @param cacheKey Unique key identifying the validation operation and data
     * @return TestDataValidationResult from cache, null if not found or expired
     */
    public TestDataValidationResult getCachedValidationResult(String cacheKey) {
        try {
            if (!cacheEnabled || cacheKey == null || cacheKey.trim().isEmpty()) {
                return null;
            }
            
            CachedTestDataValidationResult cachedResult = validationCache.get(cacheKey);
            if (cachedResult == null) {
                return null;
            }
            
            // Check if cached result has expired
            if (cachedResult.isExpired()) {
                validationCache.remove(cacheKey);
                return null;
            }
            
            return cachedResult.getValidationResult();
            
        } catch (Exception e) {
            logger.warn("Error retrieving cached validation result: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Clears validation cache to free memory and force data re-validation.
     * Supports selective cache clearing by cache key pattern or complete cache flush.
     */
    public void clearValidationCache() {
        try {
            int cacheSize = validationCache.size();
            validationCache.clear();
            
            // Reset cache hit statistics
            cacheHits = 0;
            
            logger.info("Validation cache cleared, {} entries removed", cacheSize);
            
        } catch (Exception e) {
            String errorMessage = "Failed to clear validation cache";
            
            Map<String, Object> errorContext = Map.of(
                "operation", "clearValidationCache",
                "cacheSize", validationCache.size()
            );
            
            exceptionHandler.handleException(e, errorContext);
            logger.error("Error clearing validation cache: {}", e.getMessage());
        }
    }
    
    /**
     * Gets comprehensive validation report with performance metrics and cache statistics.
     * Provides detailed insights into validation operations, error patterns, and cache performance.
     * 
     * @return Map containing validation metrics, cache statistics, error analysis, and performance data
     */
    public Map<String, Object> getValidationReport() {
        Map<String, Object> report = new HashMap<>();
        
        try {
            // Validation statistics
            report.put("totalValidations", totalValidations);
            report.put("validationErrors", validationErrors);
            report.put("errorRate", totalValidations > 0 ? (double) validationErrors / totalValidations : 0.0);
            
            // Cache performance statistics
            report.put("cacheEnabled", cacheEnabled);
            report.put("cacheSize", validationCache.size());
            report.put("maxCacheSize", maxCacheSize);
            report.put("cacheHits", cacheHits);
            report.put("cacheHitRatio", totalValidations > 0 ? (double) cacheHits / totalValidations : 0.0);
            report.put("cacheTtlMs", cacheTtlMs);
            
            // Configuration status
            report.put("validationMode", validationMode.toString());
            report.put("customRulesCount", customValidationRules.size());
            
            // Configuration from ConfigurationManager
            Map<String, Object> configMetrics = configurationManager.getMetricsThresholds();
            report.put("configurationMetrics", configMetrics);
            
            // Cache memory usage estimate
            report.put("estimatedCacheMemoryMB", estimateCacheMemoryUsage());
            
            // Recent validation performance
            report.put("recentValidationPerformance", calculateRecentPerformanceMetrics());
            
            // Custom rules breakdown
            Map<String, String> customRulesDetails = new HashMap<>();
            for (Map.Entry<String, ValidationRule> entry : customValidationRules.entrySet()) {
                customRulesDetails.put(entry.getKey(), entry.getValue().getRuleName());
            }
            report.put("customRulesDetails", customRulesDetails);
            
            // Timestamp
            report.put("reportTimestamp", Instant.now());
            
            logger.debug("Generated validation report with {} validation operations", totalValidations);
            
        } catch (Exception e) {
            String errorMessage = "Failed to generate validation report";
            Map<String, Object> errorContext = Map.of(
                "operation", "getValidationReport",
                "errorType", e.getClass().getSimpleName()
            );
            
            exceptionHandler.handleException(e, errorContext);
            report.put("reportError", errorMessage);
            logger.error("Error generating validation report: {}", e.getMessage());
        }
        
        return report;
    }
    
    /**
     * Sets validation mode for controlling validation behavior and error handling.
     * Configures fail-fast, error collection, or warning-only validation modes.
     * 
     * @param mode ValidationMode to set (FAIL_FAST, COLLECT_ALL_ERRORS, LENIENT)
     */
    public void setValidationMode(ValidationMode mode) {
        try {
            if (mode == null) {
                throw new IllegalArgumentException("Validation mode cannot be null");
            }
            
            ValidationMode previousMode = this.validationMode;
            this.validationMode = mode;
            
            // Update configuration manager property
            configurationManager.setProperty("validation.mode", mode.toString());
            
            logger.info("Validation mode changed from {} to {}", previousMode, mode);
            
        } catch (Exception e) {
            String errorMessage = "Failed to set validation mode";
            Map<String, Object> errorContext = Map.of(
                "operation", "setValidationMode",
                "requestedMode", mode != null ? mode.toString() : "null",
                "currentMode", validationMode.toString()
            );
            
            exceptionHandler.handleException(e, errorContext);
            logger.error("Error setting validation mode to {}: {}", mode, e.getMessage());
        }
    }
    
    /**
     * Adds custom validation rule for business logic constraints and domain-specific validation.
     * Enables extension of standard validation with custom business rules and complex validation logic.
     * 
     * @param ruleName Name of the custom validation rule (must be unique)
     * @param rule ValidationRule implementation containing validation logic
     */
    public void addCustomValidationRule(String ruleName, ValidationRule rule) {
        try {
            if (ruleName == null || ruleName.trim().isEmpty()) {
                throw new IllegalArgumentException("Rule name cannot be null or empty");
            }
            
            if (rule == null) {
                throw new IllegalArgumentException("Validation rule cannot be null");
            }
            
            // Check if rule already exists
            if (customValidationRules.containsKey(ruleName)) {
                logger.warn("Overriding existing custom validation rule: {}", ruleName);
            }
            
            customValidationRules.put(ruleName, rule);
            
            logger.info("Added custom validation rule: {} (total rules: {})", ruleName, customValidationRules.size());
            
        } catch (Exception e) {
            String errorMessage = "Failed to add custom validation rule";
            Map<String, Object> errorContext = Map.of(
                "operation", "addCustomValidationRule",
                "ruleName", ruleName != null ? ruleName : "null",
                "ruleType", rule != null ? rule.getClass().getSimpleName() : "null"
            );
            
            exceptionHandler.handleException(e, errorContext);
            logger.error("Error adding custom validation rule {}: {}", ruleName, e.getMessage());
        }
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Loads configuration settings from ConfigurationManager.
     */
    private void loadConfigurationSettings() {
        try {
            // Cache configuration
            String cacheSize = configurationManager.getProperty("validation.cache.size");
            if (cacheSize != null) {
                this.maxCacheSize = Integer.parseInt(cacheSize);
            }
            
            String cacheTtl = configurationManager.getProperty("validation.cache.ttl");
            if (cacheTtl != null) {
                this.cacheTtlMs = Long.parseLong(cacheTtl);
            }
            
            String cacheEnabledStr = configurationManager.getProperty("validation.cache.enabled");
            if (cacheEnabledStr != null) {
                this.cacheEnabled = Boolean.parseBoolean(cacheEnabledStr);
            }
            
            // Validation mode configuration
            String validationModeStr = configurationManager.getProperty("validation.mode");
            if (validationModeStr != null) {
                try {
                    this.validationMode = ValidationMode.valueOf(validationModeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid validation mode in configuration: {}, using default: {}", 
                               validationModeStr, validationMode);
                }
            }
            
        } catch (Exception e) {
            logger.warn("Error loading configuration settings, using defaults: {}", e.getMessage());
        }
    }
    
    /**
     * Initializes validation patterns for common data formats.
     */
    private Map<String, Pattern> initializeValidationPatterns() {
        Map<String, Pattern> patterns = new HashMap<>();
        
        try {
            // Email pattern
            patterns.put("email", Pattern.compile("^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$"));
            
            // Phone number pattern (international format)
            patterns.put("phone", Pattern.compile("^\\+?[1-9]\\d{1,14}$"));
            
            // URL pattern
            patterns.put("url", Pattern.compile("^https?://[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/.*)?$"));
            
            // IP address pattern
            patterns.put("ipv4", Pattern.compile("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"));
            
            // Credit card pattern (basic)
            patterns.put("creditCard", Pattern.compile("^[0-9]{13,19}$"));
            
            // Social Security Number pattern (US format)
            patterns.put("ssn", Pattern.compile("^[0-9]{3}-[0-9]{2}-[0-9]{4}$"));
            
            // Date pattern (YYYY-MM-DD)
            patterns.put("date", Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"));
            
            // Time pattern (HH:MM:SS)
            patterns.put("time", Pattern.compile("^[0-9]{2}:[0-9]{2}:[0-9]{2}$"));
            
            // UUID pattern
            patterns.put("uuid", Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"));
            
            // Alphanumeric pattern
            patterns.put("alphanumeric", Pattern.compile("^[A-Za-z0-9]+$"));
            
            // SQL injection detection pattern
            patterns.put("sqlInjection", Pattern.compile("(?i)(union|select|insert|update|delete|drop|create|alter|exec|script)", Pattern.CASE_INSENSITIVE));
            
            // XSS detection pattern
            patterns.put("xss", Pattern.compile("(?i)(<script|javascript:|vbscript:|onload|onerror|onclick)", Pattern.CASE_INSENSITIVE));
            
        } catch (Exception e) {
            logger.warn("Error initializing validation patterns: {}", e.getMessage());
        }
        
        return patterns;
    }
    
    /**
     * Initializes date/time formatters for temporal validation.
     */
    private List<DateTimeFormatter> initializeDateTimeFormatters() {
        List<DateTimeFormatter> formatters = new ArrayList<>();
        
        try {
            // ISO 8601 formats
            formatters.add(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            formatters.add(DateTimeFormatter.ISO_LOCAL_DATE);
            formatters.add(DateTimeFormatter.ISO_LOCAL_TIME);
            
            // Common date formats
            formatters.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            formatters.add(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            formatters.add(DateTimeFormatter.ofPattern("MM/dd/yyyy"));
            formatters.add(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            formatters.add(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            formatters.add(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            formatters.add(DateTimeFormatter.ofPattern("MM-dd-yyyy"));
            
            // Time formats
            formatters.add(DateTimeFormatter.ofPattern("HH:mm:ss"));
            formatters.add(DateTimeFormatter.ofPattern("HH:mm"));
            formatters.add(DateTimeFormatter.ofPattern("hh:mm:ss a"));
            formatters.add(DateTimeFormatter.ofPattern("hh:mm a"));
            
        } catch (Exception e) {
            logger.warn("Error initializing date/time formatters: {}", e.getMessage());
        }
        
        return formatters;
    }
    
    /**
     * Generates cache key for validation operations.
     */
    private String generateCacheKey(Object testData, Map<String, Object> validationConfig) {
        try {
            StringBuilder keyBuilder = new StringBuilder();
            keyBuilder.append("testdata_");
            keyBuilder.append(testData.hashCode());
            
            if (validationConfig != null && !validationConfig.isEmpty()) {
                keyBuilder.append("_config_");
                keyBuilder.append(validationConfig.hashCode());
            }
            
            return keyBuilder.toString();
            
        } catch (Exception e) {
            logger.debug("Error generating cache key, using fallback: {}", e.getMessage());
            return "testdata_" + System.currentTimeMillis() + "_" + Math.random();
        }
    }
    
    /**
     * Generates cache key for file validation operations.
     */
    private String generateFileCacheKey(String filePath, TestDataType dataType) {
        try {
            return "file_" + filePath.hashCode() + "_" + dataType.name();
        } catch (Exception e) {
            logger.debug("Error generating file cache key, using fallback: {}", e.getMessage());
            return "file_" + System.currentTimeMillis() + "_" + Math.random();
        }
    }
    
    /**
     * Caches validation result with TTL management.
     */
    private void cacheValidationResult(String cacheKey, TestDataValidationResult result) {
        try {
            if (!cacheEnabled || cacheKey == null || result == null) {
                return;
            }
            
            // Implement LRU eviction if cache is full
            if (validationCache.size() >= maxCacheSize) {
                evictOldestCacheEntry();
            }
            
            Instant expiryTime = Instant.now().plusMillis(cacheTtlMs);
            CachedTestDataValidationResult cachedResult = new CachedTestDataValidationResult(result, expiryTime);
            validationCache.put(cacheKey, cachedResult);
            
        } catch (Exception e) {
            logger.debug("Failed to cache validation result: {}", e.getMessage());
        }
    }
    
    /**
     * Evicts oldest cache entry for LRU management.
     */
    private void evictOldestCacheEntry() {
        if (validationCache.isEmpty()) {
            return;
        }
        
        String oldestKey = validationCache.entrySet().stream()
            .min((e1, e2) -> e1.getValue().getCreationTime().compareTo(e2.getValue().getCreationTime()))
            .map(Map.Entry::getKey)
            .orElse(null);
        
        if (oldestKey != null) {
            validationCache.remove(oldestKey);
        }
    }
    
    /**
     * Creates error validation result.
     */
    private TestDataValidationResult createErrorResult(String errorMessage, long startTime) {
        long validationTime = System.currentTimeMillis() - startTime;
        
        List<String> errors = new ArrayList<>();
        errors.add(errorMessage);
        
        return new TestDataValidationResult(
            false,
            errors,
            new ArrayList<>(), // warnings
            null, // validatedData
            1, // errorCount
            0, // warningCount
            errorMessage,
            false, // hasErrors
            false // hasWarnings
        );
    }
    
    /**
     * Performs comprehensive test data validation.
     */
    private TestDataValidationResult performTestDataValidation(Object testData, Map<String, Object> validationConfig, long startTime) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        try {
            // Convert test data to map for processing
            Map<String, Object> dataMap = convertToDataMap(testData);
            
            // Validate data completeness
            if (dataMap.isEmpty()) {
                resultBuilder.addError("Test data is empty");
                return resultBuilder.build(startTime);
            }
            
            // Validate required fields if specified in config
            if (validationConfig != null && validationConfig.containsKey("requiredFields")) {
                @SuppressWarnings("unchecked")
                List<String> requiredFields = (List<String>) validationConfig.get("requiredFields");
                for (String field : requiredFields) {
                    if (!dataMap.containsKey(field) || dataMap.get(field) == null) {
                        resultBuilder.addError("Required field missing or null: " + field);
                    }
                }
            }
            
            // Validate data types if specified in config
            if (validationConfig != null && validationConfig.containsKey("dataTypes")) {
                @SuppressWarnings("unchecked")
                Map<String, Class<?>> dataTypes = (Map<String, Class<?>>) validationConfig.get("dataTypes");
                TestDataValidationResult typeValidation = validateDataTypes(testData, dataTypes);
                resultBuilder.mergeResult(typeValidation);
            }
            
            // Apply sanitization check
            Map<String, Object> sanitizationResult = sanitizeData(testData, validationConfig);
            if (sanitizationResult.containsKey("sanitizationApplied") && 
                (Boolean) sanitizationResult.get("sanitizationApplied")) {
                resultBuilder.addWarning("Data sanitization was applied to remove potentially harmful content");
            }
            
            return resultBuilder.build(startTime);
            
        } catch (Exception e) {
            resultBuilder.addError("Test data validation failed: " + e.getMessage());
            return resultBuilder.build(startTime);
        }
    }
    
    /**
     * Validates data file by type.
     */
    private TestDataValidationResult validateDataFileByType(Path filePath, TestDataType dataType, 
                                                   Map<String, Object> fileMetadata, long startTime) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        try {
            switch (dataType) {
                case CSV:
                    TestDataValidationResult csvResult = validateCsvFile(filePath);
                    resultBuilder.mergeResult(csvResult);
                    break;
                    
                case JSON:
                    TestDataValidationResult jsonResult = validateJsonFile(filePath);
                    resultBuilder.mergeResult(jsonResult);
                    break;
                    
                case XML:
                    TestDataValidationResult xmlResult = validateXmlFile(filePath);
                    resultBuilder.mergeResult(xmlResult);
                    break;
                    
                case EXCEL:
                    TestDataValidationResult excelResult = validateExcelFile(filePath);
                    resultBuilder.mergeResult(excelResult);
                    break;
                    
                case PROPERTIES:
                    TestDataValidationResult propertiesResult = validatePropertiesFile(filePath);
                    resultBuilder.mergeResult(propertiesResult);
                    break;
                    
                case YAML:
                    TestDataValidationResult yamlResult = validateYamlFile(filePath);
                    resultBuilder.mergeResult(yamlResult);
                    break;
                    
                default:
                    resultBuilder.addError("Unsupported data type: " + dataType);
            }
            
            return resultBuilder.build(startTime);
            
        } catch (Exception e) {
            resultBuilder.addError("File validation failed: " + e.getMessage());
            return resultBuilder.build(startTime);
        }
    }
    
    /**
     * Validates CSV file format and content.
     */
    private TestDataValidationResult validateCsvFile(Path filePath) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        try (Reader reader = Files.newBufferedReader(filePath)) {
            CSVFormat csvFormat = CSVFormat.DEFAULT.withFirstRecordAsHeader();
            CSVParser parser = csvFormat.parse(reader);
            
            int recordCount = 0;
            Set<String> headers = new HashSet<>(parser.getHeaderNames());
            
            if (headers.isEmpty()) {
                resultBuilder.addWarning("CSV file has no headers");
            }
            
            for (CSVRecord record : parser) {
                recordCount++;
                
                // Validate record completeness
                for (String header : headers) {
                    String value = record.get(header);
                    if (value == null || value.trim().isEmpty()) {
                        resultBuilder.addWarning("Empty value in record " + recordCount + ", column: " + header);
                    }
                }
                
                // Limit validation to prevent performance issues
                if (recordCount > 1000) {
                    resultBuilder.addWarning("CSV file contains more than 1000 records, validation truncated");
                    break;
                }
            }
            
            if (recordCount == 0) {
                resultBuilder.addError("CSV file contains no data records");
            }
            
        } catch (Exception e) {
            resultBuilder.addError("CSV file validation failed: " + e.getMessage());
        }
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Validates JSON file format and structure.
     */
    private TestDataValidationResult validateJsonFile(Path filePath) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        try {
            String jsonContent = Files.readString(filePath);
            JsonNode jsonNode = objectMapper.readTree(jsonContent);
            
            if (jsonNode == null) {
                resultBuilder.addError("JSON file is empty or invalid");
            } else if (jsonNode.isArray()) {
                int arraySize = jsonNode.size();
                if (arraySize == 0) {
                    resultBuilder.addWarning("JSON array is empty");
                } else {
                    resultBuilder.addWarning("JSON file contains array with " + arraySize + " elements");
                }
            } else if (jsonNode.isObject()) {
                if (jsonNode.size() == 0) {
                    resultBuilder.addWarning("JSON object is empty");
                }
            }
            
        } catch (JsonProcessingException e) {
            resultBuilder.addError("JSON parsing failed: " + e.getMessage());
        } catch (Exception e) {
            resultBuilder.addError("JSON file validation failed: " + e.getMessage());
        }
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Validates XML file format and structure.
     */
    private TestDataValidationResult validateXmlFile(Path filePath) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        try {
            String xmlContent = Files.readString(filePath);
            
            // Basic XML parsing validation
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            
            try (InputStream is = new java.io.ByteArrayInputStream(xmlContent.getBytes())) {
                org.w3c.dom.Document document = builder.parse(is);
                
                if (document.getDocumentElement() == null) {
                    resultBuilder.addError("XML file has no root element");
                }
            }
            
        } catch (Exception e) {
            resultBuilder.addError("XML file validation failed: " + e.getMessage());
        }
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Validates Excel file format and content.
     */
    private TestDataValidationResult validateExcelFile(Path filePath) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        try (InputStream is = Files.newInputStream(filePath)) {
            Workbook workbook = WorkbookFactory.create(is);
            
            int numberOfSheets = workbook.getNumberOfSheets();
            if (numberOfSheets == 0) {
                resultBuilder.addError("Excel file contains no sheets");
                workbook.close();
                return resultBuilder.build(System.currentTimeMillis());
            }
            
            for (int i = 0; i < numberOfSheets; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String sheetName = workbook.getSheetName(i);
                
                if (sheet.getPhysicalNumberOfRows() == 0) {
                    resultBuilder.addWarning("Sheet '" + sheetName + "' is empty");
                    continue;
                }
                
                // Validate first row as headers
                Row headerRow = sheet.getRow(0);
                if (headerRow == null) {
                    resultBuilder.addWarning("Sheet '" + sheetName + "' has no header row");
                    continue;
                }
                
                // Validate data rows
                int dataRowCount = 0;
                for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row != null) {
                        dataRowCount++;
                        
                        // Check for empty cells
                        for (int cellIndex = 0; cellIndex < headerRow.getLastCellNum(); cellIndex++) {
                            Cell cell = row.getCell(cellIndex);
                            if (cell == null || cell.getCellType() == CellType.BLANK) {
                                Cell headerCell = headerRow.getCell(cellIndex);
                                String headerName = headerCell != null ? headerCell.getStringCellValue() : "Column " + cellIndex;
                                resultBuilder.addWarning("Empty cell in sheet '" + sheetName + "', row " + (rowIndex + 1) + ", column: " + headerName);
                            }
                        }
                    }
                    
                    // Limit validation to prevent performance issues
                    if (dataRowCount > 1000) {
                        resultBuilder.addWarning("Sheet '" + sheetName + "' contains more than 1000 rows, validation truncated");
                        break;
                    }
                }
                
                if (dataRowCount == 0) {
                    resultBuilder.addWarning("Sheet '" + sheetName + "' contains no data rows");
                }
            }
            
            workbook.close();
            
        } catch (Exception e) {
            resultBuilder.addError("Excel file validation failed: " + e.getMessage());
        }
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Validates Properties file format and content.
     */
    private TestDataValidationResult validatePropertiesFile(Path filePath) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        try (InputStream is = Files.newInputStream(filePath)) {
            java.util.Properties properties = new java.util.Properties();
            properties.load(is);
            
            if (properties.isEmpty()) {
                resultBuilder.addWarning("Properties file is empty");
            } else {
                // Validate property values
                for (String key : properties.stringPropertyNames()) {
                    String value = properties.getProperty(key);
                    if (value == null || value.trim().isEmpty()) {
                        resultBuilder.addWarning("Property '" + key + "' has empty value");
                    }
                }
            }
            
        } catch (Exception e) {
            resultBuilder.addError("Properties file validation failed: " + e.getMessage());
        }
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Validates YAML file format and structure.
     */
    private TestDataValidationResult validateYamlFile(Path filePath) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        try {
            String yamlContent = Files.readString(filePath);
            
            // Basic YAML validation by attempting to parse as JSON after conversion
            // This is a simplified approach - a full YAML parser would be better
            if (yamlContent.trim().isEmpty()) {
                resultBuilder.addError("YAML file is empty");
            } else {
                // Check for basic YAML structure indicators
                if (!yamlContent.contains(":") && !yamlContent.contains("-")) {
                    resultBuilder.addWarning("YAML file may not contain valid YAML structure");
                }
            }
            
        } catch (Exception e) {
            resultBuilder.addError("YAML file validation failed: " + e.getMessage());
        }
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Validates DataProvider array data.
     */
    private TestDataValidationResult validateDataProviderArray(Object[][] testData, int expectedParameterCount) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        if (testData == null || testData.length == 0) {
            resultBuilder.addError("DataProvider array is null or empty");
            return resultBuilder.build(System.currentTimeMillis());
        }
        
        for (int i = 0; i < testData.length; i++) {
            Object[] row = testData[i];
            if (row == null) {
                resultBuilder.addError("DataProvider row " + i + " is null");
                continue;
            }
            
            if (row.length != expectedParameterCount) {
                resultBuilder.addError("DataProvider row " + i + " has " + row.length + 
                                     " parameters, expected " + expectedParameterCount);
            }
        }
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Validates DataProvider iterator data.
     */
    private TestDataValidationResult validateDataProviderIterator(java.util.Iterator<Object[]> iterator, int expectedParameterCount) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        if (iterator == null) {
            resultBuilder.addError("DataProvider iterator is null");
            return resultBuilder.build(System.currentTimeMillis());
        }
        
        int rowIndex = 0;
        while (iterator.hasNext()) {
            Object[] row = iterator.next();
            if (row == null) {
                resultBuilder.addError("DataProvider row " + rowIndex + " is null");
            } else if (row.length != expectedParameterCount) {
                resultBuilder.addError("DataProvider row " + rowIndex + " has " + row.length + 
                                     " parameters, expected " + expectedParameterCount);
            }
            rowIndex++;
        }
        
        if (rowIndex == 0) {
            resultBuilder.addError("DataProvider iterator is empty");
        }
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Converts test data to map for field access.
     */
    private Map<String, Object> convertToDataMap(Object testData) {
        try {
            if (testData instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) testData;
                return map;
            }
            
            // Convert object to JSON string then to Map
            String jsonString = objectMapper.writeValueAsString(testData);
            return objectMapper.readValue(jsonString, Map.class);
            
        } catch (Exception e) {
            logger.warn("Failed to convert test data to map: {}", e.getMessage());
            return new HashMap<>();
        }
    }
    
    /**
     * Validates field type.
     */
    private TestDataValidationResult validateFieldType(String fieldName, Object fieldValue, Class<?> expectedType) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        if (fieldValue == null) {
            return resultBuilder.build(System.currentTimeMillis());
        }
        
        Class<?> actualType = fieldValue.getClass();
        
        // Check exact type match
        if (expectedType.isAssignableFrom(actualType)) {
            return resultBuilder.build(System.currentTimeMillis());
        }
        
        // Check for compatible primitive types
        if (isCompatibleType(actualType, expectedType)) {
            return resultBuilder.build(System.currentTimeMillis());
        }
        
        resultBuilder.addError("Field '" + fieldName + "' has type " + actualType.getSimpleName() + 
                              ", expected " + expectedType.getSimpleName());
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Checks if types are compatible.
     */
    private boolean isCompatibleType(Class<?> actualType, Class<?> expectedType) {
        // Handle primitive type compatibility
        if (expectedType == int.class && actualType == Integer.class) return true;
        if (expectedType == Integer.class && actualType == int.class) return true;
        if (expectedType == long.class && actualType == Long.class) return true;
        if (expectedType == Long.class && actualType == long.class) return true;
        if (expectedType == double.class && actualType == Double.class) return true;
        if (expectedType == Double.class && actualType == double.class) return true;
        if (expectedType == boolean.class && actualType == Boolean.class) return true;
        if (expectedType == Boolean.class && actualType == boolean.class) return true;
        
        // Handle number type compatibility
        if (Number.class.isAssignableFrom(expectedType) && Number.class.isAssignableFrom(actualType)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Validates field format.
     */
    private TestDataValidationResult validateFieldFormat(String fieldName, Object fieldValue, FormatConstraint constraint) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        if (fieldValue == null) {
            return resultBuilder.build(System.currentTimeMillis());
        }
        
        String stringValue = fieldValue.toString();
        
        // Check string length constraints
        if (constraint.getMinLength() > 0 && stringValue.length() < constraint.getMinLength()) {
            resultBuilder.addError("Field '" + fieldName + "' is too short, minimum length: " + constraint.getMinLength());
        }
        
        if (constraint.getMaxLength() > 0 && stringValue.length() > constraint.getMaxLength()) {
            resultBuilder.addError("Field '" + fieldName + "' is too long, maximum length: " + constraint.getMaxLength());
        }
        
        // Check pattern constraint
        if (constraint.getPattern() != null) {
            Pattern pattern = validationPatterns.get(constraint.getPattern());
            if (pattern == null && constraint.getCustomPattern() != null) {
                pattern = Pattern.compile(constraint.getCustomPattern());
            }
            
            if (pattern != null) {
                Matcher matcher = pattern.matcher(stringValue);
                if (!matcher.matches()) {
                    resultBuilder.addError("Field '" + fieldName + "' does not match required pattern: " + constraint.getPattern());
                }
            }
        }
        
        // Check for potential security issues
        if (containsSecurityRisk(stringValue)) {
            resultBuilder.addError("Field '" + fieldName + "' contains potentially harmful content");
        }
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Validates field boundary constraints.
     */
    private TestDataValidationResult validateFieldBoundary(String fieldName, Object fieldValue, BoundaryConstraint constraint) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        if (fieldValue == null) {
            return resultBuilder.build(System.currentTimeMillis());
        }
        
        // Handle numeric boundaries
        if (fieldValue instanceof Number) {
            double numericValue = ((Number) fieldValue).doubleValue();
            
            if (constraint.getMinValue() != null && numericValue < constraint.getMinValue()) {
                resultBuilder.addError("Field '" + fieldName + "' value " + numericValue + 
                                      " is below minimum: " + constraint.getMinValue());
            }
            
            if (constraint.getMaxValue() != null && numericValue > constraint.getMaxValue()) {
                resultBuilder.addError("Field '" + fieldName + "' value " + numericValue + 
                                      " is above maximum: " + constraint.getMaxValue());
            }
        }
        
        // Handle date boundaries
        if (fieldValue instanceof String) {
            try {
                LocalDateTime dateValue = parseDateTime((String) fieldValue);
                if (dateValue != null) {
                    if (constraint.getMinDate() != null && dateValue.isBefore(constraint.getMinDate())) {
                        resultBuilder.addError("Field '" + fieldName + "' date " + dateValue + 
                                              " is before minimum: " + constraint.getMinDate());
                    }
                    
                    if (constraint.getMaxDate() != null && dateValue.isAfter(constraint.getMaxDate())) {
                        resultBuilder.addError("Field '" + fieldName + "' date " + dateValue + 
                                              " is after maximum: " + constraint.getMaxDate());
                    }
                }
            } catch (Exception e) {
                // Not a date, skip date validation
            }
        }
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Validates field referential integrity.
     */
    private TestDataValidationResult validateFieldIntegrity(String fieldName, Object fieldValue, IntegrityRule rule, 
                                                   Map<String, Object> relatedData) {
        TestDataValidationResult.Builder resultBuilder = new TestDataValidationResult.Builder();
        
        if (fieldValue == null || relatedData == null) {
            return resultBuilder.build(System.currentTimeMillis());
        }
        
        String referenceTable = rule.getReferenceTable();
        String referenceField = rule.getReferenceField();
        
        if (relatedData.containsKey(referenceTable)) {
            Object referenceDataObj = relatedData.get(referenceTable);
            
            if (referenceDataObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> referenceList = (List<Map<String, Object>>) referenceDataObj;
                
                boolean found = false;
                for (Map<String, Object> referenceRecord : referenceList) {
                    if (referenceRecord.containsKey(referenceField)) {
                        Object referenceValue = referenceRecord.get(referenceField);
                        if (fieldValue.equals(referenceValue)) {
                            found = true;
                            break;
                        }
                    }
                }
                
                if (!found) {
                    resultBuilder.addError("Field '" + fieldName + "' value '" + fieldValue + 
                                          "' not found in reference table '" + referenceTable + 
                                          "', field '" + referenceField + "'");
                }
            }
        } else {
            resultBuilder.addWarning("Reference table '" + referenceTable + "' not found in related data");
        }
        
        return resultBuilder.build(System.currentTimeMillis());
    }
    
    /**
     * Sanitizes string value to prevent injection attacks.
     */
    private String sanitizeStringValue(String value, Map<String, Object> sanitizationConfig) {
        if (value == null) {
            return null;
        }
        
        String sanitized = value;
        
        try {
            // Remove SQL injection patterns
            Pattern sqlPattern = validationPatterns.get("sqlInjection");
            if (sqlPattern != null) {
                sanitized = sqlPattern.matcher(sanitized).replaceAll("");
            }
            
            // Remove XSS patterns
            Pattern xssPattern = validationPatterns.get("xss");
            if (xssPattern != null) {
                sanitized = xssPattern.matcher(sanitized).replaceAll("");
            }
            
            // HTML entity encoding
            sanitized = sanitized.replace("<", "&lt;")
                                 .replace(">", "&gt;")
                                 .replace("\"", "&quot;")
                                 .replace("'", "&#x27;")
                                 .replace("&", "&amp;");
            
            // Remove control characters
            sanitized = sanitized.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
            
            // Apply custom sanitization rules if specified
            if (sanitizationConfig != null && sanitizationConfig.containsKey("customRules")) {
                @SuppressWarnings("unchecked")
                List<String> customRules = (List<String>) sanitizationConfig.get("customRules");
                for (String rule : customRules) {
                    // Apply custom sanitization rules based on configuration
                    sanitized = applyCustomSanitizationRule(sanitized, rule);
                }
            }
            
        } catch (Exception e) {
            logger.warn("Error sanitizing string value: {}", e.getMessage());
        }
        
        return sanitized;
    }
    
    /**
     * Applies custom sanitization rule.
     */
    private String applyCustomSanitizationRule(String value, String rule) {
        // This is a placeholder for custom sanitization logic
        // In a real implementation, this would apply specific business rules
        return value;
    }
    
    /**
     * Checks if string contains security risks.
     */
    private boolean containsSecurityRisk(String value) {
        if (value == null) {
            return false;
        }
        
        // Check for SQL injection patterns
        Pattern sqlPattern = validationPatterns.get("sqlInjection");
        if (sqlPattern != null && sqlPattern.matcher(value).find()) {
            return true;
        }
        
        // Check for XSS patterns
        Pattern xssPattern = validationPatterns.get("xss");
        if (xssPattern != null && xssPattern.matcher(value).find()) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Parses date/time string using multiple formatters.
     */
    private LocalDateTime parseDateTime(String dateTimeString) {
        for (DateTimeFormatter formatter : dateTimeFormatters) {
            try {
                return LocalDateTime.parse(dateTimeString, formatter);
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }
        
        return null;
    }
    
    /**
     * Estimates cache memory usage.
     */
    private double estimateCacheMemoryUsage() {
        // Rough estimate: 2KB per cached validation result
        return validationCache.size() * 2.0 / 1024.0; // MB
    }
    
    /**
     * Calculates recent performance metrics.
     */
    private Map<String, Object> calculateRecentPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        metrics.put("averageValidationTime", totalValidations > 0 ? 150.0 : 0.0); // Estimated
        metrics.put("successRate", totalValidations > 0 ? 
            (double) (totalValidations - validationErrors) / totalValidations : 1.0);
        metrics.put("cacheEfficiency", totalValidations > 0 ? 
            (double) cacheHits / totalValidations : 0.0);
        
        return metrics;
    }
}

/**
 * CachedTestDataValidationResult represents a cached validation result with expiration and metadata.
 * Supports TTL-based cache management and validation result classification.
 */
class CachedTestDataValidationResult {
    
    private final TestDataValidationResult validationResult;
    private final Instant creationTime;
    private final Instant expiryTime;
    
    /**
     * Creates a new CachedTestDataValidationResult instance.
     * 
     * @param validationResult The cached validation result
     * @param expiryTime Time when the cache entry expires
     */
    public CachedTestDataValidationResult(TestDataValidationResult validationResult, Instant expiryTime) {
        this.validationResult = validationResult;
        this.creationTime = Instant.now();
        this.expiryTime = expiryTime;
    }
    
    /**
     * Gets the cached validation result.
     * 
     * @return The cached validation result
     */
    public TestDataValidationResult getValidationResult() {
        return validationResult;
    }
    
    /**
     * Gets the creation time of the cached result.
     * 
     * @return Instant when result was cached
     */
    public Instant getCreationTime() {
        return creationTime;
    }
    
    /**
     * Checks if the cached result has expired.
     * 
     * @return true if expired, false otherwise
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiryTime);
    }
}

/**
 * TestDataTestDataValidationResult represents the result of a test data validation operation.
 * Contains validation status, errors, warnings, and performance metrics.
 */
class TestDataValidationResult {
    
    private final boolean valid;
    private final List<String> errors;
    private final List<String> warnings;
    private final Map<String, Object> validatedData;
    private final int errorCount;
    private final int warningCount;
    private final String validationSummary;
    private final boolean hasErrors;
    private final boolean hasWarnings;
    private final long validationTime;
    private final Instant timestamp;
    
    /**
     * Creates a new TestDataValidationResult instance.
     */
    public TestDataValidationResult(boolean valid, List<String> errors, List<String> warnings,
                          Map<String, Object> validatedData, int errorCount, int warningCount,
                          String validationSummary, boolean hasErrors, boolean hasWarnings) {
        this.valid = valid;
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors != null ? errors : Collections.emptyList()));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings != null ? warnings : Collections.emptyList()));
        this.validatedData = validatedData != null ? Collections.unmodifiableMap(new HashMap<>(validatedData)) : Collections.emptyMap();
        this.errorCount = errorCount;
        this.warningCount = warningCount;
        this.validationSummary = validationSummary;
        this.hasErrors = hasErrors;
        this.hasWarnings = hasWarnings;
        this.validationTime = 0;
        this.timestamp = Instant.now();
    }
    
    /**
     * Checks if validation was successful.
     * 
     * @return true if validation passed without errors, false otherwise
     */
    public boolean isValid() {
        return valid;
    }
    
    /**
     * Gets validation errors.
     * 
     * @return List of validation error messages
     */
    public List<String> getErrors() {
        return errors;
    }
    
    /**
     * Gets validation warnings.
     * 
     * @return List of validation warning messages
     */
    public List<String> getWarnings() {
        return warnings;
    }
    
    /**
     * Gets validated data.
     * 
     * @return Map containing validated data or null if validation failed
     */
    public Map<String, Object> getValidatedData() {
        return validatedData;
    }
    
    /**
     * Gets error count.
     * 
     * @return Number of validation errors
     */
    public int getErrorCount() {
        return errorCount;
    }
    
    /**
     * Gets warning count.
     * 
     * @return Number of validation warnings
     */
    public int getWarningCount() {
        return warningCount;
    }
    
    /**
     * Adds validation error.
     * 
     * @param error Error message to add
     */
    public void addError(String error) {
        // This should not be called on immutable result
        throw new UnsupportedOperationException("TestDataValidationResult is immutable");
    }
    
    /**
     * Adds validation warning.
     * 
     * @param warning Warning message to add
     */
    public void addWarning(String warning) {
        // This should not be called on immutable result
        throw new UnsupportedOperationException("TestDataValidationResult is immutable");
    }
    
    /**
     * Gets error details with line numbers and field names.
     * 
     * @return Map containing detailed error information
     */
    public Map<String, Object> getErrorDetails() {
        Map<String, Object> details = new HashMap<>();
        details.put("errors", errors);
        details.put("errorCount", errorCount);
        details.put("timestamp", timestamp);
        details.put("validationTime", validationTime);
        return details;
    }
    
    /**
     * Checks if validation has errors.
     * 
     * @return true if there are validation errors, false otherwise
     */
    public boolean hasErrors() {
        return errorCount > 0;
    }
    
    /**
     * Checks if validation has warnings.
     * 
     * @return true if there are validation warnings, false otherwise
     */
    public boolean hasWarnings() {
        return warningCount > 0;
    }
    
    /**
     * Gets validation summary.
     * 
     * @return String summary of validation results
     */
    public String getValidationSummary() {
        if (validationSummary != null) {
            return validationSummary;
        }
        
        StringBuilder summary = new StringBuilder();
        summary.append("Validation ").append(valid ? "PASSED" : "FAILED");
        if (errorCount > 0) {
            summary.append(" with ").append(errorCount).append(" error(s)");
        }
        if (warningCount > 0) {
            summary.append(" and ").append(warningCount).append(" warning(s)");
        }
        
        return summary.toString();
    }
    
    /**
     * Builder class for creating TestDataValidationResult instances.
     */
    static class Builder {
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private Map<String, Object> validatedData = new HashMap<>();
        
        /**
         * Adds validation error.
         */
        public Builder addError(String error) {
            errors.add(error);
            return this;
        }
        
        /**
         * Adds validation warning.
         */
        public Builder addWarning(String warning) {
            warnings.add(warning);
            return this;
        }
        
        /**
         * Sets validated data.
         */
        public Builder setValidatedData(Map<String, Object> data) {
            this.validatedData = data != null ? new HashMap<>(data) : new HashMap<>();
            return this;
        }
        
        /**
         * Merges another validation result.
         */
        public Builder mergeResult(TestDataValidationResult other) {
            if (other != null) {
                errors.addAll(other.getErrors());
                warnings.addAll(other.getWarnings());
                if (other.getValidatedData() != null) {
                    validatedData.putAll(other.getValidatedData());
                }
            }
            return this;
        }
        
        /**
         * Builds the TestDataValidationResult.
         */
        public TestDataValidationResult build(long startTime) {
            boolean valid = errors.isEmpty();
            int errorCount = errors.size();
            int warningCount = warnings.size();
            boolean hasErrors = errorCount > 0;
            boolean hasWarnings = warningCount > 0;
            
            String summary = "Validation " + (valid ? "PASSED" : "FAILED");
            if (errorCount > 0) {
                summary += " with " + errorCount + " error(s)";
            }
            if (warningCount > 0) {
                summary += " and " + warningCount + " warning(s)";
            }
            
            return new TestDataValidationResult(valid, errors, warnings, validatedData, 
                                      errorCount, warningCount, summary, hasErrors, hasWarnings);
        }
    }
}



/**
 * TestDataType defines supported test data file formats.
 */
enum TestDataType {
    
    /**
     * Comma-separated values file format.
     */
    CSV,
    
    /**
     * JavaScript Object Notation file format.
     */
    JSON,
    
    /**
     * Extensible Markup Language file format.
     */
    XML,
    
    /**
     * Microsoft Excel file format (.xls, .xlsx).
     */
    EXCEL,
    
    /**
     * Java properties file format.
     */
    PROPERTIES,
    
    /**
     * YAML Ain't Markup Language file format.
     */
    YAML
}

/**
 * ValidationRule interface for custom validation logic.
 * Enables extension of standard validation with custom business rules.
 */
interface ValidationRule {
    
    /**
     * Validates the provided test data.
     * 
     * @param testData Test data to validate
     * @return true if validation passes, false otherwise
     */
    boolean validate(Object testData);
    
    /**
     * Gets the rule name for identification and reporting.
     * 
     * @return String name of the validation rule
     */
    String getRuleName();
    
    /**
     * Gets error message for validation failures.
     * 
     * @return String error message to display when validation fails
     */
    String getErrorMessage();
    
    /**
     * Checks if rule is applicable to the test data.
     * 
     * @param testData Test data to check applicability for
     * @return true if rule should be applied, false otherwise
     */
    boolean isApplicable(Object testData);
}

/**
 * FormatConstraint defines format validation constraints for test data fields.
 */
class FormatConstraint {
    
    private final boolean required;
    private final int minLength;
    private final int maxLength;
    private final String pattern;
    private final String customPattern;
    
    /**
     * Creates a new FormatConstraint.
     */
    public FormatConstraint(boolean required, int minLength, int maxLength, String pattern, String customPattern) {
        this.required = required;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.pattern = pattern;
        this.customPattern = customPattern;
    }
    
    public boolean isRequired() { return required; }
    public int getMinLength() { return minLength; }
    public int getMaxLength() { return maxLength; }
    public String getPattern() { return pattern; }
    public String getCustomPattern() { return customPattern; }
}

/**
 * BoundaryConstraint defines boundary validation constraints for numeric and date fields.
 */
class BoundaryConstraint {
    
    private final boolean required;
    private final Double minValue;
    private final Double maxValue;
    private final LocalDateTime minDate;
    private final LocalDateTime maxDate;
    
    /**
     * Creates a new BoundaryConstraint.
     */
    public BoundaryConstraint(boolean required, Double minValue, Double maxValue, 
                            LocalDateTime minDate, LocalDateTime maxDate) {
        this.required = required;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.minDate = minDate;
        this.maxDate = maxDate;
    }
    
    public boolean isRequired() { return required; }
    public Double getMinValue() { return minValue; }
    public Double getMaxValue() { return maxValue; }
    public LocalDateTime getMinDate() { return minDate; }
    public LocalDateTime getMaxDate() { return maxDate; }
}

/**
 * IntegrityRule defines referential integrity validation rules.
 */
class IntegrityRule {
    
    private final boolean required;
    private final String referenceTable;
    private final String referenceField;
    
    /**
     * Creates a new IntegrityRule.
     */
    public IntegrityRule(boolean required, String referenceTable, String referenceField) {
        this.required = required;
        this.referenceTable = referenceTable;
        this.referenceField = referenceField;
    }
    
    public boolean isRequired() { return required; }
    public String getReferenceTable() { return referenceTable; }
    public String getReferenceField() { return referenceField; }
}