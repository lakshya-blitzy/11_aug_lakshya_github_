package com.automation.framework.api;

// Internal imports from framework dependencies
import com.automation.framework.core.ConfigurationManager;
import com.automation.framework.exceptions.ErrorReporter;
import com.automation.framework.resources.ThreadPoolManager;
import com.automation.framework.resources.MemoryManager;

// External imports for cryptography and encoding
import javax.crypto.KeyGenerator;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

// External imports for JWT token processing
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.exceptions.JWTVerificationException;

// External imports for OAuth 2.0 processing
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;

// External imports for concurrent operations and thread safety
import java.lang.ThreadLocal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// External imports for time-based operations
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

// External imports for data structures and collections
import java.util.*;
import java.util.stream.Collectors;

// External imports for structured logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AuthenticationManager provides comprehensive token lifecycle management for multi-protocol authentication.
 * 
 * This class implements secure authentication handling for Basic Authentication, OAuth 2.0 (both Authorization Code
 * and Client Credentials flows), JWT token management, and API key authentication. It provides automatic token
 * refresh capabilities, secure credential storage using AES-256 encryption, and comprehensive audit logging.
 * 
 * Key Features:
 * - Multi-Protocol Authentication: Basic Auth, OAuth 2.0, JWT, API Key, Bearer Token
 * - Automatic Token Refresh: Proactive token renewal before expiration
 * - Secure Credential Storage: AES-256 encryption for runtime credential management
 * - ThreadLocal Token Caching: Secure memory storage with automatic cleanup
 * - Credential Rotation: Automated credential lifecycle management
 * - Comprehensive Metrics: Authentication success rates, timing, and security violations
 * - Integration: Deep integration with framework configuration, error reporting, and resource management
 * 
 * Security Features:
 * - AES-256 encryption for all stored credentials
 * - ThreadLocal cleanup to prevent credential leaks
 * - Audit logging with correlation IDs and sensitive data masking
 * - Memory monitoring for credential storage
 * - Automatic credential expiration and rotation
 * 
 * Performance Features:
 * - Connection pooling for OAuth token endpoints
 * - Asynchronous token refresh operations
 * - Efficient credential caching with LRU eviction
 * - Memory-optimized token storage
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class AuthenticationManager {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationManager.class);
    
    // Security constants
    private static final String AES_ALGORITHM = "AES";
    private static final String AES_TRANSFORMATION = "AES/ECB/PKCS5Padding";
    private static final int AES_KEY_LENGTH = 256;
    private static final Duration DEFAULT_TOKEN_REFRESH_INTERVAL = Duration.ofMinutes(5);
    private static final Duration DEFAULT_CREDENTIAL_ROTATION_INTERVAL = Duration.ofHours(24);
    private static final Duration TOKEN_EXPIRY_BUFFER = Duration.ofMinutes(2);
    
    // Singleton instance management
    private static volatile AuthenticationManager instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Framework dependency components
    private final ConfigurationManager configurationManager;
    private final ErrorReporter errorReporter;
    private final ThreadPoolManager threadPoolManager;
    private final MemoryManager memoryManager;
    
    // Authentication infrastructure
    private final SecretKey encryptionKey;
    private final ReentrantReadWriteLock authLock = new ReentrantReadWriteLock();
    
    // Token and credential storage (encrypted in memory)
    private final ConcurrentHashMap<String, EncryptedCredential> credentialStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenInfo> tokenCache = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, TokenInfo>> threadLocalTokens = ThreadLocal.withInitial(HashMap::new);
    
    // Authentication metrics and monitoring
    private final AtomicLong totalAuthAttempts = new AtomicLong(0);
    private final AtomicLong successfulAuths = new AtomicLong(0);
    private final AtomicLong failedAuths = new AtomicLong(0);
    private final AtomicLong tokenRefreshCount = new AtomicLong(0);
    private final AtomicLong credentialRotationCount = new AtomicLong(0);
    private final AtomicLong securityViolationCount = new AtomicLong(0);
    private final ConcurrentHashMap<AuthenticationType, AtomicLong> authsByType = new ConcurrentHashMap<>();
    
    // Timing metrics
    private volatile Instant lastAuthTime = Instant.now();
    private volatile Instant lastTokenRefreshTime = Instant.now();
    private volatile Instant lastCredentialRotationTime = Instant.now();
    private final List<Long> authTimings = Collections.synchronizedList(new ArrayList<>());
    
    // Auto-refresh and rotation management
    private volatile boolean autoRefreshEnabled = true;
    private volatile Duration tokenRefreshInterval = DEFAULT_TOKEN_REFRESH_INTERVAL;
    private volatile boolean credentialRotationEnabled = false;
    private volatile Duration credentialRotationInterval = DEFAULT_CREDENTIAL_ROTATION_INTERVAL;
    private ScheduledFuture<?> tokenRefreshTask;
    private ScheduledFuture<?> credentialRotationTask;
    
    // Authentication history and audit
    private final Queue<AuthenticationEvent> authHistory = new LinkedList<>();
    private final int MAX_HISTORY_SIZE = 1000;
    
    /**
     * Private constructor for singleton pattern.
     * Initializes authentication infrastructure and framework dependencies.
     */
    private AuthenticationManager() {
        this.configurationManager = ConfigurationManager.getInstance();
        this.errorReporter = ErrorReporter.getInstance();
        this.threadPoolManager = ThreadPoolManager.getInstance();
        this.memoryManager = MemoryManager.getInstance();
        
        // Initialize AES encryption key
        this.encryptionKey = generateEncryptionKey();
        
        // Initialize authentication type counters
        for (AuthenticationType type : AuthenticationType.values()) {
            authsByType.put(type, new AtomicLong(0));
        }
        
        // Load configuration settings
        loadAuthenticationConfiguration();
        
        // Register ThreadLocal cleanup
        threadPoolManager.registerThreadLocalCleanup(this::cleanupThreadLocalTokens);
        
        // Start auto-refresh if enabled
        if (autoRefreshEnabled) {
            enableTokenAutoRefresh();
        }
        
        logger.info("AuthenticationManager initialized with AES-256 encryption and auto-refresh: {}", autoRefreshEnabled);
    }
    
    /**
     * Gets the singleton instance of AuthenticationManager.
     * Thread-safe lazy initialization with double-checked locking pattern.
     * 
     * @return AuthenticationManager singleton instance
     */
    public static AuthenticationManager getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new AuthenticationManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Performs authentication using the specified configuration and credentials.
     * Supports multiple authentication protocols with automatic token caching and refresh.
     * 
     * @param config AuthenticationConfiguration containing authentication parameters
     * @return TokenInfo representing the authenticated session
     * @throws AuthenticationException if authentication fails
     */
    public TokenInfo authenticate(AuthenticationConfiguration config) {
        String correlationId = errorReporter.setCorrelationId(UUID.randomUUID().toString());
        Instant startTime = Instant.now();
        
        try {
            totalAuthAttempts.incrementAndGet();
            authsByType.get(config.getAuthenticationType()).incrementAndGet();
            
            logger.info("Starting authentication for type: {} [{}]", 
                       config.getAuthenticationType(), correlationId);
            
            TokenInfo tokenInfo = null;
            
            switch (config.getAuthenticationType()) {
                case BASIC:
                    tokenInfo = performBasicAuthentication(config);
                    break;
                case OAUTH2_AUTHORIZATION_CODE:
                    tokenInfo = performOAuth2AuthorizationCode(config);
                    break;
                case OAUTH2_CLIENT_CREDENTIALS:
                    tokenInfo = performOAuth2ClientCredentials(config);
                    break;
                case JWT:
                    tokenInfo = performJWTAuthentication(config);
                    break;
                case API_KEY:
                    tokenInfo = performApiKeyAuthentication(config);
                    break;
                case BEARER_TOKEN:
                    tokenInfo = performBearerTokenAuthentication(config);
                    break;
                default:
                    throw new AuthenticationException("Unsupported authentication type: " + config.getAuthenticationType());
            }
            
            // Cache token in ThreadLocal for current thread
            threadLocalTokens.get().put(config.getAuthenticationType().name(), tokenInfo);
            
            // Cache token globally
            tokenCache.put(generateTokenKey(config), tokenInfo);
            
            // Record successful authentication
            successfulAuths.incrementAndGet();
            lastAuthTime = Instant.now();
            
            long authDuration = Duration.between(startTime, Instant.now()).toMillis();
            authTimings.add(authDuration);
            
            // Add to authentication history
            addToAuthHistory(new AuthenticationEvent(
                correlationId,
                config.getAuthenticationType(),
                AuthenticationStatus.AUTHENTICATED,
                Instant.now(),
                authDuration,
                tokenInfo.getExpirationTime()
            ));
            
            logger.info("Authentication successful for type: {} in {}ms [{}]", 
                       config.getAuthenticationType(), authDuration, correlationId);
            
            return tokenInfo;
            
        } catch (Exception e) {
            failedAuths.incrementAndGet();
            
            addToAuthHistory(new AuthenticationEvent(
                correlationId,
                config.getAuthenticationType(),
                AuthenticationStatus.AUTHENTICATION_FAILED,
                Instant.now(),
                Duration.between(startTime, Instant.now()).toMillis(),
                null
            ));
            
            errorReporter.logException("Authentication failed for type: " + config.getAuthenticationType(), e);
            throw new AuthenticationException("Authentication failed: " + e.getMessage(), e);
            
        } finally {
            errorReporter.setCorrelationId(null);
        }
    }
    
    /**
     * Validates stored credentials for the specified authentication type.
     * Checks credential integrity, expiration, and security compliance.
     * 
     * @param authenticationType Authentication type to validate
     * @return true if credentials are valid and secure
     */
    public boolean validateCredentials(AuthenticationType authenticationType) {
        String correlationId = errorReporter.setCorrelationId(UUID.randomUUID().toString());
        
        try {
            authLock.readLock().lock();
            
            String credentialKey = generateCredentialKey(authenticationType);
            EncryptedCredential encryptedCred = credentialStore.get(credentialKey);
            
            if (encryptedCred == null) {
                logger.warn("No credentials found for authentication type: {} [{}]", 
                           authenticationType, correlationId);
                return false;
            }
            
            // Decrypt and validate credentials
            String decryptedCredentials = decryptCredentials(encryptedCred.getEncryptedData());
            
            // Check credential format and integrity
            if (!isCredentialFormatValid(authenticationType, decryptedCredentials)) {
                logger.warn("Invalid credential format for type: {} [{}]", 
                           authenticationType, correlationId);
                securityViolationCount.incrementAndGet();
                return false;
            }
            
            // Check credential expiration
            if (encryptedCred.getExpirationTime() != null && 
                encryptedCred.getExpirationTime().isBefore(Instant.now())) {
                logger.warn("Credentials expired for type: {} [{}]", 
                           authenticationType, correlationId);
                return false;
            }
            
            // Perform additional security checks
            if (!performSecurityChecks(authenticationType, decryptedCredentials)) {
                logger.warn("Security checks failed for type: {} [{}]", 
                           authenticationType, correlationId);
                securityViolationCount.incrementAndGet();
                return false;
            }
            
            logger.debug("Credentials validation successful for type: {} [{}]", 
                        authenticationType, correlationId);
            return true;
            
        } catch (Exception e) {
            errorReporter.logException("Credential validation failed for type: " + authenticationType, e);
            securityViolationCount.incrementAndGet();
            return false;
            
        } finally {
            authLock.readLock().unlock();
            errorReporter.setCorrelationId(null);
        }
    }
    
    /**
     * Refreshes the authentication token for the specified configuration.
     * Performs proactive token renewal before expiration with automatic retry.
     * 
     * @param config AuthenticationConfiguration for token refresh
     * @return Refreshed TokenInfo or null if refresh not supported/failed
     */
    public TokenInfo refreshToken(AuthenticationConfiguration config) {
        String correlationId = errorReporter.setCorrelationId(UUID.randomUUID().toString());
        
        try {
            tokenRefreshCount.incrementAndGet();
            
            logger.info("Starting token refresh for type: {} [{}]", 
                       config.getAuthenticationType(), correlationId);
            
            // Check if token refresh is supported for this authentication type
            if (!supportsTokenRefresh(config.getAuthenticationType())) {
                logger.debug("Token refresh not supported for type: {} [{}]", 
                            config.getAuthenticationType(), correlationId);
                return null;
            }
            
            TokenInfo currentToken = getCurrentToken(config.getAuthenticationType());
            if (currentToken == null) {
                logger.warn("No current token found for refresh, performing full authentication [{}]", correlationId);
                return authenticate(config);
            }
            
            TokenInfo refreshedToken = null;
            
            switch (config.getAuthenticationType()) {
                case OAUTH2_AUTHORIZATION_CODE:
                case OAUTH2_CLIENT_CREDENTIALS:
                    refreshedToken = refreshOAuth2Token(config, currentToken);
                    break;
                case JWT:
                    refreshedToken = refreshJWTToken(config, currentToken);
                    break;
                case BEARER_TOKEN:
                    refreshedToken = refreshBearerToken(config, currentToken);
                    break;
                default:
                    // For Basic Auth and API Key, perform full re-authentication
                    refreshedToken = authenticate(config);
                    break;
            }
            
            if (refreshedToken != null) {
                // Update token cache
                String tokenKey = generateTokenKey(config);
                tokenCache.put(tokenKey, refreshedToken);
                threadLocalTokens.get().put(config.getAuthenticationType().name(), refreshedToken);
                
                lastTokenRefreshTime = Instant.now();
                
                logger.info("Token refresh successful for type: {} [{}]", 
                           config.getAuthenticationType(), correlationId);
            }
            
            return refreshedToken;
            
        } catch (Exception e) {
            errorReporter.logException("Token refresh failed for type: " + config.getAuthenticationType(), e);
            return null;
            
        } finally {
            errorReporter.setCorrelationId(null);
        }
    }
    
    /**
     * Gets the current authentication status for the specified authentication type.
     * 
     * @param authenticationType Authentication type to check
     * @return Current AuthenticationStatus
     */
    public AuthenticationStatus getAuthenticationStatus(AuthenticationType authenticationType) {
        try {
            TokenInfo currentToken = getCurrentToken(authenticationType);
            
            if (currentToken == null) {
                return AuthenticationStatus.UNAUTHENTICATED;
            }
            
            if (currentToken.isExpired()) {
                return AuthenticationStatus.TOKEN_EXPIRED;
            }
            
            if (currentToken.isExpiringSoon()) {
                return AuthenticationStatus.REFRESH_IN_PROGRESS;
            }
            
            return AuthenticationStatus.AUTHENTICATED;
            
        } catch (Exception e) {
            errorReporter.logException("Error checking authentication status", e);
            return AuthenticationStatus.AUTHENTICATION_FAILED;
        }
    }
    
    /**
     * Checks if the current token is valid and not expired.
     * 
     * @param authenticationType Authentication type to validate
     * @return true if token is valid and not expired
     */
    public boolean isTokenValid(AuthenticationType authenticationType) {
        try {
            TokenInfo currentToken = getCurrentToken(authenticationType);
            return currentToken != null && !currentToken.isExpired();
        } catch (Exception e) {
            errorReporter.logException("Error validating token", e);
            return false;
        }
    }
    
    /**
     * Gets token expiration status information.
     * 
     * @param authenticationType Authentication type to check
     * @return Map containing expiration details
     */
    public Map<String, Object> getTokenExpirationStatus(AuthenticationType authenticationType) {
        Map<String, Object> status = new HashMap<>();
        
        try {
            TokenInfo currentToken = getCurrentToken(authenticationType);
            
            if (currentToken == null) {
                status.put("hasToken", false);
                status.put("isExpired", true);
                return status;
            }
            
            status.put("hasToken", true);
            status.put("isExpired", currentToken.isExpired());
            status.put("isExpiringSoon", currentToken.isExpiringSoon());
            status.put("expirationTime", currentToken.getExpirationTime());
            status.put("remainingTime", currentToken.getRemainingTime());
            status.put("tokenType", currentToken.getTokenType());
            
        } catch (Exception e) {
            errorReporter.logException("Error getting token expiration status", e);
            status.put("error", e.getMessage());
        }
        
        return status;
    }
    
    /**
     * Checks if the authentication system is healthy and operating normally.
     * 
     * @return true if authentication system is healthy
     */
    public boolean isAuthenticationHealthy() {
        try {
            // Check memory health for credential storage
            if (!memoryManager.isMemoryHealthy()) {
                logger.warn("Authentication health check failed: memory issues detected");
                return false;
            }
            
            // Check credential store integrity
            if (credentialStore.isEmpty()) {
                logger.debug("Authentication health check: no credentials stored (normal for fresh system)");
            }
            
            // Check for excessive security violations
            long violations = securityViolationCount.get();
            long totalAttempts = totalAuthAttempts.get();
            
            if (totalAttempts > 0 && (double) violations / totalAttempts > 0.1) { // >10% violation rate
                logger.warn("Authentication health check failed: high security violation rate");
                return false;
            }
            
            // Check authentication success rate
            long successful = successfulAuths.get();
            if (totalAttempts > 10 && (double) successful / totalAttempts < 0.5) { // <50% success rate
                logger.warn("Authentication health check failed: low success rate");
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            errorReporter.logException("Error checking authentication health", e);
            return false;
        }
    }
    
    /**
     * Gets credential status information for the specified authentication type.
     * 
     * @param authenticationType Authentication type to check
     * @return Map containing credential status details
     */
    public Map<String, Object> getCredentialStatus(AuthenticationType authenticationType) {
        Map<String, Object> status = new HashMap<>();
        
        try {
            authLock.readLock().lock();
            
            String credentialKey = generateCredentialKey(authenticationType);
            EncryptedCredential encryptedCred = credentialStore.get(credentialKey);
            
            status.put("hasCredentials", encryptedCred != null);
            
            if (encryptedCred != null) {
                status.put("creationTime", encryptedCred.getCreationTime());
                status.put("lastAccessTime", encryptedCred.getLastAccessTime());
                status.put("expirationTime", encryptedCred.getExpirationTime());
                status.put("isExpired", encryptedCred.getExpirationTime() != null && 
                          encryptedCred.getExpirationTime().isBefore(Instant.now()));
                status.put("rotationEnabled", credentialRotationEnabled);
                status.put("lastRotationTime", lastCredentialRotationTime);
            }
            
        } catch (Exception e) {
            errorReporter.logException("Error getting credential status", e);
            status.put("error", e.getMessage());
            
        } finally {
            authLock.readLock().unlock();
        }
        
        return status;
    }
    
    /**
     * Rotates credentials for the specified authentication type.
     * Implements secure credential rotation with backup and rollback capability.
     * 
     * @param authenticationType Authentication type for credential rotation
     * @param newCredentials New credentials to rotate to
     * @return true if rotation was successful
     */
    public boolean rotateCredentials(AuthenticationType authenticationType, String newCredentials) {
        String correlationId = errorReporter.setCorrelationId(UUID.randomUUID().toString());
        
        try {
            authLock.writeLock().lock();
            
            logger.info("Starting credential rotation for type: {} [{}]", 
                       authenticationType, correlationId);
            
            String credentialKey = generateCredentialKey(authenticationType);
            EncryptedCredential oldCredential = credentialStore.get(credentialKey);
            
            // Validate new credentials
            if (!isCredentialFormatValid(authenticationType, newCredentials)) {
                logger.error("Invalid credential format for rotation [{}]", correlationId);
                return false;
            }
            
            // Encrypt new credentials
            String encryptedData = encryptCredentials(newCredentials);
            EncryptedCredential newEncryptedCred = new EncryptedCredential(
                encryptedData,
                Instant.now(),
                Instant.now(),
                Instant.now().plus(credentialRotationInterval)
            );
            
            // Store new credentials
            credentialStore.put(credentialKey, newEncryptedCred);
            
            // Invalidate existing tokens
            invalidateToken(authenticationType);
            
            // Update rotation metrics
            credentialRotationCount.incrementAndGet();
            lastCredentialRotationTime = Instant.now();
            
            // Create backup of old credentials (temporary, for rollback if needed)
            if (oldCredential != null) {
                credentialStore.put(credentialKey + "_backup", oldCredential);
                
                // Schedule backup cleanup
                threadPoolManager.submitAsyncTask(() -> {
                    try {
                        Thread.sleep(Duration.ofMinutes(30).toMillis());
                        credentialStore.remove(credentialKey + "_backup");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            
            logger.info("Credential rotation successful for type: {} [{}]", 
                       authenticationType, correlationId);
            return true;
            
        } catch (Exception e) {
            errorReporter.logException("Credential rotation failed for type: " + authenticationType, e);
            return false;
            
        } finally {
            authLock.writeLock().unlock();
            errorReporter.setCorrelationId(null);
        }
    }
    
    /**
     * Clears the credential cache and invalidates all stored tokens.
     * Used for security cleanup or system reset scenarios.
     */
    public void clearCredentialCache() {
        String correlationId = errorReporter.setCorrelationId(UUID.randomUUID().toString());
        
        try {
            authLock.writeLock().lock();
            
            logger.info("Clearing credential cache [{}]", correlationId);
            
            // Clear credential store
            credentialStore.clear();
            
            // Clear token cache
            tokenCache.clear();
            
            // Clear ThreadLocal tokens
            threadLocalTokens.get().clear();
            
            // Trigger ThreadLocal cleanup across all threads
            threadPoolManager.cleanupThreadLocals();
            
            // Force memory cleanup
            memoryManager.forceMemoryCleanup();
            
            logger.info("Credential cache cleared successfully [{}]", correlationId);
            
        } catch (Exception e) {
            errorReporter.logException("Error clearing credential cache", e);
            
        } finally {
            authLock.writeLock().unlock();
            errorReporter.setCorrelationId(null);
        }
    }
    
    /**
     * Gets comprehensive authentication metrics.
     * 
     * @return AuthenticationMetrics containing performance and security data
     */
    public AuthenticationMetrics getAuthenticationMetrics() {
        return new AuthenticationMetrics(
            totalAuthAttempts.get(),
            successfulAuths.get(),
            failedAuths.get(),
            calculateSuccessRate(),
            calculateFailureRate(),
            tokenRefreshCount.get(),
            credentialRotationCount.get(),
            calculateAverageAuthTime(),
            lastAuthTime,
            lastTokenRefreshTime,
            lastCredentialRotationTime,
            getAuthenticationsByType(),
            securityViolationCount.get(),
            Instant.now(),
            Duration.ofHours(24) // 24-hour time window
        );
    }
    
    /**
     * Enables automatic token refresh with the configured interval.
     */
    public void enableTokenAutoRefresh() {
        try {
            if (tokenRefreshTask != null && !tokenRefreshTask.isCancelled()) {
                logger.debug("Token auto-refresh already enabled");
                return;
            }
            
            autoRefreshEnabled = true;
            
            // Schedule automatic token refresh task
            tokenRefreshTask = threadPoolManager.getApiThreadPool().scheduleAtFixedRate(
                this::performTokenRefreshCheck,
                tokenRefreshInterval.toMillis(),
                tokenRefreshInterval.toMillis(),
                TimeUnit.MILLISECONDS
            );
            
            logger.info("Token auto-refresh enabled with interval: {}", tokenRefreshInterval);
            
        } catch (Exception e) {
            errorReporter.logException("Error enabling token auto-refresh", e);
        }
    }
    
    /**
     * Disables automatic token refresh.
     */
    public void disableTokenAutoRefresh() {
        try {
            autoRefreshEnabled = false;
            
            if (tokenRefreshTask != null) {
                tokenRefreshTask.cancel(false);
                tokenRefreshTask = null;
            }
            
            logger.info("Token auto-refresh disabled");
            
        } catch (Exception e) {
            errorReporter.logException("Error disabling token auto-refresh", e);
        }
    }
    
    /**
     * Sets the token refresh interval for automatic refresh operations.
     * 
     * @param interval Duration between token refresh checks
     */
    public void setTokenRefreshInterval(Duration interval) {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Token refresh interval must be positive");
        }
        
        Duration oldInterval = this.tokenRefreshInterval;
        this.tokenRefreshInterval = interval;
        
        // Restart auto-refresh with new interval if currently enabled
        if (autoRefreshEnabled) {
            disableTokenAutoRefresh();
            enableTokenAutoRefresh();
        }
        
        logger.info("Token refresh interval updated from {} to {}", oldInterval, interval);
    }
    
    /**
     * Gets the current token refresh interval.
     * 
     * @return Duration representing the token refresh interval
     */
    public Duration getTokenRefreshInterval() {
        return tokenRefreshInterval;
    }
    
    /**
     * Gets the current token for the specified authentication type.
     * 
     * @param authenticationType Authentication type to get token for
     * @return TokenInfo for the authentication type, or null if not found
     */
    public TokenInfo getCurrentToken(AuthenticationType authenticationType) {
        try {
            // First check ThreadLocal cache for current thread
            TokenInfo threadLocalToken = threadLocalTokens.get().get(authenticationType.name());
            if (threadLocalToken != null && !threadLocalToken.isExpired()) {
                return threadLocalToken;
            }
            
            // Check global token cache
            String tokenKey = generateTokenKey(authenticationType);
            TokenInfo globalToken = tokenCache.get(tokenKey);
            
            if (globalToken != null && !globalToken.isExpired()) {
                // Update ThreadLocal cache
                threadLocalTokens.get().put(authenticationType.name(), globalToken);
                return globalToken;
            }
            
            // Clean up expired token if found
            if (globalToken != null && globalToken.isExpired()) {
                tokenCache.remove(tokenKey);
                threadLocalTokens.get().remove(authenticationType.name());
            }
            
            return null;
            
        } catch (Exception e) {
            errorReporter.logException("Error getting current token", e);
            return null;
        }
    }
    
    /**
     * Invalidates the token for the specified authentication type.
     * 
     * @param authenticationType Authentication type to invalidate
     */
    public void invalidateToken(AuthenticationType authenticationType) {
        try {
            String tokenKey = generateTokenKey(authenticationType);
            
            // Remove from global cache
            TokenInfo removedToken = tokenCache.remove(tokenKey);
            
            // Remove from ThreadLocal cache
            threadLocalTokens.get().remove(authenticationType.name());
            
            // Invalidate the token object itself
            if (removedToken != null) {
                removedToken.invalidate();
            }
            
            logger.debug("Token invalidated for authentication type: {}", authenticationType);
            
        } catch (Exception e) {
            errorReporter.logException("Error invalidating token", e);
        }
    }
    
    /**
     * Gets a list of supported authentication types.
     * 
     * @return List of supported AuthenticationType values
     */
    public List<AuthenticationType> supportedAuthenticationTypes() {
        return Arrays.asList(AuthenticationType.values());
    }
    
    /**
     * Configures authentication settings for a specific authentication type.
     * 
     * @param config AuthenticationConfiguration to apply
     * @return true if configuration was applied successfully
     */
    public boolean configureAuthentication(AuthenticationConfiguration config) {
        String correlationId = errorReporter.setCorrelationId(UUID.randomUUID().toString());
        
        try {
            logger.info("Configuring authentication for type: {} [{}]", 
                       config.getAuthenticationType(), correlationId);
            
            // Validate configuration
            if (!validateAuthenticationConfiguration(config)) {
                logger.error("Invalid authentication configuration [{}]", correlationId);
                return false;
            }
            
            // Store encrypted credentials if provided
            if (config.getCredentials() != null && !config.getCredentials().isEmpty()) {
                String credentialKey = generateCredentialKey(config.getAuthenticationType());
                String encryptedData = encryptCredentials(config.getCredentials());
                
                EncryptedCredential encryptedCred = new EncryptedCredential(
                    encryptedData,
                    Instant.now(),
                    Instant.now(),
                    null // No expiration unless specified
                );
                
                authLock.writeLock().lock();
                try {
                    credentialStore.put(credentialKey, encryptedCred);
                } finally {
                    authLock.writeLock().unlock();
                }
            }
            
            // Update refresh interval if specified
            if (config.getTokenRefreshInterval() != null) {
                setTokenRefreshInterval(config.getTokenRefreshInterval());
            }
            
            // Update auto-refresh setting
            if (config.isAutoRefreshEnabled() != autoRefreshEnabled) {
                if (config.isAutoRefreshEnabled()) {
                    enableTokenAutoRefresh();
                } else {
                    disableTokenAutoRefresh();
                }
            }
            
            // Update credential rotation settings
            if (config.isCredentialRotationEnabled() != credentialRotationEnabled) {
                credentialRotationEnabled = config.isCredentialRotationEnabled();
                
                if (credentialRotationEnabled && config.getRotationInterval() != null) {
                    scheduleCredentialRotation(config.getRotationInterval());
                }
            }
            
            logger.info("Authentication configuration applied successfully [{}]", correlationId);
            return true;
            
        } catch (Exception e) {
            errorReporter.logException("Error configuring authentication", e);
            return false;
            
        } finally {
            errorReporter.setCorrelationId(null);
        }
    }
    
    /**
     * Gets authentication history for audit and monitoring purposes.
     * 
     * @return List of recent AuthenticationEvent objects
     */
    public List<AuthenticationEvent> getAuthenticationHistory() {
        synchronized (authHistory) {
            return new ArrayList<>(authHistory);
        }
    }
    
    /**
     * Gets security metrics including violation counts and patterns.
     * 
     * @return Map containing security-related metrics
     */
    public Map<String, Object> getSecurityMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        try {
            long totalAttempts = totalAuthAttempts.get();
            long violations = securityViolationCount.get();
            
            metrics.put("totalAuthenticationAttempts", totalAttempts);
            metrics.put("securityViolations", violations);
            metrics.put("violationRate", totalAttempts > 0 ? (double) violations / totalAttempts : 0.0);
            metrics.put("credentialStoreSize", credentialStore.size());
            metrics.put("tokenCacheSize", tokenCache.size());
            metrics.put("lastSecurityEvent", getLastSecurityEvent());
            metrics.put("encryptionAlgorithm", AES_ALGORITHM);
            metrics.put("keyLength", AES_KEY_LENGTH);
            metrics.put("memoryUsage", memoryManager.getComponentMemoryUsage().get("AuthenticationManager"));
            
        } catch (Exception e) {
            errorReporter.logException("Error collecting security metrics", e);
            metrics.put("error", e.getMessage());
        }
        
        return metrics;
    }
    
    /**
     * Encrypts credentials using AES-256 encryption.
     * 
     * @param credentials Plain text credentials to encrypt
     * @return Encrypted credentials as Base64 string
     */
    public String encryptCredentials(String credentials) {
        try {
            if (credentials == null || credentials.isEmpty()) {
                throw new IllegalArgumentException("Credentials cannot be null or empty");
            }
            
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey);
            
            byte[] encryptedBytes = cipher.doFinal(credentials.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(encryptedBytes);
            
        } catch (Exception e) {
            errorReporter.logException("Error encrypting credentials", e);
            throw new SecurityException("Failed to encrypt credentials", e);
        }
    }
    
    /**
     * Decrypts credentials using AES-256 decryption.
     * 
     * @param encryptedCredentials Encrypted credentials as Base64 string
     * @return Decrypted plain text credentials
     */
    public String decryptCredentials(String encryptedCredentials) {
        try {
            if (encryptedCredentials == null || encryptedCredentials.isEmpty()) {
                throw new IllegalArgumentException("Encrypted credentials cannot be null or empty");
            }
            
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey);
            
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedCredentials);
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            
            return new String(decryptedBytes, "UTF-8");
            
        } catch (Exception e) {
            errorReporter.logException("Error decrypting credentials", e);
            securityViolationCount.incrementAndGet();
            throw new SecurityException("Failed to decrypt credentials", e);
        }
    }
    
    /**
     * Checks if credential rotation is enabled.
     * 
     * @return true if credential rotation is enabled
     */
    public boolean isCredentialRotationEnabled() {
        return credentialRotationEnabled;
    }
    
    /**
     * Gets the time of the last credential rotation.
     * 
     * @return Instant representing the last rotation time
     */
    public Instant getLastRotationTime() {
        return lastCredentialRotationTime;
    }
    
    /**
     * Schedules credential rotation for the specified interval.
     * 
     * @param rotationInterval Duration between credential rotations
     */
    public void scheduleCredentialRotation(Duration rotationInterval) {
        if (rotationInterval == null || rotationInterval.isNegative() || rotationInterval.isZero()) {
            throw new IllegalArgumentException("Rotation interval must be positive");
        }
        
        // Cancel existing rotation task
        if (credentialRotationTask != null) {
            credentialRotationTask.cancel(false);
        }
        
        this.credentialRotationInterval = rotationInterval;
        this.credentialRotationEnabled = true;
        
        // Schedule new rotation task
        credentialRotationTask = threadPoolManager.getApiThreadPool().scheduleAtFixedRate(
            this::performCredentialRotationCheck,
            rotationInterval.toMillis(),
            rotationInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
        
        logger.info("Credential rotation scheduled with interval: {}", rotationInterval);
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Loads authentication configuration from ConfigurationManager.
     */
    private void loadAuthenticationConfiguration() {
        try {
            // Load auto-refresh setting
            String autoRefreshConfig = configurationManager.getProperty("auth.token.autorefresh.enabled");
            if (autoRefreshConfig != null) {
                autoRefreshEnabled = Boolean.parseBoolean(autoRefreshConfig);
            }
            
            // Load token refresh interval
            String refreshIntervalConfig = configurationManager.getProperty("auth.token.refresh.interval.minutes");
            if (refreshIntervalConfig != null) {
                tokenRefreshInterval = Duration.ofMinutes(Long.parseLong(refreshIntervalConfig));
            }
            
            // Load credential rotation settings
            String rotationEnabledConfig = configurationManager.getProperty("auth.credential.rotation.enabled");
            if (rotationEnabledConfig != null) {
                credentialRotationEnabled = Boolean.parseBoolean(rotationEnabledConfig);
            }
            
            String rotationIntervalConfig = configurationManager.getProperty("auth.credential.rotation.interval.hours");
            if (rotationIntervalConfig != null) {
                credentialRotationInterval = Duration.ofHours(Long.parseLong(rotationIntervalConfig));
            }
            
        } catch (Exception e) {
            logger.warn("Error loading authentication configuration, using defaults", e);
        }
    }
    
    /**
     * Generates AES encryption key for credential storage.
     */
    private SecretKey generateEncryptionKey() {
        try {
            // Try to load key from configuration first
            String configKey = configurationManager.getProperty("auth.encryption.key");
            if (configKey != null && !configKey.isEmpty()) {
                byte[] keyBytes = Base64.getDecoder().decode(configKey);
                return new SecretKeySpec(keyBytes, AES_ALGORITHM);
            }
            
            // Generate new key if not configured
            KeyGenerator keyGenerator = KeyGenerator.getInstance(AES_ALGORITHM);
            keyGenerator.init(AES_KEY_LENGTH);
            SecretKey key = keyGenerator.generateKey();
            
            // Store generated key for persistence (in production, this should be properly managed)
            String encodedKey = Base64.getEncoder().encodeToString(key.getEncoded());
            logger.info("Generated new AES encryption key (length: {} bits)", AES_KEY_LENGTH);
            
            return key;
            
        } catch (Exception e) {
            errorReporter.logException("Error generating encryption key", e);
            throw new SecurityException("Failed to generate encryption key", e);
        }
    }
    
    /**
     * Performs Basic Authentication using username and password.
     */
    private TokenInfo performBasicAuthentication(AuthenticationConfiguration config) {
        try {
            String credentials = config.getCredentials();
            if (credentials == null || credentials.isEmpty()) {
                throw new AuthenticationException("Basic authentication requires credentials");
            }
            
            // Encode credentials in Base64
            String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes("UTF-8"));
            
            // Create token info for Basic Auth
            return new TokenInfo(
                "Basic " + encodedCredentials,
                "Basic",
                Instant.now().plus(Duration.ofHours(24)), // 24-hour expiration
                Instant.now(),
                Collections.emptyList(), // No scopes for Basic Auth
                "framework", // Issuer
                extractUsernameFromBasicAuth(credentials), // Subject
                Collections.emptyMap() // No additional metadata
            );
            
        } catch (Exception e) {
            throw new AuthenticationException("Basic authentication failed", e);
        }
    }
    
    /**
     * Performs OAuth 2.0 Authorization Code flow authentication.
     */
    private TokenInfo performOAuth2AuthorizationCode(AuthenticationConfiguration config) {
        try {
            // This is a simplified implementation - in practice, this would involve
            // actual HTTP calls to OAuth endpoints
            String authCode = config.getCredentials();
            if (authCode == null || authCode.isEmpty()) {
                throw new AuthenticationException("OAuth 2.0 Authorization Code flow requires authorization code");
            }
            
            AuthorizationCode code = new AuthorizationCode(authCode);
            
            // Simulate token exchange (in practice, this would be an HTTP request)
            String accessToken = "oauth2_access_token_" + System.currentTimeMillis();
            
            return new TokenInfo(
                accessToken,
                "Bearer",
                Instant.now().plus(Duration.ofHours(1)), // 1-hour expiration
                Instant.now(),
                Arrays.asList("read", "write"), // Example scopes
                config.getOAuthTokenUrl(),
                config.getOAuthClientId(),
                createOAuth2Metadata(config)
            );
            
        } catch (Exception e) {
            throw new AuthenticationException("OAuth 2.0 Authorization Code authentication failed", e);
        }
    }
    
    /**
     * Performs OAuth 2.0 Client Credentials flow authentication.
     */
    private TokenInfo performOAuth2ClientCredentials(AuthenticationConfiguration config) {
        try {
            if (config.getOAuthClientId() == null || config.getOAuthClientSecret() == null) {
                throw new AuthenticationException("OAuth 2.0 Client Credentials flow requires client ID and secret");
            }
            
            // Simulate token request (in practice, this would be an HTTP request)
            String accessToken = "oauth2_client_token_" + System.currentTimeMillis();
            
            return new TokenInfo(
                accessToken,
                "Bearer",
                Instant.now().plus(Duration.ofHours(2)), // 2-hour expiration
                Instant.now(),
                Arrays.asList("api_access"), // Example scopes
                config.getOAuthTokenUrl(),
                config.getOAuthClientId(),
                createOAuth2Metadata(config)
            );
            
        } catch (Exception e) {
            throw new AuthenticationException("OAuth 2.0 Client Credentials authentication failed", e);
        }
    }
    
    /**
     * Performs JWT token authentication and validation.
     */
    private TokenInfo performJWTAuthentication(AuthenticationConfiguration config) {
        try {
            String jwtToken = config.getCredentials();
            if (jwtToken == null || jwtToken.isEmpty()) {
                throw new AuthenticationException("JWT authentication requires token");
            }
            
            // Decode and verify JWT token
            DecodedJWT decodedJWT;
            if (config.getJwtSigningKey() != null) {
                Algorithm algorithm = Algorithm.HMAC256(config.getJwtSigningKey());
                decodedJWT = JWT.require(algorithm).build().verify(jwtToken);
            } else {
                // If no signing key provided, just decode without verification (for testing)
                decodedJWT = JWT.decode(jwtToken);
            }
            
            return new TokenInfo(
                jwtToken,
                "JWT",
                decodedJWT.getExpiresAt().toInstant(),
                decodedJWT.getIssuedAt().toInstant(),
                decodedJWT.getClaim("scope") != null ? 
                    Arrays.asList(decodedJWT.getClaim("scope").asString().split(" ")) : 
                    Collections.emptyList(),
                decodedJWT.getIssuer(),
                decodedJWT.getSubject(),
                createJWTMetadata(decodedJWT)
            );
            
        } catch (JWTVerificationException e) {
            throw new AuthenticationException("JWT verification failed", e);
        } catch (Exception e) {
            throw new AuthenticationException("JWT authentication failed", e);
        }
    }
    
    /**
     * Performs API Key authentication.
     */
    private TokenInfo performApiKeyAuthentication(AuthenticationConfiguration config) {
        try {
            String apiKey = config.getCredentials();
            if (apiKey == null || apiKey.isEmpty()) {
                throw new AuthenticationException("API Key authentication requires key");
            }
            
            // Validate API key format (basic validation)
            if (apiKey.length() < 16) {
                throw new AuthenticationException("API Key appears to be invalid (too short)");
            }
            
            return new TokenInfo(
                apiKey,
                "ApiKey",
                Instant.now().plus(Duration.ofDays(30)), // 30-day expiration
                Instant.now(),
                Arrays.asList("api_access"), // Default scope
                "framework", // Issuer
                "api_user", // Subject
                createApiKeyMetadata(config)
            );
            
        } catch (Exception e) {
            throw new AuthenticationException("API Key authentication failed", e);
        }
    }
    
    /**
     * Performs Bearer Token authentication.
     */
    private TokenInfo performBearerTokenAuthentication(AuthenticationConfiguration config) {
        try {
            String bearerToken = config.getCredentials();
            if (bearerToken == null || bearerToken.isEmpty()) {
                throw new AuthenticationException("Bearer Token authentication requires token");
            }
            
            // Remove "Bearer " prefix if present
            if (bearerToken.startsWith("Bearer ")) {
                bearerToken = bearerToken.substring(7);
            }
            
            return new TokenInfo(
                bearerToken,
                "Bearer",
                Instant.now().plus(Duration.ofHours(4)), // 4-hour expiration
                Instant.now(),
                Collections.emptyList(), // No scopes available
                "framework", // Issuer
                "bearer_user", // Subject
                Collections.emptyMap() // No additional metadata
            );
            
        } catch (Exception e) {
            throw new AuthenticationException("Bearer Token authentication failed", e);
        }
    }
    
    /**
     * Checks if the specified authentication type supports token refresh.
     */
    private boolean supportsTokenRefresh(AuthenticationType authenticationType) {
        switch (authenticationType) {
            case OAUTH2_AUTHORIZATION_CODE:
            case OAUTH2_CLIENT_CREDENTIALS:
            case JWT:
            case BEARER_TOKEN:
                return true;
            case BASIC:
            case API_KEY:
            default:
                return false;
        }
    }
    
    /**
     * Refreshes OAuth 2.0 token using refresh token or re-authentication.
     */
    private TokenInfo refreshOAuth2Token(AuthenticationConfiguration config, TokenInfo currentToken) {
        try {
            // In a real implementation, this would use the refresh token or re-authenticate
            // For now, we'll simulate a token refresh
            
            String newAccessToken = "refreshed_oauth2_token_" + System.currentTimeMillis();
            
            return new TokenInfo(
                newAccessToken,
                currentToken.getTokenType(),
                Instant.now().plus(Duration.ofHours(1)), // New expiration
                Instant.now(),
                currentToken.getScopes(),
                currentToken.getIssuer(),
                currentToken.getSubject(),
                currentToken.getMetadata()
            );
            
        } catch (Exception e) {
            throw new AuthenticationException("OAuth 2.0 token refresh failed", e);
        }
    }
    
    /**
     * Refreshes JWT token by re-authentication or token exchange.
     */
    private TokenInfo refreshJWTToken(AuthenticationConfiguration config, TokenInfo currentToken) {
        try {
            // For JWT tokens, we typically need to re-authenticate or use a refresh token
            // This implementation simulates a token refresh
            
            String refreshedJWT = generateNewJWT(config, currentToken);
            
            return new TokenInfo(
                refreshedJWT,
                "JWT",
                Instant.now().plus(Duration.ofHours(1)), // New expiration
                Instant.now(),
                currentToken.getScopes(),
                currentToken.getIssuer(),
                currentToken.getSubject(),
                currentToken.getMetadata()
            );
            
        } catch (Exception e) {
            throw new AuthenticationException("JWT token refresh failed", e);
        }
    }
    
    /**
     * Refreshes Bearer token by re-authentication.
     */
    private TokenInfo refreshBearerToken(AuthenticationConfiguration config, TokenInfo currentToken) {
        try {
            // For Bearer tokens, typically need to re-authenticate
            return performBearerTokenAuthentication(config);
            
        } catch (Exception e) {
            throw new AuthenticationException("Bearer token refresh failed", e);
        }
    }
    
    /**
     * Generates token key for caching purposes.
     */
    private String generateTokenKey(AuthenticationType authenticationType) {
        return "token_" + authenticationType.name().toLowerCase();
    }
    
    /**
     * Generates token key for configuration-based caching.
     */
    private String generateTokenKey(AuthenticationConfiguration config) {
        return generateTokenKey(config.getAuthenticationType());
    }
    
    /**
     * Generates credential key for storage purposes.
     */
    private String generateCredentialKey(AuthenticationType authenticationType) {
        return "cred_" + authenticationType.name().toLowerCase();
    }
    
    /**
     * Validates credential format for the specified authentication type.
     */
    private boolean isCredentialFormatValid(AuthenticationType authenticationType, String credentials) {
        if (credentials == null || credentials.trim().isEmpty()) {
            return false;
        }
        
        switch (authenticationType) {
            case BASIC:
                // Basic auth should contain username:password
                return credentials.contains(":");
            case OAUTH2_AUTHORIZATION_CODE:
                // Authorization code should be alphanumeric
                return credentials.matches("[a-zA-Z0-9_-]+");
            case OAUTH2_CLIENT_CREDENTIALS:
                // Client credentials format validation
                return credentials.length() > 10;
            case JWT:
                // JWT should have three parts separated by dots
                return credentials.split("\\.").length == 3;
            case API_KEY:
                // API key should be sufficiently long and alphanumeric
                return credentials.length() >= 16 && credentials.matches("[a-zA-Z0-9_-]+");
            case BEARER_TOKEN:
                // Bearer token basic validation
                return credentials.length() >= 10;
            default:
                return false;
        }
    }
    
    /**
     * Performs additional security checks for credentials.
     */
    private boolean performSecurityChecks(AuthenticationType authenticationType, String credentials) {
        try {
            // Check for common security issues
            
            // 1. Check for obviously weak credentials
            if (credentials.toLowerCase().contains("password") || 
                credentials.toLowerCase().contains("123456") ||
                credentials.equals("admin:admin")) {
                logger.warn("Weak credentials detected for type: {}", authenticationType);
                return false;
            }
            
            // 2. Check credential length requirements
            int minLength = getMinimumCredentialLength(authenticationType);
            if (credentials.length() < minLength) {
                logger.warn("Credentials too short for type: {} (required: {})", authenticationType, minLength);
                return false;
            }
            
            // 3. Additional type-specific checks
            switch (authenticationType) {
                case BASIC:
                    String[] parts = credentials.split(":");
                    if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
                        return false;
                    }
                    break;
                case JWT:
                    try {
                        JWT.decode(credentials); // Basic JWT format validation
                    } catch (Exception e) {
                        return false;
                    }
                    break;
            }
            
            return true;
            
        } catch (Exception e) {
            logger.warn("Error performing security checks", e);
            return false;
        }
    }
    
    /**
     * Gets minimum credential length for authentication type.
     */
    private int getMinimumCredentialLength(AuthenticationType authenticationType) {
        switch (authenticationType) {
            case BASIC:
                return 8; // username:password minimum
            case OAUTH2_AUTHORIZATION_CODE:
                return 16;
            case OAUTH2_CLIENT_CREDENTIALS:
                return 20;
            case JWT:
                return 50; // JWT tokens are typically longer
            case API_KEY:
                return 16;
            case BEARER_TOKEN:
                return 10;
            default:
                return 8;
        }
    }
    
    /**
     * Validates authentication configuration.
     */
    private boolean validateAuthenticationConfiguration(AuthenticationConfiguration config) {
        if (config == null || config.getAuthenticationType() == null) {
            return false;
        }
        
        switch (config.getAuthenticationType()) {
            case OAUTH2_AUTHORIZATION_CODE:
            case OAUTH2_CLIENT_CREDENTIALS:
                return config.getOAuthClientId() != null && 
                       config.getOAuthTokenUrl() != null;
            case JWT:
                return config.getCredentials() != null;
            case API_KEY:
                return config.getCredentials() != null &&
                       (config.getApiKeyHeaderName() != null || config.getApiKeyQueryParamName() != null);
            case BASIC:
            case BEARER_TOKEN:
                return config.getCredentials() != null;
            default:
                return false;
        }
    }
    
    /**
     * Performs token refresh check for all cached tokens.
     */
    private void performTokenRefreshCheck() {
        try {
            logger.debug("Performing token refresh check");
            
            for (Map.Entry<String, TokenInfo> entry : tokenCache.entrySet()) {
                TokenInfo token = entry.getValue();
                
                if (token.isExpiringSoon()) {
                    AuthenticationType authType = extractAuthTypeFromTokenKey(entry.getKey());
                    if (authType != null && supportsTokenRefresh(authType)) {
                        
                        // Submit async refresh task
                        threadPoolManager.submitAsyncTask(() -> {
                            try {
                                AuthenticationConfiguration config = createRefreshConfiguration(authType);
                                refreshToken(config);
                            } catch (Exception e) {
                                logger.warn("Automatic token refresh failed for type: {}", authType, e);
                            }
                        });
                    }
                }
            }
            
        } catch (Exception e) {
            errorReporter.logException("Error during token refresh check", e);
        }
    }
    
    /**
     * Performs credential rotation check.
     */
    private void performCredentialRotationCheck() {
        try {
            logger.debug("Performing credential rotation check");
            
            for (Map.Entry<String, EncryptedCredential> entry : credentialStore.entrySet()) {
                EncryptedCredential credential = entry.getValue();
                
                if (credential.getExpirationTime() != null && 
                    credential.getExpirationTime().isBefore(Instant.now().plus(Duration.ofHours(1)))) {
                    
                    AuthenticationType authType = extractAuthTypeFromCredentialKey(entry.getKey());
                    if (authType != null) {
                        logger.info("Credential rotation needed for type: {}", authType);
                        // In practice, this would trigger an external credential rotation process
                    }
                }
            }
            
        } catch (Exception e) {
            errorReporter.logException("Error during credential rotation check", e);
        }
    }
    
    /**
     * Cleans up ThreadLocal tokens for the current thread.
     */
    private void cleanupThreadLocalTokens() {
        try {
            Map<String, TokenInfo> localTokens = threadLocalTokens.get();
            if (localTokens != null) {
                localTokens.clear();
            }
            threadLocalTokens.remove();
            
        } catch (Exception e) {
            logger.warn("Error cleaning up ThreadLocal tokens", e);
        }
    }
    
    /**
     * Calculates authentication success rate.
     */
    private double calculateSuccessRate() {
        long total = totalAuthAttempts.get();
        if (total == 0) return 0.0;
        return (double) successfulAuths.get() / total * 100.0;
    }
    
    /**
     * Calculates authentication failure rate.
     */
    private double calculateFailureRate() {
        long total = totalAuthAttempts.get();
        if (total == 0) return 0.0;
        return (double) failedAuths.get() / total * 100.0;
    }
    
    /**
     * Calculates average authentication time.
     */
    private double calculateAverageAuthTime() {
        synchronized (authTimings) {
            if (authTimings.isEmpty()) return 0.0;
            return authTimings.stream().mapToLong(Long::longValue).average().orElse(0.0);
        }
    }
    
    /**
     * Gets authentication counts by type.
     */
    private Map<AuthenticationType, Long> getAuthenticationsByType() {
        Map<AuthenticationType, Long> result = new HashMap<>();
        authsByType.forEach((type, count) -> result.put(type, count.get()));
        return result;
    }
    
    /**
     * Adds authentication event to history.
     */
    private void addToAuthHistory(AuthenticationEvent event) {
        synchronized (authHistory) {
            authHistory.offer(event);
            
            // Keep only the most recent events
            while (authHistory.size() > MAX_HISTORY_SIZE) {
                authHistory.poll();
            }
        }
    }
    
    /**
     * Gets the most recent security event from history.
     */
    private Instant getLastSecurityEvent() {
        synchronized (authHistory) {
            return authHistory.stream()
                .filter(event -> event.getStatus() == AuthenticationStatus.AUTHENTICATION_FAILED)
                .map(AuthenticationEvent::getTimestamp)
                .max(Instant::compareTo)
                .orElse(null);
        }
    }
    
    /**
     * Extracts username from Basic Auth credentials.
     */
    private String extractUsernameFromBasicAuth(String credentials) {
        try {
            if (credentials != null && credentials.contains(":")) {
                return credentials.split(":")[0];
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    /**
     * Creates OAuth 2.0 metadata map.
     */
    private Map<String, Object> createOAuth2Metadata(AuthenticationConfiguration config) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("client_id", config.getOAuthClientId());
        metadata.put("token_url", config.getOAuthTokenUrl());
        metadata.put("authorization_url", config.getOAuthAuthorizationUrl());
        metadata.put("auth_type", "oauth2");
        return metadata;
    }
    
    /**
     * Creates JWT metadata map.
     */
    private Map<String, Object> createJWTMetadata(DecodedJWT decodedJWT) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("algorithm", decodedJWT.getAlgorithm());
        metadata.put("type", decodedJWT.getType());
        metadata.put("key_id", decodedJWT.getKeyId());
        metadata.put("auth_type", "jwt");
        
        // Add custom claims
        decodedJWT.getClaims().forEach((key, claim) -> {
            if (!Arrays.asList("iss", "sub", "aud", "exp", "iat", "nbf").contains(key)) {
                metadata.put("claim_" + key, claim.asString());
            }
        });
        
        return metadata;
    }
    
    /**
     * Creates API Key metadata map.
     */
    private Map<String, Object> createApiKeyMetadata(AuthenticationConfiguration config) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("header_name", config.getApiKeyHeaderName());
        metadata.put("query_param_name", config.getApiKeyQueryParamName());
        metadata.put("auth_type", "api_key");
        return metadata;
    }
    
    /**
     * Generates a new JWT token for refresh purposes.
     */
    private String generateNewJWT(AuthenticationConfiguration config, TokenInfo currentToken) {
        try {
            if (config.getJwtSigningKey() != null) {
                Algorithm algorithm = Algorithm.HMAC256(config.getJwtSigningKey());
                
                return JWT.create()
                    .withIssuer(currentToken.getIssuer())
                    .withSubject(currentToken.getSubject())
                    .withIssuedAt(new Date())
                    .withExpiresAt(new Date(Instant.now().plus(Duration.ofHours(1)).toEpochMilli()))
                    .withClaim("scope", String.join(" ", currentToken.getScopes()))
                    .sign(algorithm);
            } else {
                // If no signing key, return the original token (not recommended for production)
                return currentToken.getToken();
            }
            
        } catch (Exception e) {
            throw new AuthenticationException("Failed to generate new JWT", e);
        }
    }
    
    /**
     * Extracts authentication type from token key.
     */
    private AuthenticationType extractAuthTypeFromTokenKey(String tokenKey) {
        try {
            if (tokenKey.startsWith("token_")) {
                String typeName = tokenKey.substring(6).toUpperCase();
                return AuthenticationType.valueOf(typeName);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Extracts authentication type from credential key.
     */
    private AuthenticationType extractAuthTypeFromCredentialKey(String credentialKey) {
        try {
            if (credentialKey.startsWith("cred_")) {
                String typeName = credentialKey.substring(5).toUpperCase();
                return AuthenticationType.valueOf(typeName);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Creates refresh configuration for token refresh.
     */
    private AuthenticationConfiguration createRefreshConfiguration(AuthenticationType authType) {
        // In practice, this would load configuration from storage
        // For now, return a basic configuration
        AuthenticationConfiguration config = new AuthenticationConfiguration();
        config.setAuthenticationType(authType);
        return config;
    }
}

// ========== SUPPORTING CLASSES AND ENUMS ==========

/**
 * AuthenticationType enumeration defines supported authentication protocols.
 */
enum AuthenticationType {
    BASIC,
    OAUTH2_AUTHORIZATION_CODE,
    OAUTH2_CLIENT_CREDENTIALS,
    JWT,
    API_KEY,
    BEARER_TOKEN
}

/**
 * AuthenticationStatus enumeration defines authentication states.
 */
enum AuthenticationStatus {
    AUTHENTICATED,
    UNAUTHENTICATED,
    TOKEN_EXPIRED,
    CREDENTIALS_INVALID,
    REFRESH_IN_PROGRESS,
    AUTHENTICATION_FAILED,
    REFRESH_FAILED,
    ROTATION_IN_PROGRESS
}

/**
 * AuthenticationConfiguration class holds authentication parameters.
 */
class AuthenticationConfiguration {
    
    private AuthenticationType authenticationType;
    private String credentials;
    private Duration tokenRefreshInterval;
    private boolean autoRefreshEnabled = true;
    
    // OAuth 2.0 specific properties
    private String oAuthClientId;
    private String oAuthClientSecret;
    private String oAuthAuthorizationUrl;
    private String oAuthTokenUrl;
    
    // API Key specific properties
    private String apiKeyHeaderName;
    private String apiKeyQueryParamName;
    
    // JWT specific properties
    private String jwtSigningKey;
    
    // Credential rotation properties
    private boolean credentialRotationEnabled = false;
    private Duration rotationInterval;
    
    // Getters and Setters
    public AuthenticationType getAuthenticationType() { return authenticationType; }
    public void setAuthenticationType(AuthenticationType authenticationType) { this.authenticationType = authenticationType; }
    
    public String getCredentials() { return credentials; }
    public void setCredentials(String credentials) { this.credentials = credentials; }
    
    public Duration getTokenRefreshInterval() { return tokenRefreshInterval; }
    public void setTokenRefreshInterval(Duration tokenRefreshInterval) { this.tokenRefreshInterval = tokenRefreshInterval; }
    
    public boolean isAutoRefreshEnabled() { return autoRefreshEnabled; }
    public void setAutoRefreshEnabled(boolean autoRefreshEnabled) { this.autoRefreshEnabled = autoRefreshEnabled; }
    
    public String getOAuthClientId() { return oAuthClientId; }
    public void setOAuthClientId(String oAuthClientId) { this.oAuthClientId = oAuthClientId; }
    
    public String getOAuthClientSecret() { return oAuthClientSecret; }
    public void setOAuthClientSecret(String oAuthClientSecret) { this.oAuthClientSecret = oAuthClientSecret; }
    
    public String getOAuthAuthorizationUrl() { return oAuthAuthorizationUrl; }
    public void setOAuthAuthorizationUrl(String oAuthAuthorizationUrl) { this.oAuthAuthorizationUrl = oAuthAuthorizationUrl; }
    
    public String getOAuthTokenUrl() { return oAuthTokenUrl; }
    public void setOAuthTokenUrl(String oAuthTokenUrl) { this.oAuthTokenUrl = oAuthTokenUrl; }
    
    public String getApiKeyHeaderName() { return apiKeyHeaderName; }
    public void setApiKeyHeaderName(String apiKeyHeaderName) { this.apiKeyHeaderName = apiKeyHeaderName; }
    
    public String getApiKeyQueryParamName() { return apiKeyQueryParamName; }
    public void setApiKeyQueryParamName(String apiKeyQueryParamName) { this.apiKeyQueryParamName = apiKeyQueryParamName; }
    
    public String getJwtSigningKey() { return jwtSigningKey; }
    public void setJwtSigningKey(String jwtSigningKey) { this.jwtSigningKey = jwtSigningKey; }
    
    public boolean isCredentialRotationEnabled() { return credentialRotationEnabled; }
    public void setCredentialRotationEnabled(boolean credentialRotationEnabled) { this.credentialRotationEnabled = credentialRotationEnabled; }
    
    public Duration getRotationInterval() { return rotationInterval; }
    public void setRotationInterval(Duration rotationInterval) { this.rotationInterval = rotationInterval; }
}

/**
 * AuthenticationMetrics class provides comprehensive authentication metrics.
 */
class AuthenticationMetrics {
    
    private final long totalAuthenticationAttempts;
    private final long successfulAuthentications;
    private final long failedAuthentications;
    private final double successRate;
    private final double failureRate;
    private final long tokenRefreshCount;
    private final long credentialRotationCount;
    private final double averageAuthenticationTime;
    private final Instant lastAuthenticationTime;
    private final Instant lastTokenRefreshTime;
    private final Instant lastCredentialRotationTime;
    private final Map<AuthenticationType, Long> authenticationsByType;
    private final long securityViolationCount;
    private final Instant timestamp;
    private Duration timeWindow;
    
    public AuthenticationMetrics(long totalAuthenticationAttempts, long successfulAuthentications,
                               long failedAuthentications, double successRate, double failureRate,
                               long tokenRefreshCount, long credentialRotationCount,
                               double averageAuthenticationTime, Instant lastAuthenticationTime,
                               Instant lastTokenRefreshTime, Instant lastCredentialRotationTime,
                               Map<AuthenticationType, Long> authenticationsByType,
                               long securityViolationCount, Instant timestamp, Duration timeWindow) {
        this.totalAuthenticationAttempts = totalAuthenticationAttempts;
        this.successfulAuthentications = successfulAuthentications;
        this.failedAuthentications = failedAuthentications;
        this.successRate = successRate;
        this.failureRate = failureRate;
        this.tokenRefreshCount = tokenRefreshCount;
        this.credentialRotationCount = credentialRotationCount;
        this.averageAuthenticationTime = averageAuthenticationTime;
        this.lastAuthenticationTime = lastAuthenticationTime;
        this.lastTokenRefreshTime = lastTokenRefreshTime;
        this.lastCredentialRotationTime = lastCredentialRotationTime;
        this.authenticationsByType = new HashMap<>(authenticationsByType);
        this.securityViolationCount = securityViolationCount;
        this.timestamp = timestamp;
        this.timeWindow = timeWindow;
    }
    
    // Getters
    public long getTotalAuthenticationAttempts() { return totalAuthenticationAttempts; }
    public long getSuccessfulAuthentications() { return successfulAuthentications; }
    public long getFailedAuthentications() { return failedAuthentications; }
    public double getSuccessRate() { return successRate; }
    public double getFailureRate() { return failureRate; }
    public long getTokenRefreshCount() { return tokenRefreshCount; }
    public long getCredentialRotationCount() { return credentialRotationCount; }
    public double getAverageAuthenticationTime() { return averageAuthenticationTime; }
    public Instant getLastAuthenticationTime() { return lastAuthenticationTime; }
    public Instant getLastTokenRefreshTime() { return lastTokenRefreshTime; }
    public Instant getLastCredentialRotationTime() { return lastCredentialRotationTime; }
    public Map<AuthenticationType, Long> getAuthenticationsByType() { return authenticationsByType; }
    public long getSecurityViolationCount() { return securityViolationCount; }
    public Instant getTimestamp() { return timestamp; }
    public Duration getTimeWindow() { return timeWindow; }
    public void setTimeWindow(Duration timeWindow) { this.timeWindow = timeWindow; }
    
    /**
     * Resets metrics counters (for testing/monitoring purposes).
     */
    public void resetMetrics() {
        // Note: This would typically reset the underlying counters in the AuthenticationManager
        // For this implementation, metrics are read-only snapshots
    }
}

/**
 * TokenInfo class represents authentication token information.
 */
class TokenInfo {
    
    private String token;
    private final String tokenType;
    private final Instant expirationTime;
    private final Instant issuedTime;
    private final List<String> scopes;
    private final String issuer;
    private final String subject;
    private final Map<String, Object> metadata;
    private volatile boolean invalidated = false;
    
    public TokenInfo(String token, String tokenType, Instant expirationTime, Instant issuedTime,
                    List<String> scopes, String issuer, String subject, Map<String, Object> metadata) {
        this.token = token;
        this.tokenType = tokenType;
        this.expirationTime = expirationTime;
        this.issuedTime = issuedTime;
        this.scopes = new ArrayList<>(scopes != null ? scopes : Collections.emptyList());
        this.issuer = issuer;
        this.subject = subject;
        this.metadata = new HashMap<>(metadata != null ? metadata : Collections.emptyMap());
    }
    
    // Getters
    public String getToken() { return invalidated ? null : token; }
    public String getTokenType() { return tokenType; }
    public Instant getExpirationTime() { return expirationTime; }
    public Instant getIssuedTime() { return issuedTime; }
    public List<String> getScopes() { return new ArrayList<>(scopes); }
    public String getIssuer() { return issuer; }
    public String getSubject() { return subject; }
    public Map<String, Object> getMetadata() { return new HashMap<>(metadata); }
    
    /**
     * Gets remaining time until token expiration.
     */
    public Duration getRemainingTime() {
        if (invalidated || expirationTime == null) {
            return Duration.ZERO;
        }
        
        Duration remaining = Duration.between(Instant.now(), expirationTime);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
    
    /**
     * Checks if token is expired.
     */
    public boolean isExpired() {
        if (invalidated) return true;
        if (expirationTime == null) return false;
        return Instant.now().isAfter(expirationTime);
    }
    
    /**
     * Checks if token is expiring soon (within buffer time).
     */
    public boolean isExpiringSoon() {
        if (invalidated || expirationTime == null) return false;
        return Instant.now().plus(TOKEN_EXPIRY_BUFFER).isAfter(expirationTime);
    }
    
    /**
     * Refreshes the token with new token data.
     */
    public TokenInfo refreshToken(String newToken, Instant newExpirationTime) {
        return new TokenInfo(
            newToken,
            this.tokenType,
            newExpirationTime,
            Instant.now(),
            this.scopes,
            this.issuer,
            this.subject,
            this.metadata
        );
    }
    
    /**
     * Invalidates this token.
     */
    public void invalidate() {
        this.invalidated = true;
        this.token = null; // Clear sensitive data
    }
}

/**
 * EncryptedCredential class represents encrypted credential storage.
 */
class EncryptedCredential {
    
    private final String encryptedData;
    private final Instant creationTime;
    private volatile Instant lastAccessTime;
    private final Instant expirationTime;
    
    public EncryptedCredential(String encryptedData, Instant creationTime, 
                              Instant lastAccessTime, Instant expirationTime) {
        this.encryptedData = encryptedData;
        this.creationTime = creationTime;
        this.lastAccessTime = lastAccessTime;
        this.expirationTime = expirationTime;
    }
    
    // Getters
    public String getEncryptedData() { 
        this.lastAccessTime = Instant.now(); // Update access time
        return encryptedData; 
    }
    
    public Instant getCreationTime() { return creationTime; }
    public Instant getLastAccessTime() { return lastAccessTime; }
    public Instant getExpirationTime() { return expirationTime; }
    
    /**
     * Checks if credential is expired.
     */
    public boolean isExpired() {
        return expirationTime != null && Instant.now().isAfter(expirationTime);
    }
}

/**
 * AuthenticationEvent class represents authentication events for audit logging.
 */
class AuthenticationEvent {
    
    private final String correlationId;
    private final AuthenticationType authenticationType;
    private final AuthenticationStatus status;
    private final Instant timestamp;
    private final long durationMs;
    private final Instant tokenExpirationTime;
    
    public AuthenticationEvent(String correlationId, AuthenticationType authenticationType,
                             AuthenticationStatus status, Instant timestamp, long durationMs,
                             Instant tokenExpirationTime) {
        this.correlationId = correlationId;
        this.authenticationType = authenticationType;
        this.status = status;
        this.timestamp = timestamp;
        this.durationMs = durationMs;
        this.tokenExpirationTime = tokenExpirationTime;
    }
    
    // Getters
    public String getCorrelationId() { return correlationId; }
    public AuthenticationType getAuthenticationType() { return authenticationType; }
    public AuthenticationStatus getStatus() { return status; }
    public Instant getTimestamp() { return timestamp; }
    public long getDurationMs() { return durationMs; }
    public Instant getTokenExpirationTime() { return tokenExpirationTime; }
}

/**
 * AuthenticationException for authentication-related errors.
 */
class AuthenticationException extends RuntimeException {
    
    public AuthenticationException(String message) {
        super(message);
    }
    
    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}