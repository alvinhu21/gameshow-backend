package com.gameShows.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.HashMap;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        // Scans ONLY the Wheel of Fortune sub-package
        basePackages = "com.gameShows.repositories.wof", entityManagerFactoryRef = "wofEntityManagerFactory", transactionManagerRef = "wofTransactionManager")
public class WoFDbConfig {

    @Bean(name = "wofDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.wof")
    public DataSource dataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean(name = "wofEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            @Qualifier("wofDataSource") DataSource dataSource) {

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);

        // Point specifically to WoF entities (e.g., Puzzle.java)
        em.setPackagesToScan("com.gameShows.models.wof");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);

        HashMap<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "update");
        properties.put("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
        em.setJpaPropertyMap(properties);

        return em;
    }

    @Bean(name = "wofTransactionManager")
    public PlatformTransactionManager transactionManager(
            @Qualifier("wofEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}