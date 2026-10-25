package com.bnpp.regliss;

// TODO: imports (lines 3-20) were folded in the IDE; reconstructed from usage.
//       Verify packages of IEmailClient and ReglissRequestContext.
import lombok.extern.slf4j.Slf4j;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.CompositeCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.Scope;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;          // jakarta.annotation.* on Spring Boot 3

import static java.util.Collections.singleton;

@Slf4j
@SpringBootApplication
@PropertySource("classpath:config-app.properties")
@PropertySource("classpath:config-app-local.properties")
@ComponentScan(basePackages = "com.bnpp.regliss", excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes= RestController.class))
public class TestConfig  {

    @MockBean
    private IEmailClient emailClient;

    @Value("${spring.datasource.url}")
    private String databaseUrl;

    @PostConstruct
    public void printDatabaseUrl() { log.info("Connecting to database url: {}", databaseUrl); }

    @Bean
    public SimpleCacheManager reflectionCache() {
        log.debug("Creating the reference cache");
        SimpleCacheManager cache = new SimpleCacheManager();
        cache.setCaches(singleton(new ConcurrentMapCache("reflection", true)));
        return cache;
    }

    @Bean
    public SimpleCacheManager versionProgramLabelsCache() {
        log.debug("Creating the version Program Labels cache");
        SimpleCacheManager cache = new SimpleCacheManager();
        cache.setCaches(singleton(new ConcurrentMapCache("versionProgramLabels", true)));
        return cache;
    }

    @Bean
    public ThreadPoolTaskExecutor manualImportExecutor() { return new ThreadPoolTaskExecutor(); }

    @Bean
    public ThreadPoolTaskExecutor exportExecutor() { return new ThreadPoolTaskExecutor(); }

    @Bean
    @Scope("prototype")
    public SimpleCacheManager threadCache() {
        log.debug("Creating the reference cache");
        SimpleCacheManager cache = new SimpleCacheManager();
        cache.setCaches(singleton(new ConcurrentMapCache("threadCache", true)));
        return cache;
    }

    @Bean
    public ReglissRequestContext reglissRequestContext() { return new ReglissRequestContext(); }

    @Bean
    @Primary
    public CompositeCacheManager cacheManager() {
        return new CompositeCacheManager(reflectionCache(), threadCache(), versionProgramLabelsCache());
    }

    @Bean
    public JavaMailSender javaMailSender() { return Mockito.mock(JavaMailSender.class); }
}
