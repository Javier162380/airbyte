/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.airbyte.commons.io.IOs;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.util.AutoCloseableIterator;
import io.airbyte.integrations.base.sentry.AirbyteSentry;
import io.airbyte.protocol.models.AirbyteConnectionStatus;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteMessage.Type;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.validation.json.JsonSchemaValidator;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts EITHER a destination or a source. Routes commands from the commandline to the appropriate
 * methods on the integration. Keeps itself DRY for methods that are common between source and
 * destination.
 */
public class IntegrationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(IntegrationRunner.class);

  private final IntegrationCliParser cliParser;
  private final Consumer<AirbyteMessage> outputRecordCollector;
  private final Integration integration;
  private final Destination destination;
  private final Source source;
  private static JsonSchemaValidator validator;

  public IntegrationRunner(final Destination destination) {
    this(new IntegrationCliParser(), Destination::defaultOutputRecordCollector, destination, null);
  }

  public IntegrationRunner(final Source source) {
    this(new IntegrationCliParser(), Destination::defaultOutputRecordCollector, null, source);
  }

  @VisibleForTesting
  IntegrationRunner(final IntegrationCliParser cliParser,
                    final Consumer<AirbyteMessage> outputRecordCollector,
                    final Destination destination,
                    final Source source) {
    Preconditions.checkState(destination != null ^ source != null, "can only pass in a destination or a source");
    this.cliParser = cliParser;
    this.outputRecordCollector = outputRecordCollector;
    // integration iface covers the commands that are the same for both source and destination.
    this.integration = source != null ? source : destination;
    this.source = source;
    this.destination = destination;
    validator = new JsonSchemaValidator();
  }

  @VisibleForTesting
  IntegrationRunner(final IntegrationCliParser cliParser,
                    final Consumer<AirbyteMessage> outputRecordCollector,
                    final Destination destination,
                    final Source source,
                    final JsonSchemaValidator jsonSchemaValidator) {
    this(cliParser, outputRecordCollector, destination, source);
    this.validator = jsonSchemaValidator;
  }

  public void run(final String[] args) throws Exception {
    initSentry();

    final IntegrationConfig parsed = cliParser.parse(args);
    final ITransaction transaction = Sentry.startTransaction(
        integration.getClass().getSimpleName(),
        parsed.getCommand().toString(),
        true);
    LOGGER.info("Sentry transaction event: {}", transaction.getEventId());
    try {
      runInternal(transaction, parsed);
      transaction.finish(SpanStatus.OK);
    } catch (final Exception e) {
      transaction.setThrowable(e);
      transaction.finish(SpanStatus.INTERNAL_ERROR);
      throw e;
    } finally {
      /*
       * This finally block may not run, probably because the container can be terminated by the worker.
       * So the transaction should always be finished in the try and catch blocks.
       */
      transaction.finish();
    }
  }

  public void runInternal(final ITransaction transaction, final IntegrationConfig parsed) throws Exception {
    LOGGER.info("Running integration: {}", integration.getClass().getName());
    LOGGER.info("Command: {}", parsed.getCommand());
    LOGGER.info("Integration config: {}", parsed);

    switch (parsed.getCommand()) {
      // common
      case SPEC -> outputRecordCollector.accept(new AirbyteMessage().withType(Type.SPEC).withSpec(integration.spec()));
      case CHECK -> {
        final JsonNode config = parseConfig(parsed.getConfigPath());
        try {
          validateConfig(integration.spec().getConnectionSpecification(), config, "CHECK");
        } catch (final Exception e) {
          // if validation fails don't throw an exception, return a failed connection check message
          outputRecordCollector.accept(new AirbyteMessage().withType(Type.CONNECTION_STATUS).withConnectionStatus(
              new AirbyteConnectionStatus().withStatus(AirbyteConnectionStatus.Status.FAILED).withMessage(e.getMessage())));
        }

        outputRecordCollector.accept(new AirbyteMessage().withType(Type.CONNECTION_STATUS).withConnectionStatus(integration.check(config)));
      }
      // source only
      case DISCOVER -> {
        final JsonNode config = parseConfig(parsed.getConfigPath());
        validateConfig(integration.spec().getConnectionSpecification(), config, "DISCOVER");
        outputRecordCollector.accept(new AirbyteMessage().withType(Type.CATALOG).withCatalog(source.discover(config)));
      }
      // todo (cgardens) - it is incongruous that that read and write return airbyte message (the
      // envelope) while the other commands return what goes inside it.
      case READ -> {
        final JsonNode config = parseConfig(parsed.getConfigPath());
        validateConfig(integration.spec().getConnectionSpecification(), config, "READ");
        final ConfiguredAirbyteCatalog catalog = parseConfig(parsed.getCatalogPath(), ConfiguredAirbyteCatalog.class);
        final Optional<JsonNode> stateOptional = parsed.getStatePath().map(IntegrationRunner::parseConfig);
        try (final AutoCloseableIterator<AirbyteMessage> messageIterator = source.read(config, catalog, stateOptional.orElse(null))) {
          AirbyteSentry.executeWithTracing("ReadSource", () -> messageIterator.forEachRemaining(outputRecordCollector::accept));
        }
      }
      // destination only
      case WRITE -> {
        final JsonNode config = parseConfig(parsed.getConfigPath());
        validateConfig(integration.spec().getConnectionSpecification(), config, "WRITE");
        final ConfiguredAirbyteCatalog catalog = parseConfig(parsed.getCatalogPath(), ConfiguredAirbyteCatalog.class);
        try (final AirbyteMessageConsumer consumer = destination.getConsumer(config, catalog, outputRecordCollector)) {
          AirbyteSentry.executeWithTracing("WriteDestination", () -> consumeWriteStream(consumer));
        }
      }
      default -> throw new IllegalStateException("Unexpected value: " + parsed.getCommand());
    }

    LOGGER.info("Completed integration: {}", integration.getClass().getName());
  }

  @VisibleForTesting
  static void consumeWriteStream(final AirbyteMessageConsumer consumer) throws Exception {
    // use a Scanner that only processes new line characters to strictly abide with the
    // https://jsonlines.org/ standard
    final Scanner input = new Scanner(System.in).useDelimiter("[\r\n]+");
    consumer.start();
    while (input.hasNext()) {
      final String inputString = input.next();
      final Optional<AirbyteMessage> messageOptional = Jsons.tryDeserialize(inputString, AirbyteMessage.class);
      if (messageOptional.isPresent()) {
        consumer.accept(messageOptional.get());
      } else {
        LOGGER.error("Received invalid message: " + inputString);
      }
    }
  }

  private static void validateConfig(final JsonNode schemaJson, final JsonNode objectJson, final String operationType) throws Exception {
    final Set<String> validationResult = validator.validate(schemaJson, objectJson);
    if (!validationResult.isEmpty()) {
      throw new Exception(String.format("Verification error(s) occurred for %s. Errors: %s ",
          operationType, validationResult));
    }
  }

  private static JsonNode parseConfig(final Path path) {
    return Jsons.deserialize(IOs.readFile(path));
  }

  private static <T> T parseConfig(final Path path, final Class<T> klass) {
    final JsonNode jsonNode = parseConfig(path);
    return Jsons.object(jsonNode, klass);
  }

  private static void initSentry() {
    final Map<String, String> env = System.getenv();
    final String connector = env.getOrDefault("APPLICATION", "unknown");
    final String version = env.getOrDefault("APPLICATION_VERSION", "unknown");
    final boolean enableSentry = Boolean.parseBoolean(env.getOrDefault("ENABLE_SENTRY", "false"));

    // https://docs.sentry.io/platforms/java/configuration/
    Sentry.init(options -> {
      options.setDsn(enableSentry ? env.getOrDefault("SENTRY_DSN", "") : "");
      options.setEnableExternalConfiguration(true);
      options.setTracesSampleRate(enableSentry ? 1.0 : 0.0);
      options.setRelease(String.format("%s@%s", connector, version));
      options.setTag("connector", connector);
      options.setTag("connector_version", version);
    });
  }

}
