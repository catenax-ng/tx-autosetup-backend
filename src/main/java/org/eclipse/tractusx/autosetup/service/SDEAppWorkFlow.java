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

package org.eclipse.tractusx.autosetup.service;

import static org.eclipse.tractusx.autosetup.constant.AppNameConstant.SDE;

import java.util.Map;

import org.eclipse.tractusx.autosetup.constant.AppActions;
import org.eclipse.tractusx.autosetup.entity.AutoSetupTriggerEntry;
import org.eclipse.tractusx.autosetup.manager.AppDeleteManager;
import org.eclipse.tractusx.autosetup.manager.AutomaticStorageMediaSetupManager;
import org.eclipse.tractusx.autosetup.manager.SDEManager;
import org.eclipse.tractusx.autosetup.model.AutoSetupRequest;
import org.eclipse.tractusx.autosetup.model.Customer;
import org.eclipse.tractusx.autosetup.model.SelectedTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SDEAppWorkFlow {

	private final SDEManager sdeManager;
	private final AutomaticStorageMediaSetupManager automaticStorageMediaSetupManager;

	private final AppDeleteManager appDeleteManager;

	@Value("${automatic.storage.media:true}")
	private boolean manualStorageMedia;

	public Map<String, String> getWorkFlow(Customer customerDetails, SelectedTools tool, AppActions workflowAction,
			Map<String, String> inputConfiguration, AutoSetupTriggerEntry triger) {

		if (manualStorageMedia)
			automaticStorageMediaSetupManager.createStorageMedia(customerDetails, tool, inputConfiguration,
					workflowAction, triger);

		inputConfiguration
				.putAll(sdeManager.managePackage(customerDetails, workflowAction, tool, inputConfiguration, triger));

		return inputConfiguration;
	}

	public void deletePackageWorkFlow(SelectedTools tool, Map<String, String> inputConfiguration,
			AutoSetupTriggerEntry triger, AutoSetupRequest orgRequest) {

		appDeleteManager.deletePackage(SDE, tool, inputConfiguration, triger);

		if (manualStorageMedia) {
			String tenantId = inputConfiguration.get("targetNamespace");
			automaticStorageMediaSetupManager.deleteStorageMedia(tenantId, orgRequest.getCustomer().getEmail());
		}

	}
}
