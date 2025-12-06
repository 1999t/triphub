package com.triphub.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.triphub.common.properties.AiProperties;
import com.triphub.common.properties.JwtProperties;

@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class, AiProperties.class})
public class TriphubServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TriphubServerApplication.class, args);
    }
}


