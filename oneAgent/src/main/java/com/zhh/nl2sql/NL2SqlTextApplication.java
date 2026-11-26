package com.zhh.nl2sql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
//@EnableScheduling
@EnableConfigurationProperties
public class NL2SqlTextApplication {
    public static void main(String[] args) {
        SpringApplication.run(NL2SqlTextApplication.class, args);
    }
}