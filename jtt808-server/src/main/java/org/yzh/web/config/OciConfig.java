package org.yzh.web.config;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class OciConfig {

    private final OciProperties properties;

    public OciConfig(OciProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ObjectStorage objectStorage() throws IOException {
        AuthenticationDetailsProvider provider;

        if (properties.getAuthType() == OciProperties.AuthType.INSTANCE_PRINCIPAL) {
            provider = InstancePrincipalsAuthenticationDetailsProvider.builder().build();
        } else {
            String configurationFilePath = properties.getConfig().getFilePath();
            String profile = properties.getConfig().getProfile();

            ConfigFileReader.ConfigFile configFile;
            if (configurationFilePath != null && !configurationFilePath.isEmpty()) {
                configFile = ConfigFileReader.parse(configurationFilePath, profile);
            } else {
                configFile = ConfigFileReader.parseDefault(profile);
            }
            provider = new ConfigFileAuthenticationDetailsProvider(configFile);
        }

        ObjectStorage client = ObjectStorageClient.builder().build(provider);
        client.setRegion(properties.getRegion());
        return client;
    }
}
