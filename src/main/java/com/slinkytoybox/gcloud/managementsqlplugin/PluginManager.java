/*
 *   management-sql-plugin - PluginManager.java
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

import com.slinkytoybox.gcloud.platformconnectorplugin.ContainerInterface;
import com.slinkytoybox.gcloud.platformconnectorplugin.PlatformConnectorPlugin;
import com.slinkytoybox.gcloud.platformconnectorplugin.PluginOperation;
import com.slinkytoybox.gcloud.platformconnectorplugin.SourceContainer;
import com.slinkytoybox.gcloud.platformconnectorplugin.health.HealthResult;
import com.slinkytoybox.gcloud.platformconnectorplugin.request.PluginRequest;
import com.slinkytoybox.gcloud.platformconnectorplugin.response.PluginResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;
import org.pf4j.PluginWrapper;
import org.pf4j.spring.SpringPlugin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 *
 * @author Michael Junek (michael@juneks.com.au)
 */
@Slf4j
public class PluginManager extends SpringPlugin {

    public PluginManager(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        final String logPrefix = "startPlugin() - ";
        log.trace("{}Entering Method", logPrefix);
        log.info("{}Starting plugin {} version {}", logPrefix, getPluginName(), getPluginVersion());
        log.trace("{}Leaving Method", logPrefix);
    }

    @Override
    public void stop() {
        final String logPrefix = "stopPlugin() - ";
        log.trace("{}Entering Method", logPrefix);
        log.info("{}Stopping plugin {} version {}", logPrefix, getPluginName(), getPluginVersion());
        super.stop();  // must call to super here to ensure application context is shut down and nulled out (custom version of pf4j-spring needed for the latter)
        log.trace("{}Leaving Method", logPrefix);

    }

    @Override
    protected ApplicationContext createApplicationContext() {
        AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext();
        applicationContext.setClassLoader(getWrapper().getPluginClassLoader());
        applicationContext.register(PluginConfiguration.class);
        applicationContext.setId(getPluginName());  // this is important - it is how the plugin knows who it is.
        applicationContext.setDisplayName("Plugin_" + getPluginName() + "_" + getPluginVersion() + "_" + getPluginDescription());
        applicationContext.refresh();
        return applicationContext;
    }

    private String getPluginName() {
        return this.getWrapper().getDescriptor().getPluginId();
    }

    private String getPluginVersion() {
        return this.getWrapper().getDescriptor().getVersion();
    }

    private String getPluginDescription() {
        return this.getWrapper().getDescriptor().getPluginDescription();
    }

    @Extension(ordinal = 1)
    public static class PluginWrapperExtension implements PlatformConnectorPlugin {

        private final PlatformConnectorPlugin worker;

        @Autowired
        public PluginWrapperExtension(final PlatformConnectorPlugin pluginInterface) {
            this.worker = pluginInterface;

        }

        @Override
        public PluginResponse getResponseFromRequest(PluginRequest request) {
            return worker.getResponseFromRequest(request);
        }

        @Override
        public HealthResult getPluginHealth() {
            return worker.getPluginHealth();
        }

        @Override
        public List<PluginOperation> getValidOperations() {
            return worker.getValidOperations();
        }

        @Override
        public void setContainerInterface(ContainerInterface containerInterface) {
            worker.setContainerInterface(containerInterface);
        }

        @Override
        public SourceContainer getSourceCode() {
            return worker.getSourceCode();
        }

        @Override
        public boolean isSourceAvailable() {
            return worker.isSourceAvailable();
        }
    }

}
