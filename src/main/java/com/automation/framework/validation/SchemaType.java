package com.automation.framework.validation;

/**
 * SchemaType enumeration defines the supported types of schema validation.
 * 
 * This enum provides standardized classification of schema types to enable appropriate
 * validation strategies, parser selection, and processing approaches for different
 * types of schema validation operations within the automation framework.
 * 
 * Supported Schema Types:
 * - JSON_SCHEMA: JSON Schema validation using draft-07 or later specifications
 * - XML_SCHEMA: XML Schema (XSD) validation for SOAP and XML-based APIs  
 * - OPENAPI_SCHEMA: OpenAPI v3.x specification validation for REST API contracts
 * - SWAGGER_SCHEMA: Swagger v2.x specification validation for legacy API contracts
 * 
 * @author Blitzy Framework
 * @version 1.0.0
 * @since 2024
 */
public enum SchemaType {
    /**
     * JSON Schema validation using draft-07 or later specifications.
     * Supports nested object validation, array validation, and complex data type constraints.
     */
    JSON_SCHEMA,
    
    /**
     * XML Schema (XSD) validation for SOAP and XML-based APIs.
     * Supports complex XML structures, namespace validation, and schema imports.
     */
    XML_SCHEMA,
    
    /**
     * OpenAPI v3.x specification validation for REST API contracts.
     * Supports comprehensive API contract validation and endpoint-specific validation.
     */
    OPENAPI_SCHEMA,
    
    /**
     * Swagger v2.x specification validation for legacy API contracts.
     * Provides backward compatibility for older API specification formats.
     */
    SWAGGER_SCHEMA;
    
    /**
     * Gets the file extensions commonly associated with this schema type.
     * 
     * @return Array of file extensions for this schema type
     */
    public String[] getFileExtensions() {
        switch (this) {
            case JSON_SCHEMA:
                return new String[]{".json", ".jsonschema"};
            case XML_SCHEMA:
                return new String[]{".xsd", ".xml"};
            case OPENAPI_SCHEMA:
                return new String[]{".yaml", ".yml", ".json"};
            case SWAGGER_SCHEMA:
                return new String[]{".yaml", ".yml", ".json"};
            default:
                return new String[]{};
        }
    }
    
    /**
     * Gets the MIME types associated with this schema type.
     * 
     * @return Array of MIME types for this schema type
     */
    public String[] getMimeTypes() {
        switch (this) {
            case JSON_SCHEMA:
                return new String[]{"application/json", "application/schema+json"};
            case XML_SCHEMA:
                return new String[]{"application/xml", "text/xml"};
            case OPENAPI_SCHEMA:
            case SWAGGER_SCHEMA:
                return new String[]{"application/yaml", "application/json"};
            default:
                return new String[]{};
        }
    }
    
    /**
     * Determines schema type from file extension.
     * 
     * @param filename The filename to analyze
     * @return SchemaType based on file extension, or null if not recognized
     */
    public static SchemaType fromFileExtension(String filename) {
        if (filename == null) {
            return null;
        }
        
        String lowerFilename = filename.toLowerCase();
        
        if (lowerFilename.endsWith(".json") || lowerFilename.endsWith(".jsonschema")) {
            return JSON_SCHEMA;
        } else if (lowerFilename.endsWith(".xsd")) {
            return XML_SCHEMA;
        } else if (lowerFilename.endsWith(".yaml") || lowerFilename.endsWith(".yml")) {
            // Default to OpenAPI for YAML files
            return OPENAPI_SCHEMA;
        }
        
        return null;
    }
    
    /**
     * Determines schema type from MIME type.
     * 
     * @param mimeType The MIME type to analyze
     * @return SchemaType based on MIME type, or null if not recognized
     */
    public static SchemaType fromMimeType(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        
        String lowerMimeType = mimeType.toLowerCase();
        
        if (lowerMimeType.contains("json")) {
            return JSON_SCHEMA;
        } else if (lowerMimeType.contains("xml")) {
            return XML_SCHEMA;
        } else if (lowerMimeType.contains("yaml")) {
            return OPENAPI_SCHEMA;
        }
        
        return null;
    }
}