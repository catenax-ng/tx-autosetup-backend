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

package org.eclipse.tractusx.autosetup.minio;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.admin.MinioAdminClient;
import io.minio.admin.UserInfo;
import io.minio.messages.Bucket;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class MinioHandler {

	private final MinioClient minioClient;

	private final MinioAdminClient minioAdminClient;

	public MinioHandler(@Value("${automatic.storage.media.minio.endpoint:default}") String endpoint,
			@Value("${automatic.storage.media.minio.accessKey:default}") String accessKey,
			@Value("${automatic.storage.media.minio.secretKey:default}") String secretKey) {
		minioAdminClient = MinioAdminClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
		minioClient = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
	}

	@SneakyThrows
	public boolean makeBucket(String nameOfBucket) {
		boolean flag = bucketExists(nameOfBucket);
		if (!flag) {
			minioClient.makeBucket(MakeBucketArgs.builder().bucket(nameOfBucket).build());
			log.info(nameOfBucket + " bucket created successfully");
		} else {
			log.info(nameOfBucket + " bucket already exist");
		}
		return flag;
	}

	@SneakyThrows
	public List<Bucket> listBucket() {
		return minioClient.listBuckets();
	}

	@SneakyThrows
	public void removeBucket(String nameOfBucket) {
		minioClient.removeBucket(RemoveBucketArgs.builder().bucket(nameOfBucket).build());
	}

	@SneakyThrows
	public void addCannedPolicy(String policyName, String policy) {
		minioAdminClient.addCannedPolicy(policyName, policy);
	}

	@SneakyThrows
	public void removeCannedPolicy(String policyName) {
		minioAdminClient.removeCannedPolicy(policyName);
	}

	@SneakyThrows
	public void assignPolicyToUser(String userAccessKey, String policyName) {
		minioAdminClient.setPolicy(userAccessKey, false, policyName);
	}

	@SneakyThrows
	public void getUserInfo(String userAccessKey) {
		minioAdminClient.getUserInfo(userAccessKey);
	}

	@SneakyThrows
	public void addUser(String userAccessKey, String userSecretKey, String policyName) {
		minioAdminClient.addUser(userAccessKey, UserInfo.Status.ENABLED, userSecretKey, policyName, null);
	}

	@SneakyThrows
	public void removeUser(String userAccessKey) {
		minioAdminClient.deleteUser(userAccessKey);
	}

	@SneakyThrows
	public boolean bucketExists(String bucketName) {
		return minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
	}

}
