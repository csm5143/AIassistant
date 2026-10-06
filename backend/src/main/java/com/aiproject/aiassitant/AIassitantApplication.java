package com.aiproject.aiassitant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.aiproject.aiassitant.module.**.mapper")
public class AIassitantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AIassitantApplication.class, args);
    }
}
