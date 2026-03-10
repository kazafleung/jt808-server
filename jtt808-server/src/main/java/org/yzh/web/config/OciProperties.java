package org.yzh.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "oci")
public class OciProperties {
    private String namespace;
    private String bucket;
    private String region;
    private AuthType authType = AuthType.CONFIG_FILE;
    private Config config = new Config();

    public enum AuthType {
        CONFIG_FILE, INSTANCE_PRINCIPAL
    }

    @Data
    public static class Config {
        private String filePath;
        private String profile;
    }
}
