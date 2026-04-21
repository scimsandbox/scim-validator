# SCIM Sandbox - Validator

This repository contains the standalone SCIM 2.0 compliance suite for the SCIM
Sandbox project. It is packaged as a Maven library and can run either against
an existing SCIM endpoint or against a disposable local target bootstrapped
with Testcontainers.

## What Is In This Repo

- `src/main/groovy/de/palsoftware/scim/validator/ScimValidatorSuite.groovy`
  selects the full A1-A9 suite.
- `src/main/groovy/de/palsoftware/scim/validator/base` contains shared request
  setup, configuration loading, capture, and the `ScimBootstrapProvider` SPI.
- `src/main/groovy/de/palsoftware/scim/validator/specs` contains the compliance
  specs themselves.
- `src/main/resources/validator-application.yml` defines the default settings
  and environment-variable bindings.
- `src/test/groovy/de/palsoftware/scim/validator/ScimValidatorSuiteRunner.groovy`
  is a thin delegation class that lets Surefire invoke the suite via `mvn test`.
- `src/test/groovy/de/palsoftware/scim/validator/base/TestcontainersBootstrapProvider.groovy`
  implements `ScimBootstrapProvider` by starting disposable containers.
- `src/test/groovy/de/palsoftware/scim/validator/base/ScimValidatorEnvironment.groovy`
  manages the Testcontainers lifecycle (PostgreSQL, init, API).

## Coverage

- A1 Service discovery
- A2 Schema validation
- A3 User CRUD
- A4 PATCH operations
- A5 Filtering, pagination, and sorting
- A6 Group lifecycle
- A7 Bulk operations
- A8 Security and robustness
- A9 Negative and edge cases

## Using as a Library Dependency

All compliance specs and infrastructure are compiled into the main JAR. Add it
as a compile dependency:

```xml
<dependency>
    <groupId>de.pal-software.scim</groupId>
    <artifactId>scim-validator</artifactId>
    <version>RELEASE</version>
</dependency>
```

The JUnit Platform engines are declared as `runtime` scope and will be pulled
in transitively. Provide your own `ScimBootstrapProvider` implementation (via
`java.util.ServiceLoader`) to supply the target URL and auth token, then invoke
the suite with any JUnit Platform-compatible runner.

## Running the Suite

Run the suite from the repository root:

```bash
mvn test
```

### Mode 1 — Testcontainers (default)

When no explicit target is configured, the suite automatically starts disposable
containers (PostgreSQL, flyway init, SCIM API), seeds a workspace and a bearer
token, and runs the full spec set against them. This is the zero-config path:

The container images used are configurable (see [Configuration](#configuration)).

### Mode 2 — Existing SCIM server

Supply the SCIM base URL and a valid bearer token to run the specs against an
already-running server. No containers are started.

Using environment variables:

```bash
export SCIM_BASE_URL=http://localhost:8080/ws/<workspace-uuid>/scim/v2
export SCIM_AUTH_TOKEN=<workspace-token>
mvn test
```

Using system properties:

```bash
mvn test \
  -Dscim.baseUrl=http://localhost:8080/ws/<workspace-uuid>/scim/v2 \
  -Dscim.authToken=<workspace-token>
```

Alternatively, if you prefer to specify the API root and workspace ID
separately, the suite constructs `/ws/<workspace-uuid>/scim/v2` for you:

```bash
mvn test \
  -Dscim.apiUrl=http://localhost:8080 \
  -Dscim.workspaceId=<workspace-uuid> \
  -Dscim.authToken=<workspace-token>
```

### Skipping the suite

```bash
mvn test -Dskip.validator.tests=true
```

## Configuration

The default values come from `src/main/resources/validator-application.yml`.

| Purpose | Environment variable | System property | Default |
| --- | --- | --- | --- |
| Explicit SCIM base URL | `SCIM_BASE_URL` | `scim.baseUrl` | empty |
| API base URL used for derived mode | `SCIM_API_URL` | `scim.apiUrl` | `http://localhost:8080` |
| Workspace ID used for derived mode | `SCIM_WORKSPACE_ID` | `scim.workspaceId` | empty |
| Bearer token used by the suite | `SCIM_AUTH_TOKEN` | `scim.authToken` | empty |
| Enable automatic bootstrap | `SCIM_TESTCONTAINERS_ENABLED` | `scim.testcontainers.enabled` | `true` |
| PostgreSQL image | `SCIM_VALIDATOR_POSTGRES_IMAGE` | `scim.testcontainers.postgresImage` | `postgres:17-alpine3.23` |
| Init image | `SCIM_VALIDATOR_INIT_IMAGE` | `scim.testcontainers.initImage` | `edipal/scim-flyway-api:latest` |
| API image | `SCIM_VALIDATOR_API_IMAGE` | `scim.testcontainers.apiImage` | `edipal/scim-server-api:latest` |

`SCIM_BASE_URL` takes precedence over the derived `SCIM_API_URL` plus
`SCIM_WORKSPACE_ID` mode. If neither explicit base URL nor workspace/token is
provided, the suite falls back to bootstrap when Testcontainers is enabled.

## Development Notes

- The compliance specs and shared validator infrastructure live in
  `src/main/groovy` so the suite can be consumed as a library.
- The Testcontainers bootstrap implementation and focused unit specs live in
  `src/test/groovy` so the heavier test-only dependencies stay out of the
  published library artifact.
- `ScimBaseSpec` discovers runtime environments through
  `java.util.ServiceLoader<ScimBootstrapProvider>`.
- The base helpers capture HTTP exchanges and remove `Authorization` from the
  recorded request headers.

## Versioning

The working version lives in `pom.xml`. Pushes to `main` publish `-SNAPSHOT`
artifacts to GitHub Packages. The manual release workflow runs from `main`,
reads the current `x.y.z-SNAPSHOT` version from `pom.xml`, lets Maven Release
Plugin create the `vX.Y.Z` tag and next snapshot version, publishes the Maven
package, and creates a GitHub release with the built JAR and source bundle.

## Validation

Before merging a validator change, run `mvn test`. If the change touches
bootstrap, target selection, or exchange capture, also review the focused specs
under `src/test/groovy/de/palsoftware/scim/validator/base`.

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md).

## Security

See [SECURITY.md](./SECURITY.md).