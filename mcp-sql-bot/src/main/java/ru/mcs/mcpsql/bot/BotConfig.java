package ru.mcs.mcpsql.bot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class BotConfig {

    @Bean
    public JsonMapper botJsonMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    public HttpClient botHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }
}
