/*
 *  Copyright (c) 2024, WSO2 LLC. (https://www.wso2.com).
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.google.ads.connector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.wso2.carbon.connector.core.AbstractConnector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ParameterNormalizer extends AbstractConnector {
    private static final String PreProcessedParameters = "normalized.parameters";
    private String parameters = "";

    // Getters and setters
    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    // JSON object mapper
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Method to hash a given string using SHA-256
    private static String hashSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new SynapseException("Error hashing input", e);
        }
    }

    // Normalize text (lowercase and trim)
    private static String normalizeText(String input) {
        if (input == null) {
            return null;
        }
        return input.trim().toLowerCase();
    }

    // Validate email
    private static boolean isValidEmail(String email) {
        String emailRegex = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$";
        return email != null && email.matches(emailRegex);
    }

    // Normalize phone number to E164 format
    private static String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        // Remove non-digit characters
        String digitsOnly = phoneNumber.replaceAll("[^0-9]", "");
        // Ensure the number starts with a country code (e.g., "+1")
        if (digitsOnly.length() > 10 && !digitsOnly.startsWith("+")) {
            digitsOnly = "+" + digitsOnly;
        }
        return digitsOnly.length() <= 15 && digitsOnly.startsWith("+") ? digitsOnly : null;
    }

    // Transform user identifier payload
    public static String transformUserIdentifierPayload(String jsonPayload) throws JsonProcessingException {
        JsonNode inputArray = objectMapper.readTree(jsonPayload);
        if (!inputArray.isArray()) {
            throw new IllegalArgumentException("Input must be a JSON array");
        }

        for (JsonNode userNode : inputArray) {
            ObjectNode userObject = (ObjectNode) userNode;

            // Process and validate email
            if (!userNode.has("hashedEmail")) { // Check if hashedEmail doesn't exist
                String email = userNode.has("email") ? userNode.get("email").asText(null) : null;
                if (isValidEmail(email)) {
                    userObject.put("hashedEmail", hashSha256(normalizeText(email)));
                    userObject.remove("email");
                }
            }

            // Process and validate phone number
            if (!userNode.has("hashedPhoneNumber")) { // Check if hashedPhoneNumber doesn't exist
                String phoneNumber = userNode.has("phoneNumber") ? userNode.get("phoneNumber").asText(null) : null;
                String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
                if (normalizedPhoneNumber != null) {
                    userObject.put("hashedPhoneNumber", hashSha256(normalizedPhoneNumber));
                    userObject.remove("phoneNumber");
                }
            }

            // Address info processing
            JsonNode addressInfo = userNode.get("addressInfo");
            if (addressInfo != null && addressInfo.isObject()) {
                ObjectNode addressInfoNode = (ObjectNode) addressInfo;

                // Normalize and hash first name
                if (!addressInfoNode.has("hashedFirstName")) { // Check if hashedFirstName doesn't exist
                    String firstName =
                            addressInfoNode.has("firstName") ? addressInfoNode.get("firstName").asText(null) : null;
                    if (firstName != null) {
                        addressInfoNode.put("hashedFirstName", hashSha256(normalizeText(firstName)));
                        addressInfoNode.remove("firstName");
                    }
                }
                // Normalize and hash last name
                if (!addressInfoNode.has("hashedLastName")) { // Check if hashedLastName doesn't exist
                    String lastName =
                            addressInfoNode.has("lastName") ? addressInfoNode.get("lastName").asText(null) : null;
                    if (lastName != null) {
                        addressInfoNode.put("hashedLastName", hashSha256(normalizeText(lastName)));
                        addressInfoNode.remove("lastName");
                    }
                }

                // Normalize and hash street address
                if (!addressInfoNode.has("hashedStreetAddress")) { // Check if hashedStreetAddress doesn't exist
                    String streetAddress =
                            addressInfoNode.has("streetAddress") ? addressInfoNode.get("streetAddress").asText(null) :
                                    null;
                    if (streetAddress != null) {
                        addressInfoNode.put("hashedStreetAddress", hashSha256(normalizeText(streetAddress)));
                        addressInfoNode.remove("streetAddress");
                    }
                }
            }
        }

        // Return the transformed payload as a JSON string
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(inputArray);
    }


    public static String transformOperationsPayload(String jsonPayload) throws JsonProcessingException {
        JsonNode rootNode = objectMapper.readTree(jsonPayload);
        if (!rootNode.isArray()) {
            throw new IllegalArgumentException("Input must be a JSON array");
        }

        for (JsonNode parentNode : rootNode) {
            // Check for either "create" or "remove"
            JsonNode actionNode = parentNode.get("create");
            if (actionNode == null) {
                actionNode = parentNode.get("remove");  // Check for "remove" as well
            }

            if (actionNode != null) {
                JsonNode userIdentifiers = actionNode.get("userIdentifiers");
                if (userIdentifiers != null && userIdentifiers.isArray()) {
                    // Remove the existing userIdentifiers
                    ((ObjectNode) actionNode).remove("userIdentifiers");

                    // Use the existing transformUserIdentifierPayload method for processing
                    String transformedPayload =
                            cleanPayload(transformUserIdentifierPayload(userIdentifiers.toString()));

                    // Add the transformed userIdentifiers back to the action node
                    JsonNode transformedUserIdentifiers = objectMapper.readTree(transformedPayload);
                    ((ObjectNode) actionNode).set("userIdentifiers", transformedUserIdentifiers);
                }
            }
        }

        // Return the transformed payload as a JSON string
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
    }

    // Method to clean the JSON payload
    public static String cleanPayload(String jsonPayload) throws JsonProcessingException {
        JsonNode rootNode = objectMapper.readTree(jsonPayload);
        if (!rootNode.isArray()) {
            throw new IllegalArgumentException("Input must be a JSON array");
        }

        ArrayNode cleanedArray = objectMapper.createArrayNode();

        for (JsonNode parentNode : rootNode) {
            // Process only objects
            if (parentNode.isObject()) {
                ObjectNode parentObjectNode = (ObjectNode) parentNode;

                // Remove unwanted fields
                parentObjectNode.fieldNames().forEachRemaining(field -> {
                    if (!isValidField(field, parentObjectNode)) {
                        parentObjectNode.remove(field);
                    }
                });

                // Process and clean addressInfo
                JsonNode addressInfoNode = parentObjectNode.get("addressInfo");
                if (addressInfoNode != null && addressInfoNode.isObject()) {
                    cleanAddressInfo((ObjectNode) addressInfoNode);
                    if (isEmptyObject(addressInfoNode)) {
                        parentObjectNode.remove("addressInfo");
                    }
                }

                // Remove empty fields or objects
                parentObjectNode.fieldNames().forEachRemaining(field -> {
                    if (isEmptyObject(parentObjectNode.get(field))) {
                        parentObjectNode.remove(field);
                    }
                });

                // Add the cleaned object only if it's not empty
                if (!isEmptyObject(parentObjectNode)) {
                    cleanedArray.add(parentObjectNode);
                }
            }
        }

        // Return the cleaned JSON payload
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cleanedArray);
    }

    // Check if a field is allowed
    private static boolean isValidField(String field, JsonNode parentNode) {
        switch (field) {
            case "userIdentifierSource":
            case "hashedEmail":
            case "hashedPhoneNumber":
            case "mobileId":
            case "thirdPartyUserId":
                return parentNode.get(field).isTextual();
            case "addressInfo":
                return parentNode.get(field).isObject();
            default:
                return false;
        }
    }

    // Clean the addressInfo object
    private static void cleanAddressInfo(ObjectNode addressInfoNode) {
        String[] allowedFields = {
                "hashedFirstName", "hashedLastName", "city", "state", "countryCode", "postalCode", "hashedStreetAddress"
        };

        // Remove invalid fields
        addressInfoNode.fieldNames().forEachRemaining(field -> {
            if (!isValidAddressInfoField(field, addressInfoNode)) {
                addressInfoNode.remove(field);
            }
        });

        // Remove empty fields
        for (String field : allowedFields) {
            if (addressInfoNode.has(field) && addressInfoNode.get(field).asText("").isEmpty()) {
                addressInfoNode.remove(field);
            }
        }
    }

    // Check if a field in addressInfo is valid
    private static boolean isValidAddressInfoField(String field, JsonNode addressInfoNode) {
        switch (field) {
            case "hashedFirstName":
            case "hashedLastName":
            case "city":
            case "state":
            case "countryCode":
            case "postalCode":
            case "hashedStreetAddress":
                return addressInfoNode.get(field).isTextual();
            default:
                return false;
        }
    }

    // Check if a node is an empty object
    private static boolean isEmptyObject(JsonNode node) {
        return node != null && node.isObject() && node.isEmpty();
    }

    @Override
    public void connect(MessageContext messageContext) {
        try {
            String parameters = getParameters();
            messageContext.setProperty(PreProcessedParameters, transformOperationsPayload(parameters));
        } catch (JsonProcessingException e) {
            Utils.setErrorPropertiesToMessage(messageContext, Constants.ErrorCodes.GENERAL_ERROR, e.getMessage());
            handleException(Constants.GENERAL_ERROR_MSG + e.getMessage(), e, messageContext);
        }
    }
}
