package org.techpleiad.http.client.metrics.spring.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.commons.httpclient.CustomDefaultApacheHttpClientConnectionManagerFactory;
import org.springframework.cloud.commons.httpclient.DefaultApacheHttpClientConnectionManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * User: rajeshgupta
 * Date: 30/12/20
 */
@Configuration
public class HttpClientMetricsConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(value = "http.client.metrics.enabled", havingValue = "true", matchIfMissing = false)
    public CustomDefaultApacheHttpClientConnectionManagerFactory apacheHttpClientConnectionManagerFactory(final MeterRegistry meterRegistry) {
        return new CustomDefaultApacheHttpClientConnectionManagerFactory(meterRegistry);
    }
}
