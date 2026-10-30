package com.yizhaoqi.smartpai.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "paismartDotenv";
    private static final String DOTENV_FILE = ".env";
    private static final String TEST_PROFILE = "test";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // test 配置用内存 H2 等隔离环境，.env 是本地开发配置，优先级又高于 application-test.yml，加载进来会把数据源等覆盖成开发库
        if (isTestProfileActive(environment)) {
            return;
        }
        Path dotenvPath = Path.of(System.getProperty("user.dir"), DOTENV_FILE);
        if (!Files.isRegularFile(dotenvPath)) {
            return;
        }

        Map<String, Object> properties = loadDotenv(dotenvPath);
        if (properties.isEmpty()) {
            return;
        }

        applyActiveProfiles(environment, properties);

        SystemEnvironmentPropertySource propertySource = new SystemEnvironmentPropertySource(PROPERTY_SOURCE_NAME, properties);
        MutablePropertySources propertySources = environment.getPropertySources();
        if (propertySources.contains(PROPERTY_SOURCE_NAME)) {
            propertySources.remove(PROPERTY_SOURCE_NAME);
        }

        if (propertySources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, propertySource);
            return;
        }

        propertySources.addLast(propertySource);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    static boolean isTestProfileActive(ConfigurableEnvironment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains(TEST_PROFILE)) {
            return true;
        }
        // @ActiveProfiles 写入的是 spring.profiles.active[0] 这种索引形式，用 Binder 才能同时识别逗号分隔和索引两种写法
        return Binder.get(environment)
                .bind("spring.profiles.active", Bindable.listOf(String.class))
                .map(profiles -> profiles.stream().map(String::trim).anyMatch(TEST_PROFILE::equals))
                .orElse(false);
    }

    private Map<String, Object> loadDotenv(Path dotenvPath) {
        Map<String, Object> properties = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(dotenvPath, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int separatorIndex = line.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }

                String key = line.substring(0, separatorIndex).trim();
                String value = line.substring(separatorIndex + 1).trim();
                if (key.isEmpty()) {
                    continue;
                }

                properties.put(key, unquote(value));
            }
        } catch (IOException ignored) {
            // Ignore malformed or unreadable .env files and continue with normal environment resolution.
        }
        return properties;
    }

    private void applyActiveProfiles(ConfigurableEnvironment environment, Map<String, Object> properties) {
        Object rawProfiles = properties.get("SPRING_PROFILES_ACTIVE");
        if (!(rawProfiles instanceof String profilesValue) || profilesValue.isBlank()) {
            return;
        }

        String[] profiles = profilesValue.split(",");
        for (int i = 0; i < profiles.length; i++) {
            profiles[i] = profiles[i].trim();
        }
        environment.setActiveProfiles(profiles);
    }

    private String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
