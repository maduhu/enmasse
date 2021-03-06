/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.controller.auth;

import java.util.*;
import java.util.stream.Collectors;

import io.enmasse.address.model.*;
import io.enmasse.config.AnnotationKeys;
import io.enmasse.controller.CertProviderFactory;
import io.enmasse.controller.Controller;
import io.enmasse.controller.common.ControllerKind;
import io.enmasse.k8s.api.*;
import io.fabric8.kubernetes.api.model.Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.enmasse.controller.common.ControllerReason.CertCreateFailed;
import static io.enmasse.controller.common.ControllerReason.CertCreated;
import static io.enmasse.k8s.api.EventLogger.Type.Normal;
import static io.enmasse.k8s.api.EventLogger.Type.Warning;

/**
 * Manages certificates issuing, revoking etc. for EnMasse services
 */
public class AuthController implements Controller {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class.getName());

    private final CertManager certManager;
    private final EventLogger eventLogger;
    private final CertProviderFactory certProviderFactory;

    public AuthController(CertManager certManager,
                          EventLogger eventLogger,
                          CertProviderFactory certProviderFactory) {
        this.certManager = certManager;
        this.eventLogger = eventLogger;
        this.certProviderFactory = certProviderFactory;
    }

    public void issueExternalCertificates(AddressSpace addressSpace) {
        List<EndpointSpec> endpoints = addressSpace.getEndpoints();
        if (endpoints != null) {
            Map<String, EndpointSpec> endpointSpecMap = new HashMap<>();
            Map<String, EndpointInfo> endpointInfoMap = new HashMap<>();

            for (EndpointSpec endpoint : endpoints) {
                endpointSpecMap.put(endpoint.getName(), endpoint);
                if (endpoint.getCertSpec().isPresent()) {
                    EndpointInfo info = endpointInfoMap.get(endpoint.getService());
                    if (info == null) {
                        info = new EndpointInfo(endpoint.getService(), endpoint.getCertSpec().get());
                        endpointInfoMap.put(endpoint.getService(), info);
                    }
                }
            }

            for (EndpointStatus status : addressSpace.getStatus().getEndpointStatuses()) {
                EndpointSpec spec = endpointSpecMap.get(status.getName());
                EndpointInfo info = endpointInfoMap.get(spec.getService());
                if (info != null) {
                    info.addHost(status.getServiceHost());
                    if (status.getHost() != null && !status.getHost().isEmpty()) {
                        info.addHost(status.getHost());
                    }
                }
            }

            for (EndpointInfo info : endpointInfoMap.values()) {
                try {
                    CertProvider certProvider = certProviderFactory.createProvider(info.getCertSpec());
                    List<String> hosts = info.getHosts();
                    String cn = null;
                    if (!hosts.isEmpty()) {
                        cn = hosts.iterator().next();
                    }
                    Secret secret = certProvider.provideCert(addressSpace, cn, hosts);
                    certManager.grantServiceAccountAccess(secret, "default", addressSpace.getAnnotation(AnnotationKeys.NAMESPACE));
                } catch (Exception e) {
                    log.warn("Error providing certificate for service {} hosts {}: {}", info.getServiceName(), info.getHosts(), e.getMessage(), e);
                }
            }
        }
    }


    public Secret issueAddressSpaceCert(final AddressSpace addressSpace) {
        try {
            final String addressSpaceCaSecretName = KubeUtil.getAddressSpaceCaSecretName(addressSpace);
            Secret secret = certManager.getCertSecret(addressSpace.getAnnotation(AnnotationKeys.NAMESPACE), addressSpaceCaSecretName);
            if (secret == null) {
                secret = certManager.createSelfSignedCertSecret(addressSpace.getAnnotation(AnnotationKeys.NAMESPACE), addressSpaceCaSecretName);
                //put crt into address space
                eventLogger.log(CertCreated, "Created address space CA", Normal, ControllerKind.AddressSpace, addressSpace.getName());
            }
            return secret;
        } catch (Exception e) {
            log.warn("Error issuing addressspace ca certificate", e);
            eventLogger.log(CertCreateFailed, "Error creating certificate: " + e.getMessage(), Warning, ControllerKind.AddressSpace, addressSpace.getName());
            return null;
        }
    }

    public void issueComponentCertificates(AddressSpace addressSpace, Secret addressSpaceCaSecret) {
        try {
            List<Cert> certs = certManager.listComponents(addressSpace.getAnnotation(AnnotationKeys.NAMESPACE)).stream()
                    .filter(component -> !certManager.certExists(component))
                    .map(certManager::createCsr)
                    .map(request -> certManager.signCsr(request, addressSpaceCaSecret, Collections.emptySet()))
                    .map(cert -> {
                        certManager.createSecret(cert, addressSpaceCaSecret);
                        return cert; })
                    .collect(Collectors.toList());

            if (!certs.isEmpty()) {
                eventLogger.log(CertCreated, "Created component certificates", Normal, ControllerKind.AddressSpace, addressSpace.getName());
            }
        } catch (Exception e) {
            log.warn("Error issuing component certificates", e);
            eventLogger.log(CertCreateFailed, "Error creating component certificates: " + e.getMessage(), Warning, ControllerKind.AddressSpace, addressSpace.getName());
        }
    }

    public String getDefaultCertProvider() {
        return certProviderFactory.getDefaultProviderName();
    }

    @Override
    public AddressSpace handle(AddressSpace addressSpace) throws Exception {
        Secret addressSpaceCa = issueAddressSpaceCert(addressSpace);
        if (addressSpaceCa != null) {
            issueComponentCertificates(addressSpace, addressSpaceCa);
        }
        issueExternalCertificates(addressSpace);
        return addressSpace;
    }

    @Override
    public String toString() {
        return "AuthController";
    }
}
