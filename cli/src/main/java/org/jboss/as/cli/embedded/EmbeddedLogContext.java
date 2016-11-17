/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.as.cli.embedded;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;

import org.jboss.as.cli.CommandContext;
import org.jboss.logmanager.Configurator;
import org.jboss.logmanager.Level;
import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.Logger;
import org.jboss.logmanager.PropertyConfigurator;
import org.jboss.logmanager.config.LogContextConfiguration;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * Used to control the {@linkplain LogContext log context} for embedded servers.
 * <p>
 * A single global log context is used for all embedded servers launched from a session. This is needed as static
 * loggers will be created on a single log context. Reusing the same log context allows these loggers to still work and
 * the log context to be reconfigured based on the embedded server that's booting.
 * </p>
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class EmbeddedLogContext {

    private static class Holder {
        static final LogContext LOG_CONTEXT = LogContext.create();
    }


    /**
     * Configures the log context for the server and returns the configured log context.
     *
     * @param baseDir            the base directory where the configuration and log directories can be found, e.g. {@code $JBOSS_HOME/standalone}
     * @param defaultLogFileName the name of the log file to pass to {@code org.jboss.boot.log.file}
     * @param ctx                the command context used to report errors to
     *
     * @return the configured log context
     */
    static synchronized LogContext configureLogContext(final File baseDir, final String defaultLogFileName, final CommandContext ctx) {
        final LogContext embeddedLogContext = Holder.LOG_CONTEXT;
        final Path bootLog = baseDir.toPath().resolve(Paths.get("log", defaultLogFileName));
        final Path loggingProperties = baseDir.toPath().resolve(Paths.get("configuration", "logging.properties"));
        if (Files.exists(loggingProperties)) {
            WildFlySecurityManager.setPropertyPrivileged("org.jboss.boot.log.file", bootLog.toAbsolutePath().toString());

            try (final InputStream in = Files.newInputStream(loggingProperties)) {
                // Attempt to get the configurator from the root logger
                Configurator configurator = embeddedLogContext.getAttachment("", Configurator.ATTACHMENT_KEY);
                if (configurator == null) {
                    configurator = new PropertyConfigurator(embeddedLogContext);
                    final Configurator existing = embeddedLogContext.getLogger("").attachIfAbsent(Configurator.ATTACHMENT_KEY, configurator);
                    if (existing != null) {
                        configurator = existing;
                    }
                }
                configurator.configure(in);
            } catch (IOException e) {
                ctx.printLine(String.format("Unable to configure logging from configuration file %s. Reason: %s", loggingProperties, e.getLocalizedMessage()));
            }
        }
        return embeddedLogContext;
    }

    /**
     * Attempts to clear the global log context used for embedded servers.
     */
    static synchronized void clearLogContext() {
        final LogContext embeddedLogContext = Holder.LOG_CONTEXT;
        // Remove the configurator and clear the log context
        final Configurator configurator = embeddedLogContext.getLogger("").detach(Configurator.ATTACHMENT_KEY);
        // If this was a PropertyConfigurator we can use the LogContextConfiguration API to tear down the LogContext
        if (configurator instanceof PropertyConfigurator) {
            final LogContextConfiguration logContextConfiguration = ((PropertyConfigurator) configurator).getLogContextConfiguration();
            clearLogContext(logContextConfiguration);
        } else if (configurator instanceof LogContextConfiguration) {
            clearLogContext((LogContextConfiguration) configurator);
        } else {
            // Remove all the handlers and close them as well as reset the loggers
            final List<String> loggerNames = Collections.list(embeddedLogContext.getLoggerNames());
            for (String name : loggerNames) {
                final Logger logger = embeddedLogContext.getLoggerIfExists(name);
                if (logger != null) {
                    final Handler[] handlers = logger.clearHandlers();
                    if (handlers != null) {
                        for (Handler handler : handlers) {
                            handler.close();
                        }
                    }
                    logger.setFilter(null);
                    logger.setUseParentFilters(false);
                    logger.setUseParentHandlers(true);
                    logger.setLevel(Level.INFO);
                }
            }
        }
    }

    private static void clearLogContext(final LogContextConfiguration logContextConfiguration) {
        try {
            // Remove all the handlers
            logContextConfiguration.getHandlerNames().forEach(logContextConfiguration::removeHandlerConfiguration);
            // Remove all the formatters
            logContextConfiguration.getFormatterNames().forEach(logContextConfiguration::removeFormatterConfiguration);
            // Remove all the error managers
            logContextConfiguration.getErrorManagerNames().forEach(logContextConfiguration::removeErrorManagerConfiguration);
            // Remove all the POJO's
            logContextConfiguration.getPojoNames().forEach(logContextConfiguration::removePojoConfiguration);
            // Remove all the loggers
            logContextConfiguration.getLoggerNames().forEach(logContextConfiguration::removeLoggerConfiguration);
            // Remove all the filters
            logContextConfiguration.getFilterNames().forEach(logContextConfiguration::removeFilterConfiguration);
            logContextConfiguration.commit();
        } finally {
            logContextConfiguration.forget();
        }
    }
}