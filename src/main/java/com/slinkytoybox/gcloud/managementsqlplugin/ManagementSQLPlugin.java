/*
 *   management-sql-plugin - ManagementSQLPlugin.java
 *
 *   Copyright (c) 2022-2023, Slinky Software
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as
 *   published by the Free Software Foundation, either version 3 of the
 *   License, or (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   A copy of the GNU Affero General Public License is located in the 
 *   AGPL-3.0.md supplied with the source code.
 *
 */
package com.slinkytoybox.gcloud.managementsqlplugin;

import com.slinkytoybox.gcloud.platformconnectorplugin.*;
import com.slinkytoybox.gcloud.platformconnectorplugin.exceptions.PluginException;
import com.slinkytoybox.gcloud.platformconnectorplugin.health.*;
import com.slinkytoybox.gcloud.platformconnectorplugin.request.*;
import com.slinkytoybox.gcloud.platformconnectorplugin.response.*;
import com.slinkytoybox.gcloud.platformconnectorplugin.response.PluginResponse.ResponseStatus;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.StreamSupport;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.stereotype.Component;

/**
 *
 *
 * @author Michael Junek (michael@juneks.com.au)
 *
 */
@Slf4j
@Component
public class ManagementSQLPlugin implements PlatformConnectorPlugin {

    private String buildArtifact;
    private String buildVersion;

    private final Properties config;
    private final String pluginId;
    private final String pluginDescription;
    private boolean doneConfig = false;

    private HikariDataSource poolSource;

    private final List<PluginOperation> supportedOperations = new ArrayList<>();
    private final List<HealthMetric> metrics = new ArrayList<>();
    private final Map<String, HealthStatus> componentStatusMap = new HashMap<>();
    private final HealthStatus overallStatus = new HealthStatus();
    private ContainerInterface container = null;
    private final Map<String, DatabaseQuery> queryMap = new HashMap<>();

    // Custom Setup Routine
    private void pluginSetup() throws PluginException {
        final String logPrefix = "pluginSetup() - ";
        log.trace("{}Entering Method", logPrefix);

        supportedOperations.add(PluginOperation.READ);
        setOverallHealth(HealthState.WARNING, "Platform initialising");

        log.info("{}Setting up plugin {}", logPrefix, pluginId);
        connectToMssql();
        configureQueries();
    }

    private void connectToMssql() throws PluginException {
        final String logPrefix = "connectToMssql() - ";
        log.trace("{}Entering Method", logPrefix);
        log.info("{}Connecting to MSSQL Database", logPrefix);

        String jdbcUrl = config.getProperty("cloud.database.url", "NOT_SET");
        String jdbcUser = config.getProperty("cloud.database.username", "NOT_SET");
        String jdbcPassword = config.getProperty("cloud.database.password", "NOT_SET");
        Integer poolMinSize = Integer.valueOf(config.getProperty("cloud.database.pool.min-size", "3"));
        String poolTestQuery = config.getProperty("cloud.database.pool.test-query", "SELECT 1");
        Long poolIdleTimeout = Long.valueOf(config.getProperty("cloud.database.pool.idle-timeout", "300000"));
        Long poolKeepaliveTime = Long.valueOf(config.getProperty("cloud.database.pool.keepalive-time", "60000"));

        if (jdbcUrl.equalsIgnoreCase("NOT_SET") || jdbcUser.equalsIgnoreCase("NOT_SET") || jdbcPassword.equalsIgnoreCase("NOT_SET")) {
            log.error("{}JDBC Connection paramaters 'cloud.database.url|username|password' are not defined correctly", logPrefix);
            setOverallHealth(HealthState.FAILED, "Database connection issue");
            setComponentHealth("mssqlDatabase", new HealthStatus().setHealthState(HealthState.FAILED).setHealthComment("Required MSSQL configuration parameters were not found"));
            throw new PluginException("Required MSSQL configuration parameters were not found");
        }

        log.info("{}Starting database connection pool", logPrefix);

        log.debug("{}Connection Parameters:\nURL:  {}\nUser:  {}", logPrefix, jdbcUrl, jdbcUser);

        String decryptedPassword;
        decryptedPassword = container.decrypt(jdbcPassword);
        if (decryptedPassword == null || decryptedPassword.isBlank()) {
            throw new IllegalArgumentException("Encrypted password could not be decrypted");
        }

        log.debug("{}Getting data source properties", logPrefix);
        Properties dsProps = new Properties();
        StreamSupport.stream(config.entrySet().spliterator(), false)
                .filter(ps -> ps instanceof EnumerablePropertySource)
                .map(ps -> ((EnumerablePropertySource) ps).getPropertyNames())
                .flatMap(Arrays::stream)
                .distinct()
                .filter(prop -> (prop.startsWith("cloud.database.properties.")))
                .forEach(prop -> dsProps.setProperty(prop.replace("cloud.database.properties.", ""), config.getProperty(prop)));
        dsProps.setProperty("applicationName", pluginId);

        log.debug("{}Creating Connection Pool", logPrefix);
        poolSource.setJdbcUrl(jdbcUrl);
        poolSource.setUsername(jdbcUser);
        poolSource.setPassword(decryptedPassword);
        poolSource.setDataSourceProperties(dsProps);
        poolSource.setMinimumIdle(poolMinSize);

        poolSource.setConnectionTestQuery(poolTestQuery);
        poolSource.setPoolName("SQL-Plugin-DB");
        poolSource.setIdleTimeout(poolIdleTimeout);
        poolSource.setKeepaliveTime(poolKeepaliveTime);

        log.trace("{}Set pool parameters: {}", logPrefix, poolSource);

        log.info("{}Starting database pool", logPrefix);

        try (Connection conn = poolSource.getConnection()) {
            log.debug("{}Got SQL connection from pool. Testing", logPrefix);
            if (conn.isValid(5)) {
                log.info("{}Successfully connected to database", logPrefix);
            }
            else {
                log.error("{}Database did not respond within 5 seconds", logPrefix);
                throw new IllegalStateException("Could not start database - database did not respond within 5 seconds");
            }
        }
        catch (SQLException ex) {
            log.error("{}SQL Exception encountered getting connection from pool!", logPrefix);
            throw new IllegalStateException("Could not start database!", ex);
        }

        log.info("{}Plugin initialisation complete", logPrefix);
        setOverallHealth(HealthState.HEALTHY, null);
        setComponentHealth("mssqlDatabase", new HealthStatus().setHealthState(HealthState.HEALTHY));

    }

    private void configureQueries() {
        final String logPrefix = "configureQueries - ";
        log.trace("{}Entering Method", logPrefix);
        log.info("{}Configuring queries defined in file", logPrefix);
        for (String key : config.stringPropertyNames()) {
            if (key.matches("^query\\.[^.]+\\.sql$")) {
                log.trace("{}Started processing configuration {}", logPrefix, key);
                String[] parts = key.split("\\.");
                String queryName = parts[1];
                log.info("{}Found query {} - starting to process", logPrefix, queryName);
                DatabaseQuery dq = new DatabaseQuery(config, queryName, poolSource);
                log.debug("{}Adding query to available list", logPrefix);
                queryMap.put(queryName, dq);
            }
        }
        log.trace("{}Leaving Method", logPrefix);
    }

    // Custom destruction routine
    private void pluginDestroy() {
        final String logPrefix = "pluginDestroy() - ";
        log.trace("{}Entering Method", logPrefix);
        try {
            log.info("{}Stopping connection pool", logPrefix);
            poolSource.close();
        }
        catch (Exception ex) {
            log.error("{}Exception when stopping or destroying connection pool", ex);
        }

    }

    /**
     * Method for Creating a new object
     *
     * @param req
     * @return
     */
    private CreateResponse doWork(CreateRequest req) {
        final String logPrefix = "doWork(CreateRequest) - ";
        log.trace("{}Entering Method", logPrefix);

        log.error("{}Create is not implemented for this plugin ({})", logPrefix, req);
        return null;

    }

    /**
     * Method for Updating an existing object
     *
     * @param req
     * @return
     */
    private UpdateResponse doWork(UpdateRequest req) {
        final String logPrefix = "doWork(UpdateRequest) - ";
        log.trace("{}Entering Method", logPrefix);
        log.error("{}Update is not implemented for this plugin ({})", logPrefix, req);
        return null;

    }

    /**
     * Method for searching for and reading an object
     *
     * @param req
     * @return
     */
    private ReadResponse doWork(ReadRequest req) {
        final String logPrefix = "doWork(ReadRequest) - ";
        log.trace("{}Entering Method", logPrefix);

        ReadResponse response = new ReadResponse(); // Create new response object
        response.setRequestId(req.getRequestId());

        if (req.getRequestParameters() == null || !req.getRequestParameters().containsKey("queryId")) {
            log.error("{}Query identifier was not specified", logPrefix);
            response.setErrorMessage("Query identifier was not specified");
            response.setStatus(ResponseStatus.FAILURE);
        }
        else if (!queryMap.containsKey(req.getRequestParameters().get("queryId")[0])) {
            log.error("{}Query identifier is not recognised", logPrefix);
            response.setErrorMessage("Query identifier is not recognised");
            response.setStatus(ResponseStatus.FAILURE);

        }

        else if (req.getObjectId() == null || req.getObjectId().isEmpty()) {
            log.error("{}Ad-Hoc searches are not available in this plugin", logPrefix);
            response.setErrorMessage("Ad-Hoc searches are not available in this plugin");
            response.setStatus(ResponseStatus.FAILURE);
        }
        else {
            String queryId = req.getRequestParameters().get("queryId")[0];
            String objectId = req.getObjectId();
            DatabaseQuery dq = queryMap.get(queryId);
            log.info("{}Issuing read request for record {} to query {}", logPrefix, objectId, queryId);
            response = dq.performQuery(response, objectId);
        }

        log.debug("{}Returning response: {}", logPrefix, response);
        return response;

    }

    /**
     * Method for Deleting an object
     *
     * @param req
     * @return
     */
    private DeleteResponse doWork(DeleteRequest req) {
        final String logPrefix = "doWork(DeleteRequest) - [" + req.getRequestId() + "]";
        log.trace("{}Entering Method", logPrefix);
        log.error("{}Delete is not implemented for this plugin ({})", logPrefix, req);
        return null;

    }

    private void setMetric(String metricName, Serializable metricValue) {
        final String logPrefix = "setMetric() - ";
        log.trace("{}Entering Method", logPrefix);

        log.info("{}Setting Health Metric {} to value {}", logPrefix, metricName, metricValue);
        for (HealthMetric metric : metrics) {
            if (metric.getMetricName().equalsIgnoreCase(metricName)) {
                log.trace("{}Updated existing metric", logPrefix);
                metric.setMetricValue(metricValue);
                return;
            }
        }

        log.trace("{}Created new metric", logPrefix);
        HealthMetric hm = new HealthMetric().setMetricName(metricName)
                .setMetricValue(metricValue);
        metrics.add(hm);
        setHealth();
        log.trace("{}Leaving Method", logPrefix);

    }

    private void setComponentHealth(String componentName, HealthStatus componentStaus) {
        final String logPrefix = "setComponentHealth() - ";
        log.trace("{}Entering Method", logPrefix);

        log.info("{}Setting component {} to status {}", logPrefix, componentName, componentStaus);
        if (componentStatusMap.containsKey(componentName)) {
            log.trace("{}Updated existing component", logPrefix);
            componentStatusMap.replace(componentName, componentStaus);

        }
        else {
            log.trace("{}Creating new component", logPrefix);
            componentStatusMap.put(componentName, componentStaus);
        }
        setHealth();
        log.trace("{}Leaving Method", logPrefix);

    }

    private void setOverallHealth(HealthState state, String statusMessage) {
        final String logPrefix = "setComponentHealth() - ";
        log.trace("{}Entering Method", logPrefix);
        log.info("{}Setting overall health to status {} with message '{}'", logPrefix, state, statusMessage);
        overallStatus.setHealthState(state);
        overallStatus.setHealthComment(statusMessage);
        setHealth();
        log.trace("{}Leaving Method", logPrefix);

    }

    // Called by the container to get the health statuses
    // This should return the COMPLETE health picture.
    @Override
    public HealthResult getPluginHealth() {
        final String logPrefix = "getPluginHealth() - ";
        log.trace("{}Entering Method", logPrefix);
        log.info("{}Getting plugin health", logPrefix);

        // TODO: Modify code here to do the actual health checks and create metrics etc.
        HealthResult response = new HealthResult()
                .setOverallStatus(overallStatus) // this is the most important thing to return
                .setComponentStatus(componentStatusMap)
                .setMetrics(metrics);                 // metrics are optional

        // END actual work code
        log.debug("{}Returning response: {}", logPrefix, response);
        return response;

    }

    /// Example fucntion to show how to "push" health changes to the container
    // Note that this is the COMPLETE health picture including metrics, and not just the deltas
    private void setHealth() {
        final String logPrefix = "setHealth() - ";
        log.trace("{}Entering Method", logPrefix);

        if (container == null) {
            log.warn("{}Container interface is not yet set. Not doing callback", logPrefix);
            return;
        }
        // lets create some health details
        HealthResult response = new HealthResult()
                .setOverallStatus(overallStatus) // this is the most important thing to return
                .setComponentStatus(componentStatusMap)
                .setMetrics(metrics);

        log.debug("{}About to send the plugin health to container application", logPrefix);
        container.setPluginHealth(pluginId, response);
        log.trace("{}Leaving method", logPrefix);
    }

    /* 
////////
//////// NO NEED TO MODIFY ANYTHING DOWN HERE
////////
     */
    // Default CTOR called by instantiator
    public ManagementSQLPlugin(String pluginId, String pluginDescription, Properties config) {
        this.config = config;
        this.pluginDescription = pluginDescription;
        this.pluginId = pluginId;
    }

    // Initial post-construction routine - don't need to modify this, it calls the custom one
    //@PostConstruct
    private void setup() throws PluginException {
        final String logPrefix = "setup() - ";
        log.trace("{}Entering Method", logPrefix);
        log.info("----------------------------------------------------------------------------");
        log.info("{}Startup tasks for plugin running", logPrefix);
        log.info("Plugin Id: {}", pluginId);
        log.info("Decription: {}", pluginDescription);
        log.info("----------------------------------------------------------------------------");
        log.info("{}Configuration", logPrefix);
        log.info("{}", config);
        log.info("----------------------------------------------------------------------------");
        buildVersion = config.getProperty("info.build.version", "unknown");
        buildArtifact = config.getProperty("info.build.artifact", "unknown");
        pluginSetup();

        log.trace("{}Leaving Method", logPrefix);
    }

    // Initial pre-destruction routine - don't need to modify this, it calls the custom one
    @PreDestroy
    private void destroy() {
        final String logPrefix = "destroy() - ";
        log.trace("{}Entering Method", logPrefix);
        log.info("{}Shutdown tasks for plugin running", logPrefix);
        HealthResult response = new HealthResult().setOverallStatus(new HealthStatus().setHealthState(HealthState.FAILED).setHealthComment("Plugin shutting down"));
        log.info("{}About to send the plugin health to container application", logPrefix);
        container.setPluginHealth(pluginId, response);
        pluginDestroy();
        log.trace("{}Leaving Method", logPrefix);

    }

    // Default routine to break the incoming request into four different work types depending on class instance
    @Override
    public PluginResponse getResponseFromRequest(PluginRequest request) {
        final String logPrefix = "getResponseFromRequest() - ";
        log.trace("{}Entering Method", logPrefix);
        log.debug("{}Determining request type from class type: {}", logPrefix, request.getClass().getName());

        if (request instanceof CreateRequest createRequest) {
            log.debug("{} - Create Request", logPrefix);
            if (supportedOperations.contains(PluginOperation.CREATE)) {
                return doWork(createRequest);
            }
            else {
                log.error("{}Create requests are not supported by this plugin", logPrefix);
                throw new UnsupportedOperationException("Create requests are not supported by this plugin");
            }
        }
        if (request instanceof UpdateRequest updateRequest) {
            log.debug("{} - Update Request", logPrefix);
            if (supportedOperations.contains(PluginOperation.UPDATE)) {
                return doWork(updateRequest);
            }
            else {
                log.error("{}Update requests are not supported by this plugin", logPrefix);
                throw new UnsupportedOperationException("Update requests are not supported by this plugin");
            }
        }
        if (request instanceof ReadRequest readRequest) {
            log.debug("{} - Read Request", logPrefix);
            if (supportedOperations.contains(PluginOperation.READ)) {
                return doWork(readRequest);
            }
            else {
                log.error("{}Read requests are not supported by this plugin", logPrefix);
                throw new UnsupportedOperationException("Read requests are not supported by this plugin");
            }
        }
        if (request instanceof DeleteRequest deleteRequest) {
            log.debug("{} - Delete Request", logPrefix);
            if (supportedOperations.contains(PluginOperation.DELETE)) {
                return doWork(deleteRequest);
            }
            else {
                log.error("{}Delete requests are not supported by this plugin", logPrefix);
                throw new UnsupportedOperationException("Delete requests are not supported by this plugin");
            }
        }

        log.error("{}Request class type not implemented", logPrefix);
        throw new UnsupportedOperationException("Request class type not implemented");
    }

    @Override
    public List<PluginOperation> getValidOperations() {
        return supportedOperations;
    }

    @Override
    public void setContainerInterface(ContainerInterface containerInterface) {
        final String logPrefix = "setContainerInterface() - ";
        log.trace("{}Entering Method", logPrefix);
        log.info("{}Setting container interface", logPrefix);
        this.container = containerInterface;
        if (!doneConfig) {
            try {
                setup();

            }
            catch (PluginException ex) {
                throw new RuntimeException("Plugin exception during setup", ex);
            }
            doneConfig = true;
        }
    }

    @Override
    public SourceContainer getSourceCode() {
        final String logPrefix = "getSourceCode() - ";
        log.trace("{}Entering Method", logPrefix);
        log.info("{}Getting source code for plugin", logPrefix);

        SourceContainer sc = new SourceContainer();
        sc.setUsesAGPL(true);

        String fileName = buildArtifact + "-" + buildVersion + "-sources.jar";
        log.debug("{}Reading source file from ClassPath: {}", logPrefix, fileName);
        byte[] sourceCode = null;
        try {
            sourceCode = this.getClass().getResourceAsStream("/" + fileName).readAllBytes();
        }
        catch (IOException ex) {
            log.error("{}Exception reading source", logPrefix);
        }
        sc.setSourceFileName(fileName);
        sc.setSourceJar(sourceCode);

        return sc;

    }

    @Override
    public boolean isSourceAvailable() {
        return true;
    }

}
