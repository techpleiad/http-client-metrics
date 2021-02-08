package org.techpleiad.http.client.metrics.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.techpleiad.http.client.metrics.client.SampleClient;

/**
 * User: rajeshgupta
 * Date: 05/01/21
 */
@RestController
public class SampleController {

    @Autowired
    private SampleClient client;

    @RequestMapping("/health")
    public String getHealth() {
        return client.getStatus();
    }
}
