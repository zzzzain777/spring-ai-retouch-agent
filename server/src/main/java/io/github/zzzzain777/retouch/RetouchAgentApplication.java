package io.github.zzzzain777.retouch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RetouchAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetouchAgentApplication.class, args);
    }
}
