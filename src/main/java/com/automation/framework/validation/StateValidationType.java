package com.automation.framework.validation;

/**
 * StateValidationType enumeration defines the types of state validations that can be performed.
 * 
 * Each validation type represents a specific aspect of web application state that can be
 * verified to ensure proper functionality and user experience.
 */
public enum StateValidationType {
    
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