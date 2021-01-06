package org.techpleiad.http.client.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.stream.Collectors;

/**
 * User: rajeshgupta
 * Date: 05/01/21
 */
@ExtendWith(SpringExtension.class)
@AutoConfigureWireMock(port = 0)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class})
public class HttpClientMetricsIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    public void given() {
        final String response = restTemplate.getForObject("/health", String.class);

        final List<Meter> meterList = meterRegistry.getMeters().stream().filter(meter -> meter.getId().getName().equals("http_connections")).collect(Collectors.toList());

        Assertions.assertTrue(!meterList.isEmpty());
        meterList.forEach(meter -> {
            Assertions.assertTrue(((Gauge) meter).value() >= 0.0D);
        });
    }
}
