package ru.mcs.mcpsql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpSqlServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpSqlServiceApplication.class, args);
    }
}