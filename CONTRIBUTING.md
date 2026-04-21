# Contributing

Thanks for contributing to scim-validator.

This repository contains the standalone SCIM validator library and its test
harness. Keep changes focused on validator behavior, bootstrap logic, release
workflow changes, or documentation that matches the live repository structure.

## Ground Rules

- Keep each change narrow and intentional.
- Do not mix unrelated refactors into spec, workflow, or documentation changes.
- Do not commit bearer tokens, workspace IDs, or environment-specific SCIM
  endpoints.
- Prefer deterministic specs and shared helpers over ad hoc request setup.

## Before You Start

1. Check for existing issues or pull requests that already cover the same work.
2. Read [README.md](./README.md) to confirm the supported run modes and configuration keys.
3. If target selection, bootstrap behavior, or environment bindings change,
   update [src/main/resources/validator-application.yml](./src/main/resources/validator-application.yml)
   and the related docs together.
4. If versioning or publishing behavior changes, keep `pom.xml`, the GitHub
   workflows, and the docs consistent.

## Working Conventions

- Add validator coverage to the existing A1-A9 layout under `src/main/groovy/de/palsoftware/scim/validator/specs`.
- Reuse `ScimBaseSpec` and `A5_BaseSpec` for request setup, cleanup, and shared assertions.
- Keep auth, capture, and console-output behavior in the base helpers instead of individual specs.
- Add or update focused unit specs under `src/test/groovy/de/palsoftware/scim/validator/base` when you change configuration, bootstrap, or capture logic.

## Validation

Validate changes before opening a PR.

Common checks:

- run `mvn test`
- run against an explicit SCIM target when behavior depends on an external
  service
- verify bootstrap changes with Docker running when Testcontainers are involved

## Pull Request Checklist

- explains the validator change and why it is needed
- updates docs and configuration when run modes or environment keys change
- keeps secrets and machine-specific values out of the diff
- avoids unrelated cleanup
- passes the relevant validation steps

## Reporting Bugs

When reporting a validator problem, include:

- the failing suite area or spec name
- whether the run used Testcontainers bootstrap or an existing SCIM service
- the relevant `SCIM_*` settings with secrets removed
- the reproduction steps and the observed response or stack trace

## Security Issues

Do not report vulnerabilities through public issues. Follow
[SECURITY.md](./SECURITY.md) instead.