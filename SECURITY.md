# Security Policy

## Supported Versions

This repository supports the latest code on the `main` branch and the latest
published package derived from that branch.

Because this repository was split from a larger SCIM Sandbox codebase, long-
lived maintenance branches should not be assumed.

## Reporting a Vulnerability

Do not open public GitHub issues for security vulnerabilities.

Use GitHub Security Advisories for private reporting:

1. Open the repository Security tab.
2. Select Advisories.
3. Create a new draft security advisory.
4. Include the affected files, reproduction steps, impact, and suggested
   mitigation if known.

If private reporting is unavailable, use the maintainer contact options on the
GitHub profile.

## Scope of Security Review

Security-sensitive areas in this repository include:

- target selection and bootstrap logic in `ValidatorConfiguration`,
  `ValidatorTargetConfiguration`, and `ScimBaseSpec`
- disposable target startup in `ScimValidatorEnvironment`
- SCIM auth token handling in the bootstrap path and request helpers
- request and response capture in `ScimExchangeCaptureFilter` and
  `ScimRunContext`
- environment-variable and system-property binding from
  `src/main/resources/validator-application.yml`
- release and package publishing workflow changes that affect how artifacts are
  built or distributed

## Operational Guidance

If you run the validator against a non-local environment, apply these controls
first:

1. Use HTTPS.
2. Use a dedicated test tenant and workspace token.
3. Keep bearer tokens out of source control, issue trackers, and shell history.
4. Disable Testcontainers bootstrap when you want the suite to target an
   existing environment explicitly.
5. Review captured HTTP exchanges before sharing run artifacts.
6. Rotate any token that was used against a shared or production-like system.

## Secrets Handling

- Do not commit bearer tokens, passwords, or production endpoints.
- Do not reuse local sandbox values in shared or production environments.
- Treat `SCIM_*` environment variables and `-Dscim.*` properties as secrets when
  they carry live values.
- Keep publishing credentials such as GitHub or Docker tokens in repository
  secrets, not in workflow files or local shell history.

## Current Mitigations

The suite currently includes these baseline controls:

- `ScimExchangeCaptureFilter` removes `Authorization` from recorded request
  headers.
- `ScimOutput` suppresses console output while capture is active.
- The Testcontainers bootstrap generates a random token and stores only its
  SHA-256 hash in the seeded disposable target database.
- Target selection is explicit: you must provide a base URL, or an API URL plus
  workspace ID, or intentionally leave bootstrap enabled.

## Security Testing Expectations

When changing bootstrap, configuration resolution, authentication handling,
exchange capture, or artifact publishing, validate the relevant focused specs
under `src/test/groovy/de/palsoftware/scim/validator/base` and run `mvn test`.