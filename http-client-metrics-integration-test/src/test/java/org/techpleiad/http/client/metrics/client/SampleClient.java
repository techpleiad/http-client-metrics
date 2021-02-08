package org.techpleiad.http.client.metrics.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * User: rajeshgupta
 * Date: 05/01/21
 */
@FeignClient(value = "sampleClient")
public interface SampleClient {

    @GetMapping("/demo")
    public String getStatus();
}
