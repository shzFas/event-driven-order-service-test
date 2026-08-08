package com.yz.orderservice.config;

import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topic layout, so the broker is shaped by the application rather than
 * by auto-creation — which would silently give every topic a single partition and
 * quietly cap consumer parallelism at one.
 *
 * <p>Each topic gets a matching {@code .DLT}. Dead-letter topics are created with
 * the same partition count on purpose: the recoverer republishes a failed record to
 * the same partition number it came from, which would fail if the DLT had fewer.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

	private final int partitions;

	private final short replicationFactor;

	public KafkaConfig(@Value("${app.kafka.partitions}") int partitions,
			@Value("${app.kafka.replication-factor}") short replicationFactor) {
		this.partitions = partitions;
		this.replicationFactor = replicationFactor;
	}

	@Bean
	NewTopic ordersTopic(@Value("${app.kafka.topics.orders}") String name) {
		return topic(name);
	}

	@Bean
	NewTopic ordersDeadLetterTopic(@Value("${app.kafka.topics.orders}") String name) {
		return topic(name + ".DLT");
	}

	@Bean
	NewTopic ordersReservedTopic(@Value("${app.kafka.topics.orders-reserved}") String name) {
		return topic(name);
	}

	@Bean
	NewTopic ordersReservedDeadLetterTopic(@Value("${app.kafka.topics.orders-reserved}") String name) {
		return topic(name + ".DLT");
	}

	@Bean
	NewTopic ordersCompletedTopic(@Value("${app.kafka.topics.orders-completed}") String name) {
		return topic(name);
	}

	@Bean
	NewTopic ordersCompletedDeadLetterTopic(@Value("${app.kafka.topics.orders-completed}") String name) {
		return topic(name + ".DLT");
	}

	private NewTopic topic(String name) {
		return TopicBuilder.name(name).partitions(this.partitions).replicas(this.replicationFactor).build();
	}

}
