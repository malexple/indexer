package ru.mcs.mcpsql.site;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The site reaches mcp-sql-service only for the public statistics, and needs no key for that. */
@ConfigurationProperties("site")
public record SiteProperties(@DefaultValue("http://mcp-sql:8080") String serviceUrl) {
}
