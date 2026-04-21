package de.palsoftware.scim.validator.base

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

@SuppressWarnings("resource")
final class ScimValidatorEnvironment {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScimValidatorEnvironment)
    private static final SecureRandom SECURE_RANDOM = new SecureRandom()

    private static Network network
    private static PostgreSQLContainer<?> postgres
    private static GenericContainer<?> init
    private static GenericContainer<?> api
    private static ScimRuntimeConfiguration runtimeConfiguration

    private ScimValidatorEnvironment() {
    }

    static synchronized ScimRuntimeConfiguration ensureStarted() {
        if (runtimeConfiguration != null) {
            return runtimeConfiguration
        }

        try {
            ValidatorConfiguration.Config configuration = ValidatorConfiguration.current()
            ValidatorConfiguration.PostgresConfig postgresConfiguration = configuration.postgres
            ValidatorConfiguration.ApiConfig apiConfiguration = configuration.api

            network = Network.newNetwork()

            postgres = new PostgreSQLContainer<>(DockerImageName.parse(configuration.postgresImage))
                .withDatabaseName(postgresConfiguration.databaseName)
                .withUsername(postgresConfiguration.username)
                .withPassword(postgresConfiguration.password)
                .withNetwork(network)
                .withNetworkAliases(postgresConfiguration.alias)
            postgres.start()

            if (hasText(configuration.initImage)) {
                init = new GenericContainer<>(DockerImageName.parse(configuration.initImage))
                    .withNetwork(network)
                    .withEnv("FLYWAY_URL", "jdbc:postgresql://${postgresConfiguration.alias}:5432/${postgresConfiguration.databaseName}")
                    .withEnv("FLYWAY_USER", postgresConfiguration.username)
                    .withEnv("FLYWAY_PASSWORD", postgresConfiguration.password)
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))
                init.start()
            }

            String actuatorApiKey = randomToken(32)
            api = new GenericContainer<>(DockerImageName.parse(configuration.apiImage))
                .withNetwork(network)
                .withExposedPorts(apiConfiguration.port)
                .withEnv("SERVER_PORT", Integer.toString(apiConfiguration.port))
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://${postgresConfiguration.alias}:5432/${postgresConfiguration.databaseName}")
                .withEnv("SPRING_DATASOURCE_USERNAME", postgresConfiguration.username)
                .withEnv("SPRING_DATASOURCE_PASSWORD", postgresConfiguration.password)
                .withEnv("SPRING_FLYWAY_ENABLED", "false")
                .withEnv("ACTUATOR_API_KEY", actuatorApiKey)
                .waitingFor(Wait.forHttp("/actuator/health")
                    .forPort(apiConfiguration.port)
                    .withHeader("X-API-KEY", actuatorApiKey)
                    .forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(3))
            api.start()

            SeededTenant seededTenant = seedTenant()
            String apiUrl = "http://${api.getHost()}:${api.getMappedPort(apiConfiguration.port)}"
            runtimeConfiguration = new ScimRuntimeConfiguration(apiUrl, seededTenant.workspaceId, seededTenant.authToken)

            Runtime.runtime.addShutdownHook(new Thread(ScimValidatorEnvironment::shutdown))
            LOGGER.info("Started validator test environment against {} with workspace {}", apiUrl, seededTenant.workspaceId)
            return runtimeConfiguration
        } catch (Exception exception) {
            shutdown()
            throw new IllegalStateException("Unable to start the SCIM validator test environment", exception)
        }
    }

    private static SeededTenant seedTenant() throws SQLException {
        UUID workspaceId = UUID.randomUUID()
        UUID tokenId = UUID.randomUUID()
        Instant now = Instant.now()
        String rawToken = randomToken(64)
        String tokenHash = sha256Hex(rawToken)
        ValidatorConfiguration.PostgresConfig postgresConfiguration = ValidatorConfiguration.current().postgres

        try (Connection connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgresConfiguration.username, postgresConfiguration.password)) {
            insertWorkspace(connection, workspaceId, now)
            insertWorkspaceToken(connection, tokenId, workspaceId, tokenHash, now)
        }

        return new SeededTenant(workspaceId.toString(), rawToken)
    }

    private static void insertWorkspace(Connection connection, UUID workspaceId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO workspaces (id, name, description, created_by_username, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setObject(1, workspaceId)
            statement.setString(2, "validator-${workspaceId.toString().substring(0, 8)}")
            statement.setString(3, "Validator test workspace")
            statement.setString(4, "validator@test.local")
            statement.setTimestamp(5, Timestamp.from(now))
            statement.setTimestamp(6, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private static void insertWorkspaceToken(Connection connection,
                                             UUID tokenId,
                                             UUID workspaceId,
                                             String tokenHash,
                                             Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO workspace_tokens (id, workspace_id, token_hash, name, description, expires_at, revoked, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setObject(1, tokenId)
            statement.setObject(2, workspaceId)
            statement.setString(3, tokenHash)
            statement.setString(4, "validator-token")
            statement.setString(5, "Validator bootstrap token")
            statement.setObject(6, null)
            statement.setBoolean(7, false)
            statement.setTimestamp(8, Timestamp.from(now))
            statement.setTimestamp(9, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private static String randomToken(int bytes) {
        byte[] randomBytes = new byte[bytes]
        SECURE_RANDOM.nextBytes(randomBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank()
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256")
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8))
            StringBuilder builder = new StringBuilder(hash.length * 2)
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16))
                builder.append(Character.forDigit(b & 0xF, 16))
            }
            return builder.toString()
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception)
        }
    }

    private static synchronized void shutdown() {
        if (api != null) {
            api.stop()
            api = null
        }
        if (init != null) {
            init.stop()
            init = null
        }
        if (postgres != null) {
            postgres.stop()
            postgres = null
        }
        if (network != null) {
            network.close()
            network = null
        }
        runtimeConfiguration = null
    }

    static final class SeededTenant {
        final String workspaceId
        final String authToken

        SeededTenant(String workspaceId, String authToken) {
            this.workspaceId = workspaceId
            this.authToken = authToken
        }
    }
}