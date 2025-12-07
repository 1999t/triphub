package com.triphub.server;

import com.triphub.common.properties.AiProperties;
import com.triphub.common.properties.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class, AiProperties.class})
@EnableScheduling
public class TriphubServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TriphubServerApplication.class, args);
    }
}


