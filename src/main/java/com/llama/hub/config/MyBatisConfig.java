package com.llama.hub.config;

import com.github.pagehelper.PageInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * MyBatis 显式装配：Spring Boot 4 的自动配置求值时序与 mybatis-spring-boot 不兼容
 * （@ConditionalOnSingleCandidate(DataSource) 评估时 DataSource bean 尚不可见），
 * 故在此显式声明 SqlSessionFactory / 事务管理器 / mapper 扫描，PageInterceptor 手动挂载。
 */
@Configuration
public class MyBatisConfig {

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath:mybatis/*.xml"));
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        PageInterceptor pageInterceptor = new PageInterceptor();
        Properties pageHelperProps = new Properties();
        pageHelperProps.setProperty("helperDialect", "h2");
        pageHelperProps.setProperty("reasonable", "false");
        pageHelperProps.setProperty("params", "count");
        pageInterceptor.setProperties(pageHelperProps);
        configuration.addInterceptor(pageInterceptor);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public static MapperScannerConfigurer mapperScannerConfigurer() {
        MapperScannerConfigurer configurer = new MapperScannerConfigurer();
        configurer.setBasePackage("com.llama.hub.mapper");
        configurer.setSqlSessionFactoryBeanName("sqlSessionFactory");
        return configurer;
    }
}
