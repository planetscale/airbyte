/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.config.persistence;

import static io.airbyte.db.instance.configs.jooq.Tables.ACTOR_DEFINITION;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.ActorDefinition;
import io.airbyte.config.ConfigSchema;
import io.airbyte.db.instance.configs.ConfigsDatabaseInstance;
import io.airbyte.db.instance.configs.ConfigsDatabaseMigrator;
import io.airbyte.db.instance.development.DevDatabaseMigrator;
import io.airbyte.db.instance.development.MigrationDevHelper;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the {@link DatabaseConfigPersistence#updateConnectorDefinitions} method.
 */
public class DatabaseConfigPersistenceUpdateConnectorDefinitionsTest extends BaseDatabaseConfigPersistenceTest {

  private static final JsonNode SOURCE_GITHUB_JSON = Jsons.jsonNode(SOURCE_GITHUB);

  @BeforeAll
  public static void setup() throws Exception {
    database = new ConfigsDatabaseInstance(container.getUsername(), container.getPassword(), container.getJdbcUrl()).getAndInitialize();
    configPersistence = new DatabaseConfigPersistence(database);
    final ConfigsDatabaseMigrator configsDatabaseMigrator =
        new ConfigsDatabaseMigrator(database, DatabaseConfigPersistenceLoadDataTest.class.getName());
    final DevDatabaseMigrator devDatabaseMigrator = new DevDatabaseMigrator(configsDatabaseMigrator);
    MigrationDevHelper.runLastMigration(devDatabaseMigrator);
  }

  @AfterAll
  public static void tearDown() throws Exception {
    database.close();
  }

  @BeforeEach
  public void resetDatabase() throws SQLException {
    truncateAllTables();
  }

  @Test
  @DisplayName("When a connector does not exist, add it")
  public void testNewConnector() throws Exception {
    assertUpdateConnectorDefinition(
        Collections.emptyList(),
        Collections.emptyList(),
        List.of(SOURCE_GITHUB),
        Collections.singletonList(SOURCE_GITHUB));
  }

  @Test
  @DisplayName("When an old connector is in use, if it has all fields, do not update it")
  public void testOldConnectorInUseWithAllFields() throws Exception {
    final ActorDefinition currentSource = getSource().withDockerImageTag("0.0.0");
    final ActorDefinition latestSource = getSource().withDockerImageTag("0.1000.0");

    assertUpdateConnectorDefinition(
        Collections.singletonList(currentSource),
        Collections.singletonList(currentSource),
        Collections.singletonList(latestSource),
        Collections.singletonList(currentSource));
  }

  @Test
  @DisplayName("When a old connector is in use, add missing fields, do not update its version")
  public void testOldConnectorInUseWithMissingFields() throws Exception {
    final ActorDefinition currentSource = getSource().withDockerImageTag("0.0.0").withDocumentationUrl(null).withSourceType(null);
    final ActorDefinition latestSource = getSource().withDockerImageTag("0.1000.0");
    final ActorDefinition currentSourceWithNewFields = getSource().withDockerImageTag("0.0.0");

    assertUpdateConnectorDefinition(
        Collections.singletonList(currentSource),
        Collections.singletonList(currentSource),
        Collections.singletonList(latestSource),
        Collections.singletonList(currentSourceWithNewFields));
  }

  @Test
  @DisplayName("When an unused connector has a new version, update it")
  public void testUnusedConnectorWithOldVersion() throws Exception {
    final ActorDefinition currentSource = getSource().withDockerImageTag("0.0.0");
    final ActorDefinition latestSource = getSource().withDockerImageTag("0.1000.0");

    assertUpdateConnectorDefinition(
        Collections.singletonList(currentSource),
        Collections.emptyList(),
        Collections.singletonList(latestSource),
        Collections.singletonList(latestSource));
  }

  @Test
  @DisplayName("When an unused connector has missing fields, add the missing fields, do not update its version")
  public void testUnusedConnectorWithMissingFields() throws Exception {
    final ActorDefinition currentSource = getSource().withDockerImageTag("0.1000.0").withDocumentationUrl(null).withSourceType(null);
    final ActorDefinition latestSource = getSource().withDockerImageTag("0.99.0");
    final ActorDefinition currentSourceWithNewFields = getSource().withDockerImageTag("0.1000.0");

    assertUpdateConnectorDefinition(
        Collections.singletonList(currentSource),
        Collections.emptyList(),
        Collections.singletonList(latestSource),
        Collections.singletonList(currentSourceWithNewFields));
  }

  /**
   * Clone a source for modification and testing.
   */
  private ActorDefinition getSource() {
    return Jsons.object(Jsons.clone(SOURCE_GITHUB_JSON), ActorDefinition.class);
  }

  /**
   * @param currentSources all sources currently exist in the database
   * @param currentSourcesInUse a subset of currentSources; sources currently used in data syncing
   */
  private void assertUpdateConnectorDefinition(final List<ActorDefinition> currentSources,
                                               final List<ActorDefinition> currentSourcesInUse,
                                               final List<ActorDefinition> latestSources,
                                               final List<ActorDefinition> expectedUpdatedSources)
      throws Exception {
    for (final ActorDefinition source : currentSources) {
      writeSource(configPersistence, source);
    }

    for (final ActorDefinition source : currentSourcesInUse) {
      assertTrue(currentSources.contains(source), "currentSourcesInUse must exist in currentSources");
    }

    final Set<String> sourceRepositoriesInUse = currentSourcesInUse.stream()
        .map(ActorDefinition::getDockerRepository)
        .collect(Collectors.toSet());
    final Map<String, ActorDefinition> currentSourceRepositoryToInfo = currentSources.stream()
        .collect(Collectors.toMap(ActorDefinition::getDockerRepository, def -> def));

    database.transaction(ctx -> {
      try {
        configPersistence.updateConnectorDefinitions(
            ctx,
            ConfigSchema.STANDARD_SOURCE_DEFINITION,
            latestSources,
            sourceRepositoriesInUse,
            currentSourceRepositoryToInfo);
      } catch (final IOException e) {
        throw new SQLException(e);
      }
      return null;
    });

    assertRecordCount(expectedUpdatedSources.size(), ACTOR_DEFINITION);
    for (final ActorDefinition source : expectedUpdatedSources) {
      assertHasSource(source);
    }
  }

}
