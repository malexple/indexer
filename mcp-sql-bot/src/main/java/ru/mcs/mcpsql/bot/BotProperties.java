package ru.mcs.mcpsql.bot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The token and the internal key are secrets: they come from the environment and are never logged. */
@ConfigurationProperties("bot")
public record BotProperties(
        @DefaultValue("") String token,
        @DefaultValue("http://mcp-sql:8080") String serviceUrl,
        @DefaultValue("") String internalKey,
        @DefaultValue("") String publicUrl,
        @DefaultValue("https://api.telegram.org") String telegramUrl,
        @DefaultValue("50") int pollSeconds) {

    @Override
    public String toString() {
        return "BotProperties[serviceUrl=" + serviceUrl + ", publicUrl=" + publicUrl + ", secrets hidden]";
    }
}
