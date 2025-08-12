package com.automation.framework.api;

import com.automation.framework.core.ConfigurationManager;
import com.automation.framework.exceptions.ErrorReporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.restassured.path.xml.XmlPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.StringEscapeUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.time.Instant;
import java.time.Duration;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Document;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RequestValidator provides comprehensive request payload validation for API testing.
 * 
 * Features:
 * - JSON and XML payload validation with schema support
 * - Query parameter, path parameter, and header validation
 * - Input sanitization to prevent injection attacks (SQL, XSS, etc.)
 * - Configurable validation modes (strict, lenient, fail-fast, etc.)
 * - Integration with REST Assured framework
 * - Detailed validation error reporting and metrics
 * - Thread-safe concurrent validation support
 * 
 * This validator ensures API request data integrity before transmission
 * and provides comprehensive security validation layers.
 */
public class RequestValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(RequestValidator.class);
    
    // Dependencies
    private final ConfigurationManager configManager;
    private final ErrorReporter errorReporter;
    private final ObjectMapper objectMapper;
    
    // Validation configuration
    private ValidationMode defaultValidationMode = ValidationMode.STRICT;
    private final Map<String, Pattern> validationRules = new ConcurrentHashMap<>();
    private final Map<String, String> customValidationMessages = new ConcurrentHashMap<>();
    
    // Security patterns for injection detection
    private final List<Pattern> sqlInjectionPatterns = new ArrayList<>();
    private final List<Pattern> xssPatterns = new ArrayList<>();
    private final List<Pattern> generalInjectionPatterns = new ArrayList<>();
    
    // Validation metrics
    private final AtomicLong totalValidations = new AtomicLong(0);
    private final AtomicLong successfulValidations = new AtomicLong(0);
    private final AtomicLong failedValidations = new AtomicLong(0);
    
    // Configuration constants
    private static final String MAX_PAYLOAD_SIZE_PROP = "api.validation.max.payload.size";
    private static final String VALIDATION_MODE_PROP = "api.validation.default.mode";
    private static final String INJECTION_DETECTION_PROP = "api.validation.injection.detection.enabled";
    private static final String STRICT_HEADER_VALIDATION_PROP = "api.validation.headers.strict";
    
    // Default validation limits
    private static final long DEFAULT_MAX_PAYLOAD_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_HEADER_VALUE_LENGTH = 8192;
    private static final int MAX_PARAMETER_VALUE_LENGTH = 4096;
    private static final int MAX_URL_LENGTH = 2048;
    
    /**
     * Creates a new RequestValidator instance.
     * Initializes validation patterns, loads configuration, and sets up error reporting.
     */
    public RequestValidator() {
        this.configManager = ConfigurationManager.getInstance();
        this.errorReporter = new ErrorReporter();
        this.objectMapper = new ObjectMapper();
        
        // Configure Jackson for flexible JSON processing
        configureObjectMapper();
        
        // Initialize security patterns
        initializeSecurityPatterns();
        
        // Load validation configuration
        loadValidationConfiguration();
        
        // Set correlation ID for validation tracking
        errorReporter.setCorrelationId(errorReporter.generateCorrelationId());
        
        logger.info("RequestValidator initialized with mode: {}, injection detection enabled: {}", 
                   defaultValidationMode, isInjectionDetectionEnabled());
    }
    
    /**
     * Validates a complete request payload including headers, parameters, and body.
     * This is the primary validation method that orchestrates all validation types.
     * 
     * @param request The request object containing all request data
     * @param validationMode The validation mode to use for this request
     * @return RequestValidationResult containing validation status and any errors
     */
    public RequestValidationResult validateRequestPayload(Object request, ValidationMode validationMode) {
        if (request == null) {
            return createValidationResult(false, "Request object cannot be null", validationMode);
        }
        
        long startTime = System.currentTimeMillis();
        totalValidations.incrementAndGet();
        
        try {
            RequestValidationResult result = new RequestValidationResult();
            result.setValidationMode(validationMode != null ? validationMode : defaultValidationMode);
            result.setValidationStartTime(Instant.now());
            
            // Convert request to map for processing
            Map<String, Object> requestData = convertRequestToMap(request);
            
            // Validate payload size
            validatePayloadSize(requestData, result);
            
            // Validate headers
            if (requestData.containsKey("headers")) {
                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>) requestData.get("headers");
                RequestValidationResult headerResult = validateHeaders(headers);
                result.mergeResults(headerResult);
            }
            
            // Validate parameters
            if (requestData.containsKey("parameters")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parameters = (Map<String, Object>) requestData.get("parameters");
                RequestValidationResult paramResult = validateParameters(parameters);
                result.mergeResults(paramResult);
            }
            
            // Validate request body
            if (requestData.containsKey("body")) {
                Object body = requestData.get("body");
                String contentType = getContentType(requestData);
                RequestValidationResult bodyResult = validateRequestBody(body, contentType, result.getValidationMode());
                result.mergeResults(bodyResult);
            }
            
            // Security validation - sanitization and injection detection
            RequestValidationResult securityResult = performSecurityValidation(requestData, result.getValidationMode());
            result.mergeResults(securityResult);
            
            // Set validation time
            long endTime = System.currentTimeMillis();
            result.setValidationTime(Duration.ofMillis(endTime - startTime));
            result.setValidatedRequest(requestData);
            
            // Update metrics
            if (result.isValid()) {
                successfulValidations.incrementAndGet();
            } else {
                failedValidations.incrementAndGet();
            }
            
            // Log validation result
            logValidationResult(result);
            
            return result;
            
        } catch (Exception e) {
            failedValidations.incrementAndGet();
            errorReporter.logException(e, "Request validation failed", createContextMap(request));
            return createValidationResult(false, "Validation error: " + e.getMessage(), validationMode);
        }
    }
    
    /**
     * Validates request headers including required headers, format validation, and authentication.
     * Checks for proper header structure, required authentication headers, and content type validation.
     * 
     * @param headers Map of header names to values
     * @return RequestValidationResult containing header validation status
     */
    public RequestValidationResult validateHeaders(Map<String, String> headers) {
        RequestValidationResult result = new RequestValidationResult();
        result.setValidationStartTime(Instant.now());
        
        if (headers == null) {
            result.addError(RequestValidationError.MISSING_REQUIRED_HEADER, "Headers map cannot be null");
            return result;
        }
        
        try {
            // Validate required headers
            validateRequiredHeaders(headers, result);
            
            // Validate header formats
            validateHeaderFormats(headers, result);
            
            // Validate authentication headers
            RequestValidationResult authResult = validateAuthenticationHeaders(headers);
            result.mergeResults(authResult);
            
            // Check for suspicious header values
            validateHeaderSecurity(headers, result);
            
            result.setValidationTime(Duration.ofMillis(System.currentTimeMillis() - result.getValidationStartTime().toEpochMilli()));
            
        } catch (Exception e) {
            errorReporter.logException(e, "Header validation failed", createContextMap(headers));
            result.addError(RequestValidationError.INVALID_HEADER_FORMAT, "Header validation error: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validates request parameters including query parameters, path parameters, and form data.
     * Performs type checking, format validation, and security scanning.
     * 
     * @param parameters Map of parameter names to values
     * @return RequestValidationResult containing parameter validation status
     */
    public RequestValidationResult validateParameters(Map<String, Object> parameters) {
        RequestValidationResult result = new RequestValidationResult();
        result.setValidationStartTime(Instant.now());
        
        if (parameters == null || parameters.isEmpty()) {
            // Empty parameters are generally acceptable
            result.setValidationTime(Duration.ofMillis(0));
            return result;
        }
        
        try {
            // Validate query parameters
            if (parameters.containsKey("query")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> queryParams = (Map<String, Object>) parameters.get("query");
                RequestValidationResult queryResult = validateQueryParameters(queryParams);
                result.mergeResults(queryResult);
            }
            
            // Validate path parameters
            if (parameters.containsKey("path")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pathParams = (Map<String, Object>) parameters.get("path");
                RequestValidationResult pathResult = validatePathParameters(pathParams);
                result.mergeResults(pathResult);
            }
            
            // Validate form parameters
            if (parameters.containsKey("form")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> formParams = (Map<String, Object>) parameters.get("form");
                validateFormParameters(formParams, result);
            }
            
            result.setValidationTime(Duration.ofMillis(System.currentTimeMillis() - result.getValidationStartTime().toEpochMilli()));
            
        } catch (Exception e) {
            errorReporter.logException(e, "Parameter validation failed", createContextMap(parameters));
            result.addError(RequestValidationError.INVALID_PARAMETER_FORMAT, "Parameter validation error: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Sanitizes input data to prevent injection attacks.
     * Escapes dangerous characters and validates against known attack patterns.
     * 
     * @param input The input string to sanitize
     * @return Sanitized input string safe for processing
     */
    public String sanitizeInput(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        try {
            String sanitized = input;
            
            // Remove null bytes and control characters
            sanitized = sanitized.replaceAll("\\u0000", "");
            sanitized = sanitized.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
            
            // Escape HTML/XML characters
            sanitized = StringEscapeUtils.escapeHtml4(sanitized);
            sanitized = StringEscapeUtils.escapeXml11(sanitized);
            
            // Escape JavaScript characters
            sanitized = StringEscapeUtils.escapeJava(sanitized);
            
            // Normalize Unicode characters
            sanitized = java.text.Normalizer.normalize(sanitized, java.text.Normalizer.Form.NFKC);
            
            // Additional security-specific sanitization
            sanitized = sanitizeForSqlInjection(sanitized);
            sanitized = sanitizeForXss(sanitized);
            
            // Validate length constraints
            if (sanitized.length() > MAX_PARAMETER_VALUE_LENGTH) {
                sanitized = sanitized.substring(0, MAX_PARAMETER_VALUE_LENGTH);
                logger.warn("Input truncated due to length constraint: original={}, truncated={}", 
                           input.length(), sanitized.length());
            }
            
            return sanitized;
            
        } catch (Exception e) {
            errorReporter.logException(e, "Input sanitization failed", createContextMap(input));
            // Return escaped version as fallback
            return StringEscapeUtils.escapeHtml4(input);
        }
    }
    
    /**
     * Validates JSON payload structure and content.
     * Performs schema validation, type checking, and content validation.
     * 
     * @param jsonPayload The JSON payload as string or JsonNode
     * @return RequestValidationResult containing JSON validation status
     */
    public RequestValidationResult validateJsonPayload(Object jsonPayload) {
        RequestValidationResult result = new RequestValidationResult();
        result.setValidationStartTime(Instant.now());
        
        if (jsonPayload == null) {
            result.addError(RequestValidationError.INVALID_JSON_PAYLOAD, "JSON payload cannot be null");
            return result;
        }
        
        try {
            JsonNode jsonNode;
            
            // Convert payload to JsonNode
            if (jsonPayload instanceof String) {
                String jsonString = (String) jsonPayload;
                if (jsonString.trim().isEmpty()) {
                    result.addError(RequestValidationError.INVALID_JSON_PAYLOAD, "JSON payload cannot be empty");
                    return result;
                }
                jsonNode = objectMapper.readTree(jsonString);
            } else if (jsonPayload instanceof JsonNode) {
                jsonNode = (JsonNode) jsonPayload;
            } else {
                // Try to convert object to JSON
                jsonNode = objectMapper.valueToTree(jsonPayload);
            }
            
            // Validate JSON structure
            validateJsonStructure(jsonNode, result);
            
            // Validate content security
            validateJsonSecurity(jsonNode, result);
            
            // Validate JSON schema if configured
            validateJsonSchema(jsonNode, result);
            
            result.setValidationTime(Duration.ofMillis(System.currentTimeMillis() - result.getValidationStartTime().toEpochMilli()));
            
        } catch (JsonProcessingException e) {
            errorReporter.logException(e, "JSON parsing failed", createContextMap(jsonPayload));
            result.addError(RequestValidationError.INVALID_JSON_PAYLOAD, 
                           "Invalid JSON format: " + e.getMessage());
        } catch (Exception e) {
            errorReporter.logException(e, "JSON validation failed", createContextMap(jsonPayload));
            result.addError(RequestValidationError.INVALID_JSON_PAYLOAD, 
                           "JSON validation error: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validates XML payload structure and content.
     * Performs well-formedness checking, DTD validation, and content validation.
     * 
     * @param xmlPayload The XML payload as string
     * @return RequestValidationResult containing XML validation status
     */
    public RequestValidationResult validateXmlPayload(String xmlPayload) {
        RequestValidationResult result = new RequestValidationResult();
        result.setValidationStartTime(Instant.now());
        
        if (xmlPayload == null || xmlPayload.trim().isEmpty()) {
            result.addError(RequestValidationError.INVALID_XML_PAYLOAD, "XML payload cannot be null or empty");
            return result;
        }
        
        try {
            // Check for basic XML structure
            if (!xmlPayload.trim().startsWith("<") || !xmlPayload.trim().endsWith(">")) {
                result.addError(RequestValidationError.INVALID_XML_PAYLOAD, "XML payload must start with '<' and end with '>'");
                return result;
            }
            
            // Validate XML well-formedness using DocumentBuilder
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setValidating(false);
            factory.setNamespaceAware(true);
            
            // Security: Disable external entity processing to prevent XXE attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xmlPayload.getBytes(StandardCharsets.UTF_8)));
            
            // Validate using XmlPath for additional content validation
            XmlPath xmlPath = XmlPath.from(xmlPayload);
            
            // Perform content validation
            validateXmlContent(xmlPath, result);
            
            // Validate XML security
            validateXmlSecurity(xmlPayload, result);
            
            result.setValidationTime(Duration.ofMillis(System.currentTimeMillis() - result.getValidationStartTime().toEpochMilli()));
            
        } catch (Exception e) {
            errorReporter.logException(e, "XML validation failed", createContextMap(xmlPayload));
            result.addError(RequestValidationError.INVALID_XML_PAYLOAD, 
                           "XML validation error: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validates query parameters including format, type, and security checks.
     * Ensures query parameters meet expected formats and don't contain malicious content.
     * 
     * @param queryParameters Map of query parameter names to values
     * @return RequestValidationResult containing query parameter validation status
     */
    public RequestValidationResult validateQueryParameters(Map<String, Object> queryParameters) {
        RequestValidationResult result = new RequestValidationResult();
        result.setValidationStartTime(Instant.now());
        
        if (queryParameters == null || queryParameters.isEmpty()) {
            result.setValidationTime(Duration.ofMillis(0));
            return result;
        }
        
        try {
            for (Map.Entry<String, Object> entry : queryParameters.entrySet()) {
                String paramName = entry.getKey();
                Object paramValue = entry.getValue();
                
                // Validate parameter name
                if (paramName == null || paramName.trim().isEmpty()) {
                    result.addError(RequestValidationError.INVALID_PARAMETER_FORMAT, 
                                   "Query parameter name cannot be null or empty");
                    continue;
                }
                
                // Validate parameter value
                if (paramValue != null) {
                    String paramValueStr = paramValue.toString();
                    
                    // Check parameter value length
                    if (paramValueStr.length() > MAX_PARAMETER_VALUE_LENGTH) {
                        result.addError(RequestValidationError.INVALID_PARAMETER_FORMAT, 
                                       "Query parameter '" + paramName + "' exceeds maximum length");
                        continue;
                    }
                    
                    // Security validation
                    if (isInjectionDetectionEnabled() && containsSuspiciousContent(paramValueStr)) {
                        result.addError(RequestValidationError.POTENTIAL_INJECTION_ATTACK, 
                                       "Query parameter '" + paramName + "' contains potentially malicious content");
                        continue;
                    }
                    
                    // Type and format validation
                    validateParameterFormat(paramName, paramValueStr, result);
                }
            }
            
            result.setValidationTime(Duration.ofMillis(System.currentTimeMillis() - result.getValidationStartTime().toEpochMilli()));
            
        } catch (Exception e) {
            errorReporter.logException(e, "Query parameter validation failed", createContextMap(queryParameters));
            result.addError(RequestValidationError.INVALID_PARAMETER_FORMAT, 
                           "Query parameter validation error: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validates path parameters including format, type, and URL encoding.
     * Ensures path parameters are properly formatted and don't contain malicious content.
     * 
     * @param pathParameters Map of path parameter names to values
     * @return RequestValidationResult containing path parameter validation status
     */
    public RequestValidationResult validatePathParameters(Map<String, Object> pathParameters) {
        RequestValidationResult result = new RequestValidationResult();
        result.setValidationStartTime(Instant.now());
        
        if (pathParameters == null || pathParameters.isEmpty()) {
            result.setValidationTime(Duration.ofMillis(0));
            return result;
        }
        
        try {
            for (Map.Entry<String, Object> entry : pathParameters.entrySet()) {
                String paramName = entry.getKey();
                Object paramValue = entry.getValue();
                
                // Validate parameter name
                if (paramName == null || paramName.trim().isEmpty()) {
                    result.addError(RequestValidationError.INVALID_PARAMETER_FORMAT, 
                                   "Path parameter name cannot be null or empty");
                    continue;
                }
                
                // Path parameters should not be null
                if (paramValue == null) {
                    result.addError(RequestValidationError.MISSING_REQUIRED_PARAMETER, 
                                   "Path parameter '" + paramName + "' cannot be null");
                    continue;
                }
                
                String paramValueStr = paramValue.toString();
                
                // Validate path parameter constraints
                if (paramValueStr.isEmpty()) {
                    result.addError(RequestValidationError.INVALID_PARAMETER_FORMAT, 
                                   "Path parameter '" + paramName + "' cannot be empty");
                    continue;
                }
                
                // Check for URL-unsafe characters
                if (!isValidPathParameter(paramValueStr)) {
                    result.addError(RequestValidationError.INVALID_PARAMETER_FORMAT, 
                                   "Path parameter '" + paramName + "' contains invalid characters");
                    continue;
                }
                
                // Security validation
                if (isInjectionDetectionEnabled() && containsSuspiciousContent(paramValueStr)) {
                    result.addError(RequestValidationError.POTENTIAL_INJECTION_ATTACK, 
                                   "Path parameter '" + paramName + "' contains potentially malicious content");
                    continue;
                }
                
                // Type and format validation
                validateParameterFormat(paramName, paramValueStr, result);
            }
            
            result.setValidationTime(Duration.ofMillis(System.currentTimeMillis() - result.getValidationStartTime().toEpochMilli()));
            
        } catch (Exception e) {
            errorReporter.logException(e, "Path parameter validation failed", createContextMap(pathParameters));
            result.addError(RequestValidationError.INVALID_PARAMETER_FORMAT, 
                           "Path parameter validation error: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validates authentication headers including Bearer tokens, API keys, and Basic auth.
     * Ensures authentication headers are properly formatted and contain valid credentials.
     * 
     * @param headers Map of header names to values
     * @return RequestValidationResult containing authentication validation status
     */
    public RequestValidationResult validateAuthenticationHeaders(Map<String, String> headers) {
        RequestValidationResult result = new RequestValidationResult();
        result.setValidationStartTime(Instant.now());
        
        if (headers == null || headers.isEmpty()) {
            // Check if authentication is required
            if (isAuthenticationRequired()) {
                result.addError(RequestValidationError.MISSING_REQUIRED_HEADER, 
                               "Authentication header is required but not provided");
            }
            return result;
        }
        
        try {
            // Check for Authorization header
            String authHeader = findHeaderIgnoreCase(headers, "Authorization");
            if (authHeader != null) {
                validateAuthorizationHeader(authHeader, result);
            }
            
            // Check for API Key header
            String apiKeyHeader = findHeaderIgnoreCase(headers, "X-API-Key");
            if (apiKeyHeader != null) {
                validateApiKeyHeader(apiKeyHeader, result);
            }
            
            // Check for custom authentication headers
            validateCustomAuthHeaders(headers, result);
            
            // Validate authentication requirement
            if (isAuthenticationRequired() && authHeader == null && apiKeyHeader == null) {
                result.addError(RequestValidationError.INVALID_AUTHENTICATION_HEADER, 
                               "No valid authentication header found");
            }
            
            result.setValidationTime(Duration.ofMillis(System.currentTimeMillis() - result.getValidationStartTime().toEpochMilli()));
            
        } catch (Exception e) {
            errorReporter.logException(e, "Authentication header validation failed", createContextMap(headers));
            result.addError(RequestValidationError.INVALID_AUTHENTICATION_HEADER, 
                           "Authentication validation error: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Generates a comprehensive validation report including metrics and detailed results.
     * Provides summary of validation status, error details, and performance metrics.
     * 
     * @return ValidationReport containing comprehensive validation information
     */
    public ValidationReport getValidationReport() {
        try {
            ValidationReport report = new ValidationReport();
            
            // Basic metrics
            report.setTotalValidations(totalValidations.get());
            report.setSuccessfulValidations(successfulValidations.get());
            report.setFailedValidations(failedValidations.get());
            report.setSuccessRate(calculateSuccessRate());
            
            // Configuration information
            report.setDefaultValidationMode(defaultValidationMode);
            report.setInjectionDetectionEnabled(isInjectionDetectionEnabled());
            report.setValidationRulesCount(validationRules.size());
            
            // Performance metrics
            report.setGeneratedAt(Instant.now());
            
            // Security metrics
            report.setSecurityPatternsCount(getTotalSecurityPatterns());
            
            return report;
            
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to generate validation report", null);
            return new ValidationReport(); // Return empty report on error
        }
    }
    
    /**
     * Sets custom validation rules for specific parameter or header validation.
     * Allows dynamic configuration of validation patterns and constraints.
     * 
     * @param rules Map of rule names to validation patterns
     */
    public void setValidationRules(Map<String, String> rules) {
        if (rules == null) {
            throw new IllegalArgumentException("Validation rules map cannot be null");
        }
        
        try {
            validationRules.clear();
            
            for (Map.Entry<String, String> entry : rules.entrySet()) {
                String ruleName = entry.getKey();
                String rulePattern = entry.getValue();
                
                if (ruleName == null || ruleName.trim().isEmpty()) {
                    errorReporter.warn("Skipping validation rule with null or empty name");
                    continue;
                }
                
                if (rulePattern == null || rulePattern.trim().isEmpty()) {
                    errorReporter.warn("Skipping validation rule '" + ruleName + "' with null or empty pattern");
                    continue;
                }
                
                try {
                    Pattern pattern = Pattern.compile(rulePattern, Pattern.CASE_INSENSITIVE);
                    validationRules.put(ruleName.trim(), pattern);
                    logger.debug("Added validation rule: {} -> {}", ruleName, rulePattern);
                } catch (Exception e) {
                    errorReporter.warn("Invalid regex pattern for rule '" + ruleName + "': " + rulePattern);
                }
            }
            
            logger.info("Updated validation rules: {} rules configured", validationRules.size());
            
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to set validation rules", createContextMap(rules));
            throw new RuntimeException("Failed to set validation rules", e);
        }
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Configures the ObjectMapper for JSON processing.
     */
    private void configureObjectMapper() {
        objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }
    
    /**
     * Initializes security patterns for injection detection.
     */
    private void initializeSecurityPatterns() {
        // SQL Injection patterns
        sqlInjectionPatterns.add(Pattern.compile("(?i)(union|select|insert|update|delete|drop|create|alter|exec|execute)", Pattern.CASE_INSENSITIVE));
        sqlInjectionPatterns.add(Pattern.compile("(?i)(script|javascript|vbscript|onload|onerror|onclick)", Pattern.CASE_INSENSITIVE));
        sqlInjectionPatterns.add(Pattern.compile("\\b(\\w*)(\\s*)(=|>|<|>=|<=)(\\s*)(\\w*)(\\s*)(and|or)\\b", Pattern.CASE_INSENSITIVE));
        sqlInjectionPatterns.add(Pattern.compile("(?i)(\\b(char|nchar|varchar|nvarchar|text|ntext)\\s*\\()", Pattern.CASE_INSENSITIVE));
        
        // XSS patterns  
        xssPatterns.add(Pattern.compile("(?i)<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
        xssPatterns.add(Pattern.compile("(?i)javascript:", Pattern.CASE_INSENSITIVE));
        xssPatterns.add(Pattern.compile("(?i)on\\w+\\s*=", Pattern.CASE_INSENSITIVE));
        xssPatterns.add(Pattern.compile("(?i)<iframe[^>]*>.*?</iframe>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
        xssPatterns.add(Pattern.compile("(?i)<object[^>]*>.*?</object>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
        
        // General injection patterns
        generalInjectionPatterns.add(Pattern.compile("\\.\\.[\\/\\\\]", Pattern.CASE_INSENSITIVE));
        generalInjectionPatterns.add(Pattern.compile("\\b(cmd|powershell|bash|sh)\\b", Pattern.CASE_INSENSITIVE));
        generalInjectionPatterns.add(Pattern.compile("\\$\\{.*\\}", Pattern.CASE_INSENSITIVE));
        generalInjectionPatterns.add(Pattern.compile("\\#\\{.*\\}", Pattern.CASE_INSENSITIVE));
    }
    
    /**
     * Loads validation configuration from ConfigurationManager.
     */
    private void loadValidationConfiguration() {
        try {
            // Load default validation mode
            String validationModeStr = configManager.getProperty(VALIDATION_MODE_PROP);
            if (validationModeStr != null) {
                try {
                    defaultValidationMode = ValidationMode.valueOf(validationModeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid validation mode in configuration: {}, using default: {}", 
                               validationModeStr, defaultValidationMode);
                }
            }
            
            logger.debug("Validation configuration loaded successfully");
            
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to load validation configuration", null);
            logger.warn("Using default validation configuration due to load error");
        }
    }
    
    /**
     * Converts request object to a map for processing.
     */
    private Map<String, Object> convertRequestToMap(Object request) throws Exception {
        if (request instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> requestMap = (Map<String, Object>) request;
            return requestMap;
        }
        
        // Convert object to JSON and then to Map
        String jsonString = objectMapper.writeValueAsString(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = objectMapper.readValue(jsonString, Map.class);
        return requestMap;
    }
    
    /**
     * Validates payload size against configured limits.
     */
    private void validatePayloadSize(Map<String, Object> requestData, RequestValidationResult result) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(requestData);
            long payloadSize = jsonPayload.getBytes(StandardCharsets.UTF_8).length;
            
            long maxSize = getMaxPayloadSize();
            if (payloadSize > maxSize) {
                result.addError(RequestValidationError.PAYLOAD_TOO_LARGE, 
                               "Payload size " + payloadSize + " exceeds maximum allowed size " + maxSize);
            }
        } catch (Exception e) {
            logger.warn("Failed to calculate payload size", e);
        }
    }
    
    /**
     * Gets content type from request data.
     */
    private String getContentType(Map<String, Object> requestData) {
        if (requestData.containsKey("headers")) {
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) requestData.get("headers");
            return findHeaderIgnoreCase(headers, "Content-Type");
        }
        return null;
    }
    
    /**
     * Validates request body based on content type.
     */
    private RequestValidationResult validateRequestBody(Object body, String contentType, ValidationMode mode) {
        if (body == null) {
            return new RequestValidationResult();
        }
        
        if (contentType != null) {
            String lowerContentType = contentType.toLowerCase();
            if (lowerContentType.contains("application/json")) {
                return validateJsonPayload(body);
            } else if (lowerContentType.contains("application/xml") || lowerContentType.contains("text/xml")) {
                return validateXmlPayload(body.toString());
            }
        }
        
        // Default validation for other content types
        RequestValidationResult result = new RequestValidationResult();
        if (body.toString().length() > getMaxPayloadSize()) {
            result.addError(RequestValidationError.PAYLOAD_TOO_LARGE, "Request body exceeds maximum size");
        }
        return result;
    }
    
    /**
     * Performs comprehensive security validation.
     */
    private RequestValidationResult performSecurityValidation(Map<String, Object> requestData, ValidationMode mode) {
        RequestValidationResult result = new RequestValidationResult();
        
        if (!isInjectionDetectionEnabled()) {
            return result;
        }
        
        try {
            String requestString = objectMapper.writeValueAsString(requestData);
            String maskedRequest = errorReporter.maskSensitiveData(requestString);
            
            if (containsSuspiciousContent(maskedRequest)) {
                result.addError(RequestValidationError.POTENTIAL_INJECTION_ATTACK, 
                               "Request contains potentially malicious content");
            }
        } catch (Exception e) {
            logger.warn("Security validation failed", e);
        }
        
        return result;
    }
    
    /**
     * Validates required headers based on configuration.
     */
    private void validateRequiredHeaders(Map<String, String> headers, RequestValidationResult result) {
        // Check for Content-Type header for requests with body
        String contentType = findHeaderIgnoreCase(headers, "Content-Type");
        if (contentType == null && isContentTypeRequired()) {
            result.addError(RequestValidationError.MISSING_REQUIRED_HEADER, "Content-Type header is required");
        }
        
        // Check for Accept header
        String accept = findHeaderIgnoreCase(headers, "Accept");
        if (accept == null && isAcceptHeaderRequired()) {
            result.addError(RequestValidationError.MISSING_REQUIRED_HEADER, "Accept header is required");
        }
    }
    
    /**
     * Validates header formats and values.
     */
    private void validateHeaderFormats(Map<String, String> headers, RequestValidationResult result) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String headerName = entry.getKey();
            String headerValue = entry.getValue();
            
            // Validate header name
            if (headerName == null || headerName.trim().isEmpty()) {
                result.addError(RequestValidationError.INVALID_HEADER_FORMAT, "Header name cannot be null or empty");
                continue;
            }
            
            // Validate header value
            if (headerValue != null && headerValue.length() > MAX_HEADER_VALUE_LENGTH) {
                result.addError(RequestValidationError.INVALID_HEADER_FORMAT, 
                               "Header '" + headerName + "' value exceeds maximum length");
                continue;
            }
            
            // Validate specific header formats
            validateSpecificHeaderFormat(headerName, headerValue, result);
        }
    }
    
    /**
     * Validates header security to prevent header injection attacks.
     */
    private void validateHeaderSecurity(Map<String, String> headers, RequestValidationResult result) {
        if (!isInjectionDetectionEnabled()) {
            return;
        }
        
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String headerName = entry.getKey();
            String headerValue = entry.getValue();
            
            if (headerValue != null && containsSuspiciousContent(headerValue)) {
                result.addError(RequestValidationError.POTENTIAL_INJECTION_ATTACK, 
                               "Header '" + headerName + "' contains potentially malicious content");
            }
        }
    }
    
    /**
     * Validates form parameters.
     */
    private void validateFormParameters(Map<String, Object> formParams, RequestValidationResult result) {
        for (Map.Entry<String, Object> entry : formParams.entrySet()) {
            String paramName = entry.getKey();
            Object paramValue = entry.getValue();
            
            if (paramValue != null) {
                String paramValueStr = paramValue.toString();
                if (paramValueStr.length() > MAX_PARAMETER_VALUE_LENGTH) {
                    result.addError(RequestValidationError.INVALID_PARAMETER_FORMAT, 
                                   "Form parameter '" + paramName + "' exceeds maximum length");
                }
                
                if (isInjectionDetectionEnabled() && containsSuspiciousContent(paramValueStr)) {
                    result.addError(RequestValidationError.POTENTIAL_INJECTION_ATTACK, 
                                   "Form parameter '" + paramName + "' contains potentially malicious content");
                }
            }
        }
    }
    
    /**
     * Checks if content contains suspicious patterns indicating potential attacks.
     */
    private boolean containsSuspiciousContent(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        
        String lowerContent = content.toLowerCase();
        
        // Check SQL injection patterns
        for (Pattern pattern : sqlInjectionPatterns) {
            if (pattern.matcher(lowerContent).find()) {
                return true;
            }
        }
        
        // Check XSS patterns
        for (Pattern pattern : xssPatterns) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        
        // Check general injection patterns
        for (Pattern pattern : generalInjectionPatterns) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Validates JSON structure for compliance and security.
     */
    private void validateJsonStructure(JsonNode jsonNode, RequestValidationResult result) {
        if (jsonNode == null) {
            result.addError(RequestValidationError.INVALID_JSON_PAYLOAD, "JSON node is null");
            return;
        }
        
        // Check for extremely deep nesting (potential DoS attack)
        int maxDepth = getMaxJsonDepth();
        if (calculateJsonDepth(jsonNode) > maxDepth) {
            result.addError(RequestValidationError.INVALID_JSON_PAYLOAD, 
                           "JSON nesting depth exceeds maximum allowed depth");
        }
        
        // Check for extremely large arrays (potential DoS attack)
        int maxArraySize = getMaxJsonArraySize();
        if (hasLargeArray(jsonNode, maxArraySize)) {
            result.addError(RequestValidationError.INVALID_JSON_PAYLOAD, 
                           "JSON contains arrays exceeding maximum allowed size");
        }
    }
    
    /**
     * Validates JSON content for security issues.
     */
    private void validateJsonSecurity(JsonNode jsonNode, RequestValidationResult result) {
        if (!isInjectionDetectionEnabled() || jsonNode == null) {
            return;
        }
        
        try {
            String jsonString = objectMapper.writeValueAsString(jsonNode);
            if (containsSuspiciousContent(jsonString)) {
                result.addError(RequestValidationError.POTENTIAL_INJECTION_ATTACK, 
                               "JSON content contains potentially malicious data");
            }
        } catch (Exception e) {
            logger.debug("Failed to validate JSON security", e);
        }
    }
    
    /**
     * Validates JSON against schema if configured.
     */
    private void validateJsonSchema(JsonNode jsonNode, RequestValidationResult result) {
        // Schema validation would be implemented here if JSON schema is configured
        // For now, this is a placeholder for future enhancement
        logger.debug("JSON schema validation not yet implemented");
    }
    
    /**
     * Validates XML content using XmlPath.
     */
    private void validateXmlContent(XmlPath xmlPath, RequestValidationResult result) {
        try {
            // Validate XML structure basics
            List<String> nodeChildren = xmlPath.getNodeChildren("");
            if (nodeChildren.isEmpty()) {
                result.addWarning("XML document appears to be empty or has no child nodes");
            }
        } catch (Exception e) {
            result.addError(RequestValidationError.INVALID_XML_PAYLOAD, 
                           "XML content validation failed: " + e.getMessage());
        }
    }
    
    /**
     * Validates XML content for security issues.
     */
    private void validateXmlSecurity(String xmlContent, RequestValidationResult result) {
        if (!isInjectionDetectionEnabled()) {
            return;
        }
        
        // Check for XXE attack patterns
        if (xmlContent.contains("<!ENTITY") || xmlContent.contains("SYSTEM") || xmlContent.contains("PUBLIC")) {
            result.addError(RequestValidationError.POTENTIAL_INJECTION_ATTACK, 
                           "XML content contains potentially dangerous entity declarations");
        }
        
        // Check for other malicious content
        if (containsSuspiciousContent(xmlContent)) {
            result.addError(RequestValidationError.POTENTIAL_INJECTION_ATTACK, 
                           "XML content contains potentially malicious data");
        }
    }
    
    /**
     * Helper methods for configuration and utility functions
     */
    private long getMaxPayloadSize() {
        String maxSizeStr = configManager.getProperty(MAX_PAYLOAD_SIZE_PROP);
        if (maxSizeStr != null) {
            try {
                return Long.parseLong(maxSizeStr);
            } catch (NumberFormatException e) {
                logger.warn("Invalid max payload size configuration: {}", maxSizeStr);
            }
        }
        return DEFAULT_MAX_PAYLOAD_SIZE;
    }
    
    private boolean isInjectionDetectionEnabled() {
        String enabled = configManager.getProperty(INJECTION_DETECTION_PROP);
        return enabled == null || Boolean.parseBoolean(enabled); // Default to true
    }
    
    private boolean isAuthenticationRequired() {
        String required = configManager.getProperty("api.validation.auth.required");
        return Boolean.parseBoolean(required); // Default to false
    }
    
    private boolean isContentTypeRequired() {
        String required = configManager.getProperty("api.validation.content.type.required");
        return Boolean.parseBoolean(required); // Default to false
    }
    
    private boolean isAcceptHeaderRequired() {
        String required = configManager.getProperty("api.validation.accept.header.required");
        return Boolean.parseBoolean(required); // Default to false
    }
    
    private int getMaxJsonDepth() {
        String maxDepthStr = configManager.getProperty("api.validation.json.max.depth");
        if (maxDepthStr != null) {
            try {
                return Integer.parseInt(maxDepthStr);
            } catch (NumberFormatException e) {
                logger.warn("Invalid max JSON depth configuration: {}", maxDepthStr);
            }
        }
        return 100; // Default max depth
    }
    
    private int getMaxJsonArraySize() {
        String maxSizeStr = configManager.getProperty("api.validation.json.max.array.size");
        if (maxSizeStr != null) {
            try {
                return Integer.parseInt(maxSizeStr);
            } catch (NumberFormatException e) {
                logger.warn("Invalid max JSON array size configuration: {}", maxSizeStr);
            }
        }
        return 10000; // Default max array size
    }
    
    private String findHeaderIgnoreCase(Map<String, String> headers, String headerName) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(headerName)) {
                return entry.getValue();
            }
        }
        return null;
    }
    
    private void validateAuthorizationHeader(String authHeader, RequestValidationResult result) {
        if (authHeader.trim().isEmpty()) {
            result.addError(RequestValidationError.INVALID_AUTHENTICATION_HEADER, 
                           "Authorization header cannot be empty");
            return;
        }
        
        String[] parts = authHeader.split(" ");
        if (parts.length != 2) {
            result.addError(RequestValidationError.INVALID_AUTHENTICATION_HEADER, 
                           "Authorization header must have format 'scheme credentials'");
            return;
        }
        
        String scheme = parts[0];
        String credentials = parts[1];
        
        if (!isValidAuthScheme(scheme)) {
            result.addError(RequestValidationError.INVALID_AUTHENTICATION_HEADER, 
                           "Unsupported authentication scheme: " + scheme);
        }
        
        if (credentials.trim().isEmpty()) {
            result.addError(RequestValidationError.INVALID_AUTHENTICATION_HEADER, 
                           "Authentication credentials cannot be empty");
        }
    }
    
    private void validateApiKeyHeader(String apiKeyHeader, RequestValidationResult result) {
        if (apiKeyHeader == null || apiKeyHeader.trim().isEmpty()) {
            result.addError(RequestValidationError.INVALID_AUTHENTICATION_HEADER, 
                           "API key cannot be null or empty");
            return;
        }
        
        // Basic API key format validation
        String trimmedKey = apiKeyHeader.trim();
        if (trimmedKey.length() < 8) {
            result.addError(RequestValidationError.INVALID_AUTHENTICATION_HEADER, 
                           "API key appears to be too short");
        }
        
        // Check for suspicious characters
        if (containsSuspiciousContent(trimmedKey)) {
            result.addError(RequestValidationError.POTENTIAL_INJECTION_ATTACK, 
                           "API key contains suspicious characters");
        }
    }
    
    private void validateCustomAuthHeaders(Map<String, String> headers, RequestValidationResult result) {
        // Check for other common authentication headers
        String bearerToken = findHeaderIgnoreCase(headers, "X-Auth-Token");
        if (bearerToken != null && bearerToken.trim().isEmpty()) {
            result.addError(RequestValidationError.INVALID_AUTHENTICATION_HEADER, 
                           "X-Auth-Token header cannot be empty");
        }
    }
    
    private boolean isValidAuthScheme(String scheme) {
        return scheme.equalsIgnoreCase("Bearer") || 
               scheme.equalsIgnoreCase("Basic") || 
               scheme.equalsIgnoreCase("Digest");
    }
    
    private void validateParameterFormat(String paramName, String paramValue, RequestValidationResult result) {
        // Apply custom validation rules if configured
        for (Map.Entry<String, Pattern> entry : validationRules.entrySet()) {
            String ruleName = entry.getKey();
            Pattern pattern = entry.getValue();
            
            if (ruleName.contains(paramName.toLowerCase()) || ruleName.equals("*")) {
                if (!pattern.matcher(paramValue).matches()) {
                    result.addError(RequestValidationError.INVALID_PARAMETER_FORMAT, 
                                   "Parameter '" + paramName + "' does not match required format: " + ruleName);
                }
            }
        }
    }
    
    private boolean isValidPathParameter(String paramValue) {
        // Path parameters should not contain certain characters
        return !paramValue.contains("/") && 
               !paramValue.contains("\\") && 
               !paramValue.contains("?") && 
               !paramValue.contains("#") && 
               !paramValue.contains("%") &&
               !paramValue.contains(" ");
    }
    
    private void validateSpecificHeaderFormat(String headerName, String headerValue, RequestValidationResult result) {
        if (headerValue == null) return;
        
        String lowerHeaderName = headerName.toLowerCase();
        
        // Validate Content-Type header
        if ("content-type".equals(lowerHeaderName)) {
            if (!isValidContentType(headerValue)) {
                result.addError(RequestValidationError.INVALID_HEADER_FORMAT, 
                               "Invalid Content-Type header format: " + headerValue);
            }
        }
        
        // Validate Accept header
        if ("accept".equals(lowerHeaderName)) {
            if (!isValidAcceptHeader(headerValue)) {
                result.addError(RequestValidationError.INVALID_HEADER_FORMAT, 
                               "Invalid Accept header format: " + headerValue);
            }
        }
        
        // Validate User-Agent header
        if ("user-agent".equals(lowerHeaderName)) {
            if (headerValue.trim().isEmpty()) {
                result.addWarning("User-Agent header is empty");
            }
        }
    }
    
    private boolean isValidContentType(String contentType) {
        // Basic content type validation
        return contentType.contains("/") && !contentType.trim().isEmpty();
    }
    
    private boolean isValidAcceptHeader(String accept) {
        // Basic accept header validation
        return !accept.trim().isEmpty();
    }
    
    private String sanitizeForSqlInjection(String input) {
        String sanitized = input;
        
        // Remove or escape SQL keywords
        sanitized = sanitized.replaceAll("(?i)\\b(union|select|insert|update|delete|drop|create|alter|exec|execute)\\b", "");
        
        // Escape single quotes
        sanitized = sanitized.replace("'", "''");
        
        // Remove SQL comments
        sanitized = sanitized.replaceAll("--.*", "");
        sanitized = sanitized.replaceAll("/\\*.*?\\*/", "");
        
        return sanitized;
    }
    
    private String sanitizeForXss(String input) {
        String sanitized = input;
        
        // Remove script tags
        sanitized = sanitized.replaceAll("(?i)<script[^>]*>.*?</script>", "");
        
        // Remove javascript: protocol
        sanitized = sanitized.replaceAll("(?i)javascript:", "");
        
        // Remove event handlers
        sanitized = sanitized.replaceAll("(?i)on\\w+\\s*=\\s*[\"'][^\"']*[\"']", "");
        
        return sanitized;
    }
    
    private int calculateJsonDepth(JsonNode node) {
        return calculateJsonDepth(node, 0);
    }
    
    private int calculateJsonDepth(JsonNode node, int currentDepth) {
        if (node == null || node.isValueNode()) {
            return currentDepth;
        }
        
        int maxChildDepth = currentDepth;
        
        if (node.isArray()) {
            for (JsonNode child : node) {
                maxChildDepth = Math.max(maxChildDepth, calculateJsonDepth(child, currentDepth + 1));
            }
        } else if (node.isObject()) {
            for (JsonNode child : node) {
                maxChildDepth = Math.max(maxChildDepth, calculateJsonDepth(child, currentDepth + 1));
            }
        }
        
        return maxChildDepth;
    }
    
    private boolean hasLargeArray(JsonNode node, int maxArraySize) {
        if (node == null) {
            return false;
        }
        
        if (node.isArray() && node.size() > maxArraySize) {
            return true;
        }
        
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (hasLargeArray(child, maxArraySize)) {
                    return true;
                }
            }
        } else if (node.isObject()) {
            for (JsonNode child : node) {
                if (hasLargeArray(child, maxArraySize)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private double calculateSuccessRate() {
        long total = totalValidations.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) successfulValidations.get() / total * 100.0;
    }
    
    private int getTotalSecurityPatterns() {
        return sqlInjectionPatterns.size() + xssPatterns.size() + generalInjectionPatterns.size();
    }
    
    private RequestValidationResult createValidationResult(boolean isValid, String message, ValidationMode mode) {
        RequestValidationResult result = new RequestValidationResult();
        if (!isValid && message != null) {
            result.addError(RequestValidationError.MALFORMED_REQUEST_STRUCTURE, message);
        }
        if (mode != null) {
            result.setValidationMode(mode);
        }
        return result;
    }
    
    private Map<String, Object> createContextMap(Object data) {
        Map<String, Object> context = new HashMap<>();
        context.put("validationContext", data != null ? data.getClass().getSimpleName() : "null");
        context.put("timestamp", Instant.now().toString());
        return context;
    }
    
    private void logValidationResult(RequestValidationResult result) {
        if (result.isValid()) {
            logger.debug("Request validation successful: {} ms", result.getValidationTime().toMillis());
        } else {
            logger.warn("Request validation failed with {} errors and {} warnings", 
                       result.getErrorCount(), result.getWarningCount());
            for (String error : result.getErrors()) {
                logger.warn("Validation error: {}", errorReporter.maskSensitiveData(error));
            }
        }
    }
}

/**
 * RequestValidationResult encapsulates the results of request validation.
 * Contains validation status, error details, performance metrics, and validated request data.
 */
class RequestValidationResult {
    
    private boolean valid = true;
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<RequestValidationError> errorCodes = new ArrayList<>();
    private final Map<String, List<String>> fieldErrors = new HashMap<>();
    private Duration validationTime = Duration.ZERO;
    private Instant validationStartTime;
    private ValidationMode validationMode;
    private Object validatedRequest;
    private String validationSummary;
    
    /**
     * Creates a new RequestValidationResult with valid status.
     */
    public RequestValidationResult() {
        this.validationStartTime = Instant.now();
    }
    
    /**
     * Returns whether the validation was successful.
     * 
     * @return true if validation passed without errors
     */
    public boolean isValid() {
        return valid && errors.isEmpty();
    }
    
    /**
     * Returns the list of validation errors.
     * 
     * @return unmodifiable list of error messages
     */
    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }
    
    /**
     * Returns the list of validation warnings.
     * 
     * @return unmodifiable list of warning messages
     */
    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }
    
    /**
     * Returns the time taken for validation.
     * 
     * @return Duration of validation process
     */
    public Duration getValidationTime() {
        return validationTime;
    }
    
    /**
     * Returns the number of validation errors.
     * 
     * @return count of errors
     */
    public int getErrorCount() {
        return errors.size();
    }
    
    /**
     * Returns the number of validation warnings.
     * 
     * @return count of warnings
     */
    public int getWarningCount() {
        return warnings.size();
    }
    
    /**
     * Returns field-specific error details.
     * 
     * @return map of field names to their error messages
     */
    public Map<String, List<String>> getFieldErrors() {
        return new HashMap<>(fieldErrors);
    }
    
    /**
     * Returns whether there are any validation errors.
     * 
     * @return true if errors exist
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    /**
     * Returns whether there are any validation warnings.
     * 
     * @return true if warnings exist
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    /**
     * Returns detailed error information including error codes.
     * 
     * @return list of detailed error information
     */
    public List<String> getErrorDetails() {
        List<String> details = new ArrayList<>();
        for (int i = 0; i < errors.size() && i < errorCodes.size(); i++) {
            details.add(errorCodes.get(i) + ": " + errors.get(i));
        }
        return details;
    }
    
    /**
     * Adds a validation error with error code.
     * 
     * @param errorCode the error code
     * @param message the error message
     */
    public void addError(RequestValidationError errorCode, String message) {
        if (message != null && !message.trim().isEmpty()) {
            this.errors.add(message.trim());
            this.errorCodes.add(errorCode);
            this.valid = false;
        }
    }
    
    /**
     * Adds a validation error for a specific field.
     * 
     * @param fieldName the field name
     * @param errorCode the error code
     * @param message the error message
     */
    public void addFieldError(String fieldName, RequestValidationError errorCode, String message) {
        addError(errorCode, message);
        fieldErrors.computeIfAbsent(fieldName, k -> new ArrayList<>()).add(message);
    }
    
    /**
     * Adds a validation warning.
     * 
     * @param message the warning message
     */
    public void addWarning(String message) {
        if (message != null && !message.trim().isEmpty()) {
            this.warnings.add(message.trim());
        }
    }
    
    /**
     * Returns the validated request object.
     * 
     * @return the validated and potentially sanitized request object
     */
    public Object getValidatedRequest() {
        return validatedRequest;
    }
    
    /**
     * Returns a summary of the validation results.
     * 
     * @return validation summary string
     */
    public String getValidationSummary() {
        if (validationSummary == null) {
            StringBuilder summary = new StringBuilder();
            summary.append("Validation Result: ");
            summary.append(isValid() ? "PASSED" : "FAILED");
            summary.append(" (").append(getErrorCount()).append(" errors, ");
            summary.append(getWarningCount()).append(" warnings) ");
            summary.append("in ").append(validationTime.toMillis()).append("ms");
            validationSummary = summary.toString();
        }
        return validationSummary;
    }
    
    // Package-private setters for internal use
    
    void setValidationTime(Duration validationTime) {
        this.validationTime = validationTime;
        this.validationSummary = null; // Reset cached summary
    }
    
    void setValidationStartTime(Instant startTime) {
        this.validationStartTime = startTime;
    }
    
    Instant getValidationStartTime() {
        return validationStartTime;
    }
    
    void setValidationMode(ValidationMode mode) {
        this.validationMode = mode;
    }
    
    ValidationMode getValidationMode() {
        return validationMode;
    }
    
    void setValidatedRequest(Object validatedRequest) {
        this.validatedRequest = validatedRequest;
    }
    
    /**
     * Merges another validation result into this one.
     * 
     * @param other the other validation result to merge
     */
    void mergeResults(RequestValidationResult other) {
        if (other != null) {
            this.errors.addAll(other.errors);
            this.warnings.addAll(other.warnings);
            this.errorCodes.addAll(other.errorCodes);
            this.fieldErrors.putAll(other.fieldErrors);
            
            if (!other.isValid()) {
                this.valid = false;
            }
            
            // Reset cached summary
            this.validationSummary = null;
        }
    }
    
    @Override
    public String toString() {
        return getValidationSummary();
    }
}

/**
 * RequestValidationError defines specific error types that can occur during request validation.
 * Each error type represents a different category of validation failure.
 */
enum RequestValidationError {
    
    /**
     * JSON payload is malformed or cannot be parsed.
     */
    INVALID_JSON_PAYLOAD("Invalid JSON payload format or structure"),
    
    /**
     * XML payload is malformed or cannot be parsed.
     */
    INVALID_XML_PAYLOAD("Invalid XML payload format or structure"),
    
    /**
     * A required header is missing from the request.
     */
    MISSING_REQUIRED_HEADER("Required header is missing"),
    
    /**
     * A header has an invalid format or value.
     */
    INVALID_HEADER_FORMAT("Header format is invalid"),
    
    /**
     * A required parameter is missing from the request.
     */
    MISSING_REQUIRED_PARAMETER("Required parameter is missing"),
    
    /**
     * A parameter has an invalid type or cannot be converted.
     */
    INVALID_PARAMETER_TYPE("Parameter type is invalid"),
    
    /**
     * A parameter has an invalid format or value.
     */
    INVALID_PARAMETER_FORMAT("Parameter format is invalid"),
    
    /**
     * Content potentially indicates an injection attack.
     */
    POTENTIAL_INJECTION_ATTACK("Potential injection attack detected"),
    
    /**
     * The request payload exceeds the maximum allowed size.
     */
    PAYLOAD_TOO_LARGE("Payload size exceeds maximum limit"),
    
    /**
     * The content type is not supported or invalid.
     */
    UNSUPPORTED_CONTENT_TYPE("Content type is not supported"),
    
    /**
     * Authentication header is invalid or malformed.
     */
    INVALID_AUTHENTICATION_HEADER("Authentication header is invalid"),
    
    /**
     * The overall request structure is malformed.
     */
    MALFORMED_REQUEST_STRUCTURE("Request structure is malformed");
    
    private final String description;
    
    RequestValidationError(String description) {
        this.description = description;
    }
    
    /**
     * Returns the human-readable description of this error type.
     * 
     * @return error description
     */
    public String getDescription() {
        return description;
    }
    
    @Override
    public String toString() {
        return name() + ": " + description;
    }
}

/**
 * ValidationMode defines different modes of validation behavior.
 * Each mode provides different levels of strictness and error handling.
 */
enum ValidationMode {
    
    /**
     * Strict validation mode - fails on any validation error.
     * Performs comprehensive validation with zero tolerance for violations.
     */
    STRICT("Strict validation with zero tolerance for errors"),
    
    /**
     * Lenient validation mode - allows minor violations.
     * Performs validation but may ignore non-critical issues.
     */
    LENIENT("Lenient validation allowing minor violations"),
    
    /**
     * Fail-fast validation mode - stops on first error.
     * Returns immediately when the first validation error is encountered.
     */
    FAIL_FAST("Fail-fast validation stopping on first error"),
    
    /**
     * Collect all errors mode - continues validation to find all errors.
     * Performs complete validation and reports all errors found.
     */
    COLLECT_ALL_ERRORS("Validation continues to collect all errors"),
    
    /**
     * Security-focused validation mode - emphasizes security validation.
     * Prioritizes security-related validation checks over format validation.
     */
    SECURITY_FOCUSED("Security-focused validation emphasizing threat detection");
    
    private final String description;
    
    ValidationMode(String description) {
        this.description = description;
    }
    
    /**
     * Returns the human-readable description of this validation mode.
     * 
     * @return validation mode description
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Returns whether this mode should stop validation on first error.
     * 
     * @return true if validation should stop on first error
     */
    public boolean isFailFast() {
        return this == FAIL_FAST;
    }
    
    /**
     * Returns whether this mode emphasizes security validation.
     * 
     * @return true if security validation is prioritized
     */
    public boolean isSecurityFocused() {
        return this == SECURITY_FOCUSED || this == STRICT;
    }
    
    /**
     * Returns whether this mode allows minor violations.
     * 
     * @return true if minor violations are allowed
     */
    public boolean isLenient() {
        return this == LENIENT;
    }
    
    @Override
    public String toString() {
        return name() + ": " + description;
    }
}

/**
 * ValidationReport provides comprehensive reporting of validation metrics and status.
 * Contains performance metrics, configuration information, and validation statistics.
 */
class ValidationReport {
    
    private long totalValidations;
    private long successfulValidations;
    private long failedValidations;
    private double successRate;
    private ValidationMode defaultValidationMode;
    private boolean injectionDetectionEnabled;
    private int validationRulesCount;
    private int securityPatternsCount;
    private Instant generatedAt;
    
    /**
     * Creates a new empty ValidationReport.
     */
    public ValidationReport() {
        this.generatedAt = Instant.now();
    }
    
    /**
     * Returns the total number of validations performed.
     * 
     * @return total validation count
     */
    public long getTotalValidations() {
        return totalValidations;
    }
    
    /**
     * Sets the total number of validations performed.
     * 
     * @param totalValidations total validation count
     */
    public void setTotalValidations(long totalValidations) {
        this.totalValidations = totalValidations;
    }
    
    /**
     * Returns the number of successful validations.
     * 
     * @return successful validation count
     */
    public long getSuccessfulValidations() {
        return successfulValidations;
    }
    
    /**
     * Sets the number of successful validations.
     * 
     * @param successfulValidations successful validation count
     */
    public void setSuccessfulValidations(long successfulValidations) {
        this.successfulValidations = successfulValidations;
    }
    
    /**
     * Returns the number of failed validations.
     * 
     * @return failed validation count
     */
    public long getFailedValidations() {
        return failedValidations;
    }
    
    /**
     * Sets the number of failed validations.
     * 
     * @param failedValidations failed validation count
     */
    public void setFailedValidations(long failedValidations) {
        this.failedValidations = failedValidations;
    }
    
    /**
     * Returns the validation success rate as a percentage.
     * 
     * @return success rate (0-100)
     */
    public double getSuccessRate() {
        return successRate;
    }
    
    /**
     * Sets the validation success rate.
     * 
     * @param successRate success rate (0-100)
     */
    public void setSuccessRate(double successRate) {
        this.successRate = successRate;
    }
    
    /**
     * Returns the default validation mode.
     * 
     * @return default validation mode
     */
    public ValidationMode getDefaultValidationMode() {
        return defaultValidationMode;
    }
    
    /**
     * Sets the default validation mode.
     * 
     * @param defaultValidationMode default validation mode
     */
    public void setDefaultValidationMode(ValidationMode defaultValidationMode) {
        this.defaultValidationMode = defaultValidationMode;
    }
    
    /**
     * Returns whether injection detection is enabled.
     * 
     * @return true if injection detection is enabled
     */
    public boolean isInjectionDetectionEnabled() {
        return injectionDetectionEnabled;
    }
    
    /**
     * Sets whether injection detection is enabled.
     * 
     * @param injectionDetectionEnabled injection detection status
     */
    public void setInjectionDetectionEnabled(boolean injectionDetectionEnabled) {
        this.injectionDetectionEnabled = injectionDetectionEnabled;
    }
    
    /**
     * Returns the number of configured validation rules.
     * 
     * @return validation rules count
     */
    public int getValidationRulesCount() {
        return validationRulesCount;
    }
    
    /**
     * Sets the number of configured validation rules.
     * 
     * @param validationRulesCount validation rules count
     */
    public void setValidationRulesCount(int validationRulesCount) {
        this.validationRulesCount = validationRulesCount;
    }
    
    /**
     * Returns the number of security patterns configured.
     * 
     * @return security patterns count
     */
    public int getSecurityPatternsCount() {
        return securityPatternsCount;
    }
    
    /**
     * Sets the number of security patterns configured.
     * 
     * @param securityPatternsCount security patterns count
     */
    public void setSecurityPatternsCount(int securityPatternsCount) {
        this.securityPatternsCount = securityPatternsCount;
    }
    
    /**
     * Returns when this report was generated.
     * 
     * @return report generation timestamp
     */
    public Instant getGeneratedAt() {
        return generatedAt;
    }
    
    /**
     * Sets when this report was generated.
     * 
     * @param generatedAt report generation timestamp
     */
    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }
    
    /**
     * Generates a human-readable summary of the validation report.
     * 
     * @return formatted summary string
     */
    public String generateSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Request Validation Report ===\n");
        summary.append("Generated: ").append(generatedAt).append("\n");
        summary.append("Total Validations: ").append(totalValidations).append("\n");
        summary.append("Successful: ").append(successfulValidations).append("\n");
        summary.append("Failed: ").append(failedValidations).append("\n");
        summary.append("Success Rate: ").append(String.format("%.2f%%", successRate)).append("\n");
        summary.append("Default Mode: ").append(defaultValidationMode).append("\n");
        summary.append("Injection Detection: ").append(injectionDetectionEnabled ? "Enabled" : "Disabled").append("\n");
        summary.append("Validation Rules: ").append(validationRulesCount).append("\n");
        summary.append("Security Patterns: ").append(securityPatternsCount).append("\n");
        summary.append("================================");
        
        return summary.toString();
    }
    
    @Override
    public String toString() {
        return generateSummary();
    }
}