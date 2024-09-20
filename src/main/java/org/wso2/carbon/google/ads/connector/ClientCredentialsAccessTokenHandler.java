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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.core.util.ConnectorUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ClientCredentialsAccessTokenHandler extends AbstractConnector {

    private static final Log log = LogFactory.getLog(ClientCredentialsAccessTokenHandler.class);
    private static final JsonParser parser = new JsonParser();
    private static final String ERROR_MESSAGE = Constants.GENERAL_ERROR_MSG + "\"clientId\", \"clientSecret\"," +
            " \"tokenEndpoint\", \"refreshToken\", \"developerToken\" parameters are mandatory.";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        String connectionName = (String) ConnectorUtils.
                lookupTemplateParamater(messageContext, Constants.CONNECTION_NAME);

        String base = (String) getParameter(messageContext, Constants.BASE);
        if (StringUtils.endsWith(base, "/")) {
            base = StringUtils.removeEnd(base, "/");
        }
        messageContext.setProperty(Constants.PROPERTY_BASE, base);

        String clientId = (String) getParameter(messageContext, Constants.CLIENT_ID);
        String clientSecret = (String) getParameter(messageContext, Constants.CLIENT_SECRET);
        String tokenEndpoint = (String) getParameter(messageContext, Constants.TOKEN_ENDPOINT);
        String refreshToken = (String) getParameter(messageContext, Constants.REFRESH_TOKEN);
        String developerToken = (String) getParameter(messageContext, Constants.DEVELOPER_TOKEN);

        if (StringUtils.isBlank(clientId) || StringUtils.isBlank(clientSecret)
                || StringUtils.isBlank(tokenEndpoint) || StringUtils.isBlank(refreshToken) || StringUtils.isBlank(developerToken)){
            Utils.setErrorPropertiesToMessage(messageContext, Constants.ErrorCodes.INVALID_CONFIG, ERROR_MESSAGE);
            handleException(ERROR_MESSAGE, messageContext);
        }

        Map<String, String> payloadParametersMap = new HashMap<>();
        payloadParametersMap.put(Constants.OAuth2.REFRESH_TOKEN, refreshToken);
        payloadParametersMap.put(Constants.OAuth2.CLIENT_ID, clientId);
        payloadParametersMap.put(Constants.OAuth2.CLIENT_SECRET, clientSecret);

        String tokenKey = getTokenKey(connectionName, tokenEndpoint, developerToken, payloadParametersMap);

        Token token = TokenManager.getToken(tokenKey);
        if (token == null || !token.isActive()) {
            if (token != null && !token.isActive()) {
                TokenManager.removeToken(tokenKey);
            }
            if (log.isDebugEnabled()) {
                if (token == null) {
                    log.debug("Token does not exists in token store.");
                } else {
                    log.debug("Access token is inactive.");
                }
            }
            token = getAndAddNewToken(tokenKey, messageContext, payloadParametersMap, tokenEndpoint);
        }
        String accessToken = token.getAccessToken();
        messageContext.setProperty(Constants.PROPERTY_ACCESS_TOKEN, accessToken);
    }

    /**
     * Function to retrieve access token from the token store or from the token endpoint.
     *
     * @param tokenKey               The token key
     * @param messageContext         The message context that is generated for processing the message
     * @param payloadParametersMap   The payload parameters map
     * @param tokenEndpoint          The token endpoint
     */
    protected synchronized Token getAndAddNewToken(String tokenKey, MessageContext messageContext,
                                                   Map<String, String> payloadParametersMap, String tokenEndpoint) {

        Token token = getAccessToken(messageContext, payloadParametersMap, tokenEndpoint);
        TokenManager.addToken(tokenKey, token);
        return token;
    }

    /**
     * Function to retrieve access token from the token endpoint.
     *
     * @param messageContext         The message context that is generated for processing the message
     * @param payloadParametersMap   The payload parameters map
     * @param tokenEndpoint          The token endpoint
     */
    protected Token getAccessToken(MessageContext messageContext, Map<String, String> payloadParametersMap,
                                   String tokenEndpoint) {

        if (log.isDebugEnabled()) {
            log.debug("Retrieving new access token from token endpoint.");
        }

        long curTimeInMillis = System.currentTimeMillis();
        HttpPost postRequest = new HttpPost(tokenEndpoint);

        ArrayList<NameValuePair> parameters = new ArrayList<>();
        parameters.add(new BasicNameValuePair(Constants.OAuth2.GRANT_TYPE, Constants.OAuth2.REFRESH_TOKEN));

        for (Map.Entry<String, String> entry : payloadParametersMap.entrySet()) {
            parameters.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }

        try {
            postRequest.setEntity(new UrlEncodedFormEntity(parameters));
        } catch (UnsupportedEncodingException e) {
            String errorMessage = Constants.GENERAL_ERROR_MSG + "Error occurred while preparing access token request payload.";
            Utils.setErrorPropertiesToMessage(messageContext, Constants.ErrorCodes.TOKEN_ERROR, errorMessage);
            handleException(errorMessage, messageContext);
        }

        try (CloseableHttpClient httpClient = HttpClients.createDefault();
             CloseableHttpResponse response = httpClient.execute(postRequest)) {
            HttpEntity responseEntity = response.getEntity();

            if (responseEntity == null) {
                String errorMessage = Constants.GENERAL_ERROR_MSG + "Failed to retrieve access token : No entity received.";
                Utils.setErrorPropertiesToMessage(messageContext, Constants.ErrorCodes.TOKEN_ERROR, errorMessage);
                handleException(errorMessage, messageContext);
            }

            int responseStatus = response.getStatusLine().getStatusCode();
            String respMessage = EntityUtils.toString(responseEntity);
            if (responseStatus == HttpURLConnection.HTTP_OK) {
                JsonElement jsonElement = parser.parse(respMessage);
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                String accessToken = jsonObject.get(Constants.OAuth2.ACCESS_TOKEN).getAsString();
                long expireIn = jsonObject.get(Constants.OAuth2.EXPIRES_IN).getAsLong();
                return new Token(accessToken, curTimeInMillis, expireIn * 1000);
            } else {
                String errorMessage = Constants.GENERAL_ERROR_MSG + "Error occurred while retrieving access token. Response: "
                        + "[Status : " + responseStatus + " " + "Message: " + respMessage + "]";
                Utils.setErrorPropertiesToMessage(messageContext, Constants.ErrorCodes.TOKEN_ERROR, errorMessage);
                handleException(errorMessage, messageContext);
            }
        } catch (IOException e) {
            String errorMessage = Constants.GENERAL_ERROR_MSG + "Error occurred while retrieving access token.";
            Utils.setErrorPropertiesToMessage(messageContext, Constants.ErrorCodes.TOKEN_ERROR, errorMessage);
            handleException(errorMessage, messageContext);
        }
        return null;
    }

    /**
     * Function to generate the token key.
     *
     * @param connection        The connection name
     * @param tokenEp           The token endpoint
     * @param developerToken    The developer token
     * @param params            The parameters map
     */
    private String getTokenKey(String connection, String tokenEp, String developerToken, Map<String, String> params) {

        return connection + "_" + Objects.hash(tokenEp, developerToken, params);
    }
}
