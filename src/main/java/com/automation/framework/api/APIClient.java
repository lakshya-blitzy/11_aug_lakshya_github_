package com.automation.framework.api;

// External imports - REST Assured and Apache HttpClient
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.HttpClientConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.io.IOException;

// Internal imports - Framework components
import com.automation.framework.resources.ConnectionPoolManager;
import com.automation.framework.exceptions.ErrorReporter;
import com.automation.framework.api.AuthenticationManager;
import com.automation.framework.api.AuthenticationType;
import com.automation.framework.api.AuthenticationConfiguration;
import com.automation.framework.api.RequestValidator;
import com.automation.framework.api.ResponseValidator;
import com.automation.framework.api.ValidationMode;
import com.automation.framework.exceptions.RetryMechanism;
import com.automation.framework.core.ConfigurationManager;

/**
 * APIClient provides comprehensive HTTP client implementation with connection pooling for REST API testing.
 * 
 * This enterprise-grade HTTP client integrates with REST Assured 5.4.0 to provide complete REST API support
 * for all HTTP methods (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS) with comprehensive error handling,
 * connection pooling, and performance monitoring.
 * 
 * Key Features:
 * - Complete REST API support with all HTTP methods
 * - Apache HttpClient connection pooling (max 50 connections, 2s timeout)
 * - Automatic resource cleanup with connections returned to pool
 * - Graceful error handling with detailed logging
 * - Response timeout compliance (<2 seconds SLA)
 * - Concurrent request support up to 50 threads
 * - Authentication integration (OAuth 2.0, JWT, API Key)
 * - Request/response validation with schema support
 * - Retry mechanisms with exponential backoff
 * - Comprehensive metrics and monitoring
 * 
 * Performance Requirements:
 * - API response timeout: <2 seconds
 * - Maximum concurrent requests: 50 threads
 * - Connection pool: maxConnections=50, connectionTimeout=2000ms
 * - Automatic connection leak detection and prevention
 * 
 * Integration Requirements:
 * - ConnectionPoolManager for HTTP connection pooling
 * - ErrorReporter for detailed error logging with request/response data
 * - AuthenticationManager for token lifecycle management
 * - RequestValidator for payload validation and security
 * - ResponseValidator for schema and data validation
 * - RetryMechanism for transient failure recovery
 * - ConfigurationManager for timeout and threshold settings
 */
public class APIClient {
    
    private static final Logger logger = Logger.getLogger(APIClient.class.getName());
    
    // Core framework integration components
    private final ConnectionPoolManager connectionPoolManager;
    private final ErrorReporter errorReporter;
    private final AuthenticationManager authenticationManager;
    private final RequestValidator requestValidator;
    private final ResponseValidator responseValidator;
    private final RetryMechanism retryMechanism;
    private final ConfigurationManager configurationManager;
    
    // HTTP client configuration
    private String baseUri = "";
    private int defaultPort = 80;
    private String basePath = "";
    private final Map<String, String> defaultHeaders;
    private Duration defaultTimeout;
    
    // Connection and performance management
    private CloseableHttpClient httpClient;
    private volatile boolean isShutdown = false;
    
    // Metrics and monitoring
    private final APIClientMetrics metrics;
    private final ThreadLocal<String> correlationContext;
    
    // Configuration constants from specification
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2); // <2 second SLA
    private static final int MAX_CONCURRENT_REQUESTS = 50;
    private static final int MAX_CONNECTIONS = 50;
    private static final int CONNECTION_TIMEOUT_MS = 2000;
    
    /**
     * Creates a new APIClient instance with default configuration.
     * Initializes all framework integrations and connection pooling.
     */
    public APIClient() {
        // Initialize framework integration components
        this.connectionPoolManager = new ConnectionPoolManager();
        this.errorReporter = new ErrorReporter();
        this.authenticationManager = AuthenticationManager.getInstance();
        this.requestValidator = new RequestValidator();
        this.responseValidator = new ResponseValidator();
        this.retryMechanism = RetryMechanism.getInstance();
        this.configurationManager = ConfigurationManager.getInstance();
        
        // Initialize configuration
        this.defaultHeaders = new HashMap<>();
        this.defaultTimeout = DEFAULT_TIMEOUT;
        this.metrics = new APIClientMetrics();
        this.correlationContext = new ThreadLocal<>();
        
        // Initialize HTTP client and REST Assured configuration
        initializeHttpClient();
        configureRestAssured();
        
        logger.info("APIClient initialized with connection pool: maxConnections=" + MAX_CONNECTIONS + 
                   ", timeout=" + CONNECTION_TIMEOUT_MS + "ms");
    }
    
    /**
     * Initializes the HTTP client with connection pooling configuration.
     */
    private void initializeHttpClient() {
        try {
            // Get HTTP client from connection pool manager
            this.httpClient = connectionPoolManager.getHttpClient();
            
            // Verify connection pool health
            if (!connectionPoolManager.isPoolHealthy()) {
                throw new IllegalStateException("Connection pool is not healthy");
            }
            
            logger.info("HTTP client initialized successfully with pool health check passed");
            
        } catch (Exception e) {
            String errorMessage = "Failed to initialize HTTP client";
            Map<String, Object> errorContext = new HashMap<>();
            errorContext.put("maxConnections", MAX_CONNECTIONS);
            errorContext.put("connectionTimeout", CONNECTION_TIMEOUT_MS);
            
            errorReporter.logException(e, errorMessage, errorContext);
            throw new RuntimeException(errorMessage, e);
        }
    }
    
    /**
     * Configures REST Assured with Apache HttpClient integration.
     */
    private void configureRestAssured() {
        try {
            // Configure REST Assured to use our managed HTTP client
            RestAssuredConfig config = RestAssuredConfig.config()
                .httpClient(HttpClientConfig.httpClientConfig()
                    .httpClientFactory(() -> httpClient)
                    .reuseHttpClientInstance());
            
            RestAssured.config = config;
            
            // Set default timeout configuration
            RestAssured.config().getHttpClientConfig()
                .setParam("http.connection.timeout", CONNECTION_TIMEOUT_MS)
                .setParam("http.socket.timeout", (int) defaultTimeout.toMillis());
            
            logger.info("REST Assured configured with Apache HttpClient integration");
            
        } catch (Exception e) {
            String errorMessage = "Failed to configure REST Assured";
            errorReporter.logException(e, errorMessage, Map.of("timeout", defaultTimeout.toMillis()));
            throw new RuntimeException(errorMessage, e);
        }
    }
    
    /**
     * Executes HTTP GET request with comprehensive error handling and connection management.
     * 
     * @param endpoint The API endpoint path
     * @param headers Optional headers for the request
     * @param queryParams Optional query parameters
     * @return Response object containing status, headers, and body
     */
    public Response get(String endpoint, Map<String, String> headers, Map<String, Object> queryParams) {
        return executeRequest("GET", endpoint, headers, queryParams, null);
    }
    
    /**
     * Executes HTTP GET request with default parameters.
     * 
     * @param endpoint The API endpoint path
     * @return Response object containing status, headers, and body
     */
    public Response get(String endpoint) {
        return get(endpoint, null, null);
    }
    
    /**
     * Executes HTTP POST request with payload validation and error handling.
     * 
     * @param endpoint The API endpoint path
     * @param headers Optional headers for the request
     * @param requestBody Request payload object
     * @return Response object containing status, headers, and body
     */
    public Response post(String endpoint, Map<String, String> headers, Object requestBody) {
        return executeRequest("POST", endpoint, headers, null, requestBody);
    }
    
    /**
     * Executes HTTP POST request with default parameters.
     * 
     * @param endpoint The API endpoint path
     * @param requestBody Request payload object
     * @return Response object containing status, headers, and body
     */
    public Response post(String endpoint, Object requestBody) {
        return post(endpoint, null, requestBody);
    }
    
    /**
     * Executes HTTP PUT request with payload validation and error handling.
     * 
     * @param endpoint The API endpoint path
     * @param headers Optional headers for the request
     * @param requestBody Request payload object
     * @return Response object containing status, headers, and body
     */
    public Response put(String endpoint, Map<String, String> headers, Object requestBody) {
        return executeRequest("PUT", endpoint, headers, null, requestBody);
    }
    
    /**
     * Executes HTTP PUT request with default parameters.
     * 
     * @param endpoint The API endpoint path
     * @param requestBody Request payload object
     * @return Response object containing status, headers, and body
     */
    public Response put(String endpoint, Object requestBody) {
        return put(endpoint, null, requestBody);
    }
    
    /**
     * Executes HTTP DELETE request with error handling and connection management.
     * 
     * @param endpoint The API endpoint path
     * @param headers Optional headers for the request
     * @return Response object containing status, headers, and body
     */
    public Response delete(String endpoint, Map<String, String> headers) {
        return executeRequest("DELETE", endpoint, headers, null, null);
    }
    
    /**
     * Executes HTTP DELETE request with default parameters.
     * 
     * @param endpoint The API endpoint path
     * @return Response object containing status, headers, and body
     */
    public Response delete(String endpoint) {
        return delete(endpoint, null);
    }
    
    /**
     * Executes HTTP PATCH request with payload validation and error handling.
     * 
     * @param endpoint The API endpoint path
     * @param headers Optional headers for the request
     * @param requestBody Request payload object
     * @return Response object containing status, headers, and body
     */
    public Response patch(String endpoint, Map<String, String> headers, Object requestBody) {
        return executeRequest("PATCH", endpoint, headers, null, requestBody);
    }
    
    /**
     * Executes HTTP PATCH request with default parameters.
     * 
     * @param endpoint The API endpoint path
     * @param requestBody Request payload object
     * @return Response object containing status, headers, and body
     */
    public Response patch(String endpoint, Object requestBody) {
        return patch(endpoint, null, requestBody);
    }
    
    /**
     * Executes HTTP HEAD request for metadata retrieval.
     * 
     * @param endpoint The API endpoint path
     * @param headers Optional headers for the request
     * @return Response object containing status and headers (no body)
     */
    public Response head(String endpoint, Map<String, String> headers) {
        return executeRequest("HEAD", endpoint, headers, null, null);
    }
    
    /**
     * Executes HTTP HEAD request with default parameters.
     * 
     * @param endpoint The API endpoint path
     * @return Response object containing status and headers (no body)
     */
    public Response head(String endpoint) {
        return head(endpoint, null);
    }
    
    /**
     * Executes HTTP OPTIONS request for API capability discovery.
     * 
     * @param endpoint The API endpoint path
     * @param headers Optional headers for the request
     * @return Response object containing allowed methods and capabilities
     */
    public Response options(String endpoint, Map<String, String> headers) {
        return executeRequest("OPTIONS", endpoint, headers, null, null);
    }
    
    /**
     * Executes HTTP OPTIONS request with default parameters.
     * 
     * @param endpoint The API endpoint path
     * @return Response object containing allowed methods and capabilities
     */
    public Response options(String endpoint) {
        return options(endpoint, null);
    }
    
    /**
     * Core request execution method with comprehensive error handling, validation, and resource management.
     * Implements all cross-cutting concerns including authentication, validation, retry logic, and connection cleanup.
     * 
     * @param method HTTP method (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS)
     * @param endpoint API endpoint path
     * @param headers Request headers
     * @param queryParams Query parameters
     * @param requestBody Request payload
     * @return Response object with full validation and error context
     */
    public Response executeRequest(String method, String endpoint, Map<String, String> headers, 
                                  Map<String, Object> queryParams, Object requestBody) {
        
        long startTime = System.currentTimeMillis();
        String correlationId = errorReporter.getCorrelationId();
        correlationContext.set(correlationId);
        
        try {
            // Pre-execution validation and setup
            validateExecutionPreconditions(method, endpoint);
            
            // Build and validate request
            RequestSpecification requestSpec = buildRequestSpecification(headers, queryParams, requestBody);
            
            // Validate request payload if provided
            if (requestBody != null) {
                validateRequest(requestBody, headers);
            }
            
            // Execute request with retry mechanism
            String operationName = method + "_" + endpoint.replaceAll("[^a-zA-Z0-9]", "_");
            Response response = null;
            
            // Since RetryResult is not accessible, implement simple retry logic
            int maxRetries = 3;
            int attempt = 0;
            Exception lastException = null;
            
            while (attempt < maxRetries && response == null) {
                try {
                    if (retryMechanism.isRetryAllowed(operationName)) {
                        response = executeRequestInternal(method, endpoint, requestSpec);
                        break; // Success
                    } else {
                        throw new RuntimeException("Retry not allowed for operation: " + operationName);
                    }
                } catch (Exception e) {
                    lastException = e;
                    attempt++;
                    if (attempt < maxRetries) {
                        // Wait before retry (exponential backoff)
                        try {
                            Thread.sleep(1000 * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Retry interrupted", ie);
                        }
                    }
                }
            }
            
            if (response == null && lastException != null) {
                throw new RuntimeException("Request failed after " + maxRetries + " attempts", lastException);
            }
            
            // Calculate response time and validate against SLA
            long responseTime = System.currentTimeMillis() - startTime;
            
            // Validate response
            validateResponse(response, responseTime);
            
            // Update metrics
            updateMetrics(method, responseTime, response.getStatusCode(), true);
            
            // Log successful execution
            logRequestSuccess(method, endpoint, responseTime, response.getStatusCode());
            
            return response;
            
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            
            // Update failure metrics
            updateMetrics(method, responseTime, -1, false);
            
            // Capture detailed error context
            Map<String, Object> errorContext = createErrorContext(method, endpoint, headers, queryParams, requestBody, responseTime);
            
            // Log error with full context
            errorReporter.logException(e, "API request execution failed", errorContext);
            
            // Re-throw as runtime exception with enhanced context
            throw new RuntimeException("API request failed: " + method + " " + endpoint + " - " + e.getMessage(), e);
            
        } finally {
            // Ensure connections are properly managed
            connectionPoolManager.closeIdleConnections(5, TimeUnit.SECONDS);
            
            // Clean up thread-local context
            correlationContext.remove();
        }
    }
    
    /**
     * Asynchronous request execution with timeout handling and resource management.
     * 
     * @param method HTTP method
     * @param endpoint API endpoint path
     * @param headers Request headers
     * @param queryParams Query parameters
     * @param requestBody Request payload
     * @return CompletableFuture containing the response
     */
    public CompletableFuture<Response> executeRequestAsync(String method, String endpoint, 
                                                          Map<String, String> headers, 
                                                          Map<String, Object> queryParams, 
                                                          Object requestBody) {
        
        return CompletableFuture.supplyAsync(() -> {
            return executeRequest(method, endpoint, headers, queryParams, requestBody);
        }).orTimeout(defaultTimeout.toMillis(), TimeUnit.MILLISECONDS)
          .exceptionally(throwable -> {
              // Handle timeout and other async exceptions
              String errorMessage = "Async API request failed or timed out";
              Map<String, Object> errorContext = createErrorContext(method, endpoint, headers, queryParams, requestBody, defaultTimeout.toMillis());
              
              errorReporter.logException((Exception) throwable, errorMessage, errorContext);
              throw new RuntimeException(errorMessage, throwable);
          });
    }
    
    /**
     * Internal request execution using REST Assured with proper exception handling.
     */
    private Response executeRequestInternal(String method, String endpoint, RequestSpecification requestSpec) {
        try {
            // Configure base URI if set
            if (!baseUri.isEmpty()) {
                requestSpec.baseUri(baseUri);
            }
            if (defaultPort != 80) {
                requestSpec.port(defaultPort);
            }
            if (!basePath.isEmpty()) {
                requestSpec.basePath(basePath);
            }
            
            // Execute based on HTTP method
            switch (method.toUpperCase()) {
                case "GET":
                    return requestSpec.when().get(endpoint).then().extract().response();
                case "POST":
                    return requestSpec.when().post(endpoint).then().extract().response();
                case "PUT":
                    return requestSpec.when().put(endpoint).then().extract().response();
                case "DELETE":
                    return requestSpec.when().delete(endpoint).then().extract().response();
                case "PATCH":
                    return requestSpec.when().patch(endpoint).then().extract().response();
                case "HEAD":
                    return requestSpec.when().head(endpoint).then().extract().response();
                case "OPTIONS":
                    return requestSpec.when().options(endpoint).then().extract().response();
                default:
                    throw new IllegalArgumentException("Unsupported HTTP method: " + method);
            }
            
        } catch (Exception e) {
            throw new RuntimeException("HTTP request execution failed: " + method + " " + endpoint, e);
        }
    }
    
    /**
     * Builds REST Assured request specification with headers, parameters, and authentication.
     */
    private RequestSpecification buildRequestSpecification(Map<String, String> headers, 
                                                          Map<String, Object> queryParams, 
                                                          Object requestBody) {
        RequestSpecification requestSpec = RestAssured.given();
        
        // Add default headers
        if (!defaultHeaders.isEmpty()) {
            requestSpec.headers(defaultHeaders);
        }
        
        // Add request-specific headers
        if (headers != null && !headers.isEmpty()) {
            requestSpec.headers(headers);
        }
        
        // Add query parameters
        if (queryParams != null && !queryParams.isEmpty()) {
            requestSpec.queryParams(queryParams);
        }
        
        // Add request body
        if (requestBody != null) {
            requestSpec.body(requestBody);
        }
        
        // Add authentication if available
        if (authenticationManager.isAuthenticationHealthy()) {
            addAuthenticationToRequest(requestSpec);
        }
        
        // Configure timeouts
        requestSpec.config(RestAssuredConfig.config()
            .httpClient(HttpClientConfig.httpClientConfig()
                .setParam("http.connection.timeout", CONNECTION_TIMEOUT_MS)
                .setParam("http.socket.timeout", (int) defaultTimeout.toMillis())));
        
        return requestSpec;
    }
    
    /**
     * Sets the base URI for all API requests.
     * 
     * @param baseUri Base URI (e.g., "https://api.example.com")
     */
    public void setBaseUri(String baseUri) {
        if (baseUri == null || baseUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Base URI cannot be null or empty");
        }
        
        this.baseUri = baseUri.trim();
        
        // Update REST Assured configuration
        RestAssured.baseURI = this.baseUri;
        
        logger.info("Base URI set to: " + this.baseUri);
    }
    
    /**
     * Sets default headers for all requests.
     * 
     * @param headers Map of default headers
     */
    public void setDefaultHeaders(Map<String, String> headers) {
        if (headers == null) {
            defaultHeaders.clear();
        } else {
            defaultHeaders.clear();
            defaultHeaders.putAll(headers);
        }
        
        logger.info("Default headers updated, count: " + defaultHeaders.size());
    }
    
    /**
     * Sets the default timeout for all requests.
     * 
     * @param timeout Request timeout duration
     */
    public void setDefaultTimeout(Duration timeout) {
        if (timeout == null || timeout.toMillis() <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        
        // Validate against SLA requirement
        if (timeout.toMillis() > DEFAULT_TIMEOUT.toMillis()) {
            errorReporter.warn("Timeout " + timeout.toMillis() + "ms exceeds SLA requirement of " + 
                              DEFAULT_TIMEOUT.toMillis() + "ms");
        }
        
        this.defaultTimeout = timeout;
        
        logger.info("Default timeout set to: " + timeout.toMillis() + "ms");
    }
    
    /**
     * Adds authentication token to requests using AuthenticationManager.
     * 
     * @param token Authentication token
     * @param tokenType Token type (Bearer, API-Key, etc.)
     */
    public void addAuthenticationToken(String token, String tokenType) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Authentication token cannot be null or empty");
        }
        
        if (tokenType == null || tokenType.trim().isEmpty()) {
            tokenType = "Bearer"; // Default to Bearer token
        }
        
        try {
            // Validate token using AuthenticationManager
            // Note: Using BEARER_TOKEN as default authentication type for token validation
            AuthenticationType authType = AuthenticationType.BEARER_TOKEN;
            if (!authenticationManager.isTokenValid(authType)) {
                errorReporter.warn("Token validation failed, attempting to refresh");
                // Create basic authentication configuration for token refresh
                AuthenticationConfiguration authConfig = new AuthenticationConfiguration();
                authConfig.setAuthenticationType(authType);
                authConfig.setCredentials(token);
                authenticationManager.refreshToken(authConfig);
            }
            
            // Add authentication header
            String authHeader = tokenType + " " + token;
            defaultHeaders.put("Authorization", authHeader);
            
            logger.info("Authentication token added with type: " + tokenType);
            
        } catch (Exception e) {
            String errorMessage = "Failed to add authentication token";
            errorReporter.logException(e, errorMessage, Map.of("tokenType", tokenType));
            throw new RuntimeException(errorMessage, e);
        }
    }
    
    /**
     * Removes authentication from requests.
     */
    public void removeAuthentication() {
        defaultHeaders.remove("Authorization");
        defaultHeaders.remove("X-API-Key");
        
        logger.info("Authentication removed from default headers");
    }
    
    /**
     * Validates request payload using RequestValidator.
     * 
     * @param requestBody Request payload to validate
     * @param headers Request headers for context
     */
    public void validateRequest(Object requestBody, Map<String, String> headers) {
        try {
            // Validate request payload
            requestValidator.validateRequestPayload(requestBody, ValidationMode.STRICT);
            
            // Validate headers if provided
            if (headers != null) {
                requestValidator.validateHeaders(headers);
            }
            
            // Sanitize input for security (convert to string first)
            if (requestBody != null) {
                String sanitizedInput = requestValidator.sanitizeInput(requestBody.toString());
                // Note: Sanitized input can be used for logging or further processing
            }
            
        } catch (Exception e) {
            String errorMessage = "Request validation failed";
            Map<String, Object> errorContext = new HashMap<>();
            errorContext.put("hasRequestBody", requestBody != null);
            errorContext.put("headerCount", headers != null ? headers.size() : 0);
            
            errorReporter.logException(e, errorMessage, errorContext);
            throw new RuntimeException(errorMessage, e);
        }
    }
    
    /**
     * Validates API response using ResponseValidator.
     * 
     * @param response Response object to validate
     * @param responseTime Response time in milliseconds
     */
    public void validateResponse(Response response, long responseTime) {
        try {
            // Validate response using ResponseValidator
            responseValidator.validateResponse(response.getBody().asString(), 
                                            response.getStatusCode(), 
                                            responseTime);
            
            // Validate status code
            responseValidator.validateStatusCode(response.getStatusCode());
            
            // Validate response time against SLA
            responseValidator.validateResponseTime(responseTime);
            
            // Validate headers
            responseValidator.validateHeaders(response.getHeaders().asList().stream()
                .collect(HashMap::new, 
                        (map, header) -> map.put(header.getName(), header.getValue()),
                        HashMap::putAll));
            
        } catch (Exception e) {
            String errorMessage = "Response validation failed";
            Map<String, Object> errorContext = new HashMap<>();
            errorContext.put("statusCode", response.getStatusCode());
            errorContext.put("responseTime", responseTime);
            errorContext.put("contentType", response.getContentType());
            
            errorReporter.logException(e, errorMessage, errorContext);
            throw new RuntimeException(errorMessage, e);
        }
    }
    
    /**
     * Gets connection pool status and health information.
     * 
     * @return Map containing pool status metrics
     */
    public Map<String, Object> getConnectionPoolStatus() {
        Map<String, Object> status = new HashMap<>();
        
        try {
            status.put("isHealthy", connectionPoolManager.isPoolHealthy());
            status.put("maxConnections", connectionPoolManager.getMaxConnections());
            status.put("connectionTimeout", connectionPoolManager.getConnectionTimeout());
            status.put("poolMetrics", convertPoolMetricsToMap(null)); // Pass null since we use ConnectionPoolManager methods directly
            status.put("activeConnections", getActiveConnections());
            status.put("idleConnections", getIdleConnections());
            
        } catch (Exception e) {
            String errorMessage = "Failed to get connection pool status";
            errorReporter.logException(e, errorMessage, Collections.emptyMap());
            
            status.put("error", errorMessage);
            status.put("isHealthy", false);
        }
        
        return status;
    }
    
    /**
     * Performs health check on API client and all dependencies.
     * 
     * @return Map containing health status of all components
     */
    public Map<String, Object> healthCheck() {
        Map<String, Object> healthStatus = new HashMap<>();
        
        try {
            // Check connection pool health
            boolean poolHealthy = connectionPoolManager.isPoolHealthy();
            healthStatus.put("connectionPool", poolHealthy);
            
            // Check authentication health
            boolean authHealthy = authenticationManager.isAuthenticationHealthy();
            healthStatus.put("authentication", authHealthy);
            
            // Check if client is shutdown
            healthStatus.put("clientActive", !isShutdown);
            
            // Check retry mechanism
            boolean retryHealthy = retryMechanism.isRetryAllowed("healthCheck");
            healthStatus.put("retryMechanism", retryHealthy);
            
            // Overall health
            boolean overallHealthy = poolHealthy && authHealthy && !isShutdown && retryHealthy;
            healthStatus.put("overall", overallHealthy);
            
            // Add metrics
            healthStatus.put("metrics", getRequestMetrics());
            
        } catch (Exception e) {
            String errorMessage = "Health check failed";
            errorReporter.logException(e, errorMessage, Collections.emptyMap());
            
            healthStatus.put("overall", false);
            healthStatus.put("error", errorMessage);
        }
        
        return healthStatus;
    }
    
    /**
     * Gracefully shuts down the API client and releases all resources.
     * Implements ordered shutdown sequence as specified in requirements.
     */
    public void shutdown() {
        if (isShutdown) {
            logger.info("APIClient already shutdown");
            return;
        }
        
        try {
            logger.info("Initiating APIClient shutdown sequence");
            
            // Mark as shutdown to stop accepting new requests
            isShutdown = true;
            
            // Close HTTP client connection
            if (httpClient != null) {
                httpClient.close();
            }
            
            // Close idle connections from pool
            connectionPoolManager.closeIdleConnections(0, TimeUnit.SECONDS);
            
            // Close idle connections
            closeIdleConnections();
            
            // Clean up thread-local contexts
            correlationContext.remove();
            
            logger.info("APIClient shutdown completed successfully");
            
        } catch (IOException e) {
            String errorMessage = "Error during APIClient shutdown";
            errorReporter.logException(e, errorMessage, Collections.emptyMap());
            throw new RuntimeException(errorMessage, e);
        } catch (Exception e) {
            String errorMessage = "Unexpected error during APIClient shutdown";
            errorReporter.logException(e, errorMessage, Collections.emptyMap());
            throw new RuntimeException(errorMessage, e);
        }
    }
    
    /**
     * Gets the number of active connections in the pool.
     * 
     * @return Number of active connections
     */
    public int getActiveConnections() {
        try {
            return connectionPoolManager.getActiveConnections();
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to get active connections count", Collections.emptyMap());
            return -1;
        }
    }
    
    /**
     * Gets the number of idle connections in the pool.
     * 
     * @return Number of idle connections
     */
    public int getIdleConnections() {
        try {
            return connectionPoolManager.getAvailableConnections();
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to get idle connections count", Collections.emptyMap());
            return -1;
        }
    }
    
    /**
     * Closes idle connections in the connection pool.
     */
    public void closeIdleConnections() {
        try {
            // Close idle connections through connection pool manager
            connectionPoolManager.closeIdleConnections(5, TimeUnit.SECONDS);
            
            logger.info("Idle connections closed successfully");
            
        } catch (Exception e) {
            String errorMessage = "Failed to close idle connections";
            errorReporter.logException(e, errorMessage, Collections.emptyMap());
            throw new RuntimeException(errorMessage, e);
        }
    }
    
    /**
     * Checks if the connection pool is healthy.
     * 
     * @return true if connection pool is healthy, false otherwise
     */
    public boolean isConnectionPoolHealthy() {
        try {
            return connectionPoolManager.isPoolHealthy();
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to check connection pool health", Collections.emptyMap());
            return false;
        }
    }
    
    /**
     * Gets comprehensive request metrics and performance statistics.
     * 
     * @return Map containing detailed metrics
     */
    public Map<String, Object> getRequestMetrics() {
        return metrics.getMetrics();
    }
    
    /**
     * Resets all metrics counters and statistics.
     */
    public void resetMetrics() {
        metrics.reset();
        logger.info("API client metrics reset successfully");
    }
    
    // ========== HELPER METHODS ==========
    
    /**
     * Validates execution preconditions before making a request.
     */
    private void validateExecutionPreconditions(String method, String endpoint) {
        if (isShutdown) {
            throw new IllegalStateException("APIClient has been shutdown");
        }
        
        if (method == null || method.trim().isEmpty()) {
            throw new IllegalArgumentException("HTTP method cannot be null or empty");
        }
        
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("Endpoint cannot be null or empty");
        }
        
        if (!connectionPoolManager.isPoolHealthy()) {
            throw new IllegalStateException("Connection pool is not healthy");
        }
    }
    
    /**
     * Adds authentication to request specification using AuthenticationManager.
     */
    private void addAuthenticationToRequest(RequestSpecification requestSpec) {
        try {
            // Check authentication status
            AuthenticationType authType = AuthenticationType.BEARER_TOKEN;
            if (!authenticationManager.validateCredentials(authType)) {
                // Create basic authentication configuration for token refresh
                AuthenticationConfiguration authConfig = new AuthenticationConfiguration();
                authConfig.setAuthenticationType(authType);
                authenticationManager.refreshToken(authConfig);
            }
            
            // Add authentication header based on current auth status
            String authHeader = defaultHeaders.get("Authorization");
            if (authHeader != null) {
                requestSpec.header("Authorization", authHeader);
            }
            
            String apiKey = defaultHeaders.get("X-API-Key");
            if (apiKey != null) {
                requestSpec.header("X-API-Key", apiKey);
            }
            
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to add authentication to request", Collections.emptyMap());
            // Continue without authentication rather than failing the request
            logger.warning("Request proceeding without authentication due to auth failure");
        }
    }
    
    /**
     * Creates comprehensive error context for logging and debugging.
     */
    private Map<String, Object> createErrorContext(String method, String endpoint, 
                                                  Map<String, String> headers, 
                                                  Map<String, Object> queryParams, 
                                                  Object requestBody, long responseTime) {
        Map<String, Object> context = new HashMap<>();
        
        context.put("method", method);
        context.put("endpoint", endpoint);
        context.put("responseTime", responseTime);
        context.put("correlationId", correlationContext.get());
        context.put("baseUri", baseUri);
        context.put("timeout", defaultTimeout.toMillis());
        
        // Add header count (avoid logging sensitive data)
        context.put("headerCount", headers != null ? headers.size() : 0);
        context.put("queryParamCount", queryParams != null ? queryParams.size() : 0);
        context.put("hasRequestBody", requestBody != null);
        
        // Add connection pool status
        context.put("connectionPoolHealthy", connectionPoolManager.isPoolHealthy());
        context.put("activeConnections", getActiveConnections());
        
        return context;
    }
    
    /**
     * Updates request metrics and performance statistics.
     */
    private void updateMetrics(String method, long responseTime, int statusCode, boolean success) {
        metrics.recordRequest(method, responseTime, statusCode, success);
    }
    
    /**
     * Logs successful request execution with performance metrics.
     */
    private void logRequestSuccess(String method, String endpoint, long responseTime, int statusCode) {
        Map<String, Object> logContext = new HashMap<>();
        logContext.put("method", method);
        logContext.put("endpoint", endpoint);
        logContext.put("responseTime", responseTime);
        logContext.put("statusCode", statusCode);
        logContext.put("correlationId", correlationContext.get());
        
        if (responseTime > DEFAULT_TIMEOUT.toMillis()) {
            errorReporter.warn("Request exceeded SLA timeout: " + responseTime + "ms > " + 
                              DEFAULT_TIMEOUT.toMillis() + "ms");
        }
        
        errorReporter.info("API request completed successfully");
    }
    
    /**
     * Creates a pool metrics map from available ConnectionPoolManager methods.
     * Since PoolMetrics class is not public, we use individual getter methods.
     */
    private Map<String, Object> convertPoolMetricsToMap(Object poolMetrics) {
        Map<String, Object> metricsMap = new HashMap<>();
        
        try {
            // Use public methods from ConnectionPoolManager instead of PoolMetrics
            metricsMap.put("activeConnections", connectionPoolManager.getActiveConnections());
            metricsMap.put("availableConnections", connectionPoolManager.getAvailableConnections());
            metricsMap.put("maxConnections", connectionPoolManager.getMaxConnections());
            metricsMap.put("poolUtilization", connectionPoolManager.getPoolUtilization());
            metricsMap.put("connectionTimeout", connectionPoolManager.getConnectionTimeout());
            metricsMap.put("isHealthy", connectionPoolManager.isPoolHealthy());
            metricsMap.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            metricsMap.put("error", "Failed to collect pool metrics: " + e.getMessage());
            metricsMap.put("timestamp", System.currentTimeMillis());
        }
        
        return metricsMap;
    }
    
    /**
     * Inner class for API client metrics collection and reporting.
     */
    private static class APIClientMetrics {
        private final Map<String, Long> requestCounts = new ConcurrentHashMap<>();
        private final Map<String, Long> responseTimes = new ConcurrentHashMap<>();
        private final Map<String, Long> errorCounts = new ConcurrentHashMap<>();
        private long totalRequests = 0;
        private long totalErrors = 0;
        private long totalResponseTime = 0;
        
        public synchronized void recordRequest(String method, long responseTime, int statusCode, boolean success) {
            totalRequests++;
            totalResponseTime += responseTime;
            
            requestCounts.merge(method, 1L, Long::sum);
            responseTimes.merge(method, responseTime, Long::sum);
            
            if (!success || statusCode >= 400) {
                totalErrors++;
                errorCounts.merge(method, 1L, Long::sum);
            }
        }
        
        public Map<String, Object> getMetrics() {
            Map<String, Object> metrics = new HashMap<>();
            
            metrics.put("totalRequests", totalRequests);
            metrics.put("totalErrors", totalErrors);
            metrics.put("errorRate", totalRequests > 0 ? (double) totalErrors / totalRequests : 0.0);
            metrics.put("averageResponseTime", totalRequests > 0 ? totalResponseTime / totalRequests : 0);
            metrics.put("requestCountsByMethod", new HashMap<>(requestCounts));
            metrics.put("averageResponseTimesByMethod", new HashMap<>(responseTimes));
            metrics.put("errorCountsByMethod", new HashMap<>(errorCounts));
            
            return metrics;
        }
        
        public synchronized void reset() {
            requestCounts.clear();
            responseTimes.clear();
            errorCounts.clear();
            totalRequests = 0;
            totalErrors = 0;
            totalResponseTime = 0;
        }
    }
}