package com.festo.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// enables @CreatedDate in BaseEntity
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
