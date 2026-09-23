package com.pokeranalytics.livesession;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Same pattern as {@code com.pokeranalytics.storage.DataSourceConfig}'s use of {@code
 *  @EnableConfigurationProperties(StorageProperties.class)}. */
@Configuration
@EnableConfigurationProperties(LiveSessionProperties.class)
public class LiveSessionConfig {
}
