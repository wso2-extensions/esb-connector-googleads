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

import org.apache.commons.lang3.StringUtils;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class RestURLBuilder extends AbstractConnector {

    private static final String encoding = "UTF-8";
    private static final String URL_PATH = "uri.var.urlPath";
    private static final String URL_QUERY = "uri.var.urlQuery";
    private String operationPath = "";
    private String pathParameters = "";
    private String queryParameters = "";

    public String getOperationPath() {

        return operationPath;
    }

    public void setOperationPath(String operationPath) {

        this.operationPath = operationPath;
    }

    public String getPathParameters() {

        return pathParameters;
    }

    public void setPathParameters(String pathParameters) {

        this.pathParameters = pathParameters;
    }

    public String getQueryParameters() {

        return queryParameters;
    }

    public void setQueryParameters(String queryParameters) {

        this.queryParameters = queryParameters;
    }

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        try {
            String urlPath = getOperationPath();
            if (StringUtils.isNotEmpty(this.pathParameters)) {
                String[] pathParameterList = getPathParameters().split(",");
                for (String pathParameter : pathParameterList) {
                    String paramValue = (String) getParameter(messageContext, pathParameter);
                    if (StringUtils.isNotEmpty(paramValue)) {
                        String encodedParamValue = URLEncoder.encode(paramValue, encoding);
                        urlPath = urlPath.replace("{" + pathParameter + "}", encodedParamValue);
                    } else {
                        String errorMessage = Constants.GENERAL_ERROR_MSG + "Mapping parameter '" + pathParameter + "' is not set.";
                        Utils.setErrorPropertiesToMessage(messageContext, Constants.ErrorCodes.INVALID_CONFIG, errorMessage);
                        handleException(errorMessage, messageContext);
                    }
                }
            }

            StringBuilder urlQueryBuilder = new StringBuilder();
            if (StringUtils.isNotEmpty(this.queryParameters)) {
                String[] queryParameterList = getQueryParameters().split(",");
                for (String queryParameter : queryParameterList) {
                    String paramValue = (String) getParameter(messageContext, queryParameter);
                    if (StringUtils.isNotEmpty(paramValue)) {
                        String encodedParamValue = URLEncoder.encode(paramValue, encoding);
                        urlQueryBuilder.append(queryParameter).append('=').append(encodedParamValue).append('&');
                    }
                }
            }

            String urlQuery = "";
            if (urlQueryBuilder.length() > 0) {
                urlQuery = "?" + urlQueryBuilder.substring(0, urlQueryBuilder.length() - 1);
            }

            messageContext.setProperty(URL_PATH, urlPath);
            messageContext.setProperty(URL_QUERY, urlQuery);

        } catch (UnsupportedEncodingException e) {
            String errorMessage = Constants.GENERAL_ERROR_MSG + "Error occurred while constructing the URL query.";
            Utils.setErrorPropertiesToMessage(messageContext, Constants.ErrorCodes.GENERAL_ERROR, errorMessage);
            handleException(errorMessage, messageContext);
        }
    }
}
