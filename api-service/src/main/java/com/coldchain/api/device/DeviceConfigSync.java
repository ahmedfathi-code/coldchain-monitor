package com.coldchain.api.device;

import com.coldchain.api.outbox.Outbox;
import com.coldchain.common.topic.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * On startup, queues the thresholds of EVERY device for device.config (through the outbox,
 * like every other change). This covers devices inserted by Flyway seed data (SQL cannot
 * talk to Kafka) and repairs the topic if it was ever lost. Re-publishing is harmless: on a
 * compacted topic the latest value per key wins.
 */
@Component
public class DeviceConfigSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeviceConfigSync.class);

    private final DeviceRepository devices;
    private final Outbox outbox;

    public DeviceConfigSync(DeviceRepository devices, Outbox outbox) {
        this.devices = devices;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Device> all = devices.findAll();
        all.forEach(device -> outbox.add(KafkaTopics.DEVICE_CONFIG, device.getId(), device.toConfigEvent()));
        log.info("Queued the thresholds of {} devices for device.config", all.size());
    }
}
