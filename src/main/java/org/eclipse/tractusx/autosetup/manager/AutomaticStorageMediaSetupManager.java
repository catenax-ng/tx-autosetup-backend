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

import java.util.Map;
import java.util.UUID;

import org.eclipse.tractusx.autosetup.constant.AppActions;
import org.eclipse.tractusx.autosetup.constant.TriggerStatusEnum;
import org.eclipse.tractusx.autosetup.entity.AutoSetupTriggerDetails;
import org.eclipse.tractusx.autosetup.entity.AutoSetupTriggerEntry;
import org.eclipse.tractusx.autosetup.exception.ServiceException;
import org.eclipse.tractusx.autosetup.minio.MinioHandler;
import org.eclipse.tractusx.autosetup.model.Customer;
import org.eclipse.tractusx.autosetup.model.SelectedTools;
import org.eclipse.tractusx.autosetup.utility.PasswordGenerator;
import org.eclipse.tractusx.autosetup.utility.ValueReplacerUtility;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;

import io.minio.admin.UserInfo;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AutomaticStorageMediaSetupManager {

	private final AutoSetupTriggerManager autoSetupTriggerManager;
	private final MinioHandler minioHandler;
	private final ValueReplacerUtility valueReplacerUtility;

	@Value("${automatic.storage.media.minio.endpoint:default}")
	private String endpoint;

	@SneakyThrows
	@Retryable(retryFor = {
			ServiceException.class }, maxAttemptsExpression = "${retry.maxAttempts}", backoff = @Backoff(delayExpression = "#{${retry.backOffDelay}}"))
	public Map<String, String> createStorageMedia(Customer customerDetails, SelectedTools tool,
			Map<String, String> inputData, AppActions action, AutoSetupTriggerEntry triger) {

		AutoSetupTriggerDetails autoSetupTriggerDetails = AutoSetupTriggerDetails.builder()
				.id(UUID.randomUUID().toString()).step("STORAGE_MEDIA").build();
		try {
			String tenantNameNamespace = triger.getAutosetupTenantName();
			minioHandler.makeBucket(tenantNameNamespace);

			checkAndCreatePolicy(tenantNameNamespace);

			String accessKey= checkAndCreateUserGetSecret(inputData, tenantNameNamespace, customerDetails);
			
			minioHandler.assignPolicyToUser(accessKey, tenantNameNamespace);
			log.info(accessKey + " assigned '"+tenantNameNamespace+"' policy");

			autoSetupTriggerDetails.setStatus(TriggerStatusEnum.SUCCESS.name());
			inputData.put("storage.media.bucket", tenantNameNamespace);
			inputData.put("storage.media.endpoint", endpoint);
			

		} catch (Exception ex) {

			log.error("StorageMediaManager failed retry attempt: : {}",
					RetrySynchronizationManager.getContext().getRetryCount() + 1);
			autoSetupTriggerDetails.setStatus(TriggerStatusEnum.FAILED.name());
			autoSetupTriggerDetails.setRemark(ex.getMessage());
			throw new ServiceException("StorageMediaManager Oops! We have an exception - " + ex.getMessage());

		} finally {
			autoSetupTriggerManager.saveTriggerDetails(autoSetupTriggerDetails, triger);
		}

		return inputData;
	}

	@SneakyThrows
	private String checkAndCreateUserGetSecret(Map<String, String> inputData, String tenantNameNamespace,
			Customer customerDetails) {
		UserInfo userInfo = null;
		String email= customerDetails.getEmail();
		
		try {
			userInfo = minioHandler.getUserInfo(email);
		} catch (Exception e) {
			log.info("Exception to get minio user " + e.getMessage());
		}
		
		if (userInfo == null) {
			String generateRandomPassword = PasswordGenerator.generateRandomPassword(50);
			minioHandler.addUser(email, generateRandomPassword, tenantNameNamespace);
			log.info(email + " user does not exist so created user");
			inputData.put("storage.media.accessKey", email);
			inputData.put("storage.media.secretKey", generateRandomPassword);
			return email;
		} else {
			String generateAccessKey = PasswordGenerator.generateRandomPassword(20);
			String generateRandomPassword = PasswordGenerator.generateRandomPassword(50);
			inputData.put("storage.media.accessKey", generateAccessKey);
			inputData.put("storage.media.secretKey", generateRandomPassword);
			minioHandler.addUser(generateAccessKey, generateRandomPassword, tenantNameNamespace);
			log.info(email + " user already exist with email so creating new access key");
			return generateAccessKey;
		}
		
	}

	@SneakyThrows
	private void checkAndCreatePolicy(String tenantNameNamespace) {
		// deleting policy before creation if exist
		deletePolicy(tenantNameNamespace);

		minioHandler.addCannedPolicy(tenantNameNamespace, valueReplacerUtility
				.valueReplacer("/request-template/s3-policy-template.json", Map.of("bucket", tenantNameNamespace)));
		log.info(tenantNameNamespace + " bucket policy created successfully");
	}
	

	public void deleteStorageMedia(String tenantName, String userEmail) {
		deleteBucket(tenantName);
		deleteUser(userEmail);
		deletePolicy(tenantName);
	}

	private void deleteBucket(String tenantName) {
		try {
			minioHandler.removeBucket(tenantName);
			log.info(tenantName + " bucket deleted");
		} catch (Exception e) {
			log.error("Delete Bucket Exception " + e.getMessage());
		}
	}

	private void deleteUser(String userEmail) {
		try {
			minioHandler.removeUser(userEmail);
			log.info(userEmail + " user deleted");
		} catch (Exception e) {
			log.error("Delete User Exception " + e.getMessage());
		}
	}

	private void deletePolicy(String tenantName) {
		try {
			minioHandler.removeCannedPolicy(tenantName);
			log.info(tenantName + " bucket policy deleted");
		} catch (Exception e) {
			log.error("Delete Bucket Policy Exception " + e.getMessage());
		}
	}

}
