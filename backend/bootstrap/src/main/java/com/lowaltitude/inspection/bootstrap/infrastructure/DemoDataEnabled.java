package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;

/** Opt-in for loading repeatable business fixtures into an isolated development database. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnBooleanProperty(prefix = "platform.demo-data", name = "enabled")
@interface DemoDataEnabled {}
