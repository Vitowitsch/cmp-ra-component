/*
 *  Copyright (c) 2022 Siemens AG
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  SPDX-License-Identifier: Apache-2.0
 */
package com.siemens.pki.cmpracomponent.msgprocessing;

import static com.siemens.pki.cmpracomponent.util.NullUtil.ifNotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.function.BiFunction;

import org.bouncycastle.asn1.cmp.PKIBody;
import org.bouncycastle.asn1.cmp.PKIFailureInfo;
import org.bouncycastle.asn1.cmp.PKIMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.siemens.pki.cmpracomponent.configuration.Configuration;
import com.siemens.pki.cmpracomponent.main.CmpRaComponent;
import com.siemens.pki.cmpracomponent.main.CmpRaComponent.CmpRaInterface;
import com.siemens.pki.cmpracomponent.msgvalidation.CmpProcessingException;
import com.siemens.pki.cmpracomponent.persistency.PersistencyContextManager;
import com.siemens.pki.cmpracomponent.util.CmpBiFuncEx;
import com.siemens.pki.cmpracomponent.util.FileTracer;
import com.siemens.pki.cmpracomponent.util.MessageDumper;

/**
 * implementation of a RA composed from a {@link CmpRaUpstream} and a
 * {@link RaDownstream}
 *
 */
public class CmpRaImplementation implements CmpRaInterface {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(CmpRaImplementation.class);

    private static final Collection<Integer> supportedMessageTypesOnDownstream =
            new HashSet<>(Arrays.asList(PKIBody.TYPE_INIT_REQ,
                    PKIBody.TYPE_CERT_REQ, PKIBody.TYPE_KEY_UPDATE_REQ,
                    PKIBody.TYPE_P10_CERT_REQ, PKIBody.TYPE_POLL_REQ,
                    PKIBody.TYPE_CERT_CONFIRM, PKIBody.TYPE_REVOCATION_REQ,
                    PKIBody.TYPE_GEN_MSG));

    private static final String INTERFACE_NAME = "upstream exchange";
    private final CmpRaUpstream upstream;

    private final RaDownstream downstream;

    /**
     * @param config
     *            specific configuration
     * @param rawUpstreamExchange
     *            upstream interface function
     * @throws Exception
     *             in case of error
     * @see CmpRaComponent
     */
    public CmpRaImplementation(final Configuration config,
            final BiFunction<byte[], String, byte[]> rawUpstreamExchange)
            throws Exception {
        final PersistencyContextManager persistencyContextManager =
                new PersistencyContextManager(config.getPersistency());
        final CmpBiFuncEx<PKIMessage, String, PKIMessage> upstreamExchange =
                (request, certProfile) -> {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("REQUEST at upstream for " + certProfile
                                + " >>>>>");
                        LOGGER.trace(MessageDumper.dumpPkiMessage(request));
                    }
                    FileTracer.logMessage(request, "upstream");
                    if (rawUpstreamExchange == null) {
                        throw new CmpProcessingException(INTERFACE_NAME,
                                PKIFailureInfo.systemUnavail,
                                "no upstream configured");
                    }
                    try {
                        final byte[] rawResponse = rawUpstreamExchange.apply(
                                ifNotNull(request, PKIMessage::getEncoded),
                                certProfile);
                        final PKIMessage response =
                                ifNotNull(rawResponse, PKIMessage::getInstance);
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace("RESPONSE at upstream for "
                                    + certProfile + " <<<<");
                            LOGGER.trace(
                                    MessageDumper.dumpPkiMessage(response));
                        }
                        FileTracer.logMessage(response, "upstream");
                        return response;
                    } catch (final Throwable th) {
                        throw new CmpProcessingException(INTERFACE_NAME,
                                PKIFailureInfo.systemFailure,
                                "exception at external upstream while processing request for "
                                        + certProfile,
                                th);
                    }
                };
        this.upstream = new CmpRaUpstream(persistencyContextManager, config,
                upstreamExchange);
        this.downstream = new RaDownstream(persistencyContextManager, config,
                upstream, supportedMessageTypesOnDownstream);

    }

    @Override
    public void gotResponseAtUpstream(final byte[] rawResponse)
            throws Exception {
        final PKIMessage response = PKIMessage.getInstance(rawResponse);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("ASYNC RESPONSE at upstream <<<<");
            LOGGER.trace(MessageDumper.dumpPkiMessage(response));
        }
        FileTracer.logMessage(response, "upstream");
        upstream.gotResponseAtUpstream(response);

    }

    @Override
    public byte[] processRequest(final byte[] rawRequest) throws Exception {
        final PKIMessage request = PKIMessage.getInstance(rawRequest);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("REQUEST at downstream >>>>>");
            LOGGER.trace(MessageDumper.dumpPkiMessage(request));
        }
        FileTracer.logMessage(request, "downstream");
        final PKIMessage response = downstream.handleInputMessage(request);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("RESPONSE at downstream <<<<");
            LOGGER.trace(MessageDumper.dumpPkiMessage(response));
        }
        FileTracer.logMessage(response, "downstream");
        return ifNotNull(response, PKIMessage::getEncoded);
    }

}