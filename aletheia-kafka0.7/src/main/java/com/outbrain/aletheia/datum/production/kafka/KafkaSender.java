package com.outbrain.aletheia.datum.production.kafka;

import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.outbrain.aletheia.datum.production.NamedKeyAwareSender;
import com.outbrain.aletheia.datum.production.NamedSender;
import com.outbrain.aletheia.datum.production.SilentSenderException;
import com.outbrain.aletheia.metrics.common.Counter;
import com.outbrain.aletheia.metrics.common.Histogram;
import com.outbrain.aletheia.metrics.common.MetricsFactory;
import kafka.javaapi.producer.Producer;
import kafka.javaapi.producer.ProducerData;
import kafka.producer.ProducerConfig;
import kafka.producer.async.QueueFullException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public abstract class KafkaSender<TInput, TPayload> implements NamedKeyAwareSender<TInput>, NamedSender<TInput> {

  private static final Logger logger = LoggerFactory.getLogger(KafkaSender.class);

  private Producer<String, TPayload> producer;
  private final KafkaTopicProductionEndPoint kafkaTopicDeliveryEndPoint;
  private final MetricsFactory metricFactory;
  private final int connectionAttempts = 0;
  private boolean connected = false;
  private static final int TEN_SECONDS = 10000;

  private final Timer connectionTimer = new Timer("KafkaBinaryTransporter-kafkaInitialConnectionTimer");
  private Counter sendCount;
  private Counter sendDuration;
  private Counter failureDueToUnconnected;
  private Counter failureDuration;
  private Counter messageLengthCounter;
  private Histogram messageSizeHistogram;
  private final ProducerConfig customConfiguration;


  public KafkaSender(final KafkaTopicProductionEndPoint kafkaTopicDeliveryEndPoint,
                     final MetricsFactory metricFactory) {

    this.kafkaTopicDeliveryEndPoint = kafkaTopicDeliveryEndPoint;
    this.metricFactory = metricFactory;

    logger.info("Creating kafka transporter for endpoint:" + kafkaTopicDeliveryEndPoint.toString());

    if (this.kafkaTopicDeliveryEndPoint.getAddShutdownHook()) {
      Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
        @Override
        public void run() {
          close();
        }
      }));
    }

    customConfiguration = customizeConfig(getProducerConfig());
    initMetrics(metricFactory);
    connectToKafka();
  }

  private void initMetrics(final MetricsFactory metricFactory) {
    sendCount = metricFactory.createCounter("Send.Attempts", "Success");
    sendDuration = metricFactory.createCounter("Send.Attempts", "Success");
    failureDuration = metricFactory.createCounter("Send.Attempts.Failures", "Duration");
    messageLengthCounter = metricFactory.createCounter("Message", "Length");
    messageSizeHistogram = metricFactory.createHistogram("Message", "Size", false);
    failureDueToUnconnected = metricFactory.createCounter("Send.Attempts.Failures", "UnableToConnect");
  }

  private boolean singleConnect(final ProducerConfig config) {
    try {
      producer = new Producer<>(config);
      connected = true;
      logger.info("Connected to kafka. for destination " + this.kafkaTopicDeliveryEndPoint.getName());
      return true;
    } catch (final Exception e) {
      logger.error("Failed to connect to kafka. (topic name : " + kafkaTopicDeliveryEndPoint.getTopicName() + " )", e);
      return false;
    }
  }

  private void connectToKafka() {
    connected = singleConnect(customConfiguration);
    if (!connected) {
      logger.warn("Failed attempting connection to kafka (" + customConfiguration.toString() + ").");
      connectionTimer.schedule(new TimerTask() {
        @Override
        public void run() {
          final Thread myThread = Thread.currentThread();
          final String prevName = myThread.getName();
          myThread.setName("Connecting to kafka " + System.currentTimeMillis());
          try {
            connectToKafka();
          } finally {
            myThread.setName(prevName);
          }
        }
      }, TEN_SECONDS);
    }
  }

  private static void validate(final Properties properties) {

    final List<String> knownPropertyNames = Lists.newArrayList("serializer.class",
                                                               "partitioner.class",
                                                               "producer.type",
                                                               "broker.list",
                                                               "zk.connect",
                                                               "buffer.size",
                                                               "connect.timeout.ms",
                                                               "socket.timeout.ms",
                                                               "reconnect.interval",
                                                               "reconnect.time.interval.ms",
                                                               "max.message.size",
                                                               "compression.codec",
                                                               "compressed.topics",
                                                               "zk.read.num.retries",
                                                               "queue.time",
                                                               "queue.size",
                                                               "batch.size",
                                                               "event.handler",
                                                               "event.handler.props",
                                                               "callback.handler",
                                                               "callback.handler.props");

    final Predicate<Object> unknownPropertyName = new Predicate<Object>() {
      @Override
      public boolean apply(final Object propertyName) {
        return !knownPropertyNames.contains(propertyName);
      }
    };

    if (Iterables.any(properties.keySet(), unknownPropertyName)) {
      final Iterable<Object> unknownProperties = Iterables.filter(properties.keySet(), unknownPropertyName);
      final String unknownPropertiesString = Joiner.on(",").join(unknownProperties);

      throw new IllegalArgumentException("Unknown properties: " + unknownPropertiesString);
    }
  }

  protected ProducerConfig getProducerConfig() {

    final Properties producerProperties = (Properties) kafkaTopicDeliveryEndPoint.getProperties().clone();

    validate(producerProperties);

    if (producerProperties.getProperty("serializer.class") != null) {
      logger.warn(
              "serializerClass cannot be provided as producer properties. Overriding manually to be the correct serialization type.");
    }

    producerProperties.setProperty("serializer.class", "kafka.serializer.DefaultEncoder");

    producerProperties.setProperty("zk.connect", kafkaTopicDeliveryEndPoint.getZkConnect());

    producerProperties.setProperty("batch.size", Integer.toString(kafkaTopicDeliveryEndPoint.getBatchSize()));

    return new ProducerConfig(producerProperties);

  }

  protected abstract ProducerConfig customizeConfig(ProducerConfig config);

  protected abstract TPayload convertInputToSendingFormat(TInput input);

  protected abstract int getPayloadSize(TPayload payload);

  protected void validateConfiguration(final ProducerConfig config) {

  }

  private void internalSend(final TInput data, final String key) {
    if (!connected) {
      failureDueToUnconnected.inc();
      return;
    }
    final long startTime = System.currentTimeMillis();
    try {
      final TPayload transportPayload = convertInputToSendingFormat(data);

      if (key != null) {
        final ProducerData<String, TPayload> messageData =
                new ProducerData<>(kafkaTopicDeliveryEndPoint.getTopicName(),
                                   key,
                                   Collections.singletonList(transportPayload));
        producer.send(messageData);
      } else {
        final ProducerData<String, TPayload> messageData =
                new ProducerData<>(kafkaTopicDeliveryEndPoint.getTopicName(),
                                   transportPayload);
        producer.send(messageData);
      }

      final int size = getPayloadSize(transportPayload);

      final long duration = System.currentTimeMillis() - startTime;
      messageLengthCounter.inc(size);
      messageSizeHistogram.update(size);
      sendCount.inc();
      sendDuration.inc(duration);
    } catch (final Exception e) {
      final long duration = System.currentTimeMillis() - startTime;
      failureDuration.inc(duration);
      if ((e instanceof QueueFullException)) {
        metricFactory.createCounter("Send.Attempts.Failures", QueueFullException.class.getSimpleName()).inc();
      } else {
        metricFactory.createCounter("Send.Attempts.Failures", e.getClass().getSimpleName()).inc();
        logger.error("Error while sending message to kafka.", e);
      }
    }
  }

  @Override
  public void send(final TInput data) throws SilentSenderException {
    internalSend(data, null);
  }

  @Override
  public void send(final TInput data, final String key) throws SilentSenderException {
    internalSend(data, key);
  }

  @Override
  public String getName() {
    return kafkaTopicDeliveryEndPoint.getName();
  }

  public void close() {
    if (connected) {
      if (producer != null) {
        try {
          producer.close();
        } catch (final Exception e) {
          logger.info("Could not close producer. Continuing", e);
        } finally {
          connected = false;
        }
      }
    }
  }
}
