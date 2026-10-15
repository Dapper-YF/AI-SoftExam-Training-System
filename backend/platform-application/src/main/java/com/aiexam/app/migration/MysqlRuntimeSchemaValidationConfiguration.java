package com.aiexam.app.migration;

import java.sql.DriverManager;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@Profile("mysql")
public class MysqlRuntimeSchemaValidationConfiguration {
  private static final List<String> REQUIRED_MYSQL_PROPERTIES = List.of(
      "MYSQL_URL", "MYSQL_USERNAME", "MYSQL_PASSWORD");

  @Bean
  static BeanFactoryPostProcessor mysqlRuntimeSchemaValidation(Environment environment) {
    return beanFactory -> {
      for (String property : REQUIRED_MYSQL_PROPERTIES) {
        String value = environment.getProperty(property);
        boolean missing = "MYSQL_PASSWORD".equals(property)
            ? value == null
            : !StringUtils.hasText(value);
        if (missing) {
          throw new IllegalStateException(
              "Required mysql profile environment variable is missing: " + property);
        }
      }
      try (var connection = DriverManager.getConnection(
          environment.getRequiredProperty("MYSQL_URL"),
          environment.getRequiredProperty("MYSQL_USERNAME"),
          environment.getRequiredProperty("MYSQL_PASSWORD"))) {
        new V9RuntimeSchemaValidator().validate(connection);
        new AiAssistantRuntimeSchemaValidator().validate(connection);
        beanFactory.registerSingleton("v9RuntimeSchemaValidated", Boolean.TRUE);
      } catch (RuntimeException exception) {
        throw exception;
      } catch (Exception exception) {
        throw new IllegalStateException("V9 runtime schema validation failed", exception);
      }
    };
  }
}
