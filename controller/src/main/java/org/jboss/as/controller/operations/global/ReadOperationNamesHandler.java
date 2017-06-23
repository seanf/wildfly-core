/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller.operations.global;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FILTERED_OPERATIONS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATION_HEADERS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_OPERATION_NAMES_OPERATION;

import java.util.Map;
import java.util.Set;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationDefinition;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.access.AuthorizationResult;
import org.jboss.as.controller.access.AuthorizationResult.Decision;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.descriptions.common.ControllerResolver;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * {@link org.jboss.as.controller.OperationStepHandler} returning the names of the defined operations at a given model address.
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @author Brian Stansberry (c) 2012 Red Hat Inc.
 */
public class ReadOperationNamesHandler implements OperationStepHandler {

    private static final AttributeDefinition ACCESS_CONTROL = new SimpleAttributeDefinitionBuilder(ModelDescriptionConstants.ACCESS_CONTROL, ModelType.BOOLEAN, true)
        .setDefaultValue(new ModelNode(false))
        .build();

    static final OperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(READ_OPERATION_NAMES_OPERATION, ControllerResolver.getResolver("global"))
            .setReadOnly()
            .setParameters(ACCESS_CONTROL)
            .setReplyType(ModelType.LIST)
            .setReplyValueType(ModelType.STRING)
            .build();


    static OperationStepHandler INSTANCE = new ReadOperationNamesHandler();

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {

        final ImmutableManagementResourceRegistration registry = context.getResourceRegistration();
        if (registry == null) {
            throw new OperationFailedException(ControllerLogger.ROOT_LOGGER.noSuchResourceType(context.getCurrentAddress()));
        }
        final Map<String, OperationEntry> operations = registry.getOperationDescriptions(PathAddress.EMPTY_ADDRESS, true);

        final boolean accessControl = ACCESS_CONTROL.resolveModelAttribute(context, operation).asBoolean();

        final ModelNode result = new ModelNode();
        if (operations.size() > 0) {
            final PathAddress address = context.getCurrentAddress();
            for (final Map.Entry<String, OperationEntry> entry : operations.entrySet()) {
                if (isVisible(entry.getValue(), context)) {
                    boolean add = true;
                    if (accessControl) {
                        ModelNode operationToCheck = Util.createOperation(entry.getKey(), address);
                        operationToCheck.get(OPERATION_HEADERS).set(operation.get(OPERATION_HEADERS));
                        AuthorizationResult authorizationResult = context.authorizeOperation(operationToCheck);
                        add = authorizationResult.getDecision() == Decision.PERMIT;
                    }

                    if (add) {
                        result.add(entry.getKey());
                    } else {
                        context.getResponseHeaders().get(ModelDescriptionConstants.ACCESS_CONTROL, FILTERED_OPERATIONS).add(entry.getKey());
                    }
                }
            }
        } else {
            result.setEmptyList();
        }
        context.getResult().set(result);
    }

    static boolean isVisible(OperationEntry operationEntry, OperationContext context) {
        Set<OperationEntry.Flag> flags = operationEntry.getFlags();
        return operationEntry.getType() == OperationEntry.EntryType.PUBLIC
                && !flags.contains(OperationEntry.Flag.HIDDEN)
                && (context.getProcessType() != ProcessType.DOMAIN_SERVER || flags.contains(OperationEntry.Flag.RUNTIME_ONLY)
                || flags.contains(OperationEntry.Flag.READ_ONLY));

    }
}
