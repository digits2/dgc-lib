/*-
 * ---license-start
 * EU Digital Green Certificate Gateway Service / dgc-lib
 * ---
 * Copyright (C) 2021 T-Systems International GmbH and all other contributors
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---license-end
 */

package eu.europa.ec.dgc.gateway.connector;

import eu.europa.ec.dgc.gateway.connector.client.DgcGatewayConnectorRestClient;
import eu.europa.ec.dgc.gateway.connector.config.DgcGatewayConnectorConfigProperties;
import eu.europa.ec.dgc.gateway.connector.dto.CertificateTypeDto;
import eu.europa.ec.dgc.gateway.connector.dto.TrustListItemDto;
import eu.europa.ec.dgc.gateway.connector.mapper.TrustListMapper;
import eu.europa.ec.dgc.gateway.connector.model.TrustListItem;
import eu.europa.ec.dgc.signing.SignedCertificateMessageParser;
import eu.europa.ec.dgc.utils.CertificateUtils;
import feign.FeignException;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.cert.X509CertificateHolder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

@ConditionalOnProperty("dgc.gateway.connector.enabled")
@Lazy
@Service
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@RequiredArgsConstructor
@EnableScheduling
@Slf4j
public class DgcGatewayDownloadConnector {

    private final DgcGatewayConnectorUtils connectorUtils;

    private final CertificateUtils certificateUtils;

    private final DgcGatewayConnectorRestClient dgcGatewayConnectorRestClient;

    private final DgcGatewayConnectorConfigProperties properties;

    private final TrustListMapper trustListMapper;

    @Qualifier("trustAnchor")
    private final KeyStore trustAnchorKeyStore;

    private X509CertificateHolder trustAnchor;

    @Getter
    private LocalDateTime lastUpdated = null;

    private List<TrustListItem> trustedCertificates = new ArrayList<>();

    private List<X509CertificateHolder> trustedCscaCertificates = new ArrayList<>();
    private List<X509CertificateHolder> trustedUploadCertificates = new ArrayList<>();

    @PostConstruct
    void init() throws KeyStoreException, CertificateEncodingException, IOException {
        String trustAnchorAlias = properties.getTrustAnchor().getAlias();
        X509Certificate trustAnchorCert = (X509Certificate) trustAnchorKeyStore.getCertificate(trustAnchorAlias);

        if (trustAnchorCert == null) {
            log.error("Could not find TrustAnchor Certificate in Keystore");
            throw new KeyStoreException("Could not find TrustAnchor Certificate in Keystore");
        }
        trustAnchor = certificateUtils.convertCertificate(trustAnchorCert);
    }

    /**
     * Gets the list of downloaded and validated trusted signer certificates.
     * This call will return a cached list if caching is enabled.
     * If cache is outdated a refreshed list will be returned.
     *
     * @return List of {@link TrustListItem}
     */
    public List<TrustListItem> getTrustedCertificates() {
        updateIfRequired();
        return Collections.unmodifiableList(trustedCertificates);
    }

    private synchronized void updateIfRequired() {
        if (lastUpdated == null
            || ChronoUnit.SECONDS.between(lastUpdated, LocalDateTime.now()) >= properties.getMaxCacheAge()) {
            log.info("Maximum age of cache reached. Fetching new TrustList from DGCG.");

            trustedCscaCertificates = fetchCertificatesAndVerifyByTrustAnchor(CertificateTypeDto.CSCA);
            log.info("CSCA TrustStore contains {} trusted certificates.", trustedCscaCertificates.size());

            trustedUploadCertificates = fetchCertificatesAndVerifyByTrustAnchor(CertificateTypeDto.UPLOAD);
            log.info("Upload TrustStore contains {} trusted certificates.", trustedUploadCertificates.size());

            fetchTrustListAndVerifyByCscaAndUpload();
            log.info("DSC TrustStore contains {} trusted certificates.", trustedCertificates.size());
        } else {
            log.debug("Cache needs no refresh.");
        }
    }

    private List<X509CertificateHolder> fetchCertificatesAndVerifyByTrustAnchor(CertificateTypeDto type) {
        ResponseEntity<List<TrustListItemDto>> downloadedCertificates;
        try {
            downloadedCertificates = dgcGatewayConnectorRestClient.getTrustedCertificates(type);
        } catch (FeignException e) {
            log.error("Failed to Download certificates from DGC Gateway. Type: {}, status code: {}", type, e.status());
            return Collections.emptyList();
        }

        if (downloadedCertificates.getStatusCode() != HttpStatus.OK || downloadedCertificates.getBody() == null) {
            log.error("Failed to Download certificates from DGC Gateway, Type: {}, Status Code: {}",
                type, downloadedCertificates.getStatusCodeValue());
            return Collections.emptyList();
        }

        return downloadedCertificates.getBody().stream()
            .filter(this::checkThumbprintIntegrity)
            .filter(c -> connectorUtils.checkTrustAnchorSignature(c, trustAnchor))
            .map(connectorUtils::getCertificateFromTrustListItem)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    private void fetchTrustListAndVerifyByCscaAndUpload() {
        log.info("Fetching TrustList from DGCG");

        ResponseEntity<List<TrustListItemDto>> responseEntity;
        try {
            responseEntity = dgcGatewayConnectorRestClient.getTrustedCertificates(CertificateTypeDto.DSC);
        } catch (FeignException e) {
            log.error("Download of TrustListItems failed. DGCG responded with status code: {}",
                e.status());
            return;
        }

        List<TrustListItemDto> downloadedDcs = responseEntity.getBody();

        if (responseEntity.getStatusCode() != HttpStatus.OK || downloadedDcs == null) {
            log.error("Download of TrustListItems failed. DGCG responded with status code: {}",
                responseEntity.getStatusCode());
            return;
        } else {
            log.info("Got Response from DGCG, Downloaded Certificates: {}", downloadedDcs.size());
        }

        trustedCertificates = downloadedDcs.stream()
            .filter(this::checkCscaCertificate)
            .filter(this::checkUploadCertificate)
            .map(trustListMapper::map)
            .collect(Collectors.toList());

        lastUpdated = LocalDateTime.now();
        log.info("Put {} trusted certificates into TrustList", trustedCertificates.size());
    }

    private boolean checkThumbprintIntegrity(TrustListItemDto trustListItem) {
        byte[] certificateRawData = Base64.getDecoder().decode(trustListItem.getRawData());
        try {
            return trustListItem.getThumbprint().equals(
                certificateUtils.getCertThumbprint(new X509CertificateHolder(certificateRawData)));

        } catch (IOException e) {
            log.error("Could not parse certificate raw data");
            return false;
        }
    }

    private boolean checkCscaCertificate(TrustListItemDto trustListItem) {
        return trustedCscaCertificates
            .stream()
            .anyMatch(ca -> connectorUtils.trustListItemSignedByCa(trustListItem, ca));
    }

    private boolean checkUploadCertificate(TrustListItemDto trustListItem) {
        SignedCertificateMessageParser parser =
            new SignedCertificateMessageParser(trustListItem.getSignature(), trustListItem.getRawData());
        X509CertificateHolder uploadCertificate = parser.getSigningCertificate();

        return trustedUploadCertificates
            .stream()
            .anyMatch(uploadCertificate::equals);
    }
}
