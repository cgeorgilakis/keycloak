/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.broker.saml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.xml.namespace.QName;

import org.keycloak.Config.Scope;
import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.common.util.Time;
import org.keycloak.dom.saml.v2.assertion.AttributeType;
import org.keycloak.dom.saml.v2.mdrpi.RegistrationInfoType;
import org.keycloak.dom.saml.v2.mdui.LogoType;
import org.keycloak.dom.saml.v2.mdui.UIInfoType;
import org.keycloak.dom.saml.v2.metadata.ContactType;
import org.keycloak.dom.saml.v2.metadata.EndpointType;
import org.keycloak.dom.saml.v2.metadata.EntitiesDescriptorType;
import org.keycloak.dom.saml.v2.metadata.EntityDescriptorType;
import org.keycloak.dom.saml.v2.metadata.IDPSSODescriptorType;
import org.keycloak.dom.saml.v2.metadata.KeyDescriptorType;
import org.keycloak.dom.saml.v2.metadata.KeyTypes;
import org.keycloak.dom.saml.v2.metadata.LocalizedNameType;
import org.keycloak.dom.saml.v2.metadata.LocalizedURIType;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.saml.common.constants.GeneralConstants;
import org.keycloak.saml.common.constants.JBossSAMLURIConstants;
import org.keycloak.saml.common.exceptions.ParsingException;
import org.keycloak.saml.common.util.DocumentUtil;
import org.keycloak.saml.processing.core.parsers.saml.SAMLParser;
import org.keycloak.saml.validators.DestinationValidator;
import org.keycloak.util.JsonSerialization;
import org.w3c.dom.Element;

/**
 * @author Pedro Igor
 */
public class SAMLIdentityProviderFactory extends AbstractIdentityProviderFactory<SAMLIdentityProvider> {

    public static final String PROVIDER_ID = "saml";

    private static final String REFEDS_HIDE_FROM_DISCOVERY = "http://refeds.org/category/hide-from-discovery";
    private static final String MACEDIR_ENTITY_CATEGORY = "http://macedir.org/entity-category";

    private DestinationValidator destinationValidator;

    @Override
    public String getName() {
        return "SAML v2.0";
    }

    @Override
    public SAMLIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        return new SAMLIdentityProvider(session, new SAMLIdentityProviderConfig(model), destinationValidator);
    }

    @Override
    public SAMLIdentityProviderConfig createConfig() {
        return new SAMLIdentityProviderConfig();
    }

    @Override
    public Map<String, String> parseConfig(KeycloakSession session, InputStream inputStream) {
        try {
            Object parsedObject = SAMLParser.getInstance().parse(inputStream);
            EntityDescriptorType entityType;

            if (EntitiesDescriptorType.class.isInstance(parsedObject)) {
                entityType = (EntityDescriptorType) ((EntitiesDescriptorType) parsedObject).getEntityDescriptor().get(0);
            } else {
                entityType = (EntityDescriptorType) parsedObject;
            }

            List<EntityDescriptorType.EDTChoiceType> choiceType = entityType.getChoiceType();

            if (!choiceType.isEmpty()) {
                IDPSSODescriptorType idpDescriptor = null;

                //Metadata documents can contain multiple Descriptors (See ADFS metadata documents) such as RoleDescriptor, SPSSODescriptor, IDPSSODescriptor.
                //So we need to loop through to find the IDPSSODescriptor.
                for(EntityDescriptorType.EDTChoiceType edtChoiceType : entityType.getChoiceType()) {
                    List<EntityDescriptorType.EDTDescriptorChoiceType> descriptors = edtChoiceType.getDescriptors();

                    if(!descriptors.isEmpty() && descriptors.get(0).getIdpDescriptor() != null) {
                        idpDescriptor = descriptors.get(0).getIdpDescriptor();
                    }
                }

                if (idpDescriptor != null) {
                    SAMLIdentityProviderConfig samlIdentityProviderConfig = new SAMLIdentityProviderConfig();
                     
                    // find default locale
                    String lang = (String) session.getAttribute("locale");
                   
                    String singleSignOnServiceUrl = null;
                    boolean postBindingResponse = false;
                    boolean postBindingLogout = false;
                    for (EndpointType endpoint : idpDescriptor.getSingleSignOnService()) {
                        if (endpoint.getBinding().toString().equals(JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.get())) {
                            singleSignOnServiceUrl = endpoint.getLocation().toString();
                            postBindingResponse = true;
                            break;
                        } else if (endpoint.getBinding().toString().equals(JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.get())){
                            singleSignOnServiceUrl = endpoint.getLocation().toString();
                        }
                    }
                    String singleLogoutServiceUrl = null;
                    for (EndpointType endpoint : idpDescriptor.getSingleLogoutService()) {
                        if (postBindingResponse && endpoint.getBinding().toString().equals(JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.get())) {
                            singleLogoutServiceUrl = endpoint.getLocation().toString();
                            postBindingLogout = true;
                            break;
                        } else if (!postBindingResponse && endpoint.getBinding().toString().equals(JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.get())){
                            singleLogoutServiceUrl = endpoint.getLocation().toString();
                            break;
                        }

                    }
                    samlIdentityProviderConfig.setSingleLogoutServiceUrl(singleLogoutServiceUrl);
                    samlIdentityProviderConfig.setSingleSignOnServiceUrl(singleSignOnServiceUrl);
                    samlIdentityProviderConfig.setWantAuthnRequestsSigned(idpDescriptor.isWantAuthnRequestsSigned());
                    samlIdentityProviderConfig.setAddExtensionsElementWithKeyInfo(false);
                    samlIdentityProviderConfig.setValidateSignature(idpDescriptor.isWantAuthnRequestsSigned());
                    samlIdentityProviderConfig.setPostBindingResponse(postBindingResponse);
                    samlIdentityProviderConfig.setPostBindingAuthnRequest(postBindingResponse);
                    samlIdentityProviderConfig.setPostBindingLogout(postBindingLogout);
                    samlIdentityProviderConfig.setLoginHint(false);

                    List<String> nameIdFormatList = idpDescriptor.getNameIDFormat();
                    if (nameIdFormatList != null && !nameIdFormatList.isEmpty())
                        samlIdentityProviderConfig.setNameIDPolicyFormat(nameIdFormatList.get(0));

                    List<KeyDescriptorType> keyDescriptor = idpDescriptor.getKeyDescriptor();
                    String defaultCertificate = null;

                    if (keyDescriptor != null) {
                        for (KeyDescriptorType keyDescriptorType : keyDescriptor) {
                            Element keyInfo = keyDescriptorType.getKeyInfo();
                            Element x509KeyInfo = DocumentUtil.getChildElement(keyInfo, new QName("dsig", "X509Certificate"));

                            if (KeyTypes.SIGNING.equals(keyDescriptorType.getUse())) {
                                samlIdentityProviderConfig.addSigningCertificate(x509KeyInfo.getTextContent());
                            } else if (KeyTypes.ENCRYPTION.equals(keyDescriptorType.getUse())) {
                                samlIdentityProviderConfig.setEncryptionPublicKey(x509KeyInfo.getTextContent());
                            } else if (keyDescriptorType.getUse() ==  null) {
                                defaultCertificate = x509KeyInfo.getTextContent();
                            }
                        }
                    }

                    if (defaultCertificate != null) {
                        if (samlIdentityProviderConfig.getSigningCertificates().length == 0) {
                            samlIdentityProviderConfig.addSigningCertificate(defaultCertificate);
                        }

                        if (samlIdentityProviderConfig.getEncryptionPublicKey() == null) {
                            samlIdentityProviderConfig.setEncryptionPublicKey(defaultCertificate);
                        }
                    }
                    if (idpDescriptor.getExtensions() != null && idpDescriptor.getExtensions().getUIInfo() != null) {
                        UIInfoType uiInfo = idpDescriptor.getExtensions().getUIInfo();
                        // import attributes only if values with these locale exist
                        Optional<LocalizedNameType> displayName = uiInfo.getDisplayName().stream()
                            .filter(dn -> lang.equals(dn.getLang())).findFirst();
                        if (displayName.isPresent())
                            samlIdentityProviderConfig.setConfigMduiDisplayName(displayName.get().getValue());
                        Optional<LocalizedNameType> description = uiInfo.getDescription().stream()
                            .filter(dn -> lang.equals(dn.getLang())).findFirst();
                        if (description.isPresent())
                            samlIdentityProviderConfig.setMduiDescription(description.get().getValue());
                        Optional<LocalizedURIType> informationURL = uiInfo.getInformationURL().stream()
                            .filter(dn -> lang.equals(dn.getLang())).findFirst();
                        if (informationURL.isPresent())
                            samlIdentityProviderConfig.setMduiInformationURL(informationURL.get().getValue().toString());
                        Optional<LocalizedURIType> privacyStatementURL = uiInfo.getPrivacyStatementURL().stream()
                            .filter(dn -> lang.equals(dn.getLang())).findFirst();
                        if (privacyStatementURL.isPresent())
                            samlIdentityProviderConfig.setMduiPrivacyStatementURL(privacyStatementURL.get().getValue().toString());
                        if (!uiInfo.getLogo().isEmpty()) {
                            LogoType logo = uiInfo.getLogo().get(0);
                            samlIdentityProviderConfig.setMduiLogo(logo.getValue().toString());
                            samlIdentityProviderConfig.setMduiLogoHeight(logo.getHeight());
                            samlIdentityProviderConfig.setMduiLogoWidth(logo.getWidth());
                        }

                    }

                    // organization
                    if (entityType.getOrganization() != null) {
                        Optional<LocalizedNameType> organizationName = entityType.getOrganization().getOrganizationName()
                            .stream().filter(dn -> lang.equals(dn.getLang())).findFirst();
                        if (organizationName.isPresent())
                            samlIdentityProviderConfig.setMdOrganizationName(organizationName.get().getValue());
                        Optional<LocalizedNameType> organizationDisplayName = entityType.getOrganization()
                            .getOrganizationDisplayName().stream().filter(dn -> lang.equals(dn.getLang())).findFirst();
                        if (organizationDisplayName.isPresent())
                            samlIdentityProviderConfig.setMdOrganizationDisplayName(organizationDisplayName.get().getValue());
                        Optional<LocalizedURIType> organizationURL = entityType.getOrganization().getOrganizationURL().stream()
                            .filter(dn -> lang.equals(dn.getLang())).findFirst();
                        if (organizationURL.isPresent())
                            samlIdentityProviderConfig.setMdOrganizationURL(organizationURL.get().getValue().toString());

                    }

                    // contact person
                    if (!entityType.getContactPerson().isEmpty()) {
                        ContactType contact = entityType.getContactPerson().get(0);
                        samlIdentityProviderConfig.setMdContactType(contact.getContactType().name());
                        samlIdentityProviderConfig.setMdContactCompany(contact.getCompany());
                        samlIdentityProviderConfig.setMdContactGivenName(contact.getGivenName());
                        samlIdentityProviderConfig.setMdContactSurname(contact.getSurName());
                        if (!contact.getEmailAddress().isEmpty())
                            samlIdentityProviderConfig.setMdContactEmailAddress(String.join(",", contact.getEmailAddress()));
                        if (!contact.getTelephoneNumber().isEmpty())
                            samlIdentityProviderConfig
                                .setMdContactTelephoneNumber(String.join(",", contact.getTelephoneNumber()));
                    }

                    samlIdentityProviderConfig.setEnabledFromMetadata(entityType.getValidUntil() == null
                        || entityType.getValidUntil().toGregorianCalendar().getTime().after(new Date(System.currentTimeMillis())));

                    // check in extensions
                    if (entityType.getExtensions() != null) {
                        if (entityType.getExtensions().getEntityAttributes() != null) {
                            Map<String, String> samlAttributes = new HashMap<>();
                            for (AttributeType attribute : entityType.getExtensions().getEntityAttributes().getAttribute()) {
                                if (MACEDIR_ENTITY_CATEGORY.equals(attribute.getName())
                                    && attribute.getAttributeValue().contains(REFEDS_HIDE_FROM_DISCOVERY)) {
                                    samlIdentityProviderConfig.setHideOnLogin(true);
                                } else {
                                    if (samlAttributes.containsKey(attribute.getName())) {
                                        attribute.getAttributeValue().stream()
                                            .forEach(obj -> samlAttributes.put(attribute.getName(),
                                                String.join(",",samlAttributes.get(attribute.getName()), obj.toString())));
                                    } else {
                                        samlAttributes.put(attribute.getName(), String.join(",", attribute.getAttributeValue()
                                            .stream().map(Object::toString).collect(Collectors.toList())));
                                    }
                                }
                            }
                            if (!samlAttributes.isEmpty()) 
                                samlIdentityProviderConfig.setSamlAttributes(JsonSerialization.writeValueAsPrettyString(samlAttributes));

                        }

                        if (entityType.getExtensions().getRegistrationInfo() != null) {
                            RegistrationInfoType registrationInfo = entityType.getExtensions().getRegistrationInfo();
                            samlIdentityProviderConfig
                                .setΜdrpiRegistrationAuthority(registrationInfo.getRegistrationAuthority().toString());
                            Optional<LocalizedURIType> registrationPolicy = registrationInfo.getRegistrationPolicy().stream()
                                .filter(dn -> lang.equals(dn.getLang())).findFirst();
                            if (registrationPolicy.isPresent())
                                samlIdentityProviderConfig
                                    .setΜdrpiRegistrationPolicy(registrationPolicy.get().getValue().toString());
                        }

                    }

                    String test = samlIdentityProviderConfig.getSamlAttributes();
                    return samlIdentityProviderConfig.getConfig();
                }
            }
        } catch (ParsingException | IOException pe) {
            throw new RuntimeException("Could not parse IdP SAML Metadata", pe);
        }

        return new HashMap<>();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void init(Scope config) {
        super.init(config);

        this.destinationValidator = DestinationValidator.forProtocolMap(config.getArray("knownProtocols"));
    }
}
