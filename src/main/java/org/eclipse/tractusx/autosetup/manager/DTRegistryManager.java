/********************************************************************************

 * Copyright (c) 2023 T-Systems International GmbH
 * Copyright (c) 2023 Contributors to the Eclipse Foundation
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

import static org.eclipse.tractusx.autosetup.constant.AppNameConstant.DT_REGISTRY;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.tractusx.autosetup.apiproxy.EDCProxyService;
import org.eclipse.tractusx.autosetup.constant.AppActions;
import org.eclipse.tractusx.autosetup.constant.SDEConfigurationProperty;
import org.eclipse.tractusx.autosetup.constant.TriggerStatusEnum;
import org.eclipse.tractusx.autosetup.entity.AutoSetupTriggerDetails;
import org.eclipse.tractusx.autosetup.entity.AutoSetupTriggerEntry;
import org.eclipse.tractusx.autosetup.exception.ServiceException;
import org.eclipse.tractusx.autosetup.model.Customer;
import org.eclipse.tractusx.autosetup.model.SelectedTools;
import org.eclipse.tractusx.autosetup.utility.LogUtil;
import org.eclipse.tractusx.autosetup.utility.WaitingTimeUtility;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DTRegistryManager {

	private final KubeAppsPackageManagement appManagement;
	private final AutoSetupTriggerManager autoSetupTriggerManager;

	private final SDEConfigurationProperty sDEConfigurationProperty;

	private final EDCProxyService eDCProxyService;

	@Retryable(retryFor = {
			ServiceException.class }, maxAttemptsExpression = "${retry.maxAttempts}", backoff = @Backoff(delayExpression = "#{${retry.backOffDelay}}"))
	public Map<String, String> managePackage(Customer customerDetails, AppActions action, SelectedTools tool,
			Map<String, String> inputData, AutoSetupTriggerEntry triger) {

		AutoSetupTriggerDetails autoSetupTriggerDetails = AutoSetupTriggerDetails.builder()
				.id(UUID.randomUUID().toString()).step(DT_REGISTRY.name()).build();
		try {
			String packageName = tool.getLabel();

			String dnsName = inputData.get("dnsName");
			String dnsNameURLProtocol = inputData.get("dnsNameURLProtocol");

			String dturi = sDEConfigurationProperty.getDtregistryApiUri();
			dturi = StringUtils.isAllEmpty(dturi) ? "/api/v3.0" : dturi;
			String dtregistryUrl = dnsNameURLProtocol + "://" + dnsName + "/"
					+ sDEConfigurationProperty.getDtregistryUrlPrefix() + dturi;

			inputData.put("rgdatabase", "registry");
			inputData.put("rgdbpass", "admin@123");
			inputData.put("rgusername", "catenax");
			inputData.put("idpClientId", sDEConfigurationProperty.getDtregistryidpClientId());
			inputData.put("idpIssuerUri", sDEConfigurationProperty.getResourceServerIssuer());
			inputData.put("tenantId", sDEConfigurationProperty.getDtregistrytenantId());
			inputData.put("dtregistryUrlPrefix", sDEConfigurationProperty.getDtregistryUrlPrefix());

			inputData.put("dtregistryUrl", dtregistryUrl);

			if (AppActions.CREATE.equals(action))
				appManagement.createPackage(DT_REGISTRY, packageName, inputData);
			else
				appManagement.updatePackage(DT_REGISTRY, packageName, inputData);

			autoSetupTriggerDetails.setStatus(TriggerStatusEnum.SUCCESS.name());

		} catch (Exception ex) {

			log.error("DTRegistryManager failed retry attempt: : {}",
					RetrySynchronizationManager.getContext().getRetryCount() + 1);

			autoSetupTriggerDetails.setStatus(TriggerStatusEnum.FAILED.name());
			autoSetupTriggerDetails.setRemark(ex.getMessage());
			throw new ServiceException("DTRegistryManager Oops! We have an exception - " + ex.getMessage());
		} finally {
			autoSetupTriggerManager.saveTriggerDetails(autoSetupTriggerDetails, triger);
		}

		return inputData;
	}

	@Retryable(retryFor = {
			ServiceException.class }, maxAttemptsExpression = "${retry.maxAttempts}", backoff = @Backoff(delayExpression = "#{${retry.backOffDelay}}"))
	public void dtRegistryRegistrationInEDC(Customer customerDetails, SelectedTools tool, Map<String, String> inputData,
			AutoSetupTriggerEntry triger) {
		String tenantName = LogUtil.encode(customerDetails.getOrganizationName());
		try {
			WaitingTimeUtility.waitingTime(
					tenantName + ": Waiting for EDC asset creation after DT setup to get connector pod up");

			List<Object> asset = eDCProxyService.getAssets(customerDetails, inputData);

			if (asset != null && asset.isEmpty()) {
				createEDCDTAsset(customerDetails, tool, inputData, triger);
			}

		} catch (Exception e) {
			String errorMsg = tenantName
					+ ":It looks EDC connector is not up for DT asset creation, Oops! We have an exception - "
					+ e.getMessage();
			log.error(errorMsg);
			throw new ServiceException(errorMsg);
		}

	}

	private void createEDCDTAsset(Customer customerDetails, SelectedTools tool, Map<String, String> inputData,
			AutoSetupTriggerEntry triger) {

		createEDCAsset(customerDetails, tool, inputData, triger);
		createEDCPolicy(customerDetails, tool, inputData, triger);
		createContractDefination(customerDetails, tool, inputData, triger);

	}

	@Retryable(retryFor = {
			ServiceException.class }, maxAttemptsExpression = "${retry.maxAttempts}", backoff = @Backoff(delayExpression = "#{${retry.backOffDelay}}"))
	private void createEDCAsset(Customer customerDetails, SelectedTools tool, Map<String, String> inputData,
			AutoSetupTriggerEntry triger) {

		AutoSetupTriggerDetails autoSetupTriggerDetails = AutoSetupTriggerDetails.builder()
				.id(UUID.randomUUID().toString()).step("DT_CreateEDCAsset").build();
		String tenantName = LogUtil.encode(customerDetails.getOrganizationName());

		log.info(tenantName + ":DT createEDCAsset creating");
		try {

			String assetId = eDCProxyService.createAsset(customerDetails, inputData);
			log.info(tenantName + ":DT createEDCAsset created " + assetId);
		} catch (Exception ex) {
			log.error(tenantName + ":DTRegistryManager createEDCAsset failed retry attempt: : {}",
					RetrySynchronizationManager.getContext().getRetryCount() + 1);
			autoSetupTriggerDetails.setStatus(TriggerStatusEnum.FAILED.name());
			autoSetupTriggerDetails.setRemark(ex.getMessage());
			throw new ServiceException(
					tenantName + ":DTRegistryManager createEDCAsset Oops! We have an exception - " + ex.getMessage());
		} finally {
			autoSetupTriggerManager.saveTriggerDetails(autoSetupTriggerDetails, triger);
		}

	}

	@Retryable(retryFor = {
			ServiceException.class }, maxAttemptsExpression = "${retry.maxAttempts}", backoff = @Backoff(delayExpression = "#{${retry.backOffDelay}}"))
	private void createEDCPolicy(Customer customerDetails, SelectedTools tool, Map<String, String> inputData,
			AutoSetupTriggerEntry triger) {
		AutoSetupTriggerDetails autoSetupTriggerDetails = AutoSetupTriggerDetails.builder()
				.id(UUID.randomUUID().toString()).step("DT_CreateEDCPolicy").build();
		String tenantName = LogUtil.encode(customerDetails.getOrganizationName());
		log.info(tenantName + ":DT CreateEDCPolicy creating");
		try {

			String policyId = eDCProxyService.createPolicy(customerDetails, inputData);
			log.info(tenantName + ":DT createEDCPolicy created :" + policyId);

		} catch (Exception ex) {

			log.error(tenantName + ":DTRegistryManager CreateEDCPolicy failed retry attempt: : {}",
					RetrySynchronizationManager.getContext().getRetryCount() + 1);

			autoSetupTriggerDetails.setStatus(TriggerStatusEnum.FAILED.name());
			autoSetupTriggerDetails.setRemark(ex.getMessage());
			throw new ServiceException(
					tenantName + ":DTRegistryManager CreateEDCPolicy Oops! We have an exception - " + ex.getMessage());
		} finally {
			autoSetupTriggerManager.saveTriggerDetails(autoSetupTriggerDetails, triger);
		}
	}

	@Retryable(retryFor = {
			ServiceException.class }, maxAttemptsExpression = "${retry.maxAttempts}", backoff = @Backoff(delayExpression = "#{${retry.backOffDelay}}"))
	private void createContractDefination(Customer customerDetails, SelectedTools tool, Map<String, String> inputData,
			AutoSetupTriggerEntry triger) {
		AutoSetupTriggerDetails autoSetupTriggerDetails = AutoSetupTriggerDetails.builder()
				.id(UUID.randomUUID().toString()).step("DT_CreateContractDefination").build();
		String tenantName = LogUtil.encode(customerDetails.getOrganizationName());
		log.info(tenantName + ":DT createContractDefination creating");
		try {

			String contractPolicyId = eDCProxyService.createContractDefination(customerDetails, inputData);
			log.info(tenantName + ":DT CreateContractDefination created " + contractPolicyId);

		} catch (Exception ex) {

			log.error(tenantName + ":DTRegistryManager CreateContractDefination failed retry attempt: : {}",
					RetrySynchronizationManager.getContext().getRetryCount() + 1);

			autoSetupTriggerDetails.setStatus(TriggerStatusEnum.FAILED.name());
			autoSetupTriggerDetails.setRemark(ex.getMessage());
			throw new ServiceException(tenantName
					+ ":DTRegistryManager CreateContractDefination Oops! We have an exception - " + ex.getMessage());
		} finally {
			autoSetupTriggerManager.saveTriggerDetails(autoSetupTriggerDetails, triger);
		}
	}

}
