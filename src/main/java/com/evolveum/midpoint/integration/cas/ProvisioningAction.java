/* Copyright (c) 2013-2015 Evolveum

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License. */
package com.evolveum.midpoint.integration.cas;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.validation.constraints.NotNull;
import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;

import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.interceptor.LoggingOutInterceptor;
import org.apache.cxf.ws.security.wss4j.WSS4JOutInterceptor;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.handler.WSHandlerConstants;
import org.jasig.cas.authentication.Authentication;
import org.jasig.cas.authentication.principal.Principal;
import org.jasig.cas.ticket.TicketGrantingTicket;
import org.jasig.cas.ticket.registry.TicketRegistry;
import org.jasig.cas.web.support.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.webflow.action.AbstractAction;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.execution.RequestContext;

import com.evolveum.midpoint.model.client.ModelClientUtil;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectDeltaListType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceObjectShadowChangeDescriptionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowAttributesType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;
import com.evolveum.midpoint.xml.ns._public.common.fault_3.FaultMessage;
import com.evolveum.midpoint.xml.ns._public.model.model_3.ModelPortType;
import com.evolveum.midpoint.xml.ns._public.model.model_3.ModelService;
import com.evolveum.prism.xml.ns._public.types_3.ChangeTypeType;
import com.evolveum.prism.xml.ns._public.types_3.ObjectDeltaType;
import com.evolveum.prism.xml.ns._public.types_3.RawType;

public class ProvisioningAction extends AbstractAction {

	protected static final Logger logger = LoggerFactory.getLogger(ProvisioningAction.class);

	private static final String RESOURCE_NS = "http://midpoint.evolveum.com/xml/ns/public/resource/instance-3";
	private static final String COMMON_NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
	private static final QName RESOURCE_TYPE = new QName(COMMON_NS, "ResourceType");
	private static final QName SHADOW_TYPE = new QName(COMMON_NS, "ShadowType");
	private static final QName ACCOUNT_OBJECT_CLASS = new QName(RESOURCE_NS, "AccountObjectClass");
	private static final String ICFS_NS = "http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/resource-schema-3";
	private static final QName ICFS_NAME = new QName(ICFS_NS, "name");
	private static final QName ICFS_UID = new QName(ICFS_NS, "uid");

	/**
	 * TGT does not exist event ID={@value} .
	 **/
	public static final String NOT_EXISTS = "notExists";

	/**
	 * TGT invalid event ID={@value} .
	 **/
	public static final String SUCCESS = "success";

	/**
	 * TGT valid event ID={@value} .
	 **/
	public static final String ERROR = "error";

	@NotNull
	private final TicketRegistry ticketRegistry;

	@NotNull
	private MidPointConfiguration configuration;

	private List<String> excludeAttributes;

	public ProvisioningAction(TicketRegistry ticketRegistry, MidPointConfiguration configuration,
			List<String> excludeAttributes) {
		this.ticketRegistry = ticketRegistry;
		this.configuration = configuration;
		this.excludeAttributes = excludeAttributes;
	}

	@Override
	protected Event doExecute(RequestContext context) throws Exception {

		String tgtId = WebUtils.getTicketGrantingTicketId(context);

		final TicketGrantingTicket ticketGrantingTicket = this.ticketRegistry.getTicket(tgtId,
				TicketGrantingTicket.class);

		if (ticketGrantingTicket == null) {
			logger.debug("Ticket granting ticket has not been created yet. Could not provision account.");
			return new Event(this, NOT_EXISTS);
		}

		Authentication authentication = ticketGrantingTicket.getAuthentication();
		if (authentication == null) {
			logger.debug("There is no authentificated user. Could not provision account.");
			return new Event(this, NOT_EXISTS);
		}

		try {
			ModelPortType midPointClient = createModelPort(configuration);

			Principal principal = authentication.getPrincipal();

			// this is temporary hack (FIXME) - in the current version (3.1.1) midPoint does not support "virtual" resources
			createAccountOnSimulatedResource(principal, midPointClient);

			doProvisioning(principal, midPointClient);

		} catch (FaultMessage ex) {
			System.out.println("error occured: " + ex.getMessage());
			ex.printStackTrace(System.out);
			return new Event(this, ERROR);
		} catch (Exception ex) {
			System.out.println("error occured: " + ex.getMessage());
			ex.printStackTrace(System.out);
			return new Event(this, ERROR);
		}

		return new Event(this, SUCCESS);
	}

	private ObjectDeltaType createObjectAddDelta(Principal principal, boolean isSync) {
		ObjectDeltaType odt = new ObjectDeltaType();
		odt.setObjectToAdd(createShadow(principal, isSync));
		odt.setChangeType(ChangeTypeType.ADD);
		odt.setObjectType(SHADOW_TYPE);
		return odt;

	}

	private ShadowType createShadow(Principal principal, boolean isSync) {
		ShadowType shadow = new ShadowType();
		shadow.setAttributes(createShadowAttributes(principal, isSync));

		shadow.setResourceRef(createResourceRef());
		shadow.setObjectClass(ACCOUNT_OBJECT_CLASS);
		return shadow;
	}

	private ObjectReferenceType createResourceRef() {
		ObjectReferenceType ort = new ObjectReferenceType();
		ort.setOid(configuration.getResourceOid());
		ort.setType(RESOURCE_TYPE);
		return ort;
	}

	private void createAccountOnSimulatedResource(Principal principal, ModelPortType midPointClient)
			throws FaultMessage {
		ObjectDeltaListType deltas = new ObjectDeltaListType();
		deltas.getDelta().add(createObjectAddDelta(principal, false));
		midPointClient.executeChanges(deltas, null);
	}

	private void doProvisioning(Principal principal, ModelPortType midPointClient) throws FaultMessage {
		ResourceObjectShadowChangeDescriptionType changeDescription = new ResourceObjectShadowChangeDescriptionType();
		changeDescription.setObjectDelta(createObjectAddDelta(principal, true));
		System.out.println("created resource object shadow change description: " + changeDescription);
		midPointClient.notifyChange(changeDescription);
		System.out.println("notify successfull");
	}

	private ModelPortType createModelPort(MidPointConfiguration config) {
		String endpointUrl = config.getEndpoint();

		logger.trace("Endpoint URL: {}", endpointUrl);

		ModelService modelService = new ModelService();
		ModelPortType modelPort = modelService.getModelPort();
		BindingProvider bp = (BindingProvider) modelPort;
		Map<String, Object> requestContext = bp.getRequestContext();
		requestContext.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endpointUrl);

		org.apache.cxf.endpoint.Client client = ClientProxy.getClient(modelPort);
		org.apache.cxf.endpoint.Endpoint cxfEndpoint = client.getEndpoint();

		Map<String, Object> outProps = new HashMap<String, Object>();

		outProps.put(WSHandlerConstants.ACTION, WSHandlerConstants.USERNAME_TOKEN);
		outProps.put(WSHandlerConstants.USER, config.getUsername());
		outProps.put(WSHandlerConstants.PASSWORD_TYPE, WSConstants.PW_DIGEST);
		outProps.put(WSHandlerConstants.PW_CALLBACK_CLASS, ClientPasswordHandler.class.getName());

		WSS4JOutInterceptor wssOut = new WSS4JOutInterceptor(outProps);
		cxfEndpoint.getOutInterceptors().add(wssOut);
		// enable the following to get client-side logging of outgoing requests
		// and incoming responses
		cxfEndpoint.getOutInterceptors().add(new LoggingOutInterceptor());
		cxfEndpoint.getInInterceptors().add(new LoggingInInterceptor());

		logger.trace("Model port type created successfully");
		return modelPort;
	}

	private ShadowAttributesType createShadowAttributes(Principal principal, boolean isSync) {
		ShadowAttributesType attributes = new ShadowAttributesType();
		logger.trace("Start creating shadow attributes");
		for (String attrName : principal.getAttributes().keySet()) {
			if (excludeAttributes != null && excludeAttributes.contains(attrName)) {
				logger.trace("Skipping creation of attribute {} because it is set to be excluded.", attrName);
				continue;
			}
			Object value = principal.getAttributes().get(attrName);
			logger.trace("Creating attribute with name {}: ", attrName);
			if (List.class.isAssignableFrom(value.getClass())) {
				for (Object v : (List) value) {
					addAttributeValue(attributes, attrName, v, isSync);
				}
			} else {
				addAttributeValue(attributes, attrName, value, isSync);
			}

		}
		return attributes;
	}

	private void addAttributeValue(ShadowAttributesType attributes, String attrName, Object value,
			boolean isSync) {

		if (attrName.equals(configuration.getIdentifier())) {
			addIdentifierValue(attributes, value, isSync);
		} else {
			addStandardValue(attributes, attrName, value);
		}
	}

	private void addIdentifierValue(ShadowAttributesType attributes, Object value, boolean isSync) {

		if (isSync) {
			attributes.getAny().add(ModelClientUtil.toJaxbElement(ICFS_UID, value));
		}
		attributes.getAny().add(ModelClientUtil.toJaxbElement(ICFS_NAME, value));
	}

	private void addStandardValue(ShadowAttributesType attributes, String attrName, Object value) {
		RawType raw = new RawType();
		raw.getContent().add(value);
		attributes.getAny().add(ModelClientUtil.toJaxbElement(new QName(RESOURCE_NS, attrName), raw));
	}
}
