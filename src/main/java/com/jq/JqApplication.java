package com.jq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class JqApplication {

    public static void main(String[] args) {
        // avoid timezone mismatch
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(JqApplication.class, args);
    }
}
