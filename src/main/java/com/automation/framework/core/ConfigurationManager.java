package com.automation.framework.core;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.time.Duration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ConfigurationManager provides comprehensive configuration management for the automation framework.
 * 
 * Features:
 * - Multi-source configuration loading (files, environment, runtime parameters, CI/CD secrets)
 * - AES-256 encryption for sensitive credentials
 * - Configuration validation at startup
 * - Hot-reload capabilities for long-running test suites
 * - Thread-safe concurrent access
 * - Type-safe configuration access with defaults
 * 
 * This class implements the Singleton pattern to ensure consistent configuration
 * across the entire automation framework.
 */
public class ConfigurationManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationManager.class);
    private static final String AES_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String AES_ALGORITHM = "AES";
    private static final int AES_KEY_LENGTH = 256;
    private static final int IV_LENGTH = 16;
    
    // Singleton instance with thread-safe initialization
    private static volatile ConfigurationManager instance;
    private static final Object instanceLock = new Object();
    
    // Thread-safe configuration storage
    private final ConcurrentHashMap<String, Object> configurationCache;
    private final ConcurrentHashMap<String, ConfigurationSource> propertySourceMap;
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();
    
    // Hot reload functionality
    private ScheduledExecutorService hotReloadExecutor;
    private final AtomicBoolean hotReloadEnabled = new AtomicBoolean(false);
    private final AtomicReference<Duration> reloadInterval = new AtomicReference<>(Duration.ofMinutes(5));
    
    // Validation and error tracking
    private final List<String> validationErrors = new ArrayList<>();
    private final List<String> validationWarnings = new ArrayList<>();
    private final AtomicBoolean isValid = new AtomicBoolean(false);
    
    // Encryption management
    private SecretKeySpec encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();
    
    // JSON processing
    private final ObjectMapper objectMapper;
    
    // Configuration file paths and sources
    private final List<String> configurationFiles = new ArrayList<>();
    private final Set<ConfigurationSource> activeSources = EnumSet.noneOf(ConfigurationSource.class);
    
    /**
     * Private constructor for singleton pattern.
     * Initializes the configuration manager with default settings.
     */
    private ConfigurationManager() {
        this.configurationCache = new ConcurrentHashMap<>();
        this.propertySourceMap = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        
        // Configure Jackson for flexible JSON processing
        objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        // Initialize encryption key from environment or generate new
        initializeEncryptionKey();
        
        logger.info("ConfigurationManager initialized successfully");
    }
    
    /**
     * Returns the singleton instance of ConfigurationManager.
     * Thread-safe lazy initialization with double-checked locking.
     * 
     * @return ConfigurationManager instance
     */
    public static ConfigurationManager getInstance() {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new ConfigurationManager();
                    // Load default configuration on first access
                    instance.loadDefaultConfiguration();
                }
            }
        }
        return instance;
    }
    
    /**
     * Retrieves a configuration property value.
     * Searches through all configured sources in priority order.
     * 
     * @param key The property key
     * @return The property value or null if not found
     */
    public String getProperty(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Property key cannot be null or empty");
        }
        
        configLock.readLock().lock();
        try {
            Object value = configurationCache.get(key.trim());
            return value != null ? value.toString() : null;
        } finally {
            configLock.readLock().unlock();
        }
    }
    
    /**
     * Retrieves a required configuration property.
     * Throws IllegalArgumentException if the property is not found.
     * 
     * @param key The property key
     * @return The property value
     * @throws IllegalArgumentException if property is not found
     */
    public String getRequiredProperty(String key) {
        String value = getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Required property not found: " + key);
        }
        return value;
    }
    
    /**
     * Retrieves a configuration property with a default value.
     * 
     * @param key The property key
     * @param defaultValue The default value to return if property is not found
     * @return The property value or default value
     */
    public String getPropertyWithDefault(String key, String defaultValue) {
        String value = getProperty(key);
        return value != null ? value : defaultValue;
    }
    
    /**
     * Retrieves and decrypts an encrypted property value.
     * 
     * @param key The property key for encrypted value
     * @return The decrypted property value or null if not found
     * @throws RuntimeException if decryption fails
     */
    public String getEncryptedProperty(String key) {
        String encryptedValue = getProperty(key);
        if (encryptedValue == null) {
            return null;
        }
        
        try {
            return decrypt(encryptedValue);
        } catch (Exception e) {
            logger.error("Failed to decrypt property: {}", key, e);
            throw new RuntimeException("Failed to decrypt property: " + key, e);
        }
    }
    
    /**
     * Sets a configuration property value.
     * This updates the runtime configuration only.
     * 
     * @param key The property key
     * @param value The property value
     */
    public void setProperty(String key, String value) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Property key cannot be null or empty");
        }
        
        configLock.writeLock().lock();
        try {
            String trimmedKey = key.trim();
            if (value == null) {
                configurationCache.remove(trimmedKey);
                propertySourceMap.remove(trimmedKey);
            } else {
                configurationCache.put(trimmedKey, value);
                propertySourceMap.put(trimmedKey, ConfigurationSource.RUNTIME_PARAMETER);
            }
            logger.debug("Set property: {} = {}", trimmedKey, value != null ? "[HIDDEN]" : "null");
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Reloads configuration from all sources.
     * This method is thread-safe and preserves runtime parameters.
     * 
     * @return ConfigurationValidationResult indicating success or failure
     */
    public ConfigurationValidationResult reloadConfiguration() {
        logger.info("Reloading configuration from all sources");
        
        configLock.writeLock().lock();
        try {
            // Preserve runtime parameters
            HashMap<String, Object> runtimeParams = new HashMap<>();
            propertySourceMap.forEach((key, source) -> {
                if (source == ConfigurationSource.RUNTIME_PARAMETER) {
                    runtimeParams.put(key, configurationCache.get(key));
                }
            });
            
            // Clear and reload
            configurationCache.clear();
            propertySourceMap.clear();
            validationErrors.clear();
            validationWarnings.clear();
            activeSources.clear();
            
            // Load from all sources
            loadFromAllSources();
            
            // Restore runtime parameters (highest priority)
            runtimeParams.forEach((key, value) -> {
                configurationCache.put(key, value);
                propertySourceMap.put(key, ConfigurationSource.RUNTIME_PARAMETER);
            });
            
            // Validate the reloaded configuration
            ConfigurationValidationResult result = validateConfiguration();
            isValid.set(result.isValid());
            
            logger.info("Configuration reload completed. Valid: {}, Errors: {}, Warnings: {}", 
                       result.isValid(), result.getErrors().size(), result.getWarnings().size());
            
            return result;
            
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Validates the current configuration.
     * Performs comprehensive validation of all loaded properties.
     * 
     * @return ConfigurationValidationResult with validation status and any errors/warnings
     */
    public ConfigurationValidationResult validateConfiguration() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> validatedProperties = new HashMap<>();
        
        configLock.readLock().lock();
        try {
            // Validate required framework properties
            validateRequiredProperties(errors, warnings);
            
            // Validate property formats and values
            validatePropertyFormats(errors, warnings);
            
            // Validate encrypted properties
            validateEncryptedProperties(errors, warnings);
            
            // Validate configuration sources
            validateConfigurationSources(warnings);
            
            // Copy validated properties
            validatedProperties.putAll(configurationCache);
            
            boolean isValid = errors.isEmpty();
            
            // Update internal validation state
            this.validationErrors.clear();
            this.validationErrors.addAll(errors);
            this.validationWarnings.clear();
            this.validationWarnings.addAll(warnings);
            this.isValid.set(isValid);
            
            logger.info("Configuration validation completed. Valid: {}, Errors: {}, Warnings: {}", 
                       isValid, errors.size(), warnings.size());
            
            return new ConfigurationValidationResult(isValid, errors, warnings, validatedProperties);
            
        } finally {
            configLock.readLock().unlock();
        }
    }
    
    /**
     * Checks if the current configuration is valid.
     * 
     * @return true if configuration is valid, false otherwise
     */
    public boolean isConfigurationValid() {
        return isValid.get();
    }
    
    /**
     * Returns the list of active configuration sources.
     * 
     * @return Set of active ConfigurationSource values
     */
    public Set<ConfigurationSource> getConfigurationSources() {
        configLock.readLock().lock();
        try {
            return EnumSet.copyOf(activeSources);
        } finally {
            configLock.readLock().unlock();
        }
    }
    
    /**
     * Returns the current validation errors.
     * 
     * @return List of validation error messages
     */
    public List<String> getValidationErrors() {
        configLock.readLock().lock();
        try {
            return new ArrayList<>(validationErrors);
        } finally {
            configLock.readLock().unlock();
        }
    }
    
    /**
     * Enables hot reload functionality with specified interval.
     * 
     * @param interval The reload check interval
     */
    public void enableHotReload(Duration interval) {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Reload interval must be positive");
        }
        
        reloadInterval.set(interval);
        
        if (hotReloadEnabled.compareAndSet(false, true)) {
            hotReloadExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ConfigurationManager-HotReload");
                t.setDaemon(true);
                return t;
            });
            
            hotReloadExecutor.scheduleAtFixedRate(() -> {
                try {
                    if (shouldReload()) {
                        reloadConfiguration();
                    }
                } catch (Exception e) {
                    logger.error("Error during hot reload", e);
                }
            }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
            
            logger.info("Hot reload enabled with interval: {}", interval);
        }
    }
    
    /**
     * Enables hot reload with default 5-minute interval.
     */
    public void enableHotReload() {
        enableHotReload(Duration.ofMinutes(5));
    }
    
    /**
     * Disables hot reload functionality.
     */
    public void disableHotReload() {
        if (hotReloadEnabled.compareAndSet(true, false)) {
            if (hotReloadExecutor != null) {
                hotReloadExecutor.shutdown();
                try {
                    if (!hotReloadExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                        hotReloadExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    hotReloadExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                hotReloadExecutor = null;
            }
            logger.info("Hot reload disabled");
        }
    }
    
    /**
     * Exports current configuration to JSON format.
     * Sensitive values are masked for security.
     * 
     * @return JSON representation of configuration
     * @throws RuntimeException if JSON serialization fails
     */
    public String exportConfiguration() {
        configLock.readLock().lock();
        try {
            Map<String, Object> exportData = new HashMap<>();
            Map<String, Object> properties = new HashMap<>();
            Map<String, String> sources = new HashMap<>();
            
            // Export non-sensitive properties
            configurationCache.forEach((key, value) -> {
                if (isSensitiveKey(key)) {
                    properties.put(key, "[ENCRYPTED]");
                } else {
                    properties.put(key, value);
                }
                sources.put(key, propertySourceMap.get(key).toString());
            });
            
            exportData.put("properties", properties);
            exportData.put("sources", sources);
            exportData.put("activeSources", activeSources);
            exportData.put("isValid", isValid.get());
            exportData.put("validationErrors", validationErrors);
            exportData.put("validationWarnings", validationWarnings);
            exportData.put("timestamp", System.currentTimeMillis());
            
            return objectMapper.writeValueAsString(exportData);
            
        } catch (Exception e) {
            logger.error("Failed to export configuration", e);
            throw new RuntimeException("Failed to export configuration", e);
        } finally {
            configLock.readLock().unlock();
        }
    }
    
    /**
     * Performs graceful shutdown of ConfigurationManager.
     * Stops hot reload, saves state if needed, and cleans up resources.
     */
    public void shutdown() {
        logger.info("Shutting down ConfigurationManager");
        
        // Disable hot reload
        disableHotReload();
        
        // Clear sensitive data from memory
        configLock.writeLock().lock();
        try {
            configurationCache.clear();
            propertySourceMap.clear();
            validationErrors.clear();
            validationWarnings.clear();
            activeSources.clear();
            
            // Clear encryption key
            if (encryptionKey != null) {
                Arrays.fill(encryptionKey.getEncoded(), (byte) 0);
                encryptionKey = null;
            }
            
        } finally {
            configLock.writeLock().unlock();
        }
        
        logger.info("ConfigurationManager shutdown completed");
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Initializes the encryption key from environment or generates new one.
     */
    private void initializeEncryptionKey() {
        String keyEnv = System.getenv("AUTOMATION_FRAMEWORK_ENCRYPTION_KEY");
        if (keyEnv != null && !keyEnv.trim().isEmpty()) {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(keyEnv);
                this.encryptionKey = new SecretKeySpec(keyBytes, AES_ALGORITHM);
                logger.debug("Loaded encryption key from environment");
            } catch (Exception e) {
                logger.warn("Failed to load encryption key from environment, generating new key", e);
                generateNewEncryptionKey();
            }
        } else {
            generateNewEncryptionKey();
        }
    }
    
    /**
     * Generates a new AES-256 encryption key.
     */
    private void generateNewEncryptionKey() {
        try {
            byte[] keyBytes = new byte[32]; // 256 bits
            secureRandom.nextBytes(keyBytes);
            this.encryptionKey = new SecretKeySpec(keyBytes, AES_ALGORITHM);
            logger.info("Generated new AES-256 encryption key");
        } catch (Exception e) {
            logger.error("Failed to generate encryption key", e);
            throw new RuntimeException("Failed to initialize encryption", e);
        }
    }
    
    /**
     * Loads default configuration from all available sources.
     */
    private void loadDefaultConfiguration() {
        loadFromAllSources();
        ConfigurationValidationResult result = validateConfiguration();
        if (!result.isValid()) {
            logger.warn("Initial configuration validation failed: {}", result.getErrors());
        }
    }
    
    /**
     * Loads configuration from all available sources in priority order.
     */
    private void loadFromAllSources() {
        // Priority order: Runtime > Environment > CI/CD > Property Files
        
        // 1. Load from property files (lowest priority)
        loadFromPropertyFiles();
        
        // 2. Load from CI/CD secrets
        loadFromCiCdSecrets();
        
        // 3. Load from environment variables
        loadFromEnvironmentVariables();
        
        // Note: Runtime parameters are set via setProperty() method
    }
    
    /**
     * Loads configuration from property files.
     */
    private void loadFromPropertyFiles() {
        List<String> defaultFiles = Arrays.asList(
            "config/application.properties",
            "application.properties",
            "config.properties"
        );
        
        for (String filePath : defaultFiles) {
            loadFromPropertyFile(filePath);
        }
        
        // Load from custom configuration files if specified
        String customFiles = System.getProperty("automation.config.files");
        if (customFiles != null) {
            for (String file : customFiles.split(",")) {
                loadFromPropertyFile(file.trim());
            }
        }
    }
    
    /**
     * Loads configuration from a specific property file.
     */
    private void loadFromPropertyFile(String filePath) {
        Path path = Paths.get(filePath);
        if (Files.exists(path) && Files.isReadable(path)) {
            try {
                Properties props = new Properties();
                try (BufferedReader reader = Files.newBufferedReader(path)) {
                    props.load(reader);
                }
                
                props.forEach((key, value) -> {
                    String keyStr = key.toString().trim();
                    if (!propertySourceMap.containsKey(keyStr)) { // Don't override higher priority sources
                        configurationCache.put(keyStr, value);
                        propertySourceMap.put(keyStr, ConfigurationSource.PROPERTY_FILE);
                    }
                });
                
                activeSources.add(ConfigurationSource.PROPERTY_FILE);
                configurationFiles.add(filePath);
                logger.debug("Loaded {} properties from file: {}", props.size(), filePath);
                
            } catch (IOException e) {
                logger.warn("Failed to load property file: {}", filePath, e);
            }
        }
    }
    
    /**
     * Loads configuration from environment variables.
     */
    private void loadFromEnvironmentVariables() {
        int count = 0;
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            
            // Only load framework-related environment variables
            if (key.startsWith("AUTOMATION_") || key.startsWith("FRAMEWORK_")) {
                String configKey = key.toLowerCase().replace('_', '.');
                if (!propertySourceMap.containsKey(configKey) || 
                    propertySourceMap.get(configKey).ordinal() < ConfigurationSource.ENVIRONMENT_VARIABLE.ordinal()) {
                    configurationCache.put(configKey, value);
                    propertySourceMap.put(configKey, ConfigurationSource.ENVIRONMENT_VARIABLE);
                    count++;
                }
            }
        }
        
        if (count > 0) {
            activeSources.add(ConfigurationSource.ENVIRONMENT_VARIABLE);
            logger.debug("Loaded {} properties from environment variables", count);
        }
    }
    
    /**
     * Loads configuration from CI/CD secrets.
     */
    private void loadFromCiCdSecrets() {
        // GitHub Actions secrets
        loadGitHubActionsSecrets();
        
        // Jenkins secrets
        loadJenkinsSecrets();
        
        // Azure DevOps secrets
        loadAzureDevOpsSecrets();
        
        // GitLab CI secrets
        loadGitLabCiSecrets();
    }
    
    /**
     * Loads secrets from GitHub Actions environment.
     */
    private void loadGitHubActionsSecrets() {
        if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
            int count = 0;
            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("SECRET_") || key.startsWith("INPUT_")) {
                    String configKey = key.toLowerCase().replace('_', '.');
                    configurationCache.put(configKey, entry.getValue());
                    propertySourceMap.put(configKey, ConfigurationSource.CI_CD_SECRET);
                    count++;
                }
            }
            if (count > 0) {
                activeSources.add(ConfigurationSource.CI_CD_SECRET);
                logger.debug("Loaded {} GitHub Actions secrets", count);
            }
        }
    }
    
    /**
     * Loads secrets from Jenkins environment.
     */
    private void loadJenkinsSecrets() {
        if (System.getenv("JENKINS_URL") != null) {
            // Jenkins credentials binding
            int count = 0;
            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("JENKINS_") || key.endsWith("_CREDENTIAL")) {
                    String configKey = key.toLowerCase().replace('_', '.');
                    configurationCache.put(configKey, entry.getValue());
                    propertySourceMap.put(configKey, ConfigurationSource.CI_CD_SECRET);
                    count++;
                }
            }
            if (count > 0) {
                activeSources.add(ConfigurationSource.CI_CD_SECRET);
                logger.debug("Loaded {} Jenkins secrets", count);
            }
        }
    }
    
    /**
     * Loads secrets from Azure DevOps environment.
     */
    private void loadAzureDevOpsSecrets() {
        if (System.getenv("SYSTEM_TEAMPROJECT") != null) {
            int count = 0;
            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("AZURE_") || key.startsWith("SYSTEM_")) {
                    String configKey = key.toLowerCase().replace('_', '.');
                    configurationCache.put(configKey, entry.getValue());
                    propertySourceMap.put(configKey, ConfigurationSource.CI_CD_SECRET);
                    count++;
                }
            }
            if (count > 0) {
                activeSources.add(ConfigurationSource.CI_CD_SECRET);
                logger.debug("Loaded {} Azure DevOps secrets", count);
            }
        }
    }
    
    /**
     * Loads secrets from GitLab CI environment.
     */
    private void loadGitLabCiSecrets() {
        if (System.getenv("GITLAB_CI") != null) {
            int count = 0;
            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("CI_") || key.startsWith("GITLAB_")) {
                    String configKey = key.toLowerCase().replace('_', '.');
                    configurationCache.put(configKey, entry.getValue());
                    propertySourceMap.put(configKey, ConfigurationSource.CI_CD_SECRET);
                    count++;
                }
            }
            if (count > 0) {
                activeSources.add(ConfigurationSource.CI_CD_SECRET);
                logger.debug("Loaded {} GitLab CI secrets", count);
            }
        }
    }
    
    /**
     * Validates required framework properties.
     */
    private void validateRequiredProperties(List<String> errors, List<String> warnings) {
        // Define required properties for the automation framework
        List<String> requiredProps = Arrays.asList(
            "framework.name",
            "framework.version",
            "selenium.driver.path",
            "api.base.url"
        );
        
        for (String prop : requiredProps) {
            if (!configurationCache.containsKey(prop) || 
                configurationCache.get(prop) == null || 
                configurationCache.get(prop).toString().trim().isEmpty()) {
                errors.add("Required property missing or empty: " + prop);
            }
        }
    }
    
    /**
     * Validates property formats and values.
     */
    private void validatePropertyFormats(List<String> errors, List<String> warnings) {
        // URL validation pattern
        Pattern urlPattern = Pattern.compile("^https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=]+$");
        
        // Email validation pattern
        Pattern emailPattern = Pattern.compile("^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$");
        
        // Numeric validation pattern
        Pattern numericPattern = Pattern.compile("^\\d+$");
        
        configurationCache.forEach((key, value) -> {
            String valueStr = value.toString();
            
            // Validate URLs
            if (key.contains("url") || key.contains("endpoint")) {
                if (!urlPattern.matcher(valueStr).matches()) {
                    errors.add("Invalid URL format for property: " + key);
                }
            }
            
            // Validate email addresses
            if (key.contains("email") || key.contains("mail")) {
                if (!emailPattern.matcher(valueStr).matches()) {
                    errors.add("Invalid email format for property: " + key);
                }
            }
            
            // Validate numeric properties
            if (key.contains("port") || key.contains("timeout") || key.contains("retry")) {
                if (!numericPattern.matcher(valueStr).matches()) {
                    errors.add("Invalid numeric format for property: " + key);
                } else {
                    int numValue = Integer.parseInt(valueStr);
                    if (key.contains("port") && (numValue < 1 || numValue > 65535)) {
                        errors.add("Port number out of valid range (1-65535): " + key);
                    }
                    if (key.contains("timeout") && numValue < 0) {
                        errors.add("Timeout value cannot be negative: " + key);
                    }
                }
            }
            
            // Check for common security issues
            if (valueStr.toLowerCase().contains("password") && valueStr.length() < 8) {
                warnings.add("Password property may be too short: " + key);
            }
        });
    }
    
    /**
     * Validates encrypted properties can be decrypted.
     */
    private void validateEncryptedProperties(List<String> errors, List<String> warnings) {
        configurationCache.forEach((key, value) -> {
            if (isSensitiveKey(key)) {
                try {
                    String valueStr = value.toString();
                    if (valueStr.startsWith("ENC(") && valueStr.endsWith(")")) {
                        // This looks like an encrypted value, try to decrypt it
                        decrypt(valueStr);
                    }
                } catch (Exception e) {
                    errors.add("Failed to decrypt property: " + key + " - " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Validates configuration sources.
     */
    private void validateConfigurationSources(List<String> warnings) {
        if (activeSources.isEmpty()) {
            warnings.add("No configuration sources loaded");
        }
        
        if (!activeSources.contains(ConfigurationSource.PROPERTY_FILE)) {
            warnings.add("No property files loaded - relying only on environment/runtime configuration");
        }
        
        // Check for configuration file conflicts
        configurationFiles.forEach(file -> {
            if (!Files.exists(Paths.get(file))) {
                warnings.add("Configuration file no longer exists: " + file);
            }
        });
    }
    
    /**
     * Checks if a configuration key is sensitive and should be encrypted.
     */
    private boolean isSensitiveKey(String key) {
        String lowerKey = key.toLowerCase();
        return lowerKey.contains("password") || 
               lowerKey.contains("secret") || 
               lowerKey.contains("key") || 
               lowerKey.contains("token") || 
               lowerKey.contains("credential");
    }
    
    /**
     * Encrypts a sensitive value using AES-256.
     */
    private String encrypt(String plainText) throws Exception {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        
        // Generate random IV
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, ivSpec);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        
        // Combine IV and encrypted data
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        
        return "ENC(" + Base64.getEncoder().encodeToString(combined) + ")";
    }
    
    /**
     * Decrypts an encrypted value using AES-256.
     */
    private String decrypt(String encryptedText) throws Exception {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        
        // Remove ENC() wrapper if present
        String cipherText = encryptedText;
        if (encryptedText.startsWith("ENC(") && encryptedText.endsWith(")")) {
            cipherText = encryptedText.substring(4, encryptedText.length() - 1);
        }
        
        byte[] combined = Base64.getDecoder().decode(cipherText);
        
        // Extract IV and encrypted data
        byte[] iv = new byte[IV_LENGTH];
        byte[] encrypted = new byte[combined.length - IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
        System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
        
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, ivSpec);
        
        byte[] decrypted = cipher.doFinal(encrypted);
        return new String(decrypted, StandardCharsets.UTF_8);
    }
    
    /**
     * Checks if configuration should be reloaded based on file modifications.
     */
    private boolean shouldReload() {
        // Simple implementation - could be enhanced to check file modification times
        for (String configFile : configurationFiles) {
            Path path = Paths.get(configFile);
            if (Files.exists(path)) {
                try {
                    long lastModified = Files.getLastModifiedTime(path).toMillis();
                    // Could implement file modification time tracking here
                    // For now, return false to prevent unnecessary reloads
                    return false;
                } catch (IOException e) {
                    logger.debug("Could not check modification time for: {}", configFile);
                }
            }
        }
        return false;
    }
    
    /**
     * Gets performance metrics thresholds configuration.
     * 
     * @return Map containing metrics thresholds for various operations
     */
    public Map<String, Object> getMetricsThresholds() {
        Map<String, Object> thresholds = new HashMap<>();
        try {
            // Default metrics thresholds
            thresholds.put("validation.timeout.ms", Integer.parseInt(getPropertyWithDefault("validation.timeout.ms", "5000")));
            thresholds.put("validation.memory.limit.mb", Integer.parseInt(getPropertyWithDefault("validation.memory.limit.mb", "100")));
            thresholds.put("validation.file.size.limit.mb", Integer.parseInt(getPropertyWithDefault("validation.file.size.limit.mb", "50")));
            thresholds.put("validation.batch.size", Integer.parseInt(getPropertyWithDefault("validation.batch.size", "1000")));
            thresholds.put("validation.error.threshold", Integer.parseInt(getPropertyWithDefault("validation.error.threshold", "100")));
            thresholds.put("validation.warning.threshold", Integer.parseInt(getPropertyWithDefault("validation.warning.threshold", "500")));
            thresholds.put("cache.ttl.ms", Long.parseLong(getPropertyWithDefault("cache.ttl.ms", "300000")));
            thresholds.put("cache.max.size", Integer.parseInt(getPropertyWithDefault("cache.max.size", "1000")));
            thresholds.put("performance.response.time.ms", Integer.parseInt(getPropertyWithDefault("performance.response.time.ms", "2000")));
            thresholds.put("performance.throughput.per.second", Integer.parseInt(getPropertyWithDefault("performance.throughput.per.second", "100")));
        } catch (NumberFormatException e) {
            // Return empty map if there are parsing errors
            thresholds.clear();
        }
        return thresholds;
    }
}

/**
 * Enumeration of configuration sources with priority ordering.
 * Higher ordinal values indicate higher priority.
 */
enum ConfigurationSource {
    /**
     * Property files (lowest priority)
     */
    PROPERTY_FILE,
    
    /**
     * CI/CD secrets and build environment variables
     */
    CI_CD_SECRET,
    
    /**
     * System environment variables
     */
    ENVIRONMENT_VARIABLE,
    
    /**
     * Runtime parameters set programmatically (highest priority)
     */
    RUNTIME_PARAMETER
}

/**
 * Represents the result of configuration validation.
 * Contains validation status, errors, warnings, and validated properties.
 */
class ConfigurationValidationResult {
    
    private final boolean valid;
    private final List<String> errors;
    private final List<String> warnings;
    private final Map<String, Object> validatedProperties;
    
    /**
     * Creates a new ConfigurationValidationResult.
     * 
     * @param valid true if validation passed
     * @param errors list of validation errors
     * @param warnings list of validation warnings
     * @param validatedProperties map of validated configuration properties
     */
    public ConfigurationValidationResult(boolean valid, List<String> errors, List<String> warnings, 
                          Map<String, Object> validatedProperties) {
        this.valid = valid;
        this.errors = new ArrayList<>(errors != null ? errors : Collections.emptyList());
        this.warnings = new ArrayList<>(warnings != null ? warnings : Collections.emptyList());
        this.validatedProperties = new HashMap<>(validatedProperties != null ? validatedProperties : Collections.emptyMap());
    }
    
    /**
     * Returns whether the validation was successful.
     * 
     * @return true if configuration is valid, false if there are errors
     */
    public boolean isValid() {
        return valid;
    }
    
    /**
     * Returns the list of validation errors.
     * 
     * @return unmodifiable list of error messages
     */
    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }
    
    /**
     * Returns the list of validation warnings.
     * 
     * @return unmodifiable list of warning messages
     */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }
    
    /**
     * Returns the validated configuration properties.
     * 
     * @return unmodifiable map of validated properties
     */
    public Map<String, Object> getValidatedProperties() {
        return Collections.unmodifiableMap(validatedProperties);
    }
    
    /**
     * Returns whether there are any validation errors.
     * 
     * @return true if there are errors, false otherwise
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    /**
     * Returns whether there are any validation warnings.
     * 
     * @return true if there are warnings, false otherwise
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    @Override
    public String toString() {
        return String.format("ConfigurationValidationResult{valid=%s, errors=%d, warnings=%d, properties=%d}", 
                           valid, errors.size(), warnings.size(), validatedProperties.size());
    }
}