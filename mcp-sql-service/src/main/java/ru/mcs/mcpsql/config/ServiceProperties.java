package ru.mcs.mcpsql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

@ConfigurationProperties("mcpsql")
public record ServiceProperties(
        @DefaultValue("./data") Path dataDir,
        @DefaultValue("") String readKey,
        @DefaultValue("") String writeKey,
        @DefaultValue("8000") int maxResponseChars,
        @DefaultValue("3") int sqlTimeoutSeconds,
        @DefaultValue("200") int sqlMaxRows,
        @DefaultValue("200") int maxUploadMb) {
}