/*
 *   management-sql-plugin - PluginConfiguration.java
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

import com.slinkytoybox.gcloud.platformconnectorplugin.PlatformConnectorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 *
 * @author Michael Junek (michael@juneks.com.au)
 */
@Configuration
@Slf4j
public class PluginConfiguration {

    @Bean
    public PlatformConnectorPlugin pluginInterface(ApplicationContext context) {
        final String logPrefix = "pluginInterface() - ";
        log.trace("{}Entering Method", logPrefix);

        // Get the plugin Id and description from the context
        String pluginId = context.getId();
        String pluginDescription = context.getDisplayName();

        // Create a new properties class
        Properties pluginProperties = new Properties();

        // Read the inbuilt application properties from the JAR file / Classpath
        String appLoc = "/application.properties";
        try ( InputStream appProp = PluginConfiguration.class.getResourceAsStream(appLoc)) {
            log.trace("{}Using stream {}", logPrefix, PluginConfiguration.class.getResource(appLoc).toString());
            pluginProperties.load(appProp);
        }
        catch (IOException | NullPointerException ex) {
            log.error("{}IOException encountered when opening application.properties from the classpath. It may not exist", logPrefix, ex);
        }

        
        // Read the external configuration properties from "pluginId.properties"
        log.info("{}Reading configuration for plugin", logPrefix);
        File currentJavaJarFile = new File(PluginConfiguration.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        String currentJavaJarFilePath = currentJavaJarFile.getAbsolutePath();
        String currentRootDirectoryPath = currentJavaJarFilePath.replace(currentJavaJarFile.getName(), "");

        String configPath = currentRootDirectoryPath + pluginId + ".properties";
        log.trace("{}-- Scanning {}", logPrefix, configPath);
        try ( FileInputStream fileStream = new FileInputStream(configPath)) {
            pluginProperties.load(fileStream);
        }
        catch (IOException ex) {
            log.error("{}IOException encountered when opening config file. It may not exist", logPrefix, ex);
        }

        // Create the new worker with its name, description and configuration
        log.debug("{}Creating new plugin worker", logPrefix);
        return new ManagementSQLPlugin(pluginId, pluginDescription, pluginProperties);
    }

}
