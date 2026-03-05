package com.wechat.collector.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库连接管理器
 * 使用 HikariCP 连接池
 */
public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    
    private HikariDataSource dataSource;
    
    /**
     * 初始化数据库连接池
     */
    public void initialize(AppConfig.DatabaseConfig config) {
        logger.info("Initializing database connection pool...");
        
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getJdbcUrl());
        hikariConfig.setUsername(config.getUser());
        hikariConfig.setPassword(config.getPassword());
        
        // 连接池配置
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setConnectionTimeout(30000);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setMaxLifetime(1800000);
        
        // 连接测试
        hikariConfig.setConnectionTestQuery("SELECT 1");
        
        this.dataSource = new HikariDataSource(hikariConfig);
        
        // 测试连接
        try (Connection conn = dataSource.getConnection()) {
            logger.info("Database connection pool initialized successfully");
        } catch (SQLException e) {
            logger.error("Failed to test database connection", e);
            throw new RuntimeException("Database connection failed", e);
        }
    }
    
    /**
     * 获取数据源
     */
    public DataSource getDataSource() {
        return dataSource;
    }
    
    /**
     * 获取数据库连接
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    /**
     * 关闭连接池
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("Shutting down database connection pool...");
            dataSource.close();
        }
    }
}

