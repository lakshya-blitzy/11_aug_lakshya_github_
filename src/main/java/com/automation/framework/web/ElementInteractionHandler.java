package com.automation.framework.web;

// Standard Java imports for time management, collections, and utilities
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// Selenium WebDriver imports for web automation and exception handling
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.ElementNotInteractableException;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Point;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;

// SLF4J logging framework imports for structured logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Internal framework imports for error handling, validation, and browser management
import com.automation.framework.exceptions.RetryMechanism;
import com.automation.framework.exceptions.ExceptionHandler;
import com.automation.framework.exceptions.ErrorReporter;
import com.automation.framework.validation.StateValidator;
import com.automation.framework.web.BrowserManager;

/**
 * ElementInteractionHandler provides dynamic element handling with intelligent retry logic for web automation.
 * 
 * This class manages complex DOM interactions, AJAX handling, and dynamic content behaviors with automatic
 * element re-identification strategies, alternative locator fallbacks, and WebDriverWait patterns for element
 * availability. Provides robust handling of StaleElementReference exceptions and timing-related issues.
 * 
 * Key Features:
 * - Dynamic element handling with retry logic and exponential backoff (1s, 2s, 4s)
 * - Using WebDriverWait and explicit waits for element availability
 * - JavaScript execution capabilities for complex DOM interactions and AJAX handling
 * - Component-Level Recovery with automatic retry mechanisms and alternative locator strategies
 * - Maximum retry attempts: 3 with exponential backoff as per Section 0.6.2
 * - Handles dynamic web application behaviors including AJAX callbacks and DOM mutations
 * - Element state validation for web automation ensuring proper interaction conditions
 * - Comprehensive error handling and recovery with ErrorReporter integration
 * - Alternative locator strategies (ID, CSS, XPath, name, className) for element re-identification
 * - Graceful handling of element issues: not clickable, not visible, covered by overlay, disabled state
 * 
 * Thread Safety:
 * - Thread-safe interaction management with concurrent access support
 * - Atomic counters for interaction tracking and metrics collection
 * - Concurrent maps for configuration and interaction history storage
 * 
 * Resource Management:
 * - Automatic cleanup of interaction resources and temporary data
 * - Memory-efficient interaction history with configurable retention
 * - Integration with framework resource management for optimal performance
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public class ElementInteractionHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ElementInteractionHandler.class);
    
    // Constants for retry configuration and timeouts
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration DEFAULT_ELEMENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_POLLING_INTERVAL = Duration.ofMillis(500);
    private static final int MAX_INTERACTION_HISTORY = 1000;
    
    // Framework dependency components
    private final RetryMechanism retryMechanism;
    private final ExceptionHandler exceptionHandler;
    private final ErrorReporter errorReporter;
    private final StateValidator stateValidator;
    private final BrowserManager browserManager;
    
    // Configuration and state management
    private volatile ElementInteractionConfig configuration;
    private final ElementInteractionMetrics metrics;
    
    // Interaction tracking and history
    private final Map<String, InteractionResult> interactionHistory = new ConcurrentHashMap<>();
    private final AtomicInteger interactionCounter = new AtomicInteger(0);
    private final AtomicLong totalInteractions = new AtomicLong(0);
    private final AtomicLong successfulInteractions = new AtomicLong(0);
    private final AtomicLong failedInteractions = new AtomicLong(0);
    
    // Alternative locator strategies for element re-identification
    private final List<LocatorStrategy> defaultLocatorStrategies = Arrays.asList(
        LocatorStrategy.ID,
        LocatorStrategy.CSS_SELECTOR,
        LocatorStrategy.XPATH,
        LocatorStrategy.NAME,
        LocatorStrategy.CLASS_NAME
    );
    
    /**
     * Constructs a new ElementInteractionHandler with framework dependencies.
     * Initializes retry mechanisms, error handling, and interaction configuration.
     */
    public ElementInteractionHandler() {
        // Initialize framework dependencies
        this.retryMechanism = new RetryMechanism();
        this.exceptionHandler = new ExceptionHandler();
        this.errorReporter = new ErrorReporter();
        this.stateValidator = new StateValidator();
        this.browserManager = BrowserManager.getInstance();
        
        // Initialize configuration with defaults
        this.configuration = new ElementInteractionConfig();
        
        // Initialize metrics tracking
        this.metrics = new ElementInteractionMetrics();
        
        // Configure retry mechanism for element interactions
        configureRetryPolicy();
        
        // Start error reporting correlation
        errorReporter.setCorrelationId(errorReporter.generateCorrelationId());
        
        logger.info("ElementInteractionHandler initialized with max retry attempts: {}, default timeout: {}s", 
                   DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_ELEMENT_TIMEOUT.getSeconds());
    }
    
    /**
     * Performs a generic interaction with an element using retry logic and error recovery.
     * 
     * @param driver The WebDriver instance to use for interaction
     * @param locator The By locator to find the element
     * @param interactionType The type of interaction to perform
     * @param operation The interaction operation to execute
     * @return InteractionResult containing the outcome of the interaction
     */
    public InteractionResult interactWithElement(WebDriver driver, By locator, InteractionType interactionType, 
                                               Supplier<Object> operation) {
        String interactionId = generateInteractionId();
        Instant startTime = Instant.now();
        
        try {
            errorReporter.info("Starting element interaction: " + interactionType + " (ID: " + interactionId + ")");
            
            // Execute interaction with retry mechanism
            Object result = retryMechanism.executeWithRetry("elementInteraction_" + interactionType, () -> {
                // Validate element state before interaction
                WebElement element = waitForElement(driver, locator, configuration.getElementTimeout());
                validateElementForInteraction(element, interactionType);
                
                // Execute the interaction operation
                return operation.get();
            });
            
            // Record successful interaction
            InteractionResult interactionResult = recordSuccessfulInteraction(
                interactionId, interactionType, locator, result, startTime
            );
            
            successfulInteractions.incrementAndGet();
            metrics.recordSuccessfulInteraction(interactionType);
            
            return interactionResult;
            
        } catch (Exception e) {
            // Handle interaction failure with recovery
            InteractionResult failureResult = handleInteractionFailure(
                interactionId, interactionType, locator, e, startTime
            );
            
            failedInteractions.incrementAndGet();
            metrics.recordFailedInteraction(interactionType, e);
            
            return failureResult;
        } finally {
            totalInteractions.incrementAndGet();
        }
    }
    
    /**
     * Clicks on a web element with retry logic and stale element handling.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @return InteractionResult containing the outcome of the click operation
     */
    public InteractionResult clickElement(WebDriver driver, By locator) {
        return interactWithElement(driver, locator, InteractionType.CLICK, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            // Ensure element is clickable
            waitForElementClickable(driver, locator, configuration.getElementTimeout());
            
            // Perform click with JavaScript fallback if needed
            try {
                element.click();
                logger.debug("Successfully clicked element using standard click: {}", locator);
                return "Element clicked successfully";
                
            } catch (ElementNotInteractableException e) {
                if (configuration.isJavaScriptFallbackEnabled()) {
                    executeJavaScriptOnElement(driver, element, "arguments[0].click();");
                    logger.debug("Successfully clicked element using JavaScript fallback: {}", locator);
                    return "Element clicked using JavaScript fallback";
                } else {
                    throw e;
                }
            }
        });
    }
    
    /**
     * Types text into a web element with retry logic and validation.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @param text The text to type into the element
     * @return InteractionResult containing the outcome of the type operation
     */
    public InteractionResult typeIntoElement(WebDriver driver, By locator, String text) {
        return interactWithElement(driver, locator, InteractionType.TYPE, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            // Ensure element is interactable
            stateValidator.validateElementInteractability(element);
            
            // Clear existing content and type new text
            clearElement(driver, locator);
            element.sendKeys(text);
            
            // Verify text was entered correctly
            String enteredText = element.getAttribute("value");
            if (!text.equals(enteredText)) {
                logger.warn("Text verification failed. Expected: '{}', Actual: '{}'", text, enteredText);
            }
            
            logger.debug("Successfully typed text into element: {}", locator);
            return "Text typed successfully: " + text;
        });
    }
    
    /**
     * Gets the text content of a web element with retry logic.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @return InteractionResult containing the element text
     */
    public InteractionResult getElementText(WebDriver driver, By locator) {
        return interactWithElement(driver, locator, InteractionType.GET_TEXT, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            // Ensure element is visible to get meaningful text
            stateValidator.validateElementVisibility(element);
            
            String text = element.getText();
            if (text == null || text.trim().isEmpty()) {
                // Try alternative methods to get text content
                text = element.getAttribute("textContent");
                if (text == null || text.trim().isEmpty()) {
                    text = element.getAttribute("innerText");
                }
            }
            
            logger.debug("Retrieved text from element {}: '{}'", locator, text);
            return text != null ? text.trim() : "";
        });
    }
    
    /**
     * Waits for an element to be present and visible using WebDriverWait.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @param timeout The maximum time to wait for the element
     * @return WebElement if found within timeout
     * @throws TimeoutException if element is not found within timeout
     */
    public WebElement waitForElement(WebDriver driver, By locator, Duration timeout) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, timeout);
            WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(locator));
            
            logger.debug("Element found within timeout: {}", locator);
            return element;
            
        } catch (TimeoutException e) {
            String errorMessage = String.format("Element not found within %d seconds: %s", 
                                               timeout.getSeconds(), locator);
            errorReporter.error("Element wait timeout: " + errorMessage);
            throw new TimeoutException(errorMessage, e);
        }
    }
    
    /**
     * Validates element state before performing interactions.
     * 
     * @param element The WebElement to validate
     * @param interactionType The type of interaction being performed
     * @throws IllegalStateException if element is not in suitable state for interaction
     */
    public void validateElementState(WebElement element, InteractionType interactionType) {
        try {
            // Validate element presence and visibility
            if (!element.isDisplayed()) {
                throw new IllegalStateException("Element is not visible for interaction: " + interactionType);
            }
            
            // Validate element interactability for actions that require it
            if (requiresInteractableElement(interactionType) && !element.isEnabled()) {
                throw new IllegalStateException("Element is not enabled for interaction: " + interactionType);
            }
            
            // Additional validation using StateValidator
            stateValidator.validateElementInteractability(element);
            
            logger.debug("Element state validation passed for interaction: {}", interactionType);
            
        } catch (StaleElementReferenceException e) {
            throw new IllegalStateException("Element is stale and cannot be validated", e);
        }
    }
    
    /**
     * Retries an element interaction with exponential backoff strategy.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @param interactionType The type of interaction to retry
     * @param operation The operation to retry
     * @return InteractionResult containing the outcome of the retry operation
     */
    public InteractionResult retryElementInteraction(WebDriver driver, By locator, 
                                                   InteractionType interactionType, Supplier<Object> operation) {
        String retryId = generateInteractionId() + "_retry";
        
        try {
            errorReporter.info("Starting element interaction retry: " + interactionType + " (ID: " + retryId + ")");
            
            // Configure retry mechanism with exponential backoff
            retryMechanism.configureRetryPolicy(
                configuration.getMaxRetryAttempts(),
                configuration.getRetryDelay(),
                true  // Enable exponential backoff
            );
            
            Object result = retryMechanism.executeWithRetry("retry_" + interactionType, operation);
            
            return InteractionResult.success(interactionType, locator, result, 
                                           retryMechanism.getRetryCount(), Duration.between(Instant.now(), Instant.now()));
            
        } catch (Exception e) {
            errorReporter.logException(e, "Element interaction retry failed: " + interactionType,
                                     createInteractionContext(retryId, interactionType, locator));
            
            return InteractionResult.failure(interactionType, locator, e, 
                                           retryMechanism.getRetryCount(), Duration.between(Instant.now(), Instant.now()));
        }
    }
    
    /**
     * Executes JavaScript on a web element for complex DOM interactions.
     * 
     * @param driver The WebDriver instance to use
     * @param element The WebElement to execute JavaScript on
     * @param script The JavaScript code to execute
     * @return Object result of the JavaScript execution
     */
    public Object executeJavaScriptOnElement(WebDriver driver, WebElement element, String script) {
        try {
            if (!(driver instanceof JavascriptExecutor)) {
                throw new UnsupportedOperationException("Driver does not support JavaScript execution");
            }
            
            JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;
            Object result = jsExecutor.executeScript(script, element);
            
            logger.debug("Successfully executed JavaScript on element: {}", script);
            errorReporter.info("JavaScript executed successfully on element");
            
            return result;
            
        } catch (Exception e) {
            errorReporter.logException(e, "JavaScript execution failed on element: " + script,
                                     createErrorContext("executeJavaScriptOnElement", script));
            throw new RuntimeException("JavaScript execution failed", e);
        }
    }
    
    /**
     * Handles stale element reference exceptions by re-finding the element.
     * 
     * @param driver The WebDriver instance to use
     * @param originalLocator The original locator used to find the element
     * @return WebElement re-found element
     * @throws NoSuchElementException if element cannot be re-found
     */
    public WebElement handleStaleElement(WebDriver driver, By originalLocator) {
        try {
            errorReporter.warn("Handling stale element reference for locator: " + originalLocator);
            
            // Try to re-find element using original locator first
            try {
                WebElement refreshedElement = driver.findElement(originalLocator);
                logger.debug("Successfully re-found element using original locator: {}", originalLocator);
                return refreshedElement;
                
            } catch (NoSuchElementException e) {
                // Fall back to alternative locator strategies
                return findElementWithFallback(driver, originalLocator);
            }
            
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to handle stale element for locator: " + originalLocator,
                                     createErrorContext("handleStaleElement", originalLocator));
            throw new NoSuchElementException("Unable to re-find stale element: " + originalLocator, e);
        }
    }
    
    /**
     * Finds an element using alternative locator strategies for robust element identification.
     * 
     * @param driver The WebDriver instance to use
     * @param primaryLocator The primary locator to try first
     * @return WebElement found using primary or alternative locator
     * @throws NoSuchElementException if element cannot be found with any strategy
     */
    public WebElement findElementWithFallback(WebDriver driver, By primaryLocator) {
        try {
            // Try primary locator first
            try {
                WebElement element = driver.findElement(primaryLocator);
                logger.debug("Element found using primary locator: {}", primaryLocator);
                return element;
                
            } catch (NoSuchElementException e) {
                errorReporter.warn("Primary locator failed, trying alternative strategies: " + primaryLocator);
                
                // Try alternative locator strategies
                List<By> alternativeLocators = getAlternativeLocators(primaryLocator);
                
                for (By alternativeLocator : alternativeLocators) {
                    try {
                        WebElement element = driver.findElement(alternativeLocator);
                        logger.debug("Element found using alternative locator: {}", alternativeLocator);
                        errorReporter.info("Successfully found element using alternative locator strategy");
                        return element;
                        
                    } catch (NoSuchElementException ignored) {
                        // Continue to next alternative
                    }
                }
                
                // All strategies failed
                throw new NoSuchElementException("Element not found with primary or alternative locators: " + primaryLocator);
            }
            
        } catch (Exception e) {
            errorReporter.logException(e, "Element location failed with all strategies: " + primaryLocator,
                                     createErrorContext("findElementWithFallback", primaryLocator));
            throw e;
        }
    }
    
    /**
     * Waits for an element to be clickable using WebDriverWait.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @param timeout The maximum time to wait
     * @return WebElement that is clickable
     * @throws TimeoutException if element is not clickable within timeout
     */
    public WebElement waitForElementClickable(WebDriver driver, By locator, Duration timeout) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, timeout);
            WebElement element = wait.until(ExpectedConditions.elementToBeClickable(locator));
            
            logger.debug("Element is clickable within timeout: {}", locator);
            return element;
            
        } catch (TimeoutException e) {
            String errorMessage = String.format("Element not clickable within %d seconds: %s", 
                                               timeout.getSeconds(), locator);
            errorReporter.error("Element clickable timeout: " + errorMessage);
            throw new TimeoutException(errorMessage, e);
        }
    }
    
    /**
     * Waits for an element to be visible using WebDriverWait.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @param timeout The maximum time to wait
     * @return WebElement that is visible
     * @throws TimeoutException if element is not visible within timeout
     */
    public WebElement waitForElementVisible(WebDriver driver, By locator, Duration timeout) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, timeout);
            WebElement element = wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
            
            logger.debug("Element is visible within timeout: {}", locator);
            return element;
            
        } catch (TimeoutException e) {
            String errorMessage = String.format("Element not visible within %d seconds: %s", 
                                               timeout.getSeconds(), locator);
            errorReporter.error("Element visibility timeout: " + errorMessage);
            throw new TimeoutException(errorMessage, e);
        }
    }
    
    /**
     * Waits for an element to be present in the DOM using WebDriverWait.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @param timeout The maximum time to wait
     * @return WebElement that is present
     * @throws TimeoutException if element is not present within timeout
     */
    public WebElement waitForElementPresent(WebDriver driver, By locator, Duration timeout) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, timeout);
            WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(locator));
            
            logger.debug("Element is present within timeout: {}", locator);
            return element;
            
        } catch (TimeoutException e) {
            String errorMessage = String.format("Element not present within %d seconds: %s", 
                                               timeout.getSeconds(), locator);
            errorReporter.error("Element presence timeout: " + errorMessage);
            throw new TimeoutException(errorMessage, e);
        }
    }
    
    /**
     * Scrolls the page to bring an element into view.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @return InteractionResult containing the outcome of the scroll operation
     */
    public InteractionResult scrollToElement(WebDriver driver, By locator) {
        return interactWithElement(driver, locator, InteractionType.SCROLL, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            // Scroll element into view using JavaScript
            executeJavaScriptOnElement(driver, element, 
                "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});");
            
            // Wait a moment for scroll animation to complete
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            logger.debug("Successfully scrolled to element: {}", locator);
            return "Element scrolled into view";
        });
    }
    
    /**
     * Clears the content of a web element.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @return InteractionResult containing the outcome of the clear operation
     */
    public InteractionResult clearElement(WebDriver driver, By locator) {
        return interactWithElement(driver, locator, InteractionType.CLEAR, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            // Ensure element is interactable
            stateValidator.validateElementInteractability(element);
            
            // Clear element content
            element.clear();
            
            // Verify element is actually cleared
            String remainingValue = element.getAttribute("value");
            if (remainingValue != null && !remainingValue.isEmpty()) {
                // Try JavaScript approach if standard clear didn't work
                executeJavaScriptOnElement(driver, element, "arguments[0].value = '';");
                logger.debug("Used JavaScript fallback to clear element: {}", locator);
            }
            
            logger.debug("Successfully cleared element: {}", locator);
            return "Element cleared successfully";
        });
    }
    
    /**
     * Selects an option from a dropdown element.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the dropdown element
     * @param optionText The text of the option to select
     * @return InteractionResult containing the outcome of the selection operation
     */
    public InteractionResult selectFromDropdown(WebDriver driver, By locator, String optionText) {
        return interactWithElement(driver, locator, InteractionType.DROPDOWN_SELECT, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            // Ensure element is a select dropdown
            if (!"select".equalsIgnoreCase(element.getTagName())) {
                throw new IllegalArgumentException("Element is not a select dropdown: " + locator);
            }
            
            // Ensure dropdown is interactable
            stateValidator.validateElementInteractability(element);
            
            // Select option by visible text
            Select dropdown = new Select(element);
            dropdown.selectByVisibleText(optionText);
            
            // Verify selection
            String selectedText = dropdown.getFirstSelectedOption().getText();
            if (!optionText.equals(selectedText)) {
                logger.warn("Selection verification failed. Expected: '{}', Selected: '{}'", 
                           optionText, selectedText);
            }
            
            logger.debug("Successfully selected option '{}' from dropdown: {}", optionText, locator);
            return "Option selected: " + optionText;
        });
    }
    
    /**
     * Hovers the mouse over a web element.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @return InteractionResult containing the outcome of the hover operation
     */
    public InteractionResult hoverOverElement(WebDriver driver, By locator) {
        return interactWithElement(driver, locator, InteractionType.HOVER, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            // Ensure element is visible for hovering
            stateValidator.validateElementVisibility(element);
            
            // Perform hover action
            Actions actions = new Actions(driver);
            actions.moveToElement(element).perform();
            
            logger.debug("Successfully hovered over element: {}", locator);
            return "Hover action completed";
        });
    }
    
    /**
     * Performs a right-click (context click) on a web element.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @return InteractionResult containing the outcome of the right-click operation
     */
    public InteractionResult rightClickElement(WebDriver driver, By locator) {
        return interactWithElement(driver, locator, InteractionType.RIGHT_CLICK, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            // Ensure element is clickable
            waitForElementClickable(driver, locator, configuration.getElementTimeout());
            
            // Perform right-click action
            Actions actions = new Actions(driver);
            actions.contextClick(element).perform();
            
            logger.debug("Successfully right-clicked element: {}", locator);
            return "Right-click action completed";
        });
    }
    
    /**
     * Performs a double-click on a web element.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @return InteractionResult containing the outcome of the double-click operation
     */
    public InteractionResult doubleClickElement(WebDriver driver, By locator) {
        return interactWithElement(driver, locator, InteractionType.DOUBLE_CLICK, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            // Ensure element is clickable
            waitForElementClickable(driver, locator, configuration.getElementTimeout());
            
            // Perform double-click action
            Actions actions = new Actions(driver);
            actions.doubleClick(element).perform();
            
            logger.debug("Successfully double-clicked element: {}", locator);
            return "Double-click action completed";
        });
    }
    
    /**
     * Performs drag and drop operation between two elements.
     * 
     * @param driver The WebDriver instance to use
     * @param sourceLocator The By locator to find the source element
     * @param targetLocator The By locator to find the target element
     * @return InteractionResult containing the outcome of the drag and drop operation
     */
    public InteractionResult dragAndDropElement(WebDriver driver, By sourceLocator, By targetLocator) {
        String interactionId = generateInteractionId();
        Instant startTime = Instant.now();
        
        try {
            errorReporter.info("Starting drag and drop interaction (ID: " + interactionId + ")");
            
            WebElement sourceElement = findElementWithFallback(driver, sourceLocator);
            WebElement targetElement = findElementWithFallback(driver, targetLocator);
            
            // Ensure both elements are interactable
            stateValidator.validateElementInteractability(sourceElement);
            stateValidator.validateElementInteractability(targetElement);
            
            // Perform drag and drop action
            Actions actions = new Actions(driver);
            actions.dragAndDrop(sourceElement, targetElement).perform();
            
            logger.debug("Successfully dragged element from {} to {}", sourceLocator, targetLocator);
            
            return recordSuccessfulInteraction(
                interactionId, InteractionType.DRAG_DROP, sourceLocator, 
                "Drag and drop completed", startTime
            );
            
        } catch (Exception e) {
            return handleInteractionFailure(
                interactionId, InteractionType.DRAG_DROP, sourceLocator, e, startTime
            );
        }
    }
    
    /**
     * Uploads a file to a file input element.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the file input element
     * @param filePath The absolute path to the file to upload
     * @return InteractionResult containing the outcome of the file upload operation
     */
    public InteractionResult uploadFile(WebDriver driver, By locator, String filePath) {
        return interactWithElement(driver, locator, InteractionType.FILE_UPLOAD, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            // Validate this is a file input element
            String inputType = element.getAttribute("type");
            if (!"file".equalsIgnoreCase(inputType)) {
                throw new IllegalArgumentException("Element is not a file input: " + locator);
            }
            
            // Ensure element is enabled
            if (!element.isEnabled()) {
                throw new IllegalStateException("File input element is disabled: " + locator);
            }
            
            // Upload file by sending the file path
            element.sendKeys(filePath);
            
            // Verify file was uploaded (check if value attribute contains filename)
            String uploadedFile = element.getAttribute("value");
            if (uploadedFile == null || uploadedFile.trim().isEmpty()) {
                throw new RuntimeException("File upload verification failed");
            }
            
            logger.debug("Successfully uploaded file '{}' to element: {}", filePath, locator);
            return "File uploaded: " + filePath;
        });
    }
    
    /**
     * Captures a screenshot of a specific element.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @return InteractionResult containing the outcome and screenshot path
     */
    public InteractionResult captureElementScreenshot(WebDriver driver, By locator) {
        return interactWithElement(driver, locator, InteractionType.WAIT, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            // Use BrowserManager to capture screenshot
            String screenshotData = null;
            List<BrowserSession> activeSessions = browserManager.getActiveSessions();
            
            for (BrowserSession session : activeSessions) {
                if (session.getDriver().equals(driver)) {
                    screenshotData = browserManager.captureSessionScreenshot(session.getSessionId());
                    break;
                }
            }
            
            if (screenshotData == null) {
                // Fallback: use ErrorReporter to capture screenshot
                screenshotData = errorReporter.captureScreenshot(driver);
            }
            
            logger.debug("Successfully captured screenshot for element: {}", locator);
            return "Screenshot captured: " + (screenshotData != null ? "success" : "failed");
        });
    }
    
    /**
     * Gets attributes of a web element.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @param attributeNames The names of the attributes to retrieve
     * @return InteractionResult containing the element attributes
     */
    public InteractionResult getElementAttributes(WebDriver driver, By locator, String... attributeNames) {
        return interactWithElement(driver, locator, InteractionType.GET_ATTRIBUTE, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            Map<String, String> attributes = new HashMap<>();
            for (String attributeName : attributeNames) {
                String attributeValue = element.getAttribute(attributeName);
                attributes.put(attributeName, attributeValue);
            }
            
            logger.debug("Retrieved {} attributes for element: {}", attributes.size(), locator);
            return attributes;
        });
    }
    
    /**
     * Gets CSS properties of a web element.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @param propertyNames The names of the CSS properties to retrieve
     * @return InteractionResult containing the CSS properties
     */
    public InteractionResult getElementCssProperties(WebDriver driver, By locator, String... propertyNames) {
        return interactWithElement(driver, locator, InteractionType.GET_ATTRIBUTE, () -> {
            WebElement element = findElementWithFallback(driver, locator);
            
            Map<String, String> cssProperties = new HashMap<>();
            for (String propertyName : propertyNames) {
                String propertyValue = element.getCssValue(propertyName);
                cssProperties.put(propertyName, propertyValue);
            }
            
            logger.debug("Retrieved {} CSS properties for element: {}", cssProperties.size(), locator);
            return cssProperties;
        });
    }
    
    /**
     * Checks if an element is interactable (enabled and displayed).
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @return boolean indicating if element is interactable
     */
    public boolean isElementInteractable(WebDriver driver, By locator) {
        try {
            WebElement element = findElementWithFallback(driver, locator);
            boolean isInteractable = element.isEnabled() && element.isDisplayed();
            
            logger.debug("Element interactable check for {}: {}", locator, isInteractable);
            return isInteractable;
            
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to check element interactability: " + locator,
                                     createErrorContext("isElementInteractable", locator));
            return false;
        }
    }
    
    /**
     * Refreshes an element reference to handle stale element issues.
     * 
     * @param driver The WebDriver instance to use
     * @param locator The By locator to find the element
     * @return WebElement refreshed element reference
     */
    public WebElement refreshElement(WebDriver driver, By locator) {
        try {
            return handleStaleElement(driver, locator);
        } catch (Exception e) {
            errorReporter.logException(e, "Failed to refresh element: " + locator,
                                     createErrorContext("refreshElement", locator));
            throw e;
        }
    }
    
    /**
     * Gets alternative locators for an element based on its properties.
     * 
     * @param primaryLocator The primary locator that failed
     * @return List of alternative By locators to try
     */
    public List<By> getAlternativeLocators(By primaryLocator) {
        List<By> alternatives = new ArrayList<>();
        
        // Extract information from primary locator to generate alternatives
        String locatorString = primaryLocator.toString();
        
        try {
            // This is a simplified approach - in practice, you might need more sophisticated logic
            // to extract element identifiers and generate meaningful alternatives
            
            if (locatorString.contains("id=")) {
                String id = extractValue(locatorString, "id=");
                if (id != null) {
                    alternatives.add(By.cssSelector("#" + id));
                    alternatives.add(By.xpath("//*[@id='" + id + "']"));
                }
            }
            
            if (locatorString.contains("class=")) {
                String className = extractValue(locatorString, "class=");
                if (className != null) {
                    alternatives.add(By.cssSelector("." + className.replace(" ", ".")));
                    alternatives.add(By.xpath("//*[@class='" + className + "']"));
                }
            }
            
            if (locatorString.contains("name=")) {
                String name = extractValue(locatorString, "name=");
                if (name != null) {
                    alternatives.add(By.cssSelector("[name='" + name + "']"));
                    alternatives.add(By.xpath("//*[@name='" + name + "']"));
                }
            }
            
        } catch (Exception e) {
            logger.debug("Failed to generate alternative locators for: {}", primaryLocator, e);
        }
        
        logger.debug("Generated {} alternative locators for: {}", alternatives.size(), primaryLocator);
        return alternatives;
    }
    
    /**
     * Sets retry configuration for element interactions.
     * 
     * @param config The ElementInteractionConfig to apply
     */
    public void setRetryConfiguration(ElementInteractionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }
        
        this.configuration = config;
        configureRetryPolicy();
        
        logger.info("Updated element interaction configuration: max retries={}, timeout={}s", 
                   config.getMaxRetryAttempts(), config.getElementTimeout().getSeconds());
    }
    
    /**
     * Gets the interaction history for debugging and analysis.
     * 
     * @return Map of interaction IDs to InteractionResult objects
     */
    public Map<String, InteractionResult> getInteractionHistory() {
        return new HashMap<>(interactionHistory);
    }
    
    /**
     * Resets interaction metrics and clears history.
     */
    public void resetInteractionMetrics() {
        totalInteractions.set(0);
        successfulInteractions.set(0);
        failedInteractions.set(0);
        interactionCounter.set(0);
        interactionHistory.clear();
        metrics.resetMetrics();
        
        logger.info("Element interaction metrics reset successfully");
    }
    
    // ========== PRIVATE HELPER METHODS ==========
    
    /**
     * Configures the retry mechanism with default exponential backoff settings.
     */
    private void configureRetryPolicy() {
        retryMechanism.configureRetryPolicy(
            configuration.getMaxRetryAttempts(),
            configuration.getRetryDelay(),
            true  // Enable exponential backoff (1s, 2s, 4s)
        );
        retryMechanism.setMaxRetries(configuration.getMaxRetryAttempts());
    }
    
    /**
     * Validates if an element is in the correct state for the specified interaction.
     */
    private void validateElementForInteraction(WebElement element, InteractionType interactionType) {
        try {
            // Basic visibility and presence check
            if (!element.isDisplayed()) {
                throw new ElementNotInteractableException("Element is not visible: " + interactionType);
            }
            
            // Interaction-specific validations
            if (requiresInteractableElement(interactionType)) {
                if (!element.isEnabled()) {
                    throw new ElementNotInteractableException("Element is not enabled: " + interactionType);
                }
                
                // Use StateValidator for comprehensive validation
                stateValidator.validateElementInteractability(element);
            }
            
        } catch (StaleElementReferenceException e) {
            throw new RuntimeException("Element became stale during validation", e);
        }
    }
    
    /**
     * Determines if an interaction type requires the element to be interactable.
     */
    private boolean requiresInteractableElement(InteractionType interactionType) {
        switch (interactionType) {
            case CLICK:
            case TYPE:
            case CLEAR:
            case HOVER:
            case RIGHT_CLICK:
            case DOUBLE_CLICK:
            case DRAG_DROP:
            case FILE_UPLOAD:
            case DROPDOWN_SELECT:
                return true;
            case GET_TEXT:
            case GET_ATTRIBUTE:
            case WAIT:
            case SCROLL:
            case JAVASCRIPT_EXECUTE:
                return false;
            default:
                return true;  // Default to requiring interactability for safety
        }
    }
    
    /**
     * Records a successful interaction result.
     */
    private InteractionResult recordSuccessfulInteraction(String interactionId, InteractionType interactionType, 
                                                        By locator, Object result, Instant startTime) {
        Duration duration = Duration.between(startTime, Instant.now());
        InteractionResult interactionResult = InteractionResult.success(
            interactionType, locator, result, 0, duration
        );
        
        // Store in history with size limit
        if (interactionHistory.size() >= MAX_INTERACTION_HISTORY) {
            // Remove oldest entry
            String oldestKey = interactionHistory.keySet().iterator().next();
            interactionHistory.remove(oldestKey);
        }
        interactionHistory.put(interactionId, interactionResult);
        
        errorReporter.info("Element interaction completed successfully: " + interactionType + " (ID: " + interactionId + ")");
        return interactionResult;
    }
    
    /**
     * Handles interaction failures with proper error reporting and recovery.
     */
    private InteractionResult handleInteractionFailure(String interactionId, InteractionType interactionType, 
                                                     By locator, Exception exception, Instant startTime) {
        Duration duration = Duration.between(startTime, Instant.now());
        
        // Classify and handle the exception
        Map<String, Object> errorContext = createInteractionContext(interactionId, interactionType, locator);
        exceptionHandler.handleException(exception, "Element interaction failed: " + interactionType, errorContext);
        
        // Create failure result
        InteractionResult failureResult = InteractionResult.failure(
            interactionType, locator, exception, retryMechanism.getRetryCount(), duration
        );
        
        // Store in history
        if (interactionHistory.size() >= MAX_INTERACTION_HISTORY) {
            String oldestKey = interactionHistory.keySet().iterator().next();
            interactionHistory.remove(oldestKey);
        }
        interactionHistory.put(interactionId, failureResult);
        
        // Log detailed error information
        errorReporter.logException(exception, "Element interaction failed: " + interactionType + " (ID: " + interactionId + ")", 
                                 errorContext);
        
        return failureResult;
    }
    
    /**
     * Generates a unique interaction ID for tracking.
     */
    private String generateInteractionId() {
        return "ELEM_INT_" + System.nanoTime() + "_" + interactionCounter.incrementAndGet();
    }
    
    /**
     * Creates error context for logging and debugging.
     */
    private Map<String, Object> createInteractionContext(String interactionId, InteractionType interactionType, By locator) {
        Map<String, Object> context = new HashMap<>();
        context.put("interactionId", interactionId);
        context.put("interactionType", interactionType.toString());
        context.put("locator", locator.toString());
        context.put("totalInteractions", totalInteractions.get());
        context.put("successfulInteractions", successfulInteractions.get());
        context.put("failedInteractions", failedInteractions.get());
        context.put("timestamp", Instant.now().toString());
        return context;
    }
    
    /**
     * Creates error context for general operations.
     */
    private Map<String, Object> createErrorContext(String operation, Object parameter) {
        Map<String, Object> context = new HashMap<>();
        context.put("operation", operation);
        context.put("totalInteractions", totalInteractions.get());
        context.put("timestamp", Instant.now().toString());
        if (parameter != null) {
            context.put("parameter", parameter.toString());
        }
        return context;
    }
    
    /**
     * Extracts a value from a locator string for alternative locator generation.
     */
    private String extractValue(String locatorString, String prefix) {
        try {
            int startIndex = locatorString.indexOf(prefix);
            if (startIndex == -1) {
                return null;
            }
            startIndex += prefix.length();
            
            int endIndex = locatorString.indexOf("]", startIndex);
            if (endIndex == -1) {
                endIndex = locatorString.length();
            }
            
            return locatorString.substring(startIndex, endIndex).trim();
        } catch (Exception e) {
            logger.debug("Failed to extract value with prefix '{}' from: {}", prefix, locatorString);
            return null;
        }
    }
}

/**
 * InteractionResult represents the result of an element interaction operation.
 * 
 * Contains interaction status, result data, timing information, retry count,
 * and comprehensive metadata for debugging and reporting purposes.
 */
class InteractionResult {
    
    private final boolean successful;
    private final Object resultData;
    private final Exception exception;
    private final int attemptCount;
    private final Duration duration;
    private final InteractionType interactionType;
    private final By elementLocator;
    private final String errorMessage;
    private final Instant timestamp;
    private final String retryStrategy;
    private final boolean wasRetried;
    private final String failureReason;
    private final String elementState;
    private final String screenshotPath;
    
    /**
     * Creates a successful InteractionResult.
     */
    public static InteractionResult success(InteractionType interactionType, By locator, Object result, 
                                          int attemptCount, Duration duration) {
        return new InteractionResult(true, result, null, attemptCount, duration, interactionType, locator,
                                   null, Instant.now(), "exponential_backoff", attemptCount > 1, 
                                   null, "success", null);
    }
    
    /**
     * Creates a failed InteractionResult.
     */
    public static InteractionResult failure(InteractionType interactionType, By locator, Exception exception, 
                                          int attemptCount, Duration duration) {
        return new InteractionResult(false, null, exception, attemptCount, duration, interactionType, locator,
                                   exception.getMessage(), Instant.now(), "exponential_backoff", attemptCount > 1,
                                   exception.getClass().getSimpleName(), "error", null);
    }
    
    /**
     * Private constructor for InteractionResult.
     */
    private InteractionResult(boolean successful, Object resultData, Exception exception, int attemptCount,
                            Duration duration, InteractionType interactionType, By elementLocator, 
                            String errorMessage, Instant timestamp, String retryStrategy, boolean wasRetried,
                            String failureReason, String elementState, String screenshotPath) {
        this.successful = successful;
        this.resultData = resultData;
        this.exception = exception;
        this.attemptCount = attemptCount;
        this.duration = duration;
        this.interactionType = interactionType;
        this.elementLocator = elementLocator;
        this.errorMessage = errorMessage;
        this.timestamp = timestamp;
        this.retryStrategy = retryStrategy;
        this.wasRetried = wasRetried;
        this.failureReason = failureReason;
        this.elementState = elementState;
        this.screenshotPath = screenshotPath;
    }
    
    // Getter methods for all properties
    public boolean isSuccessful() { return successful; }
    public Object getResultData() { return resultData; }
    public Exception getException() { return exception; }
    public int getAttemptCount() { return attemptCount; }
    public Duration getDuration() { return duration; }
    public InteractionType getInteractionType() { return interactionType; }
    public By getElementLocator() { return elementLocator; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getTimestamp() { return timestamp; }
    public String getRetryStrategy() { return retryStrategy; }
    public boolean wasRetried() { return wasRetried; }
    public String getFailureReason() { return failureReason; }
    public String getElementState() { return elementState; }
    public String getScreenshotPath() { return screenshotPath; }
}

/**
 * LocatorStrategy enumeration defines the types of element locator strategies.
 * 
 * These strategies represent different methods for identifying web elements
 * and are used for alternative locator fallback mechanisms.
 */
enum LocatorStrategy {
    /**
     * Locate elements by ID attribute
     */
    ID,
    
    /**
     * Locate elements by CSS selector
     */
    CSS_SELECTOR,
    
    /**
     * Locate elements by XPath expression
     */
    XPATH,
    
    /**
     * Locate elements by name attribute
     */
    NAME,
    
    /**
     * Locate elements by class name
     */
    CLASS_NAME,
    
    /**
     * Locate elements by tag name
     */
    TAG_NAME,
    
    /**
     * Locate elements by link text
     */
    LINK_TEXT,
    
    /**
     * Locate elements by partial link text
     */
    PARTIAL_LINK_TEXT
}

/**
 * InteractionType enumeration defines the types of interactions that can be performed on elements.
 * 
 * Each interaction type represents a specific action that can be executed
 * on web elements during automation testing.
 */
enum InteractionType {
    /**
     * Click interaction on an element
     */
    CLICK,
    
    /**
     * Type text into an element
     */
    TYPE,
    
    /**
     * Clear content from an element
     */
    CLEAR,
    
    /**
     * Get text content from an element
     */
    GET_TEXT,
    
    /**
     * Get attribute value from an element
     */
    GET_ATTRIBUTE,
    
    /**
     * Hover mouse over an element
     */
    HOVER,
    
    /**
     * Right-click on an element
     */
    RIGHT_CLICK,
    
    /**
     * Double-click on an element
     */
    DOUBLE_CLICK,
    
    /**
     * Drag and drop operation
     */
    DRAG_DROP,
    
    /**
     * Scroll to bring element into view
     */
    SCROLL,
    
    /**
     * Wait for element condition
     */
    WAIT,
    
    /**
     * Execute JavaScript on element
     */
    JAVASCRIPT_EXECUTE,
    
    /**
     * Upload file to element
     */
    FILE_UPLOAD,
    
    /**
     * Select option from dropdown
     */
    DROPDOWN_SELECT
}

/**
 * ElementInteractionConfig manages configuration settings for element interaction operations.
 * 
 * This class provides centralized configuration management for interaction timeouts,
 * retry attempts, polling intervals, and interaction behavior settings.
 */
class ElementInteractionConfig {
    
    private int maxRetryAttempts = 3;
    private Duration retryDelay = Duration.ofSeconds(1);
    private Duration elementTimeout = Duration.ofSeconds(10);
    private Duration pollingInterval = Duration.ofMillis(500);
    private boolean staleElementRetryEnabled = true;
    private List<LocatorStrategy> alternativeLocatorStrategies = new ArrayList<>();
    private boolean javaScriptFallbackEnabled = true;
    private boolean screenshotOnFailure = true;
    
    /**
     * Creates a new ElementInteractionConfig with default settings.
     */
    public ElementInteractionConfig() {
        // Initialize with default alternative locator strategies
        alternativeLocatorStrategies.add(LocatorStrategy.ID);
        alternativeLocatorStrategies.add(LocatorStrategy.CSS_SELECTOR);
        alternativeLocatorStrategies.add(LocatorStrategy.XPATH);
        alternativeLocatorStrategies.add(LocatorStrategy.NAME);
        alternativeLocatorStrategies.add(LocatorStrategy.CLASS_NAME);
    }
    
    // Getter methods
    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public Duration getRetryDelay() { return retryDelay; }
    public Duration getElementTimeout() { return elementTimeout; }
    public Duration getPollingInterval() { return pollingInterval; }
    public boolean isStaleElementRetryEnabled() { return staleElementRetryEnabled; }
    public List<LocatorStrategy> getAlternativeLocatorStrategies() { return new ArrayList<>(alternativeLocatorStrategies); }
    public boolean isJavaScriptFallbackEnabled() { return javaScriptFallbackEnabled; }
    public boolean getScreenshotOnFailure() { return screenshotOnFailure; }
    
    // Setter methods
    public void setMaxRetryAttempts(int maxRetryAttempts) {
        if (maxRetryAttempts < 0) {
            throw new IllegalArgumentException("Max retry attempts cannot be negative");
        }
        this.maxRetryAttempts = maxRetryAttempts;
    }
    
    public void setRetryDelay(Duration retryDelay) {
        if (retryDelay == null || retryDelay.isNegative()) {
            throw new IllegalArgumentException("Retry delay must be positive");
        }
        this.retryDelay = retryDelay;
    }
    
    public void setElementTimeout(Duration elementTimeout) {
        if (elementTimeout == null || elementTimeout.isNegative()) {
            throw new IllegalArgumentException("Element timeout must be positive");
        }
        this.elementTimeout = elementTimeout;
    }
    
    public void setPollingInterval(Duration pollingInterval) {
        if (pollingInterval == null || pollingInterval.isNegative()) {
            throw new IllegalArgumentException("Polling interval must be positive");
        }
        this.pollingInterval = pollingInterval;
    }
    
    public void enableStaleElementRetry() { this.staleElementRetryEnabled = true; }
    public void disableStaleElementRetry() { this.staleElementRetryEnabled = false; }
    
    public void addAlternativeLocatorStrategy(LocatorStrategy strategy) {
        if (strategy != null && !alternativeLocatorStrategies.contains(strategy)) {
            alternativeLocatorStrategies.add(strategy);
        }
    }
    
    public void removeAlternativeLocatorStrategy(LocatorStrategy strategy) {
        alternativeLocatorStrategies.remove(strategy);
    }
    
    public void enableJavaScriptFallback() { this.javaScriptFallbackEnabled = true; }
    public void disableJavaScriptFallback() { this.javaScriptFallbackEnabled = false; }
    public void enableScreenshotOnFailure() { this.screenshotOnFailure = true; }
    public void disableScreenshotOnFailure() { this.screenshotOnFailure = false; }
}

/**
 * ElementInteractionMetrics tracks metrics and statistics for element interactions.
 * 
 * This class provides comprehensive metrics collection for monitoring interaction
 * performance, success rates, and error patterns.
 */
class ElementInteractionMetrics {
    
    private final AtomicLong totalInteractions = new AtomicLong(0);
    private final AtomicLong successfulInteractions = new AtomicLong(0);
    private final AtomicLong failedInteractions = new AtomicLong(0);
    private final AtomicLong retryCount = new AtomicLong(0);
    private final AtomicLong staleElementExceptions = new AtomicLong(0);
    private final AtomicLong timeoutExceptions = new AtomicLong(0);
    private volatile Instant lastInteractionTime = Instant.now();
    private final Map<InteractionType, AtomicLong> interactionsByType = new ConcurrentHashMap<>();
    private volatile double averageInteractionTime = 0.0;
    
    /**
     * Creates a new ElementInteractionMetrics instance.
     */
    public ElementInteractionMetrics() {
        // Initialize counters for all interaction types
        for (InteractionType type : InteractionType.values()) {
            interactionsByType.put(type, new AtomicLong(0));
        }
    }
    
    /**
     * Records a successful interaction.
     */
    public void recordSuccessfulInteraction(InteractionType type) {
        totalInteractions.incrementAndGet();
        successfulInteractions.incrementAndGet();
        interactionsByType.get(type).incrementAndGet();
        lastInteractionTime = Instant.now();
    }
    
    /**
     * Records a failed interaction.
     */
    public void recordFailedInteraction(InteractionType type, Exception exception) {
        totalInteractions.incrementAndGet();
        failedInteractions.incrementAndGet();
        interactionsByType.get(type).incrementAndGet();
        lastInteractionTime = Instant.now();
        
        // Track specific exception types
        if (exception instanceof StaleElementReferenceException) {
            staleElementExceptions.incrementAndGet();
        } else if (exception instanceof TimeoutException) {
            timeoutExceptions.incrementAndGet();
        }
    }
    
    /**
     * Records a retry attempt.
     */
    public void recordRetry() {
        retryCount.incrementAndGet();
    }
    
    // Getter methods
    public long getTotalInteractions() { return totalInteractions.get(); }
    public long getSuccessfulInteractions() { return successfulInteractions.get(); }
    public long getFailedInteractions() { return failedInteractions.get(); }
    public long getRetryCount() { return retryCount.get(); }
    public double getAverageInteractionTime() { return averageInteractionTime; }
    public long getStaleElementExceptions() { return staleElementExceptions.get(); }
    public long getTimeoutExceptions() { return timeoutExceptions.get(); }
    public Instant getLastInteractionTime() { return lastInteractionTime; }
    
    public double getSuccessRate() {
        long total = totalInteractions.get();
        return total > 0 ? (double) successfulInteractions.get() / total * 100.0 : 0.0;
    }
    
    public double getFailureRate() {
        long total = totalInteractions.get();
        return total > 0 ? (double) failedInteractions.get() / total * 100.0 : 0.0;
    }
    
    public Map<InteractionType, Long> getInteractionsByType() {
        Map<InteractionType, Long> result = new HashMap<>();
        interactionsByType.forEach((type, count) -> result.put(type, count.get()));
        return result;
    }
    
    /**
     * Resets all metrics to zero.
     */
    public void resetMetrics() {
        totalInteractions.set(0);
        successfulInteractions.set(0);
        failedInteractions.set(0);
        retryCount.set(0);
        staleElementExceptions.set(0);
        timeoutExceptions.set(0);
        averageInteractionTime = 0.0;
        lastInteractionTime = Instant.now();
        
        for (AtomicLong counter : interactionsByType.values()) {
            counter.set(0);
        }
    }
    
    /**
     * Gets a summary of all metrics.
     */
    public String getMetricsSummary() {
        return String.format(
            "ElementInteractionMetrics{total=%d, successful=%d, failed=%d, successRate=%.2f%%, " +
            "retries=%d, staleExceptions=%d, timeoutExceptions=%d, avgTime=%.2fms}",
            totalInteractions.get(), successfulInteractions.get(), failedInteractions.get(),
            getSuccessRate(), retryCount.get(), staleElementExceptions.get(), 
            timeoutExceptions.get(), averageInteractionTime
        );
    }
}