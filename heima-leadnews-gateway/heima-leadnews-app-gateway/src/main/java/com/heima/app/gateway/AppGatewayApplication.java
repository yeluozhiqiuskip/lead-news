package com.heima.app.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

import static org.springframework.boot.SpringApplication.*;


@SpringBootApplication
@EnableDiscoveryClient
public class AppGatewayApplication {
    public static void main(String[] args) {
        run(AppGatewayApplication.class, args);
    }
}
