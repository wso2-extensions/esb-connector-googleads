/*
 *  Copyright (c) 2024, WSO2 LLC. (https://www.wso2.com).
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.wso2.carbon.google.ads.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.wso2.carbon.connector.core.AbstractConnector;

import java.util.Iterator;

public class JSONContentProcessor extends AbstractConnector {

    private static final String PREPROCESSED_PARAMETERS = "preprocessed.parameters";
    private String jsonArrayContent;
    private String operationType;
    private String userIdentifierSource;
    private String transactionAttributes;
    private String userAttributes;
    private String consent;

    // Getters and setters
    public String getJsonArrayContent() {
        return jsonArrayContent;
    }

    public void setJsonArrayContent(String jsonArrayContent) {
        this.jsonArrayContent = jsonArrayContent;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getUserIdentifierSource() {
        return userIdentifierSource;
    }

    public void setUserIdentifierSource(String userIdentifierSource) {
        this.userIdentifierSource = userIdentifierSource;
    }

    public String getTransactionAttributes() {
        return transactionAttributes;
    }

    public void setTransactionAttributes(String transactionAttributes) {
        this.transactionAttributes = transactionAttributes;
    }

    public String getUserAttributes() {
        return userAttributes;
    }

    public void setUserAttributes(String userAttributes) {
        this.userAttributes = userAttributes;
    }

    public String getConsent() {
        return consent;
    }

    public void setConsent(String consent) {
        this.consent = consent;
    }

    public static String processJSON(String jsonArrayContent, String operationName, String userIdentifierSource,
                                     String transactionAttributes, String userAttributes, String consent) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode payload = mapper.createArrayNode();
        ObjectNode userData = mapper.createObjectNode();
        userData.putArray("userIdentifiers");

        try {
            JsonNode jsonArray = mapper.readTree(jsonArrayContent);

            if (!jsonArray.isArray()) {
                throw new SynapseException("Input content is not a valid JSON array");
            }

            for (JsonNode node : jsonArray) {
                processUserIdentifiers(node, userIdentifierSource, userData, mapper);
                processAddressInfo(node, userIdentifierSource, userData, mapper);
            }

            addOptionalAttributes(userData, "transactionAttributes", transactionAttributes);
            addOptionalAttributes(userData, "userAttributes", userAttributes);
            addOptionalAttributes(userData, "consent", consent);

            ObjectNode operation = mapper.createObjectNode();
            operation.set(operationName, userData);
            payload.add(operation);

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            throw new SynapseException("Failed to process JSON array content", e);
        }
    }

    private static void processUserIdentifiers(JsonNode node, String userIdentifierSource, ObjectNode userData,
                                               ObjectMapper mapper) {
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            String lowerCaseField = field.toLowerCase();

            if (lowerCaseField.startsWith("email")) {
                addUserIdentifier(node, userData, mapper, field, "email", userIdentifierSource);
            } else if (lowerCaseField.startsWith("phone")) {
                addUserIdentifier(node, userData, mapper, field, "phoneNumber", userIdentifierSource);
            }
        }
    }

    private static void addUserIdentifier(JsonNode node, ObjectNode userData, ObjectMapper mapper, String field,
                                          String key, String userIdentifierSource) {
        if (node.has(field) && !node.get(field).asText().isEmpty()) {
            ObjectNode userIdentifier = mapper.createObjectNode();
            userIdentifier.put(key, node.get(field).asText());
            if (!"UNSPECIFIED".equals(userIdentifierSource)) {
                userIdentifier.put("userIdentifierSource", userIdentifierSource);
            }
            userData.withArray("userIdentifiers").add(userIdentifier);
        }
    }

    private static void processAddressInfo(JsonNode node, String userIdentifierSource, ObjectNode userData,
                                           ObjectMapper mapper) {
        if (node.has("addressInfo")) {
            JsonNode addressInfo = node.get("addressInfo");
            ObjectNode addressObject = createAddressObject(addressInfo, mapper);
            addAddressToUserIdentifiers(addressObject, userData, mapper, userIdentifierSource);
        } else {
            ObjectNode addressObject = createAddressObject(node, mapper);
            if (!addressObject.isEmpty()) {
                addAddressToUserIdentifiers(addressObject, userData, mapper, userIdentifierSource);
            }
        }
    }

    private static ObjectNode createAddressObject(JsonNode node, ObjectMapper mapper) {
        ObjectNode addressObject = mapper.createObjectNode();
        String[] addressFields =
                {"firstName", "lastName", "city", "state", "streetAddress", "postalCode", "countryCode"};

        // Iterate over each address field
        for (String field : addressFields) {
            // Iterate over field names in the input node
            node.fieldNames().forEachRemaining(name -> {
                // Handle firstName and lastName variations
                if (name.equalsIgnoreCase("first_name") || name.equalsIgnoreCase("first name")) {
                    addressObject.put("firstName", node.get(name).asText());
                } else if (name.equalsIgnoreCase("last_name") ||
                        name.equalsIgnoreCase("last name")) {
                    addressObject.put("lastName", node.get(name).asText());
                }
                // Handle postalCode for zip field
                else if (name.equalsIgnoreCase("zip")) {
                    addressObject.put("postalCode", node.get(name).asText());
                }
                // Handle countryCode if Country has length 2
                else if (name.equalsIgnoreCase("country") && node.get(name).asText().length() == 2) {
                    addressObject.put("countryCode", node.get(name).asText());
                }
                // General case for other fields
                else if (name.equalsIgnoreCase(field)) {
                    addressObject.put(field, node.get(name).asText());
                }
            });
        }
        return addressObject;
    }


    private static void addAddressToUserIdentifiers(ObjectNode addressObject, ObjectNode userData, ObjectMapper mapper,
                                                    String userIdentifierSource) {
        if (!addressObject.isEmpty()) {
            ObjectNode userIdentifier = mapper.createObjectNode();
            userIdentifier.set("addressInfo", addressObject);
            if (!"UNSPECIFIED".equals(userIdentifierSource)) {
                userIdentifier.put("userIdentifierSource", userIdentifierSource);
            }
            userData.withArray("userIdentifiers").add(userIdentifier);
        }
    }

    private static void addOptionalAttributes(ObjectNode userData, String key, String value) {
        if (value != null && !value.isEmpty()) {
            userData.put(key, value);
        }
    }

    @Override
    public void connect(MessageContext messageContext) {
        try {
            String result = processJSON(jsonArrayContent, operationType, userIdentifierSource,
                    transactionAttributes, userAttributes, consent);
            messageContext.setProperty(PREPROCESSED_PARAMETERS, result);
        } catch (Exception e) {
            Utils.setErrorPropertiesToMessage(messageContext, Constants.ErrorCodes.GENERAL_ERROR, e.getMessage());
            handleException(Constants.GENERAL_ERROR_MSG + e.getMessage(), e, messageContext);
        }
    }
}
