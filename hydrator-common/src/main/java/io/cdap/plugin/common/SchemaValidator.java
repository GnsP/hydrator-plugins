/*
 * Copyright © 2016-2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.common;

import com.google.common.base.Strings;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.format.UnexpectedFormatException;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Array;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Utility class for with methods for validating schema fields and its types,
 * validating fields are simple, checking if schema is subset of another, checking if fields are present in schema, etc
 */
public final class SchemaValidator {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaValidator.class);

  /**
   * Validate output schema fields and if input schema is present,
   * check if output schema is a subset of the input schema and return output schema.
   * @param outputSchemaString output schema from config
   * @param rowKeyField row key field from config
   * @param pipelineConfigurer Pipelineconfigurer that's used to get input schema and set output schema.
   * @return Schema - output schema
   */
  @Nullable
  public static Schema validateOutputSchemaAndInputSchemaIfPresent(String outputSchemaString, String rowKeyField,
                                                                   PipelineConfigurer pipelineConfigurer) {
    Schema inputSchema = pipelineConfigurer.getStageConfigurer().getInputSchema();
    FailureCollector collector = pipelineConfigurer.getStageConfigurer().getFailureCollector();
    if (inputSchema == null && Strings.isNullOrEmpty(outputSchemaString)) {
       return null;
    }

    // initialize output schema if present; otherwise, set it to input schema
    Schema outputSchema;
    if (outputSchemaString == null) {
      outputSchema = inputSchema;
    } else {
      try {
        outputSchema = Schema.parseJson(outputSchemaString);
      } catch (IOException e) {
        collector.addFailure("Invalid schema : " + e.getMessage(), null)
          .withConfigProperty("schema");
        throw collector.getOrThrowException();
      }
    }

    // check that all fields in output schema are simple
    for (Schema.Field field : outputSchema.getFields()) {
      // simple type check for fields
      Schema nonNullableSchema = field.getSchema().isNullable() ? field.getSchema().getNonNullable() :
        field.getSchema();
      if (!nonNullableSchema.isSimpleOrNullableSimple()) {
        collector.addFailure(
          String.format("Field '%s' is of unexpected type '%s'.", field.getName(), nonNullableSchema.getDisplayName()),
          "Supported types are : boolean, int, long, float, double, bytes, string.")
          .withOutputSchemaField(field.getName())
          .withInputSchemaField(field.getName());
      }
    }

    if (inputSchema != null) {
      // check if output schema is a subset of input schema and check if rowkey field is present in input schema
      validateOutputSchemaIsSubsetOfInputSchema(inputSchema, outputSchema, collector);
    }
    return outputSchema;
  }

  /**
   * Checks that all the fields in output schema is part of input schema and the fields schema type matches.
   *
   * @param inputSchema input schema
   * @param outputSchema output schema
   */
  public static void validateOutputSchemaIsSubsetOfInputSchema(Schema inputSchema, Schema outputSchema) {
    // check if input schema contains all the fields expected in the output schema
    for (Schema.Field field : outputSchema.getFields()) {
      if (inputSchema.getField(field.getName()) == null) {
        throw new IllegalArgumentException(
          String.format("Field '%s' is present in output schema but not present in input schema",
                        field.getName()));
      } else if (!inputSchema.getField(field.getName()).getSchema().equals(field.getSchema())) {
        throw new IllegalArgumentException(
          String.format("Field type mismatch, field '%s' type in input schema is %s, " +
                          "while in output schema its of type %s", field.getName(),
                        inputSchema.getField(field.getName()).getSchema(), field.getSchema()));
      }
    }
  }

  /**
   * Checks that all the fields in output schema is part of input schema and the fields schema type matches.
   *
   * @param inputSchema input schema
   * @param outputSchema output schema
   * @param collector collects validation failures
   */
  public static void validateOutputSchemaIsSubsetOfInputSchema(Schema inputSchema, Schema outputSchema,
                                                               FailureCollector collector) {
    // check if input schema contains all the fields expected in the output schema
    for (Schema.Field field : outputSchema.getFields()) {
      String fieldName = field.getName();
      if (inputSchema.getField(fieldName) == null) {
        collector.addFailure(String.format("Field '%s' is present in output schema but not present in input schema.",
                                           fieldName), null).withOutputSchemaField(fieldName);
        continue;
      }

      Schema inFieldSchema = inputSchema.getField(fieldName).getSchema();
      inFieldSchema = inFieldSchema.isNullable() ? inFieldSchema.getNonNullable() : inFieldSchema;
      Schema fieldSchema = field.getSchema().isNullable() ? field.getSchema().getNonNullable() : field.getSchema();

      if (!inFieldSchema.equals(fieldSchema)) {
        collector.addFailure(
          String.format("Field '%s' has type mismatch with input schema type '%s'.",
                        fieldName, inFieldSchema.getDisplayName()), "Change type to match input schema type.")
          .withOutputSchemaField(fieldName).withInputSchemaField(fieldName);
      }
    }
  }

  /**
   * Iterates through the schema fields and checks their type is simple
   * @param schema
   */
  public static void validateSchemaFieldsAreSimple(Schema schema) {
    for (Schema.Field field : schema.getFields()) {
      // simple type check for fields
      if (!field.getSchema().isSimpleOrNullableSimple()) {
        throw new IllegalArgumentException(
          String.format("Field '%s' is not of simple type, All fields for table sink should of simple type",
                        field.getName()));
      }
    }
  }

  /**
   * Iterates through the required fields and checks if they are present in the schema
   * @param schema
   * @param requiredFields
   */
  public static void validateFieldsArePresentInSchema(Schema schema, String... requiredFields) {
    for (String field : requiredFields) {
      if (schema.getField(field) == null) {
        throw new IllegalArgumentException(
          String.format("Field : '%s' is not present in the input schema",  field));
      }
    }
  }

  /**
   * Checks whether FLL can be recorded for the given schema.
   *
   * @param schema the schema to be checked
   * @param name the name of the schema
   * @return true if the schema is not null and FLL can be recorded else false
   */
  public static boolean canRecordLineage(@Nullable Schema schema, String name) {
    if (schema == null) {
      LOG.debug(String.format("The %s schema is null. Field level lineage will not be recorded", name));
      return false;
    }
    if (schema.getFields() == null) {
      LOG.debug(String.format("The %s schema fields are null. Field level lineage will not be recorded", name));
      return false;
    }
    return true;
  }

  /**
   * Validates if the value is in ISO 8601 format without zone if schema is of type datetime logical type.
   * Recursively validates if schema is a complex type.
   *
   * @param schema    @link Schema}
   * @param fieldName String name of the field
   * @param value     Object value of the field
   * @throws io.cdap.cdap.api.data.format.UnexpectedFormatException if the format is invalid
   */
  public static void validateDateTimeField(Schema schema, String fieldName, Object value) {
    validateDateTimeField(schema, fieldName, value, new HashSet<>());
  }

  private static void validateDateTimeField(Schema schema, String fieldName, Object value, Set<String> knownRecords) {
    if (schema.getLogicalType() == Schema.LogicalType.DATETIME) {
      try {
        LocalDateTime.parse(value.toString());
      } catch (DateTimeParseException exception) {
        throw new UnexpectedFormatException(
          String
            .format("Datetime field '%s' with value '%s' is not in ISO-8601 format.", fieldName, value.toString()),
          exception);
      }
    }
    //handle non simple types
    switch (schema.getType()) {
      case UNION:
        List<Schema> unionSchemas = schema.getUnionSchemas();
        for (Schema unionSchema : unionSchemas) {
          if (unionSchema.getType() == Schema.Type.NULL) {
            continue;
          }
          //Just look for the first schema after NULL in union since there is no validation for other types
          validateDateTimeField(unionSchema, fieldName, value, knownRecords);
          break;
        }
        break;
      case RECORD:
        if (knownRecords.add(schema.getRecordName())) {
          StructuredRecord record = (StructuredRecord) value;
          schema.getFields().forEach(
            field -> validateDateTimeField(field.getSchema(), field.getName(), record.get(field.getName()),
                                           knownRecords));
        }
        break;
      case ARRAY:
        if (value.getClass().isArray()) {
          for (int index = 0; index < Array.getLength(value); index++) {
            validateDateTimeField(schema.getComponentSchema(), fieldName, Array.get(value, index), knownRecords);
          }
          break;
        }
        Collection<Object> valuesList = (Collection<Object>) value;
        valuesList.forEach(
          arrayElement -> validateDateTimeField(schema.getComponentSchema(), fieldName, arrayElement,
                                                knownRecords));
        break;
      case MAP:
        Map.Entry<Schema, Schema> mapSchema = schema.getMapSchema();
        Map<Object, Object> valueMap = (Map<Object, Object>) value;
        valueMap.entrySet().forEach(entry -> {
          validateDateTimeField(mapSchema.getKey(), fieldName, entry.getKey(), knownRecords);
          validateDateTimeField(mapSchema.getValue(), fieldName, entry.getValue(), knownRecords);
        });
        break;
      default:
        //other types can be ignored
        break;
    }
  }

  private SchemaValidator() {
    throw new AssertionError("Suppress default constructor for non-instantiability");
  }
}
