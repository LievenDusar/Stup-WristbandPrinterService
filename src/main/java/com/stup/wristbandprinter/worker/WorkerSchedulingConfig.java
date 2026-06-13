package com.stup.wristbandprinter.worker;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled (the registration heartbeat) only in the worker role. */
@Configuration
@Profile("worker")
@EnableScheduling
public class WorkerSchedulingConfig {
}
