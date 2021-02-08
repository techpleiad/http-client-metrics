package org.techpleiad.http.client.metrics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * User: rajeshgupta
 * Date: 05/01/21
 */
@SpringBootApplication(scanBasePackages = {"org.techpleiad.**"}, exclude = {DataSourceAutoConfiguration.class})
@EnableFeignClients(basePackages = {"org.techpleiad.**"})
public class Application {

    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
