package com.hermesreviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class HermesPrReviewerApplication {


    public static void main(String[] args) {
        SpringApplication.run(HermesPrReviewerApplication.class, args);
    }
}
