package com.cobre.notification.application.port.out;

import java.util.UUID;

/**
 * Source of primary keys. Ids are assigned by the application, never by the database or JPA.
 */
@FunctionalInterface
public interface IdGenerator {

    UUID newId();
}
