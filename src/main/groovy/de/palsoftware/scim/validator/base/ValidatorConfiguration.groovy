package de.palsoftware.scim.validator.base

import groovy.yaml.YamlSlurper

final class ValidatorConfiguration {

    private static final String CONFIG_RESOURCE = "/validator-application.yml"
    private static final Map<String, String> SYSTEM_PROPERTY_ALIASES = Map.of(
        "SCIM_BASE_URL", "scim.baseUrl",
        "SCIM_API_URL", "scim.apiUrl",
        "SCIM_WORKSPACE_ID", "scim.workspaceId",
        "SCIM_AUTH_TOKEN", "scim.authToken",
        "SCIM_TESTCONTAINERS_ENABLED", "scim.testcontainers.enabled",
        "SCIM_VALIDATOR_POSTGRES_IMAGE", "scim.testcontainers.postgresImage",
        "SCIM_VALIDATOR_INIT_IMAGE", "scim.testcontainers.initImage",
        "SCIM_VALIDATOR_API_IMAGE", "scim.testcontainers.apiImage"
    )
    private static final Config DEFAULT_CONFIG = load()
    private static final InheritableThreadLocal<Config> CURRENT_OVERRIDE = new InheritableThreadLocal<>()

    private ValidatorConfiguration() {
    }

    static Config current() {
        Config override = CURRENT_OVERRIDE.get()
        return override != null ? override : DEFAULT_CONFIG
    }

    static void useRunOverrides(String baseUrl, String authToken) {
        Config base = DEFAULT_CONFIG
        CURRENT_OVERRIDE.set(new Config(
            hasText(baseUrl) ? baseUrl.trim() : base.baseUrl,
            base.apiUrl,
            base.workspaceId,
            hasText(authToken) ? authToken.trim() : base.authToken,
            base.testcontainersEnabled,
            base.postgresImage,
            base.initImage,
            base.apiImage,
            base.postgres,
            base.api
        ))
    }

    static void clearRunOverrides() {
        CURRENT_OVERRIDE.remove()
    }

    private static Config load() {
        InputStream inputStream = ValidatorConfiguration.getResourceAsStream(CONFIG_RESOURCE)
        if (inputStream == null) {
            throw new IllegalStateException("Missing validator configuration resource ${CONFIG_RESOURCE}")
        }

        Map<String, Object> root = (Map<String, Object>) new YamlSlurper().parse(inputStream)
        return new Config(
            stringValue(root, "scim.base-url"),
            stringValue(root, "scim.api-url"),
            stringValue(root, "scim.workspace-id"),
            stringValue(root, "scim.auth-token"),
            booleanValue(root, "scim.testcontainers.enabled"),
            stringValue(root, "scim.testcontainers.postgres-image"),
            stringValue(root, "scim.testcontainers.init-image"),
            stringValue(root, "scim.testcontainers.api-image"),
            new PostgresConfig(
                stringValue(root, "scim.testcontainers.postgres.alias"),
                stringValue(root, "scim.testcontainers.postgres.database-name"),
                stringValue(root, "scim.testcontainers.postgres.username"),
                stringValue(root, "scim.testcontainers.postgres.password")
            ),
            new ApiConfig(intValue(root, "scim.testcontainers.api.port"))
        )
    }

    private static String stringValue(Map<String, Object> root, String path) {
        Object value = requiredValue(root, path)
        if (!(value instanceof CharSequence) && !(value instanceof Number) && !(value instanceof Boolean)) {
            throw new IllegalStateException("Unsupported configuration value for ${path}: ${value}")
        }
        return resolveValue(value.toString())
    }

    private static boolean booleanValue(Map<String, Object> root, String path) {
        String value = stringValue(root, path)
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Blank boolean configuration value for ${path}")
        }
        return Boolean.parseBoolean(value)
    }

    private static int intValue(Map<String, Object> root, String path) {
        String value = stringValue(root, path)
        try {
            return Integer.parseInt(value)
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid integer configuration value for ${path}: ${value}", exception)
        }
    }

    private static Object requiredValue(Map<String, Object> root, String path) {
        Object current = root
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map) || !((Map<?, ?>) current).containsKey(segment)) {
                throw new IllegalStateException("Missing validator configuration key ${path}")
            }
            current = ((Map<?, ?>) current).get(segment)
        }
        return current
    }

    private static String resolveValue(String rawValue) {
        if (rawValue == null) {
            return null
        }
        def matcher = rawValue =~ /^\$\{([^:}]+)(?::([^}]*))?\}$/
        if (!matcher.matches()) {
            return rawValue
        }

        String variableName = matcher.group(1)
        String defaultValue = matcher.groupCount() >= 2 ? matcher.group(2) : null
        String systemValue = System.getProperty(variableName)
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue
        }
        String aliasedSystemValue = aliasedSystemPropertyValue(variableName)
        if (aliasedSystemValue != null && !aliasedSystemValue.isBlank()) {
            return aliasedSystemValue
        }
        String environmentValue = System.getenv(variableName)
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue
        }
        return defaultValue
    }

    private static String aliasedSystemPropertyValue(String variableName) {
        String propertyName = SYSTEM_PROPERTY_ALIASES.get(variableName)
        if (propertyName == null) {
            return null
        }
        return System.getProperty(propertyName)
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank()
    }

    static final class Config {
        final String baseUrl
        final String apiUrl
        final String workspaceId
        final String authToken
        final boolean testcontainersEnabled
        final String postgresImage
        final String initImage
        final String apiImage
        final PostgresConfig postgres
        final ApiConfig api

        Config(String baseUrl,
               String apiUrl,
               String workspaceId,
               String authToken,
               boolean testcontainersEnabled,
               String postgresImage,
                    String initImage,
               String apiImage,
               PostgresConfig postgres,
               ApiConfig api) {
            this.baseUrl = baseUrl
            this.apiUrl = apiUrl
            this.workspaceId = workspaceId
            this.authToken = authToken
            this.testcontainersEnabled = testcontainersEnabled
            this.postgresImage = postgresImage
                this.initImage = initImage
            this.apiImage = apiImage
            this.postgres = postgres
            this.api = api
        }
    }

    static final class PostgresConfig {
        final String alias
        final String databaseName
        final String username
        final String password

        PostgresConfig(String alias, String databaseName, String username, String password) {
            this.alias = alias
            this.databaseName = databaseName
            this.username = username
            this.password = password
        }
    }

    static final class ApiConfig {
        final int port

        ApiConfig(int port) {
            this.port = port
        }
    }
}