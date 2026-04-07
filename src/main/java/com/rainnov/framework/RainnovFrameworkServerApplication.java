package com.rainnov.framework;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RainnovFrameworkServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RainnovFrameworkServerApplication.class, args);
    }

}
