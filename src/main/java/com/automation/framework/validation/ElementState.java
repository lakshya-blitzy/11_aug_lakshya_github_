package com.automation.framework.validation;

/**
 * ElementState enumeration defines the possible states of web elements.
 * 
 * These states represent the various conditions an element can be in during
 * web automation testing, allowing for precise state validation and handling.
 */
public enum ElementState {
    
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