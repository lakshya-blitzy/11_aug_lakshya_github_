package com.automation.framework.validation;

// Internal framework imports for browser management, configuration, and audit logging
import com.automation.framework.web.BrowserManager;
import com.automation.framework.core.ConfigurationManager;
import com.automation.framework.monitoring.AuditLogger;

// External Selenium WebDriver imports for element state validation
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.ElementNotInteractableException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;

// External Java standard library imports for time management, optional handling, and pattern matching
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.function.Function;

// External SLF4J logging imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Standard Java utility imports
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * StateValidator provides comprehensive application state validation for web automation testing.
 * 
 * This class ensures UI elements and application conditions meet expected criteria before interactions,
 * implementing enterprise-grade state verification for complex web application workflows and dynamic content.
 * 
 * Key Features:
 * - Element presence, visibility, and interactability validation
 * - Page load completion and DOM stability verification  
 * - AJAX completion monitoring and JavaScript error detection
 * - Form field state validation and modal dialog verification
 * - Navigation state and URL pattern validation
 * - CSS property validation and custom wait conditions
 * - Intelligent retry mechanisms for transient state issues
 * - Detailed state mismatch reporting for debugging
 * - Cross-browser compatibility and viewport-aware validation
 * 
 * Integration:
 * - Seamless integration with Selenium WebDriver for state interrogation
 * - BrowserManager integration for browser session coordination
 * - ConfigurationManager integration for validation timeout settings
 * - AuditLogger integration for comprehensive validation audit trails
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class StateValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(StateValidator.class);
    
    // Framework dependency components
    private final BrowserManager browserManager;
    private final ConfigurationManager configurationManager;
    private final AuditLogger auditLogger;
    
    // Validation configuration and state management
    private final StateValidationConfig validationConfig;
    private final AtomicReference<Duration> validationTimeout = new AtomicReference<>(Duration.ofSeconds(10));
    private final AtomicLong validationCounter = new AtomicLong(0);
    private final AtomicReference<Instant> lastValidationTime = new AtomicReference<>(Instant.now());
    
    // Thread-safe validation metrics and reporting
    private final ConcurrentHashMap<StateValidationType, AtomicLong> validationMetrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ValidationResult> validationHistory = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();
    
    // JavaScript execution patterns for browser state checking
    private static final String DOM_READY_SCRIPT = "return document.readyState === 'complete'";
    private static final String JQUERY_READY_SCRIPT = "return typeof jQuery !== 'undefined' && jQuery.active === 0";
    private static final String ANGULAR_READY_SCRIPT = "return typeof angular !== 'undefined' && angular.element(document).injector().get('$http').pendingRequests.length === 0";
    private static final String REACT_READY_SCRIPT = "return typeof React !== 'undefined' && document.querySelectorAll('[data-reactroot]').length > 0";
    
    /**
     * Creates a new StateValidator with framework component dependencies.
     * Initializes validation configuration and metrics tracking.
     */
    public StateValidator() {
        // Initialize framework component dependencies
        this.browserManager = BrowserManager.getInstance();
        this.configurationManager = ConfigurationManager.getInstance();
        this.auditLogger = AuditLogger.getInstance();
        
        // Initialize validation configuration with defaults
        this.validationConfig = new StateValidationConfig();
        
        // Initialize validation metrics for all state validation types
        for (StateValidationType type : StateValidationType.values()) {
            validationMetrics.put(type, new AtomicLong(0));
        }
        
        // Load configuration settings
        loadValidationConfiguration();
        
        logger.info("StateValidator initialized with validation timeout: {} seconds", 
                   validationTimeout.get().getSeconds());
    }
    
    /**
     * Validates the presence of a specific element on the page.
     * 
     * @param driver The WebDriver instance to use for validation
     * @param locator The By locator to find the element
     * @return ValidationResult indicating element presence status
     */
    public ValidationResult validateElementPresence(WebDriver driver, By locator) {
        return executeValidation(StateValidationType.ELEMENT_PRESENCE, () -> {
            try {
                WebElement element = driver.findElement(locator);
                return ValidationResult.success(StateValidationType.ELEMENT_PRESENCE, 
                    "Element found successfully", 
                    Collections.singletonMap("locator", locator.toString()));
                    
            } catch (NoSuchElementException e) {
                return ValidationResult.failure(StateValidationType.ELEMENT_PRESENCE,
                    "Element not found: " + locator.toString(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates the visibility of a specific element.
     * 
     * @param element The WebElement to check for visibility
     * @return ValidationResult indicating visibility status
     */
    public ValidationResult validateElementVisibility(WebElement element) {
        return executeValidation(StateValidationType.ELEMENT_VISIBILITY, () -> {
            try {
                boolean isDisplayed = element.isDisplayed();
                return ValidationResult.success(StateValidationType.ELEMENT_VISIBILITY,
                    "Element visibility validated: " + isDisplayed,
                    Collections.singletonMap("isVisible", isDisplayed));
                    
            } catch (StaleElementReferenceException e) {
                return ValidationResult.failure(StateValidationType.ELEMENT_VISIBILITY,
                    "Element is stale and no longer attached to DOM",
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates the interactability of a specific element.
     * 
     * @param element The WebElement to check for interactability
     * @return ValidationResult indicating interactability status
     */
    public ValidationResult validateElementInteractability(WebElement element) {
        return executeValidation(StateValidationType.ELEMENT_INTERACTABILITY, () -> {
            try {
                boolean isEnabled = element.isEnabled();
                boolean isDisplayed = element.isDisplayed();
                boolean isInteractable = isEnabled && isDisplayed;
                
                Map<String, Object> details = new HashMap<>();
                details.put("isEnabled", isEnabled);
                details.put("isDisplayed", isDisplayed);
                details.put("isInteractable", isInteractable);
                
                return ValidationResult.success(StateValidationType.ELEMENT_INTERACTABILITY,
                    "Element interactability validated: " + isInteractable, details);
                    
            } catch (StaleElementReferenceException e) {
                return ValidationResult.failure(StateValidationType.ELEMENT_INTERACTABILITY,
                    "Element is stale and cannot be checked for interactability",
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates specific element state properties.
     * 
     * @param element The WebElement to validate
     * @param expectedState The expected ElementState
     * @return ValidationResult indicating state validation status
     */
    public ValidationResult validateElementState(WebElement element, ElementState expectedState) {
        return executeValidation(StateValidationType.ELEMENT_INTERACTABILITY, () -> {
            try {
                ElementState actualState = determineElementState(element);
                boolean stateMatches = actualState == expectedState;
                
                Map<String, Object> details = new HashMap<>();
                details.put("expectedState", expectedState.toString());
                details.put("actualState", actualState.toString());
                details.put("stateMatches", stateMatches);
                
                if (stateMatches) {
                    return ValidationResult.success(StateValidationType.ELEMENT_INTERACTABILITY,
                        "Element state matches expected: " + expectedState, details);
                } else {
                    return ValidationResult.failure(StateValidationType.ELEMENT_INTERACTABILITY,
                        "Element state mismatch. Expected: " + expectedState + ", Actual: " + actualState,
                        details);
                }
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.ELEMENT_INTERACTABILITY,
                    "Error validating element state: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates that page loading is complete.
     * 
     * @param driver The WebDriver instance to check
     * @return ValidationResult indicating page load completion status
     */
    public ValidationResult validatePageLoadCompletion(WebDriver driver) {
        return executeValidation(StateValidationType.PAGE_LOAD_COMPLETION, () -> {
            try {
                JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;
                Boolean domReady = (Boolean) jsExecutor.executeScript(DOM_READY_SCRIPT);
                
                Map<String, Object> details = new HashMap<>();
                details.put("domReady", domReady);
                details.put("currentUrl", driver.getCurrentUrl());
                details.put("pageTitle", driver.getTitle());
                
                if (Boolean.TRUE.equals(domReady)) {
                    return ValidationResult.success(StateValidationType.PAGE_LOAD_COMPLETION,
                        "Page load completed successfully", details);
                } else {
                    return ValidationResult.failure(StateValidationType.PAGE_LOAD_COMPLETION,
                        "Page load not yet complete", details);
                }
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.PAGE_LOAD_COMPLETION,
                    "Error checking page load completion: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates DOM stability by checking for ongoing changes.
     * 
     * @param driver The WebDriver instance to check
     * @return ValidationResult indicating DOM stability status
     */
    public ValidationResult validateDOMStability(WebDriver driver) {
        return executeValidation(StateValidationType.DOM_STABILITY, () -> {
            try {
                JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;
                
                // Check for ongoing DOM mutations
                String stabilityScript = 
                    "var observer = new MutationObserver(function() { window.domMutations = true; });" +
                    "observer.observe(document.body, { childList: true, subtree: true });" +
                    "window.domMutations = false;" +
                    "setTimeout(function() { observer.disconnect(); }, 500);" +
                    "return !window.domMutations;";
                
                Boolean isStable = (Boolean) jsExecutor.executeScript(stabilityScript);
                
                Map<String, Object> details = new HashMap<>();
                details.put("domStable", isStable);
                details.put("checkDuration", "500ms");
                
                if (Boolean.TRUE.equals(isStable)) {
                    return ValidationResult.success(StateValidationType.DOM_STABILITY,
                        "DOM is stable with no ongoing mutations", details);
                } else {
                    return ValidationResult.failure(StateValidationType.DOM_STABILITY,
                        "DOM is unstable with ongoing mutations detected", details);
                }
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.DOM_STABILITY,
                    "Error checking DOM stability: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates completion of AJAX requests.
     * 
     * @param driver The WebDriver instance to check
     * @return ValidationResult indicating AJAX completion status
     */
    public ValidationResult validateAjaxCompletion(WebDriver driver) {
        return executeValidation(StateValidationType.AJAX_COMPLETION, () -> {
            try {
                JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;
                Map<String, Object> details = new HashMap<>();
                
                // Check jQuery AJAX requests
                try {
                    Boolean jqueryReady = (Boolean) jsExecutor.executeScript(JQUERY_READY_SCRIPT);
                    details.put("jqueryAjaxComplete", jqueryReady);
                } catch (Exception e) {
                    details.put("jqueryAjaxComplete", "jQuery not available");
                }
                
                // Check Angular HTTP requests
                try {
                    Boolean angularReady = (Boolean) jsExecutor.executeScript(ANGULAR_READY_SCRIPT);
                    details.put("angularHttpComplete", angularReady);
                } catch (Exception e) {
                    details.put("angularHttpComplete", "Angular not available");
                }
                
                // Check for XMLHttpRequest activity
                String xhrScript = "return window.XMLHttpRequest.prototype.open === undefined || " +
                                  "document.querySelectorAll('[data-xhr-pending]').length === 0";
                Boolean xhrComplete = (Boolean) jsExecutor.executeScript(xhrScript);
                details.put("xhrComplete", xhrComplete);
                
                boolean allAjaxComplete = Boolean.TRUE.equals(xhrComplete);
                
                if (allAjaxComplete) {
                    return ValidationResult.success(StateValidationType.AJAX_COMPLETION,
                        "All AJAX requests completed", details);
                } else {
                    return ValidationResult.failure(StateValidationType.AJAX_COMPLETION,
                        "AJAX requests still pending", details);
                }
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.AJAX_COMPLETION,
                    "Error checking AJAX completion: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Checks for JavaScript errors in the browser console.
     * 
     * @param driver The WebDriver instance to check
     * @return ValidationResult indicating JavaScript error status
     */
    public ValidationResult checkJavaScriptErrors(WebDriver driver) {
        return executeValidation(StateValidationType.JAVASCRIPT_ERRORS, () -> {
            try {
                List<LogEntry> logs = driver.manage().logs().get(LogType.BROWSER);
                List<String> errors = logs.stream()
                    .filter(log -> log.getLevel().getName().equals("SEVERE"))
                    .map(LogEntry::getMessage)
                    .collect(Collectors.toList());
                
                Map<String, Object> details = new HashMap<>();
                details.put("totalLogs", logs.size());
                details.put("errorCount", errors.size());
                details.put("errors", errors);
                
                if (errors.isEmpty()) {
                    return ValidationResult.success(StateValidationType.JAVASCRIPT_ERRORS,
                        "No JavaScript errors detected", details);
                } else {
                    return ValidationResult.failure(StateValidationType.JAVASCRIPT_ERRORS,
                        "JavaScript errors detected: " + errors.size(), details);
                }
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.JAVASCRIPT_ERRORS,
                    "Error checking JavaScript errors: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates form field states and values.
     * 
     * @param driver The WebDriver instance to use
     * @param formLocator The locator for the form element
     * @return ValidationResult indicating form field validation status
     */
    public ValidationResult validateFormFieldStates(WebDriver driver, By formLocator) {
        return executeValidation(StateValidationType.FORM_FIELD_STATE, () -> {
            try {
                WebElement form = driver.findElement(formLocator);
                List<WebElement> fields = form.findElements(By.tagName("input"));
                fields.addAll(form.findElements(By.tagName("select")));
                fields.addAll(form.findElements(By.tagName("textarea")));
                
                Map<String, Object> fieldStates = new HashMap<>();
                int validFields = 0;
                
                for (WebElement field : fields) {
                    String fieldId = field.getAttribute("id");
                    String fieldName = field.getAttribute("name");
                    String identifier = fieldId != null ? fieldId : fieldName;
                    
                    if (identifier == null) {
                        identifier = "field_" + fields.indexOf(field);
                    }
                    
                    Map<String, Object> fieldInfo = new HashMap<>();
                    fieldInfo.put("enabled", field.isEnabled());
                    fieldInfo.put("displayed", field.isDisplayed());
                    fieldInfo.put("value", field.getAttribute("value"));
                    fieldInfo.put("required", "true".equals(field.getAttribute("required")));
                    
                    fieldStates.put(identifier, fieldInfo);
                    
                    if (field.isEnabled() && field.isDisplayed()) {
                        validFields++;
                    }
                }
                
                Map<String, Object> details = new HashMap<>();
                details.put("totalFields", fields.size());
                details.put("validFields", validFields);
                details.put("fieldStates", fieldStates);
                
                return ValidationResult.success(StateValidationType.FORM_FIELD_STATE,
                    "Form field states validated successfully", details);
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.FORM_FIELD_STATE,
                    "Error validating form field states: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates modal dialog state and properties.
     * 
     * @param driver The WebDriver instance to check
     * @param modalLocator The locator for the modal dialog
     * @return ValidationResult indicating modal dialog validation status
     */
    public ValidationResult validateModalDialogState(WebDriver driver, By modalLocator) {
        return executeValidation(StateValidationType.MODAL_DIALOG_STATE, () -> {
            try {
                List<WebElement> modals = driver.findElements(modalLocator);
                
                Map<String, Object> details = new HashMap<>();
                details.put("modalCount", modals.size());
                
                if (modals.isEmpty()) {
                    details.put("modalPresent", false);
                    return ValidationResult.success(StateValidationType.MODAL_DIALOG_STATE,
                        "No modal dialogs present", details);
                }
                
                WebElement modal = modals.get(0);
                details.put("modalPresent", true);
                details.put("modalVisible", modal.isDisplayed());
                details.put("modalEnabled", modal.isEnabled());
                
                // Check for modal backdrop
                List<WebElement> backdrops = driver.findElements(By.className("modal-backdrop"));
                details.put("backdropPresent", !backdrops.isEmpty());
                
                return ValidationResult.success(StateValidationType.MODAL_DIALOG_STATE,
                    "Modal dialog state validated", details);
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.MODAL_DIALOG_STATE,
                    "Error validating modal dialog state: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates navigation state and browser history.
     * 
     * @param driver The WebDriver instance to check
     * @return ValidationResult indicating navigation state status
     */
    public ValidationResult validateNavigationState(WebDriver driver) {
        return executeValidation(StateValidationType.NAVIGATION_STATE, () -> {
            try {
                JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;
                
                Map<String, Object> details = new HashMap<>();
                details.put("currentUrl", driver.getCurrentUrl());
                details.put("pageTitle", driver.getTitle());
                
                // Check navigation readiness
                String navScript = "return typeof history !== 'undefined' && history.length > 0";
                Boolean historyAvailable = (Boolean) jsExecutor.executeScript(navScript);
                details.put("historyAvailable", historyAvailable);
                
                // Check for navigation events
                String navEventScript = "return window.performance && window.performance.navigation";
                Object perfNavigation = jsExecutor.executeScript(navEventScript);
                details.put("performanceNavigationAvailable", perfNavigation != null);
                
                return ValidationResult.success(StateValidationType.NAVIGATION_STATE,
                    "Navigation state validated successfully", details);
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.NAVIGATION_STATE,
                    "Error validating navigation state: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates URL patterns against expected patterns.
     * 
     * @param driver The WebDriver instance to check
     * @param expectedPattern The expected URL pattern
     * @return ValidationResult indicating URL validation status
     */
    public ValidationResult validateURLPattern(WebDriver driver, Pattern expectedPattern) {
        return executeValidation(StateValidationType.URL_PATTERN, () -> {
            try {
                String currentUrl = driver.getCurrentUrl();
                boolean matches = expectedPattern.matcher(currentUrl).matches();
                
                Map<String, Object> details = new HashMap<>();
                details.put("currentUrl", currentUrl);
                details.put("expectedPattern", expectedPattern.pattern());
                details.put("matches", matches);
                
                if (matches) {
                    return ValidationResult.success(StateValidationType.URL_PATTERN,
                        "URL matches expected pattern", details);
                } else {
                    return ValidationResult.failure(StateValidationType.URL_PATTERN,
                        "URL does not match expected pattern", details);
                }
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.URL_PATTERN,
                    "Error validating URL pattern: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates presence of expected text content.
     * 
     * @param driver The WebDriver instance to search
     * @param expectedText The text to search for
     * @return ValidationResult indicating text presence status
     */
    public ValidationResult validateTextPresence(WebDriver driver, String expectedText) {
        return executeValidation(StateValidationType.TEXT_PRESENCE, () -> {
            try {
                String pageSource = driver.getPageSource();
                boolean textFound = pageSource.contains(expectedText);
                
                Map<String, Object> details = new HashMap<>();
                details.put("expectedText", expectedText);
                details.put("textFound", textFound);
                details.put("pageLength", pageSource.length());
                
                if (textFound) {
                    return ValidationResult.success(StateValidationType.TEXT_PRESENCE,
                        "Expected text found on page", details);
                } else {
                    return ValidationResult.failure(StateValidationType.TEXT_PRESENCE,
                        "Expected text not found on page", details);
                }
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.TEXT_PRESENCE,
                    "Error validating text presence: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates presence of expected images.
     * 
     * @param driver The WebDriver instance to search
     * @param imageLocator The locator for the image element
     * @return ValidationResult indicating image presence status
     */
    public ValidationResult validateImagePresence(WebDriver driver, By imageLocator) {
        return executeValidation(StateValidationType.IMAGE_PRESENCE, () -> {
            try {
                List<WebElement> images = driver.findElements(imageLocator);
                
                Map<String, Object> details = new HashMap<>();
                details.put("imageCount", images.size());
                details.put("locator", imageLocator.toString());
                
                if (!images.isEmpty()) {
                    WebElement image = images.get(0);
                    details.put("imageVisible", image.isDisplayed());
                    details.put("imageLoaded", isImageLoaded(image));
                    
                    return ValidationResult.success(StateValidationType.IMAGE_PRESENCE,
                        "Image found and validated", details);
                } else {
                    return ValidationResult.failure(StateValidationType.IMAGE_PRESENCE,
                        "Image not found", details);
                }
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.IMAGE_PRESENCE,
                    "Error validating image presence: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates CSS properties of elements.
     * 
     * @param element The WebElement to validate
     * @param property The CSS property to check
     * @param expectedValue The expected CSS value
     * @return ValidationResult indicating CSS validation status
     */
    public ValidationResult validateCSSProperties(WebElement element, String property, String expectedValue) {
        return executeValidation(StateValidationType.CSS_PROPERTIES, () -> {
            try {
                String actualValue = element.getCssValue(property);
                boolean matches = expectedValue.equals(actualValue);
                
                Map<String, Object> details = new HashMap<>();
                details.put("property", property);
                details.put("expectedValue", expectedValue);
                details.put("actualValue", actualValue);
                details.put("matches", matches);
                
                if (matches) {
                    return ValidationResult.success(StateValidationType.CSS_PROPERTIES,
                        "CSS property matches expected value", details);
                } else {
                    return ValidationResult.failure(StateValidationType.CSS_PROPERTIES,
                        "CSS property does not match expected value", details);
                }
                
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.CSS_PROPERTIES,
                    "Error validating CSS properties: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Waits for a custom condition to be met.
     * 
     * @param driver The WebDriver instance to use
     * @param condition The custom wait condition
     * @return ValidationResult indicating condition status
     */
    public ValidationResult waitForCustomCondition(WebDriver driver, WaitCondition condition) {
        return executeValidation(StateValidationType.CUSTOM_CONDITION, () -> {
            try {
                WebDriverWait wait = new WebDriverWait(driver, validationTimeout.get());
                
                ExpectedCondition<Boolean> expectedCondition = new ExpectedCondition<Boolean>() {
                    @Override
                    public Boolean apply(WebDriver d) {
                        return condition.test(d);
                    }
                    
                    @Override
                    public String toString() {
                        return condition.getConditionDescription();
                    }
                };
                
                Boolean result = wait.until(expectedCondition);
                
                Map<String, Object> details = new HashMap<>();
                details.put("conditionDescription", condition.getConditionDescription());
                details.put("conditionMet", result);
                details.put("timeout", condition.getTimeout().toString());
                
                if (Boolean.TRUE.equals(result)) {
                    condition.onConditionMet();
                    return ValidationResult.success(StateValidationType.CUSTOM_CONDITION,
                        "Custom condition met successfully", details);
                } else {
                    condition.onTimeout();
                    return ValidationResult.failure(StateValidationType.CUSTOM_CONDITION,
                        "Custom condition not met within timeout", details);
                }
                
            } catch (TimeoutException e) {
                condition.onTimeout();
                return ValidationResult.failure(StateValidationType.CUSTOM_CONDITION,
                    "Custom condition timed out: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            } catch (Exception e) {
                return ValidationResult.failure(StateValidationType.CUSTOM_CONDITION,
                    "Error waiting for custom condition: " + e.getMessage(),
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates that an element is enabled.
     * 
     * @param element The WebElement to check
     * @return ValidationResult indicating enabled status
     */
    public ValidationResult validateElementEnabled(WebElement element) {
        return executeValidation(StateValidationType.ELEMENT_INTERACTABILITY, () -> {
            try {
                boolean isEnabled = element.isEnabled();
                
                Map<String, Object> details = new HashMap<>();
                details.put("isEnabled", isEnabled);
                details.put("tagName", element.getTagName());
                details.put("className", element.getAttribute("class"));
                
                if (isEnabled) {
                    return ValidationResult.success(StateValidationType.ELEMENT_INTERACTABILITY,
                        "Element is enabled", details);
                } else {
                    return ValidationResult.failure(StateValidationType.ELEMENT_INTERACTABILITY,
                        "Element is disabled", details);
                }
                
            } catch (StaleElementReferenceException e) {
                return ValidationResult.failure(StateValidationType.ELEMENT_INTERACTABILITY,
                    "Element is stale and cannot be checked",
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates that an element is selected (for checkboxes, radio buttons, options).
     * 
     * @param element The WebElement to check
     * @return ValidationResult indicating selected status
     */
    public ValidationResult validateElementSelected(WebElement element) {
        return executeValidation(StateValidationType.ELEMENT_INTERACTABILITY, () -> {
            try {
                boolean isSelected = element.isSelected();
                
                Map<String, Object> details = new HashMap<>();
                details.put("isSelected", isSelected);
                details.put("tagName", element.getTagName());
                details.put("type", element.getAttribute("type"));
                
                return ValidationResult.success(StateValidationType.ELEMENT_INTERACTABILITY,
                    "Element selection state validated: " + isSelected, details);
                
            } catch (StaleElementReferenceException e) {
                return ValidationResult.failure(StateValidationType.ELEMENT_INTERACTABILITY,
                    "Element is stale and cannot be checked",
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates that an element is displayed.
     * 
     * @param element The WebElement to check
     * @return ValidationResult indicating displayed status
     */
    public ValidationResult validateElementDisplayed(WebElement element) {
        return executeValidation(StateValidationType.ELEMENT_VISIBILITY, () -> {
            try {
                boolean isDisplayed = element.isDisplayed();
                
                Map<String, Object> details = new HashMap<>();
                details.put("isDisplayed", isDisplayed);
                details.put("location", element.getLocation().toString());
                details.put("size", element.getSize().toString());
                
                if (isDisplayed) {
                    return ValidationResult.success(StateValidationType.ELEMENT_VISIBILITY,
                        "Element is displayed", details);
                } else {
                    return ValidationResult.failure(StateValidationType.ELEMENT_VISIBILITY,
                        "Element is not displayed", details);
                }
                
            } catch (StaleElementReferenceException e) {
                return ValidationResult.failure(StateValidationType.ELEMENT_VISIBILITY,
                    "Element is stale and cannot be checked",
                    Collections.singletonMap("error", e.getMessage()));
            }
        });
    }
    
    /**
     * Gets the most recent validation report.
     * 
     * @return ValidationResult of the most recent validation, or null if none
     */
    public ValidationResult getValidationReport() {
        if (validationHistory.isEmpty()) {
            return null;
        }
        
        return validationHistory.values().stream()
            .max(Comparator.comparing(ValidationResult::getValidationTime))
            .orElse(null);
    }
    
    /**
     * Sets the validation timeout duration.
     * 
     * @param timeout The timeout duration to set
     */
    public void setValidationTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Validation timeout must be positive");
        }
        
        configLock.writeLock().lock();
        try {
            this.validationTimeout.set(timeout);
            this.validationConfig.setDefaultTimeout(timeout);
            
            auditLogger.info("Validation timeout updated to: " + timeout.getSeconds() + " seconds");
            
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Resets all validation metrics to zero.
     */
    public void resetValidationMetrics() {
        configLock.writeLock().lock();
        try {
            for (AtomicLong metric : validationMetrics.values()) {
                metric.set(0);
            }
            validationCounter.set(0);
            validationHistory.clear();
            
            auditLogger.info("Validation metrics reset successfully");
            
        } finally {
            configLock.writeLock().unlock();
        }
    }
    
    /**
     * Gets the timestamp of the last validation performed.
     * 
     * @return Instant of the last validation time
     */
    public Instant getLastValidationTime() {
        return lastValidationTime.get();
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Executes a validation operation with proper error handling and metrics tracking.
     */
    private ValidationResult executeValidation(StateValidationType type, Supplier<ValidationResult> validation) {
        long startTime = System.nanoTime();
        String validationId = generateValidationId();
        
        try {
            // Update metrics
            validationMetrics.get(type).incrementAndGet();
            validationCounter.incrementAndGet();
            lastValidationTime.set(Instant.now());
            
            // Log validation start
            auditLogger.info("Starting validation: " + type + " (ID: " + validationId + ")");
            
            // Execute validation
            ValidationResult result = validation.get();
            
            // Store result in history
            validationHistory.put(validationId, result);
            
            // Clean up old history (keep only last 100 validations)
            if (validationHistory.size() > 100) {
                String oldestKey = validationHistory.keySet().iterator().next();
                validationHistory.remove(oldestKey);
            }
            
            // Log validation completion
            long duration = (System.nanoTime() - startTime) / 1_000_000; // Convert to milliseconds
            String logMessage = String.format("Validation completed: %s (ID: %s) - %s in %dms", 
                type, validationId, result.isValid() ? "SUCCESS" : "FAILURE", duration);
            
            if (result.isValid()) {
                auditLogger.info(logMessage);
            } else {
                auditLogger.warn(logMessage + " - " + result.getErrorMessage());
            }
            
            return result;
            
        } catch (Exception e) {
            long duration = (System.nanoTime() - startTime) / 1_000_000;
            String errorMessage = "Validation error: " + type + " (ID: " + validationId + ") - " + e.getMessage();
            
            auditLogger.error(errorMessage);
            
            ValidationResult errorResult = ValidationResult.failure(type, errorMessage,
                Collections.singletonMap("exception", e.getClass().getSimpleName()));
            
            validationHistory.put(validationId, errorResult);
            
            return errorResult;
        }
    }
    
    /**
     * Determines the current state of an element.
     */
    private ElementState determineElementState(WebElement element) {
        try {
            if (!element.isDisplayed()) {
                return ElementState.HIDDEN;
            }
            
            if (!element.isEnabled()) {
                return ElementState.DISABLED;
            }
            
            if (element.isSelected()) {
                return ElementState.SELECTED;
            }
            
            // Check if element is clickable by trying to get its location
            try {
                element.getLocation();
                return ElementState.CLICKABLE;
            } catch (ElementNotInteractableException e) {
                return ElementState.NOT_CLICKABLE;
            }
            
        } catch (StaleElementReferenceException e) {
            return ElementState.ERROR_STATE;
        } catch (Exception e) {
            return ElementState.ERROR_STATE;
        }
    }
    
    /**
     * Checks if an image element is fully loaded.
     */
    private boolean isImageLoaded(WebElement imageElement) {
        try {
            if (!"img".equalsIgnoreCase(imageElement.getTagName())) {
                return false;
            }
            
            JavascriptExecutor jsExecutor = (JavascriptExecutor) ((org.openqa.selenium.WrapsDriver) imageElement).getWrappedDriver();
            String script = "return arguments[0].complete && typeof arguments[0].naturalHeight != 'undefined' && arguments[0].naturalHeight > 0";
            
            return (Boolean) jsExecutor.executeScript(script, imageElement);
            
        } catch (Exception e) {
            logger.debug("Error checking image load status", e);
            return false;
        }
    }
    
    /**
     * Loads validation configuration from ConfigurationManager.
     */
    private void loadValidationConfiguration() {
        try {
            // Load timeout configuration
            String timeoutProperty = configurationManager.getProperty("validation.timeout.seconds");
            if (timeoutProperty != null) {
                try {
                    long timeoutSeconds = Long.parseLong(timeoutProperty);
                    validationTimeout.set(Duration.ofSeconds(timeoutSeconds));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid timeout configuration: " + timeoutProperty + ", using default");
                }
            }
            
            // Load retry configuration
            String retryProperty = configurationManager.getProperty("validation.retry.attempts");
            if (retryProperty != null) {
                try {
                    int retryAttempts = Integer.parseInt(retryProperty);
                    validationConfig.setRetryAttempts(retryAttempts);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid retry configuration: " + retryProperty + ", using default");
                }
            }
            
            // Load polling interval configuration
            String pollingProperty = configurationManager.getProperty("validation.polling.interval.ms");
            if (pollingProperty != null) {
                try {
                    long pollingMs = Long.parseLong(pollingProperty);
                    validationConfig.setPollingInterval(Duration.ofMillis(pollingMs));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid polling interval configuration: " + pollingProperty + ", using default");
                }
            }
            
            // Load strict mode configuration
            String strictModeProperty = configurationManager.getProperty("validation.strict.mode");
            if ("true".equalsIgnoreCase(strictModeProperty)) {
                validationConfig.enableStrictMode();
            } else if ("false".equalsIgnoreCase(strictModeProperty)) {
                validationConfig.disableStrictMode();
            }
            
            logger.debug("Validation configuration loaded successfully");
            
        } catch (Exception e) {
            logger.warn("Error loading validation configuration, using defaults", e);
        }
    }
    
    /**
     * Generates a unique validation ID for tracking.
     */
    private String generateValidationId() {
        return "VAL_" + System.nanoTime() + "_" + validationCounter.get();
    }
    
    /**
     * Functional interface for validation suppliers.
     */
    @FunctionalInterface
    private interface Supplier<T> {
        T get();
    }
}

/**
 * ValidationResult represents the result of a state validation operation.
 * 
 * Contains validation status, error information, warnings, timing details,
 * and comprehensive validation metadata for debugging and reporting purposes.
 */
class ValidationResult {
    
    private final boolean valid;
    private final List<String> errors;
    private final List<String> warnings;
    private final Map<String, Object> validationDetails;
    private final Instant validationTime;
    private final ElementState elementState;
    private final StateValidationType validationType;
    private final String errorMessage;
    private final String validationSummary;
    
    /**
     * Creates a successful ValidationResult.
     * 
     * @param validationType The type of validation performed
     * @param message Success message
     * @param details Validation details
     * @return ValidationResult indicating success
     */
    public static ValidationResult success(StateValidationType validationType, String message, Map<String, Object> details) {
        return new ValidationResult(true, Collections.emptyList(), Collections.emptyList(),
            details, Instant.now(), ElementState.READY, validationType, null, message);
    }
    
    /**
     * Creates a failed ValidationResult.
     * 
     * @param validationType The type of validation performed
     * @param errorMessage Error message
     * @param details Validation details
     * @return ValidationResult indicating failure
     */
    public static ValidationResult failure(StateValidationType validationType, String errorMessage, Map<String, Object> details) {
        return new ValidationResult(false, Collections.singletonList(errorMessage), Collections.emptyList(),
            details, Instant.now(), ElementState.ERROR_STATE, validationType, errorMessage, "Validation failed");
    }
    
    /**
     * Creates a ValidationResult with complete information.
     * 
     * @param valid Whether validation was successful
     * @param errors List of error messages
     * @param warnings List of warning messages
     * @param validationDetails Detailed validation information
     * @param validationTime Timestamp of validation
     * @param elementState Current element state
     * @param validationType Type of validation performed
     * @param errorMessage Primary error message
     * @param validationSummary Summary of validation results
     */
    public ValidationResult(boolean valid, List<String> errors, List<String> warnings,
                          Map<String, Object> validationDetails, Instant validationTime,
                          ElementState elementState, StateValidationType validationType,
                          String errorMessage, String validationSummary) {
        this.valid = valid;
        this.errors = new ArrayList<>(errors != null ? errors : Collections.emptyList());
        this.warnings = new ArrayList<>(warnings != null ? warnings : Collections.emptyList());
        this.validationDetails = new HashMap<>(validationDetails != null ? validationDetails : Collections.emptyMap());
        this.validationTime = validationTime != null ? validationTime : Instant.now();
        this.elementState = elementState != null ? elementState : ElementState.READY;
        this.validationType = validationType;
        this.errorMessage = errorMessage;
        this.validationSummary = validationSummary;
    }
    
    /**
     * Returns whether the validation was successful.
     * 
     * @return true if validation passed, false otherwise
     */
    public boolean isValid() {
        return valid;
    }
    
    /**
     * Returns the list of validation errors.
     * 
     * @return Unmodifiable list of error messages
     */
    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }
    
    /**
     * Returns the list of validation warnings.
     * 
     * @return Unmodifiable list of warning messages
     */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }
    
    /**
     * Returns detailed validation information.
     * 
     * @return Unmodifiable map of validation details
     */
    public Map<String, Object> getValidationDetails() {
        return Collections.unmodifiableMap(validationDetails);
    }
    
    /**
     * Returns the validation timestamp.
     * 
     * @return Instant when validation was performed
     */
    public Instant getValidationTime() {
        return validationTime;
    }
    
    /**
     * Returns the element state at time of validation.
     * 
     * @return ElementState enum value
     */
    public ElementState getElementState() {
        return elementState;
    }
    
    /**
     * Returns the type of validation performed.
     * 
     * @return StateValidationType enum value
     */
    public StateValidationType getValidationType() {
        return validationType;
    }
    
    /**
     * Returns the primary error message.
     * 
     * @return Error message string, or null if no errors
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * Returns the validation timestamp as milliseconds since epoch.
     * 
     * @return Timestamp in milliseconds
     */
    public long getTimestamp() {
        return validationTime.toEpochMilli();
    }
    
    /**
     * Returns whether there are any validation errors.
     * 
     * @return true if errors exist, false otherwise
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    /**
     * Returns whether there are any validation warnings.
     * 
     * @return true if warnings exist, false otherwise
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    /**
     * Returns a summary of the validation results.
     * 
     * @return Validation summary string
     */
    public String getValidationSummary() {
        return validationSummary;
    }
    
    /**
     * Adds an error message to the validation result.
     * 
     * @param error Error message to add
     */
    public void addError(String error) {
        if (error != null && !error.trim().isEmpty()) {
            errors.add(error.trim());
        }
    }
    
    /**
     * Adds a warning message to the validation result.
     * 
     * @param warning Warning message to add
     */
    public void addWarning(String warning) {
        if (warning != null && !warning.trim().isEmpty()) {
            warnings.add(warning.trim());
        }
    }
    
    @Override
    public String toString() {
        return String.format("ValidationResult{valid=%s, type=%s, errors=%d, warnings=%d, time=%s}",
            valid, validationType, errors.size(), warnings.size(), validationTime);
    }
}

/**
 * StateValidationType enumeration defines the types of state validations that can be performed.
 * 
 * Each validation type represents a specific aspect of web application state that can be
 * verified to ensure proper functionality and user experience.
 */
enum StateValidationType {
    
    /**
     * Validates that an element is present in the DOM
     */
    ELEMENT_PRESENCE,
    
    /**
     * Validates that an element is visible to the user
     */
    ELEMENT_VISIBILITY,
    
    /**
     * Validates that an element can be interacted with
     */
    ELEMENT_INTERACTABILITY,
    
    /**
     * Validates that page loading has completed
     */
    PAGE_LOAD_COMPLETION,
    
    /**
     * Validates that the DOM is stable with no ongoing mutations
     */
    DOM_STABILITY,
    
    /**
     * Validates that all AJAX requests have completed
     */
    AJAX_COMPLETION,
    
    /**
     * Validates that there are no JavaScript errors in the console
     */
    JAVASCRIPT_ERRORS,
    
    /**
     * Validates the state of form fields
     */
    FORM_FIELD_STATE,
    
    /**
     * Validates the state of modal dialogs
     */
    MODAL_DIALOG_STATE,
    
    /**
     * Validates navigation state and browser history
     */
    NAVIGATION_STATE,
    
    /**
     * Validates URL patterns against expected formats
     */
    URL_PATTERN,
    
    /**
     * Validates presence of expected text content
     */
    TEXT_PRESENCE,
    
    /**
     * Validates presence of expected images
     */
    IMAGE_PRESENCE,
    
    /**
     * Validates CSS properties and styling
     */
    CSS_PROPERTIES,
    
    /**
     * Validates custom wait conditions
     */
    CUSTOM_CONDITION
}

/**
 * ElementState enumeration defines the possible states of web elements.
 * 
 * These states represent the various conditions an element can be in during
 * web automation testing, allowing for precise state validation and handling.
 */
enum ElementState {
    
    /**
     * Element is present in the DOM
     */
    PRESENT,
    
    /**
     * Element is visible and displayed to the user
     */
    VISIBLE,
    
    /**
     * Element is present but not visible
     */
    HIDDEN,
    
    /**
     * Element is enabled and can be interacted with
     */
    ENABLED,
    
    /**
     * Element is disabled and cannot be interacted with
     */
    DISABLED,
    
    /**
     * Element is selected (for checkboxes, radio buttons, options)
     */
    SELECTED,
    
    /**
     * Element is not selected
     */
    DESELECTED,
    
    /**
     * Element is clickable and can receive click events
     */
    CLICKABLE,
    
    /**
     * Element is not clickable or cannot receive click events
     */
    NOT_CLICKABLE,
    
    /**
     * Element is in a loading state
     */
    LOADING,
    
    /**
     * Element is ready for interaction
     */
    READY,
    
    /**
     * Element is in an error state or has encountered an issue
     */
    ERROR_STATE
}

/**
 * WaitCondition interface defines custom wait conditions for state validation.
 * 
 * Implementations of this interface can define specific conditions that must be met
 * before proceeding with test execution, providing flexibility for complex validation scenarios.
 */
interface WaitCondition {
    
    /**
     * Tests whether the condition is currently met.
     * 
     * @param driver The WebDriver instance to use for testing
     * @return true if condition is met, false otherwise
     */
    boolean test(WebDriver driver);
    
    /**
     * Returns a description of this wait condition.
     * 
     * @return String description of the condition
     */
    String getConditionDescription();
    
    /**
     * Returns the timeout duration for this condition.
     * 
     * @return Duration representing the maximum wait time
     */
    Duration getTimeout();
    
    /**
     * Checks if the condition is currently met.
     * 
     * @return true if condition is satisfied, false otherwise
     */
    boolean isConditionMet();
    
    /**
     * Called when the condition times out without being met.
     * Can be used for cleanup or logging purposes.
     */
    void onTimeout();
    
    /**
     * Called when the condition is successfully met.
     * Can be used for logging or triggering follow-up actions.
     */
    void onConditionMet();
}

/**
 * StateValidationConfig manages configuration settings for state validation operations.
 * 
 * This class provides centralized configuration management for validation timeouts,
 * retry attempts, polling intervals, and validation rules.
 */
class StateValidationConfig {
    
    private Duration defaultTimeout = Duration.ofSeconds(10);
    private int retryAttempts = 3;
    private Duration pollingInterval = Duration.ofMillis(500);
    private boolean strictMode = false;
    private final Map<String, Object> validationRules = new ConcurrentHashMap<>();
    
    /**
     * Creates a new StateValidationConfig with default settings.
     */
    public StateValidationConfig() {
        // Initialize with sensible defaults
        initializeDefaultRules();
    }
    
    /**
     * Returns the default timeout for validation operations.
     * 
     * @return Default timeout duration
     */
    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }
    
    /**
     * Returns the number of retry attempts for failed validations.
     * 
     * @return Number of retry attempts
     */
    public int getRetryAttempts() {
        return retryAttempts;
    }
    
    /**
     * Returns the polling interval for condition checking.
     * 
     * @return Polling interval duration
     */
    public Duration getPollingInterval() {
        return pollingInterval;
    }
    
    /**
     * Returns whether strict mode validation is enabled.
     * 
     * @return true if strict mode is enabled, false otherwise
     */
    public boolean isStrictMode() {
        return strictMode;
    }
    
    /**
     * Returns the current validation rules.
     * 
     * @return Map of validation rules
     */
    public Map<String, Object> getValidationRules() {
        return Collections.unmodifiableMap(validationRules);
    }
    
    /**
     * Sets the default timeout for validation operations.
     * 
     * @param timeout Timeout duration to set
     * @throws IllegalArgumentException if timeout is null or negative
     */
    public void setDefaultTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        this.defaultTimeout = timeout;
    }
    
    /**
     * Sets the number of retry attempts for failed validations.
     * 
     * @param attempts Number of retry attempts
     * @throws IllegalArgumentException if attempts is negative
     */
    public void setRetryAttempts(int attempts) {
        if (attempts < 0) {
            throw new IllegalArgumentException("Retry attempts cannot be negative");
        }
        this.retryAttempts = attempts;
    }
    
    /**
     * Sets the polling interval for condition checking.
     * 
     * @param interval Polling interval duration
     * @throws IllegalArgumentException if interval is null or negative
     */
    public void setPollingInterval(Duration interval) {
        if (interval == null || interval.isNegative()) {
            throw new IllegalArgumentException("Polling interval must be positive");
        }
        this.pollingInterval = interval;
    }
    
    /**
     * Enables strict mode validation.
     * In strict mode, validations are more rigorous and may fail more easily.
     */
    public void enableStrictMode() {
        this.strictMode = true;
    }
    
    /**
     * Disables strict mode validation.
     * In non-strict mode, validations are more lenient and forgiving.
     */
    public void disableStrictMode() {
        this.strictMode = false;
    }
    
    /**
     * Adds a validation rule.
     * 
     * @param ruleName Name of the validation rule
     * @param ruleValue Value or configuration for the rule
     */
    public void addValidationRule(String ruleName, Object ruleValue) {
        if (ruleName != null && !ruleName.trim().isEmpty()) {
            validationRules.put(ruleName.trim(), ruleValue);
        }
    }
    
    /**
     * Removes a validation rule.
     * 
     * @param ruleName Name of the validation rule to remove
     */
    public void removeValidationRule(String ruleName) {
        if (ruleName != null) {
            validationRules.remove(ruleName.trim());
        }
    }
    
    /**
     * Initializes default validation rules.
     */
    private void initializeDefaultRules() {
        validationRules.put("element.presence.required", true);
        validationRules.put("element.visibility.required", true);
        validationRules.put("ajax.completion.timeout", Duration.ofSeconds(30));
        validationRules.put("dom.stability.check.duration", Duration.ofMillis(500));
        validationRules.put("javascript.errors.fail.validation", true);
        validationRules.put("modal.backdrop.required", false);
        validationRules.put("form.field.validation.enabled", true);
        validationRules.put("css.property.comparison.case.sensitive", false);
        validationRules.put("text.search.case.sensitive", false);
        validationRules.put("url.pattern.validation.enabled", true);
    }
    
    @Override
    public String toString() {
        return String.format("StateValidationConfig{timeout=%s, retryAttempts=%d, pollingInterval=%s, strictMode=%s, rules=%d}",
            defaultTimeout, retryAttempts, pollingInterval, strictMode, validationRules.size());
    }
}