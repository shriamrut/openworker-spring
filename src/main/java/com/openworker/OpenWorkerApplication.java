package com.openworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OpenWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenWorkerApplication.class, args);
    }
}
