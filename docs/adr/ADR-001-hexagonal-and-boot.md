# ADR-001 - Hexagonal architecture and Spring Boot 4.1

**Status:** Accepted
**Date:** 2026-09-23

## Context

The challenge requires hexagonal architecture and Java Spring Boot. The repo already targeted
Boot **4.1.1** / Java 21. Boot 4 brings Jackson 3, Spring Kafka 4 and Testcontainers 2.

## Decision

Keep Boot 4.1.1 after a Fase 0 timebox (context, Flyway, PostgreSQL + Kafka Testcontainers).
Packages: `domain` and `application` stay free of Spring, JPA, Kafka, Jackson and `java.net.http`.
Use cases are invoked only through inbound ports; adapters implement outbound ports. JPA entities
never leave the persistence adapter.

## Consequences

Imports and JSON APIs differ from Boot 3 (`tools.jackson.*`). Springdoc was checked in Fase 0 and
kept. Tests use Testcontainers 2, not H2.

## Alternatives

- Boot 3.5.x: the documented cut if the timebox failed. It did not.
- Framework types in the domain: faster to write, harder to test and to defend as hexagonal.
