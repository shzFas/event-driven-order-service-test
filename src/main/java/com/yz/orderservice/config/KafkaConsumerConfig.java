package com.yz.orderservice.config;

import org.apache.kafka.common.TopicPartition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Failure policy for all listeners.
 *
 * <p>Retries are bounded and then the record is parked in a dead-letter topic. The
 * alternative — retrying forever — is worse than losing the message: a single
 * poison record would block its partition indefinitely, stalling every other
 * customer whose key hashes to it.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {

	private static final Logger logger = LoggerFactory.getLogger(KafkaConsumerConfig.class);

	@Bean
	DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, Object> kafkaOperations) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
				(record, exception) -> {
					logger.error("Sending record from {}-{}@{} to the dead-letter topic", record.topic(),
							record.partition(), record.offset(), exception);
					return new TopicPartition(record.topic() + ".DLT", record.partition());
				});

		ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
		backOff.setMaxAttempts(3);
		backOff.setMaxInterval(4000);

		DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

		// Retrying these is pointless — a malformed payload or an illegal state
		// transition fails identically every time. Straight to the DLT.
		errorHandler.addNotRetryableExceptions(IllegalArgumentException.class, IllegalStateException.class);
		return errorHandler;
	}

}
