package com.trace.recall;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 集成测试基类：独立 schema recall_test，与开发库 public 物理隔离。
 * Initializer 在数据源初始化前用裸连接建好 schema，随后 schema.sql 在该 schema 内建表并播种。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = AbstractIntegrationTest.TestSchemaInitializer.class)
public abstract class AbstractIntegrationTest {

    @LocalServerPort
    protected int port;

    protected String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    static class TestSchemaInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            var env = ctx.getEnvironment();
            String url = env.getProperty("spring.datasource.url");
            String user = env.getProperty("spring.datasource.username");
            String pass = env.getProperty("spring.datasource.password");
            String rootUrl = url == null ? null : url.replace("?currentSchema=recall_test", "");
            try (Connection c = DriverManager.getConnection(rootUrl, user, pass)) {
                c.createStatement().execute("CREATE SCHEMA IF NOT EXISTS recall_test");
            } catch (SQLException e) {
                throw new IllegalStateException("无法创建测试 schema recall_test", e);
            }
        }
    }
}
