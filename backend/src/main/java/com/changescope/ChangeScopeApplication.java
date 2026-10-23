package com.changescope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ChangeScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChangeScopeApplication.class, args);
    }
}
