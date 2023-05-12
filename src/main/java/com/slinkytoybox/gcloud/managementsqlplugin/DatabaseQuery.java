/*
 *   management-sql-plugin - DatabaseQuery.java
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

import com.slinkytoybox.gcloud.platformconnectorplugin.response.PluginResponse;
import com.slinkytoybox.gcloud.platformconnectorplugin.response.ReadResponse;
import com.zaxxer.hikari.HikariDataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Michael Junek (michael@juneks.com.au)
 */
@Slf4j
@ToString(exclude = {"poolSource"})
public class DatabaseQuery {

    private final HikariDataSource poolSource;

    private final String queryId;

    // SQL string for the specific select. Should only have a single ? for the primary identifier in the where clause
    private final String sqlString;

    // The database type of the searchable field.
    private final ColumnDataType searchDataType;

    private final Map<String, DatabaseColumn> columns = new TreeMap<>();

    public DatabaseQuery(Properties configuration, String queryId, HikariDataSource poolSource) {
        final String logPrefix = "ctor() - ";
        log.trace("{}Entering Method", logPrefix);
        log.info("{}Initialising query {}", logPrefix, queryId);
        if (!configuration.containsKey("query." + queryId + ".sql")) {
            log.error("{}SQL Query not defined", logPrefix);
            throw new IllegalArgumentException("SQL query no defined");
        }
        else {
            sqlString = configuration.getProperty("query." + queryId + ".sql");
        }
        if (!configuration.containsKey("query." + queryId + ".search-data-type")) {
            log.error("{}Search Data Type is not defined", logPrefix);
            throw new IllegalArgumentException("Search Data Type is not defined");
        }
        else {
            String searchDataTypeStr = configuration.getProperty("query." + queryId + ".search-data-type");
            try {
                searchDataType = ColumnDataType.valueOf(searchDataTypeStr.toUpperCase());
            }
            catch (IllegalArgumentException ex) {
                log.error("{}Data type {} is not one of TEXT/NUMBER/TIMESTAMP");
                throw new IllegalArgumentException("Search Data Type is not valid");

            }
        }
        log.debug("{}SQL String: {}", logPrefix, sqlString);
        log.debug("{}Search data type {}", logPrefix, searchDataType);

        log.debug("{}Getting column configuration", logPrefix);
        for (String key : configuration.stringPropertyNames()) {
            if (key.matches("^query\\." + Pattern.quote(queryId) + "\\.column\\.[^.]+\\.[^.]+(\\.[^.]+)?$")) {
                log.trace("{}Started processing configuration {}", logPrefix, key);

                String[] parts = key.split("\\.");
                String columnName = parts[3];
                DatabaseColumn col = null;
                if (columns.containsKey(columnName)) {
                    col = columns.get(columnName);
                    log.trace("{}Using existing column {}", logPrefix, col);
                }
                else {
                    if (configuration.containsKey("query." + queryId + ".column." + columnName + ".enabled") && configuration.getProperty("query." + queryId + ".column." + columnName + ".enabled").equalsIgnoreCase("true")) {
                        String type = configuration.getProperty("query." + queryId + ".column." + columnName + ".data-type", "string");
                        ColumnDataType dataType = null;
                        try {
                            dataType = ColumnDataType.valueOf(type.toUpperCase());
                        }
                        catch (IllegalArgumentException ex) {
                            log.warn("{}Column data type {} is not one of TEXT/NUMBER/TIMESTAMP. Ignoring", logPrefix, type.toUpperCase());
                        }
                        if (dataType != null) {
                            col = new DatabaseColumn();
                            col.name = columnName;
                            col.dataType = dataType;
                            col.jsonField = configuration.getProperty("query." + queryId + ".column." + columnName + ".json-field", columnName);
                            columns.put(columnName, col);
                            log.trace("{}Created new column {}", logPrefix, col);
                        }

                    }
                    else {
                        log.warn("{}Column name {} is not enabled, ignoring", logPrefix, columnName);
                    }
                }
                if (col != null && parts[4].equalsIgnoreCase("enum")) {
                    String enumKey = parts[5];
                    String enumValue = configuration.getProperty("query." + queryId + ".column." + columnName + ".enum." + enumKey);
                    log.trace("{}>> {} Added Enum: {} -> {}", logPrefix, columnName, enumKey, enumValue);
                    col.dataEnumeration.put(enumKey, enumValue);
                }
            }
        }
        this.poolSource = poolSource;
        this.queryId = queryId;
        log.info("{}Finished setting up QueryId: {}", logPrefix, queryId);

    }

    public ReadResponse performQuery(ReadResponse response, Object lookupId) {
        final String logPrefix = "performQuery() - {" + lookupId + " @ " + queryId + "} - ";

        log.trace("{}Entering Method", logPrefix);
        log.info("{}Performing lookup for ID: {}", logPrefix);

        try (Connection conn = poolSource.getConnection()) {
            log.trace("{}SQL: {}", logPrefix, sqlString);
            try (PreparedStatement ps = conn.prepareStatement(sqlString, ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
                String lookupIdString = "";
                switch (searchDataType) {
                    case TEXT -> {
                        ps.setString(1, (String) lookupId);
                        lookupIdString = (String) lookupId;
                    }
                    case NUMBER -> {
                        ps.setLong(1, (Long) lookupId);
                        lookupIdString = ((Long) lookupId).toString();
                    }
                    case TIMESTAMP -> {
                        ps.setTimestamp(1, Timestamp.valueOf((LocalDateTime) lookupId));
                        lookupIdString = ((LocalDateTime) lookupId).toString();
                    }
                }
                ps.setMaxRows(2); // we set max rows here to two - for performance implications. We only ever want one result, so if > 1 are returned we will error out anwyay
                log.trace("{}Added parameter {} - {} ({})", logPrefix, 1, lookupIdString, searchDataType.name());
                log.trace("{}About to execute query", logPrefix);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.last()) {
                        log.warn("{}Recordset Last record not found - no records exist", logPrefix);
                        response.setErrorMessage("Record was not found");
                        response.setStatus(PluginResponse.ResponseStatus.RECORD_NOT_FOUND);
                        return response;
                    }
                    if (rs.getRow() > 1) {
                        log.warn("{}Recordset row number greater than one - muliple records found", logPrefix);
                        response.setErrorMessage("More than one record was found");
                        response.setStatus(PluginResponse.ResponseStatus.MULTIPLE_RECORDS);
                        return response;
                    }
                    log.debug("{}Recordset one record found, constructing result", logPrefix);
                    response.setObjectId(lookupIdString);

                    Map<String, Serializable> dataMap = new HashMap<>();

                    for (DatabaseColumn col : columns.values()) {
                        log.trace("{}Looking up data for {}", logPrefix, col.name);
                        String colData = "";
                        switch (col.dataType) {
                            case TEXT -> {
                                colData = rs.getString(col.name);
                            }
                            case NUMBER -> {
                                colData = ((Long) rs.getLong(col.name)).toString();
                            }
                            case TIMESTAMP -> {
                                colData = rs.getTimestamp(col.name).toLocalDateTime().toString();
                            }
                        }
                        log.trace("{}Column {} = {}", logPrefix, col.name, colData);
                        String tempData = col.dataEnumeration.getOrDefault(colData, colData);
                        log.trace("{} >> Enum result {} -> {}", logPrefix, colData, tempData);
                        String jsonCol = col.jsonField;
                        log.trace("{} ++ Adding {} = {} to the map", logPrefix, jsonCol, tempData);
                        dataMap.put(jsonCol, tempData);
                    }

                    response.setStatus(PluginResponse.ResponseStatus.SUCCESS);
                    response.setObjectDetails(dataMap);
                    log.info("{}Successfully looked up record from DB", logPrefix);
                }
                catch (SQLException ex) {
                    log.error("{}SQL Exception on RecordSet", logPrefix, ex);
                    response.setErrorMessage("SQL Exception on RecordSet -- " + ex.getMessage());
                    response.setStatus(PluginResponse.ResponseStatus.FAILURE);
                }
            }
            catch (SQLException ex) {
                log.error("{}SQL Exception on PreparedStatement", logPrefix, ex);
                response.setErrorMessage("SQL Exception on PreparedStatement -- " + ex.getMessage());
                response.setStatus(PluginResponse.ResponseStatus.FAILURE);
            }
        }
        catch (SQLException ex) {
            log.error("{}SQL Exception on Getting Connection", logPrefix, ex);
            response.setErrorMessage("SQL Exception on Getting Connection -- " + ex.getMessage());
            response.setStatus(PluginResponse.ResponseStatus.FAILURE);
        }
        return response;
    }

    private static class DatabaseColumn {

        private String name;
        private ColumnDataType dataType;
        private String jsonField;
        private Map<String, String> dataEnumeration = new TreeMap<>();

    }

    private enum ColumnDataType {
        TEXT,
        NUMBER,
        TIMESTAMP
    }

}
