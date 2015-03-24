package com.evolveum.midpoint.integration.cas;

/* Copyright (c) 2010-2013 Evolveum

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

public class MidPointConfiguration {

	private String username = "administrator";
	private String password = "5ecr3t";
	private String identifier = "Actor.UPVSIdentityID";

	private String endpoint = "http://localhost:18080/midpoint/model/model-3";

	private String resourceOid;

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		ClientPasswordHandler.setPassword(password);
		this.password = password;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	public String getResourceOid() {
		return resourceOid;
	}

	public void setResourceOid(String resourceOid) {
		this.resourceOid = resourceOid;
	}

	public String getIdentifier() {
		return identifier;
	}

	public void setIdentifier(String identifier) {
		this.identifier = identifier;
	}

}
