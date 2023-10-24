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

update app_tbl set package_version='0.3.24' where app_name='DT_REGISTRY';
update app_tbl set package_version='2.2.5' where app_name='SDE';

INSERT INTO app_tbl
(app_name, context_cluster, context_namespace, expected_input_data, output_data, package_identifier, package_version, plugin_name, plugin_version, required_yaml_configuration, yaml_value_field_type)
VALUES('STORAGE_MEDIA', 'default', 'kubeapps', '{
    "persistentVolume": {
        "size": "1Gi",
        "subPath": "sftpdata"
    },
    "sftp": {
        "users": [
            {
                "dirs": [
                    "ToBeProcessed",
                    "InProgress",
                    "Success",
                    "PartialSuccess",
                    "Failed"
                ],
                "name": "$\{sftpuser\}",
                "pass": "$\{sftppass\}"
            }
        ]
    },
    "service": {
        "nodePort": 0,
        "port": 22,
        "type": "LoadBalancer"
    }
}', NULL, 'ftpserver/sftp-server', '0.2.0', 'helm.packages', 'v1alpha1', '$\{yamlValues\}', 'JSON');

INSERT INTO app_service_catalog_tbl
(canonical_service_id, ct_name, service_tools, workflow)
VALUES('STORAGE-MEDIA', 'STORAGE-MEDIA', '[{"tool": "STORAGE_MEDIA","label": "storage"}]', 'STORAGE_MEDIA');

update app_tbl set expected_input_data= replace(replace(expected_input_data,'\{','{'),'\}','}'), required_yaml_configuration=replace(replace(required_yaml_configuration,'\{','{'),'\}','}');