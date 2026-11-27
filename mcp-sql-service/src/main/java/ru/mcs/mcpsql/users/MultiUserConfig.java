package ru.mcs.mcpsql.users;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;

import java.io.IOException;

/** Creates the user database and the key service only when mcpsql.multi.enabled=true. */
@Configuration
@ConditionalOnProperty(name = "mcpsql.multi.enabled", havingValue = "true")
public class MultiUserConfig {

    /** In a subdirectory, so the file is never listed as a workspace. */
    @Bean
    public UserDb userDb(ServiceProperties properties) throws IOException {
        return new UserDb(properties.dataDir().resolve("system").resolve("user.db"));
    }

    @Bean
    public KeyService keyService(UserDb db, MultiUserProperties properties) {
        return new KeyService(db, properties);
    }
}
