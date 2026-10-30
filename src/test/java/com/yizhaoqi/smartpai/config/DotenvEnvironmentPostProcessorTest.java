package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotenvEnvironmentPostProcessorTest {

    @Test
    void detectsIndexedTestProfileWrittenByActiveProfiles() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active[0]", "test");

        assertTrue(DotenvEnvironmentPostProcessor.isTestProfileActive(environment));
    }

    @Test
    void detectsTestProfileInCommaSeparatedList() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "dev, test");

        assertTrue(DotenvEnvironmentPostProcessor.isTestProfileActive(environment));
    }

    @Test
    void detectsProgrammaticallyActivatedTestProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertTrue(DotenvEnvironmentPostProcessor.isTestProfileActive(environment));
    }

    @Test
    void loadsDotenvForOtherProfiles() {
        assertFalse(DotenvEnvironmentPostProcessor.isTestProfileActive(new MockEnvironment()));
        assertFalse(DotenvEnvironmentPostProcessor.isTestProfileActive(
                new MockEnvironment().withProperty("spring.profiles.active", "dev,testing")));
    }
}
