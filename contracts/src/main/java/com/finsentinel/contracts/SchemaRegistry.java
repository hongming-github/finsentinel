package com.finsentinel.contracts;

import org.apache.avro.Schema;

/**
 * The few schema-registry operations the platform needs, in the Confluent REST API that Apicurio (ccompat), Confluent
 * Schema Registry and Karapace all serve: the registry stays swappable.
 */
public interface SchemaRegistry {

    /** The id under which this exact schema is registered for the subject. */
    int idOf(String subject, Schema schema) throws SchemaNotRegisteredException;

    /**
     * A registered schema by id, as written by a producer.
     *
     * @throws SchemaNotRegisteredException when the registry has no schema with this id (the message is bad); any
     *                                      other exception means the registry could not answer (retry, do not discard)
     */
    Schema schemaById(int id);

    /** Registers a schema version; the subject's compatibility rule decides whether it is accepted. */
    int register(String subject, Schema schema) throws IncompatibleSchemaException;

    void setCompatibility(String subject, String level);

    final class SchemaNotRegisteredException extends RuntimeException {
        public SchemaNotRegisteredException(String message) {
            super(message);
        }
    }

    final class IncompatibleSchemaException extends RuntimeException {
        public IncompatibleSchemaException(String message) {
            super(message);
        }
    }
}
