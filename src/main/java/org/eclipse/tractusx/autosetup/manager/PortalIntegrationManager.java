/********************************************************************************
 * Copyright (c) 2022,2024 T-Systems International GmbH
 * Copyright (c) 2022,2024 Contributors to the Eclipse Foundation
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
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.tractusx.autosetup.constant.TriggerStatusEnum;
import org.eclipse.tractusx.autosetup.entity.AutoSetupTriggerDetails;
import org.eclipse.tractusx.autosetup.entity.AutoSetupTriggerEntry;
import org.eclipse.tractusx.autosetup.exception.NoDataFoundException;
import org.eclipse.tractusx.autosetup.exception.ServiceException;
import org.eclipse.tractusx.autosetup.model.Customer;
import org.eclipse.tractusx.autosetup.model.SelectedTools;
import org.eclipse.tractusx.autosetup.portal.model.ServiceInstanceResultRequest;
import org.eclipse.tractusx.autosetup.portal.model.ServiceInstanceResultResponse;
import org.eclipse.tractusx.autosetup.portal.model.TechnicalUserDetails;
import org.eclipse.tractusx.autosetup.portal.proxy.PortalIntegrationProxy;
import org.eclipse.tractusx.autosetup.utility.KeyCloakTokenProxyUtitlity;
import org.eclipse.tractusx.autosetup.utility.LogUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortalIntegrationManager {

	private static final String ACTIVE = "ACTIVE";

	private final PortalIntegrationProxy portalIntegrationProxy;

	private final AutoSetupTriggerManager autoSetupTriggerManager;

	private final KeyCloakTokenProxyUtitlity keyCloakTokenProxyUtitlity;

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
			String appServiceURIPath = "apps";

			if (!"app".equalsIgnoreCase(tool.getType())) {
				appServiceURIPath = "services";
			}

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
			header.put("Authorization",
					"Bearer " + keyCloakTokenProxyUtitlity.getKeycloakToken(clientId, clientSecret, tokenURI));

			ServiceInstanceResultRequest serviceInstanceResultRequest = ServiceInstanceResultRequest.builder()
					.requestId(subscriptionId).offerUrl(applicationURL).build();

			serviceInstanceResultResponse = processAppServiceGetResponse(subscriptionId, offerId, header,
					serviceInstanceResultRequest, appServiceURIPath);

			if (serviceInstanceResultResponse != null) {

				inputData.put("keycloakResourceClient", serviceInstanceResultResponse.getAppInstanceId());

				autoSetupTriggerDetails.setRemark(serviceInstanceResultResponse.toJsonString());

				if (serviceInstanceResultResponse.getTechnicalUserData() != null
						&& !serviceInstanceResultResponse.getTechnicalUserData().isEmpty()) {
					TechnicalUserDetails technicalUserDetails = serviceInstanceResultResponse.getTechnicalUserData()
							.get(0).getTechnicalUserDetails();
					inputData.put("keycloakAuthenticationClientId", technicalUserDetails.getClientId());
					inputData.put("keycloakAuthenticationClientSecret", technicalUserDetails.getSecret());
				} else {
					throw new NoDataFoundException("Technical user details not found recieved from Portal");
				}

				log.info(LogUtil.encode(tenantName) + "-" + LogUtil.encode(packageName)
						+ "-PostServiceInstanceResultAndGetTenantSpecs created");

			} else {
				throw new NoDataFoundException("Error in request process with portal");
			}
		} catch (NoDataFoundException e) {
			log.error("PortalIntegrationManager NoDataFoundException failed retry attempt: : " + e.getMessage());
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
	private ServiceInstanceResultResponse processAppServiceGetResponse(String subscriptionId, String offerId,
			Map<String, String> header, ServiceInstanceResultRequest serviceInstanceResultRequest,
			String appServiceURIPath) {

		ServiceInstanceResultResponse serviceInstanceResultResponse = verifyIsAlreadySubcribedActivatedAndGetDetails(
				subscriptionId, offerId, header, serviceInstanceResultRequest, appServiceURIPath);

		if (serviceInstanceResultResponse == null) {

			portalIntegrationProxy.postAppServiceStartAutoSetup(portalUrl, header, appServiceURIPath,
					serviceInstanceResultRequest);

			log.info("Post App/Service instanceURL, going to read credentials asynchronously");

			serviceInstanceResultResponse = verifyIsAlreadySubcribedActivatedAndGetDetails(subscriptionId, offerId,
					header, serviceInstanceResultRequest, appServiceURIPath);

		}

		if (serviceInstanceResultResponse == null) {
			throw new ServiceException("Unable to read technical user detials from portal auto setup");
		}

		readTechnicalUserDetails(subscriptionId, header, serviceInstanceResultResponse);

		return serviceInstanceResultResponse;
	}

	@SneakyThrows
	private ServiceInstanceResultResponse verifyIsAlreadySubcribedActivatedAndGetDetails(String subscriptionId,
			String offerId, Map<String, String> header, ServiceInstanceResultRequest serviceInstanceResultRequest,
			String appServiceURIPath) {

		int retry = 5;
		int counter = 1;
		ServiceInstanceResultResponse serviceInstanceResultResponse = null;
		String offerSubscriptionStatus = null;
		do {
			Thread.sleep(20000);
			try {

				header.put("Authorization",
						"Bearer " + keyCloakTokenProxyUtitlity.getKeycloakToken(clientId, clientSecret, tokenURI));

				serviceInstanceResultResponse = portalIntegrationProxy.getAppServiceInstanceSubcriptionDetails(
						portalUrl, header, appServiceURIPath, offerId, subscriptionId);

				offerSubscriptionStatus = serviceInstanceResultResponse.getOfferSubscriptionStatus();

				log.info("VerifyIsAlreadySubcribedActivatedAndGetDetails: The subscription details found for " + offerId
						+ ", " + subscriptionId + ", status is " + offerSubscriptionStatus + ", result is "
						+ serviceInstanceResultResponse.toJsonString());

			} catch (FeignException e) {
				log.error("VerifyIsAlreadySubcribedActivatedAndGetDetails FeignException request: " + e.request());
				log.error("VerifyIsAlreadySubcribedActivatedAndGetDetails FeignException response Body: "
						+ e.responseBody());
				String error = e.contentUTF8();
				error = StringUtils.isAllEmpty(error) ? error : e.getMessage();

				if (e.status() == 404) {
					log.warn("VerifyIsAlreadySubcribedActivatedAndGetDetails: The no app or subscription found for "
							+ offerId + ", " + subscriptionId + ", result is " + error);
				} else {
					log.error("VerifyIsAlreadySubcribedActivatedAndGetDetails FeignException Exception response: "
							+ error);
				}

			} catch (Exception e) {
				log.error("VerifyIsAlreadySubcribedActivatedAndGetDetails Exception processing portal call "
						+ e.getMessage());
			}
			counter++;

		} while (!ACTIVE.equalsIgnoreCase(offerSubscriptionStatus) && counter <= retry);

		return serviceInstanceResultResponse;
	}

	@SneakyThrows
	private void readTechnicalUserDetails(String subscriptionId, Map<String, String> header,
			ServiceInstanceResultResponse serviceInstanceResultResponse) {

		if (serviceInstanceResultResponse.getTechnicalUserData() != null) {

			header.put("Authorization",
					"Bearer " + keyCloakTokenProxyUtitlity.getKeycloakToken(clientId, clientSecret, tokenURI));

			serviceInstanceResultResponse.getTechnicalUserData().forEach(elel -> {
				try {
					TechnicalUserDetails technicalUserDetails = portalIntegrationProxy
							.getTechnicalUserDetails(portalUrl, header, elel.getId());
					elel.setTechnicalUserDetails(technicalUserDetails);
				} catch (FeignException e) {
					log.error("ReadTechnicalUserDetails FeignException request: " + e.request());
					log.error("ReadTechnicalUserDetails FeignException response Body: " + e.responseBody());
					String error = e.contentUTF8();
					error = StringUtils.isAllEmpty(error) ? error : e.getMessage();
					log.error("ReadTechnicalUserDetails FeignException Exception response: " + error);
				} catch (Exception e) {
					log.error("Error in read existing TechnicalUserDetails from portal " + e.getMessage());
				}
			});
		}
	}

}
