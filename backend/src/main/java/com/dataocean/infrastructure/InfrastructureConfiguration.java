package com.dataocean.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@EnableConfigurationProperties(DataOceanProperties.class)
public class InfrastructureConfiguration {
    @Bean public Clock clock() { return Clock.systemUTC(); }
}
