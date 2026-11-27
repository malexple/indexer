package ru.mcs.mcpsql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Multi-user mode; off by default, so a plain local run keeps the static read and write keys. */
@ConfigurationProperties("mcpsql.multi")
public record MultiUserProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("50") int maxActiveUsers,
        @DefaultValue("5") int keyIssuesPerDay) {
}
