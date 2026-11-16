package dev.ltidemo;

import dev.ltidemo.config.LtiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LtiProperties.class)
public class Lti13DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(Lti13DemoApplication.class, args);
    }
}
