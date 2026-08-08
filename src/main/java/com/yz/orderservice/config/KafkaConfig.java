package com.yz.orderservice.config;

import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

	/**
	 * Declares the orders topic so the broker layout is defined by the application
	 * rather than left to auto-creation, which would give a single partition.
	 *
	 * <p>Partition count is the ceiling on consumer parallelism: a partition is
	 * consumed by at most one member of a group, so three partitions allow three
	 * instances to work in parallel.
	 */
	@Bean
	NewTopic ordersTopic(@Value("${app.kafka.topics.orders}") String name,
			@Value("${app.kafka.partitions}") int partitions,
			@Value("${app.kafka.replication-factor}") short replicationFactor) {
		return TopicBuilder.name(name).partitions(partitions).replicas(replicationFactor).build();
	}

}
