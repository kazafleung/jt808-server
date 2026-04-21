package org.zendo.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "diagnostics")
public class DiagnosticsProperties {

    /**
     * Minutes of silence (with ACC on and speed > 0) before flagged as abnormal
     * offline.
     */
    private int offlineMinutes = 5;

    /**
     * Timezone used to determine the calendar-day boundary for GPS / signal
     * quality windows. Defaults to UTC. Use IANA zone IDs, e.g. "Asia/Hong_Kong".
     */
    private String windowZone = "UTC";

    /**
     * GNSS satellite count below this value is considered a "bad" location fix.
     * Records without a satellite count are excluded from the ratio.
     */
    private int satelliteThreshold = 4;

    /**
     * Wireless signal strength below this value is considered "bad".
     * Records without a signal strength value are excluded from the ratio.
     */
    private int signalThreshold = 10;
}
