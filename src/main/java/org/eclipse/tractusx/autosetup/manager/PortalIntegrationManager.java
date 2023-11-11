/********************************************************************************
 * Copyright (c) 2022, 2023 T-Systems International GmbH
 * Copyright (c) 2022, 2023 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ********************************************************************************/

package org.eclipse.tractusx.autosetup.manager;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.tractusx.autosetup.constant.TriggerStatusEnum;
import org.eclipse.tractusx.autosetup.entity.AutoSetupTriggerDetails;
import org.eclipse.tractusx.autosetup.entity.AutoSetupTriggerEntry;
import org.eclipse.tractusx.autosetup.exception.ServiceException;
import org.eclipse.tractusx.autosetup.model.Customer;
import org.eclipse.tractusx.autosetup.model.SelectedTools;
import org.eclipse.tractusx.autosetup.portal.model.ClientInfo;
import org.eclipse.tractusx.autosetup.portal.model.ServiceInstanceResultRequest;
import org.eclipse.tractusx.autosetup.portal.model.ServiceInstanceResultResponse;
import org.eclipse.tractusx.autosetup.portal.model.TechnicalUserInfo;
import org.eclipse.tractusx.autosetup.portal.proxy.PortalIntegrationProxy;
import org.eclipse.tractusx.autosetup.utility.LogUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.databind.JsonNode;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortalIntegrationManager {

	private final PortalIntegrationProxy portalIntegrationProxy;

	private final AutoSetupTriggerManager autoSetupTriggerManager;

	@Value("${portal.url}")
	private URI portalUrl;

	@Value("${portal.keycloak.clientId}")
	private String clientId;

	@Value("${portal.keycloak.clientSecret}")
	private String clientSecret;

	@Value("${portal.keycloak.tokenURI}")
	private URI tokenURI;

	@Retryable(retryFor = {
			ServiceException.class }, maxAttemptsExpression = "${retry.maxAttempts}", backoff = @Backoff(delayExpression = "#{${retry.backOffDelay}}"))
	public Map<String, String> postServiceInstanceResultAndGetTenantSpecs(Customer customerDetails, SelectedTools tool,
			Map<String, String> inputData, AutoSetupTriggerEntry triger) {

		AutoSetupTriggerDetails autoSetupTriggerDetails = AutoSetupTriggerDetails.builder()
				.id(UUID.randomUUID().toString()).step("PostServiceInstanceResultAndGetTenantSpecs").build();
		ServiceInstanceResultResponse serviceInstanceResultResponse = null;
		try {

			String packageName = tool.getLabel();
			String tenantName = customerDetails.getOrganizationName();

			log.info(LogUtil.encode(tenantName) + "-" + LogUtil.encode(packageName)
					+ "-PostServiceInstanceResultAndGetTenantSpecs creating");
			String dnsName = inputData.get("dnsName");
			String dnsNameURLProtocol = inputData.get("dnsNameURLProtocol");
			String subscriptionId = inputData.get("subscriptionId");
			String offerId = inputData.get("serviceId");

			String applicationURL = dnsNameURLProtocol + "://" + dnsName;
			inputData.put("applicationURL", applicationURL);

			Map<String, String> header = new HashMap<>();
			header.put("Authorization", "Bearer " + getKeycloakToken());

			ServiceInstanceResultRequest serviceInstanceResultRequest = ServiceInstanceResultRequest.builder()
					.requestId(subscriptionId).offerUrl(applicationURL).build();

			if ("app".equalsIgnoreCase(tool.getType())) {
				serviceInstanceResultResponse = processAppGetResponse(subscriptionId, offerId, header,
						serviceInstanceResultRequest);
			} else {
				serviceInstanceResultResponse = processServiceGetResponse(subscriptionId, offerId, header,
						serviceInstanceResultRequest);
			}

			if (serviceInstanceResultResponse != null) {

				TechnicalUserInfo technicalUserInfo = serviceInstanceResultResponse.getTechnicalUserInfo();
				if (technicalUserInfo != null) {
					inputData.put("keycloakAuthenticationClientId", technicalUserInfo.getTechnicalClientId());
					inputData.put("keycloakAuthenticationClientSecret", technicalUserInfo.getTechnicalUserSecret());
				}

				ClientInfo clientInfo = serviceInstanceResultResponse.getClientInfo();
				if (clientInfo != null) {
					inputData.put("keycloakResourceClient", clientInfo.getClientId());
				}
			} else {
				log.error("Error in request process with portal");
			}
		} catch (FeignException e) {

			log.error("PortalIntegrationManager FeignException failed retry attempt: : {}",
					RetrySynchronizationManager.getContext().getRetryCount() + 1);
			log.error("RequestBody: " + e.request());
			log.error("ResponseBody: " + e.contentUTF8());

			autoSetupTriggerDetails.setStatus(TriggerStatusEnum.FAILED.name());
			autoSetupTriggerDetails.setRemark(e.contentUTF8());
			throw new ServiceException("PortalIntegrationManager Oops! We have an FeignException - " + e.contentUTF8());

		} catch (Exception ex) {

			log.error("PortalIntegrationManager Exception failed retry attempt: : {}",
					RetrySynchronizationManager.getContext().getRetryCount() + 1);

			if (serviceInstanceResultResponse != null) {
				String msg = "PortalIntegrationManager failed with details:"
						+ serviceInstanceResultResponse.toJsonString();
				log.error(msg);
				autoSetupTriggerDetails.setRemark(msg);
			} else
				autoSetupTriggerDetails.setRemark(ex.getMessage());

			autoSetupTriggerDetails.setStatus(TriggerStatusEnum.FAILED.name());

			throw new ServiceException("PortalIntegrationManager Oops! We have an exception - " + ex.getMessage());
		} finally {
			autoSetupTriggerManager.saveTriggerDetails(autoSetupTriggerDetails, triger);
		}
		return inputData;
	}

	@SneakyThrows
	private ServiceInstanceResultResponse processAppGetResponse(String subscriptionId, String offerId,
			Map<String, String> header, ServiceInstanceResultRequest serviceInstanceResultRequest) {
		ServiceInstanceResultResponse serviceInstanceResultResponse = null;
		try {
			JsonNode appInstanceResultAndGetTenantSpecs = portalIntegrationProxy
					.getAppInstanceResultAndGetTenantSpecs(portalUrl, header, offerId, subscriptionId);

			String appid = getValueFromJsonNode(appInstanceResultAndGetTenantSpecs, "appInstanceId");
			String offerSubscriptionStatus = getValueFromJsonNode(appInstanceResultAndGetTenantSpecs,
					"offerSubscriptionStatus");
			if ((StringUtils.isNotBlank(offerSubscriptionStatus) || "ACTIVE".equalsIgnoreCase(offerSubscriptionStatus))
					&& StringUtils.isNotBlank(appid)) {
				serviceInstanceResultResponse = ServiceInstanceResultResponse.builder().build();
				serviceInstanceResultResponse.setClientInfo(ClientInfo.builder().clientId(appid).build());
				Optional.ofNullable(formatJsonData(subscriptionId, header, serviceInstanceResultRequest))
						.ifPresent(serviceInstanceResultResponse::setTechnicalUserInfo);
			}
		} catch (Exception e) {
			log.error("ProcessAppGetResponse Error in processing portal call " + e.getMessage());
		}

		if (serviceInstanceResultResponse == null) {
			serviceInstanceResultResponse = portalIntegrationProxy.postAppInstanceResultAndGetTenantSpecs(portalUrl,
					header, serviceInstanceResultRequest);
			log.info("Portal Technical created successfully");
		} else {
			log.info("Credential already created in portal side we read from it again");
		}

		return serviceInstanceResultResponse;
	}

	@SneakyThrows
	private ServiceInstanceResultResponse processServiceGetResponse(String subscriptionId, String offerId,
			Map<String, String> header, ServiceInstanceResultRequest serviceInstanceResultRequest) {
		ServiceInstanceResultResponse serviceInstanceResultResponse = null;
		try {
			JsonNode serviceInstanceResultAndGetTenantSpecs = portalIntegrationProxy
					.getServiceInstanceResultAndGetTenantSpecs(portalUrl, header, offerId, subscriptionId);

			String offerSubscriptionStatus = getValueFromJsonNode(serviceInstanceResultAndGetTenantSpecs,
					"offerSubscriptionStatus");
			String appid = getValueFromJsonNode(serviceInstanceResultAndGetTenantSpecs, "appInstanceId");

			if ((StringUtils.isNotBlank(offerSubscriptionStatus) || "ACTIVE".equalsIgnoreCase(offerSubscriptionStatus))
					&& StringUtils.isNotBlank(appid)) {
				serviceInstanceResultResponse = ServiceInstanceResultResponse.builder().build();
				serviceInstanceResultResponse.setClientInfo(ClientInfo.builder().clientId(appid).build());
				serviceInstanceResultResponse
						.setTechnicalUserInfo(formatJsonData(subscriptionId, header, serviceInstanceResultRequest));
			}
		} catch (Exception e) {
			log.error("ProcessServiceGetResponse Error in processing portal call" + e.getMessage());
		}

		if (serviceInstanceResultResponse == null) {

			serviceInstanceResultResponse = portalIntegrationProxy.postServiceInstanceResultAndGetTenantSpecs(portalUrl,
					header, serviceInstanceResultRequest);
			log.info("PostServiceInstanceResultAndGetTenantSpecs created successfully");
		} else {
			log.info("Credential already created in portal side just read from it again");
		}

		return serviceInstanceResultResponse;
	}

	@SneakyThrows
	private TechnicalUserInfo formatJsonData(String subscriptionId, Map<String, String> header,
			ServiceInstanceResultRequest serviceInstanceResultRequest) {
		try {
			JsonNode technicalUserDetails = portalIntegrationProxy.getTechnicalUserDetails(portalUrl, header,
					subscriptionId);

			return TechnicalUserInfo.builder().technicalClientId(getValueFromJsonNode(technicalUserDetails, "clientId"))
					.technicalUserSecret(getValueFromJsonNode(technicalUserDetails, "secret")).build();
		} catch (Exception e) {
			log.error("Error in read existing TechnicalUserInfo from portal " + e.getMessage());
		}
		return null;

	}

	@SneakyThrows
	private String getValueFromJsonNode(JsonNode appInstanceResultAndGetTenantSpecs, String propertyId) {
		if (appInstanceResultAndGetTenantSpecs != null && appInstanceResultAndGetTenantSpecs.get(propertyId) != null)
			return appInstanceResultAndGetTenantSpecs.get(propertyId).asText();
		else
			return "";
	}

	@SneakyThrows
	private String getKeycloakToken() {

		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("grant_type", "client_credentials");
		body.add("client_id", clientId);
		body.add("client_secret", clientSecret);
		var resultBody = portalIntegrationProxy.readAuthToken(tokenURI, body);

		if (resultBody != null) {
			return resultBody.getAccessToken();
		}
		return null;

	}

}
