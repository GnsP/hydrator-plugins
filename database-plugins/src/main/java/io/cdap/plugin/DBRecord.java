/*
 * Copyright © 2015-2019 Cask Data, Inc.
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

package io.cdap.plugin;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import io.cdap.cdap.api.common.Bytes;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.format.UnexpectedFormatException;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.plugin.common.db.DBUtils;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.lib.db.DBWritable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.rowset.serial.SerialBlob;

/**
 * Writable class for DB Source/Sink
 *
 * @see org.apache.hadoop.mapreduce.lib.db.DBInputFormat DBInputFormat
 * @see org.apache.hadoop.mapreduce.lib.db.DBOutputFormat DBOutputFormat
 * @see DBWritable DBWritable
 */
public class DBRecord implements Writable, DBWritable, Configurable, DataSizeReporter {
  private StructuredRecord record;
  private Configuration conf;
  private long bytesWritten;
  private long bytesRead;

  /**
   * Need to cache {@link ResultSetMetaData} of the record for use during writing to a table.
   * This is because we cannot rely on JDBC drivers to properly set metadata in the {@link PreparedStatement}
   * passed to the #write method in this class.
   */
  private int [] columnTypes;

  /**
   * Used to construct a DBRecord from a StructuredRecord in the ETL Pipeline
   *
   * @param record the {@link StructuredRecord} to construct the {@link DBRecord} from
   */
  public DBRecord(StructuredRecord record, int [] columnTypes) {
    this.record = record;
    this.columnTypes = columnTypes;
  }

  /**
   * Used in map-reduce. Do not remove.
   */
  @SuppressWarnings("unused")
  public DBRecord() {
  }

  public void readFields(DataInput in) throws IOException {
    // no-op, since we may never need to support a scenario where you read a DBRecord from a non-RDBMS source
  }

  /**
   * @return the {@link StructuredRecord} contained in this object
   */
  public StructuredRecord getRecord() {
    return record;
  }

  /**
   * @return the size of data written.
   */
  public long getBytesWritten() {
    return bytesWritten;
  }

  /**
   * @return the size of data read.
   */
  public long getBytesRead() {
    return bytesRead;
  }

  /**
   * Builds the {@link #record} using the specified {@link ResultSet}
   *
   * @param resultSet the {@link ResultSet} to build the {@link StructuredRecord} from
   */
  public void readFields(ResultSet resultSet) throws SQLException {
    bytesRead = 0;
    ResultSetMetaData metadata = resultSet.getMetaData();
    String outputSchemaString = conf.get(DBUtils.OVERRIDE_SCHEMA, null);
    Schema outputSchema = null;

    if (!Strings.isNullOrEmpty(outputSchemaString)) {
      try {
        outputSchema = Schema.parseJson(outputSchemaString);
      } catch (IOException e) {
        throw new IllegalArgumentException(String.format("Unable to parse schema string '%s'.", outputSchemaString), e);
      }
    }

    List<Schema.Field> originalSchema = DBUtils.getOriginalSchema(resultSet, outputSchema);
    String patternToReplace = conf.get(DBUtils.PATTERN_TO_REPLACE);
    String replaceWith = conf.get(DBUtils.REPLACE_WITH);

    // map of new name -> original name
    Map<String, String> nameMap = new HashMap<>();
    List<Schema.Field> newSchema = new ArrayList<>();
    for (Schema.Field field : originalSchema) {
      String newName = field.getName();
      if (patternToReplace != null) {
        newName = newName.replaceAll(patternToReplace, replaceWith == null ? "" : replaceWith);
      }
      nameMap.put(newName, field.getName());
      newSchema.add(Schema.Field.of(newName, field.getSchema()));
    }

    List<Schema.Field> schemaFields = DBUtils.getSchemaFields(Schema.recordOf("resultSet", newSchema),
                                                              conf.get(DBUtils.OVERRIDE_SCHEMA));
    Schema schema = Schema.recordOf("dbRecord", schemaFields);
    StructuredRecord.Builder recordBuilder = StructuredRecord.builder(schema);
    for (int i = 0; i < schemaFields.size(); i++) {
      Schema.Field field = schemaFields.get(i);
      int sqlType = metadata.getColumnType(i + 1);
      int sqlPrecision = metadata.getPrecision(i + 1);
      int sqlScale = metadata.getScale(i + 1);

      Schema outputFieldSchema = field.getSchema();
      outputFieldSchema = outputFieldSchema.isNullable() ? outputFieldSchema.getNonNullable() : outputFieldSchema;
      setField(resultSet, recordBuilder, field, sqlType, sqlPrecision, sqlScale, nameMap.getOrDefault(field.getName(),
                                                                                                      field.getName()),
               outputFieldSchema);
    }
    record = recordBuilder.build();
  }

  private void setField(ResultSet resultSet, StructuredRecord.Builder recordBuilder, Schema.Field field, int sqlType,
                        int sqlPrecision, int sqlScale, String originalName, Schema outputFieldSchema)
    throws SQLException {
    // original name has to be used to get result from result set
    Object o = DBUtils.transformValue(sqlType, sqlPrecision, sqlScale, resultSet, originalName,
                                      outputFieldSchema);
    if (o instanceof Date) {
      bytesRead += Long.BYTES;
      recordBuilder.setDate(field.getName(), ((Date) o).toLocalDate());
    } else if (o instanceof Time) {
      bytesRead += Integer.BYTES;
      recordBuilder.setTime(field.getName(), ((Time) o).toLocalTime());
    } else if (o instanceof Timestamp) {
      bytesRead += Long.BYTES;
      Instant instant = ((Timestamp) o).toInstant();
      recordBuilder.setTimestamp(field.getName(), instant.atZone(ZoneId.ofOffset("UTC", ZoneOffset.UTC)));
    } else if (o instanceof BigDecimal) {
      BigDecimal decimal = ((BigDecimal) o);
      bytesRead += decimal.unscaledValue().bitLength() / Byte.SIZE + Integer.BYTES;
      recordBuilder.setDecimal(field.getName(), decimal);
    } else if (o instanceof BigInteger) {
      Schema schema = field.getSchema();
      BigInteger bigint = ((BigInteger) o);
      if (schema.isNullable()) {
        schema = schema.getNonNullable();
      }
      if (schema.getType() == Schema.Type.LONG) {
        Long int2long = bigint.longValueExact();
        bytesRead += Long.BYTES;
        recordBuilder.set(field.getName(), int2long);
      } else {
        BigDecimal int2dec = new BigDecimal(bigint, 0);
        bytesRead += int2dec.unscaledValue().bitLength() / Byte.SIZE + Integer.BYTES;
        recordBuilder.setDecimal(field.getName(), int2dec);
      }
    } else {
      if (o != null) {
        Schema schema = field.getSchema();
        if (schema.isNullable()) {
          schema = schema.getNonNullable();
        }
        switch (schema.getType()) {
          case INT:
          case BOOLEAN:
            bytesRead += Integer.BYTES;
            break;
          case LONG:
            bytesRead += Long.BYTES;
            break;
          case DOUBLE:
            bytesRead += Double.BYTES;
            break;
          case FLOAT:
            bytesRead += Float.BYTES;
            break;
          case STRING:
            String value = (String) o;
            //make sure value is in the right format for datetime
            if (schema.getLogicalType() == Schema.LogicalType.DATETIME) {
              try {
                LocalDateTime.parse(value);
              } catch (DateTimeParseException exception) {
                throw new UnexpectedFormatException(
                  String.format("Datetime field '%s' with value '%s' is not in ISO-8601 format.", field.getName(),
                                value), exception);
              }
            }
            bytesRead += value.length();
            break;
          case BYTES:
            bytesRead += ((byte[]) o).length;
            break;
        }
      }
      recordBuilder.set(field.getName(), o);
    }
  }

  public void write(DataOutput out) throws IOException {
    Schema recordSchema = record.getSchema();
    List<Schema.Field> schemaFields = recordSchema.getFields();
    for (Schema.Field field : schemaFields) {
      writeToDataOut(out, field);
    }
  }

  /**
   * Writes the {@link #record} to the specified {@link PreparedStatement}
   *
   * @param stmt the {@link PreparedStatement} to write the {@link StructuredRecord} to
   */
  public void write(PreparedStatement stmt) throws SQLException {
    bytesWritten = 0;
    Schema recordSchema = record.getSchema();
    List<Schema.Field> schemaFields = recordSchema.getFields();
    for (int i = 0; i < schemaFields.size(); i++) {
      writeToDB(stmt, schemaFields.get(i), i);
    }
  }

  private Schema getNonNullableSchema(Schema.Field field) {
    Schema schema = field.getSchema();
    if (field.getSchema().isNullable()) {
      schema = field.getSchema().getNonNullable();
    }
    Preconditions.checkArgument(schema.getType().isSimpleType(),
                                "Only simple types are supported (boolean, int, long, float, double, string, bytes) " +
                                  "for writing a DBRecord, but found '%s' as the type for column '%s'. Please " +
                                  "remove this column or transform it to a simple type.", schema.getType(),
                                field.getName());
    return schema;
  }

  private void writeToDataOut(DataOutput out, Schema.Field field) throws IOException {
    Schema fieldSchema = getNonNullableSchema(field);
    Schema.Type fieldType = fieldSchema.getType();
    Object fieldValue = record.get(field.getName());

    if (fieldValue == null) {
      return;
    }

    switch (fieldType) {
      case NULL:
        break;
      case STRING:
        // write string appropriately
        out.writeUTF((String) fieldValue);
        break;
      case BOOLEAN:
        out.writeBoolean((Boolean) fieldValue);
        break;
      case INT:
        // write short or int appropriately
        out.writeInt((Integer) fieldValue);
        break;
      case LONG:
        // write date, timestamp or long appropriately
        out.writeLong((Long) fieldValue);
        break;
      case FLOAT:
        // both real and float are set with the same method on prepared statement
        out.writeFloat((Float) fieldValue);
        break;
      case DOUBLE:
        out.writeDouble((Double) fieldValue);
        break;
      case BYTES:
        out.write((byte[]) fieldValue);
        break;
      default:
        throw new IOException(String.format("Column %s with value %s has an unsupported datatype %s",
          field.getName(), fieldValue, fieldType));
    }
  }

  private void writeToDB(PreparedStatement stmt, Schema.Field field, int fieldIndex) throws SQLException {
    String fieldName = field.getName();
    Schema fieldSchema = getNonNullableSchema(field);
    Schema.Type fieldType = fieldSchema.getType();
    Schema.LogicalType fieldLogicalType = fieldSchema.getLogicalType();
    Object fieldValue = record.get(fieldName);
    int sqlIndex = fieldIndex + 1;

    if (fieldValue == null) {
      stmt.setNull(sqlIndex, columnTypes[fieldIndex]);
      return;
    }

    if (fieldLogicalType != null) {
      switch (fieldLogicalType) {
        case DATE:
          stmt.setDate(sqlIndex, Date.valueOf(record.getDate(fieldName)));
          bytesWritten += Long.BYTES;
          break;
        case TIME_MILLIS:
          stmt.setTime(sqlIndex, Time.valueOf(record.getTime(fieldName)));
          bytesWritten += Integer.BYTES;
          break;
        case TIME_MICROS:
          stmt.setTime(sqlIndex, Time.valueOf(record.getTime(fieldName)));
          bytesWritten += Long.BYTES;
          break;
        case TIMESTAMP_MILLIS:
        case TIMESTAMP_MICROS:
          stmt.setTimestamp(sqlIndex, Timestamp.from(record.getTimestamp(fieldName).toInstant()));
          bytesWritten += Long.BYTES;
          break;
        case DECIMAL:
          BigDecimal value = record.getDecimal(fieldName);
          stmt.setBigDecimal(sqlIndex, value);
          bytesWritten += value.unscaledValue().bitLength() / Byte.SIZE + Integer.BYTES;
          break;
        case DATETIME:
          stmt.setString(sqlIndex, (String) fieldValue);
          bytesWritten += ((String) fieldValue).length();
          break;
      }
      return;
    }

    switch (fieldType) {
      case NULL:
        stmt.setNull(sqlIndex, columnTypes[fieldIndex]);
        break;
      case STRING:
        // clob can also be written to as setString
        stmt.setString(sqlIndex, (String) fieldValue);
        bytesWritten += ((String) fieldValue).length();
        break;
      case BOOLEAN:
        stmt.setBoolean(sqlIndex, (Boolean) fieldValue);
        bytesWritten += Integer.BYTES;
        break;
      case INT:
        // write short or int appropriately
        writeInt(stmt, fieldIndex, sqlIndex, fieldValue);
        bytesWritten += Integer.BYTES;
        break;
      case LONG:
        stmt.setLong(sqlIndex, (Long) fieldValue);
        bytesWritten += Long.BYTES;
        break;
      case FLOAT:
        // both real and float are set with the same method on prepared statement
        stmt.setFloat(sqlIndex, (Float) fieldValue);
        bytesWritten += Float.BYTES;
        break;
      case DOUBLE:
        stmt.setDouble(sqlIndex, (Double) fieldValue);
        bytesWritten += Double.BYTES;
        break;
      case BYTES:
        bytesWritten += writeBytes(stmt, fieldIndex, sqlIndex, fieldValue);
        break;
      default:
        throw new SQLException(String.format("Column %s with value %s has an unsupported datatype %s",
          field.getName(), fieldValue, fieldType));
    }
  }

  private int writeBytes(PreparedStatement stmt, int fieldIndex, int sqlIndex, Object fieldValue) throws SQLException {
    byte[] byteValue = fieldValue instanceof ByteBuffer ? Bytes.toBytes((ByteBuffer) fieldValue) : (byte[]) fieldValue;
    int parameterType = columnTypes[fieldIndex];
    if (Types.BLOB == parameterType) {
      stmt.setBlob(sqlIndex, new SerialBlob(byteValue));
      return byteValue.length;
    }
    // handles BINARY, VARBINARY and LOGVARBINARY
    stmt.setBytes(sqlIndex, byteValue);
    return byteValue.length;
  }

  private void writeInt(PreparedStatement stmt, int fieldIndex, int sqlIndex, Object fieldValue) throws SQLException {
    Integer intValue = (Integer) fieldValue;
    int parameterType = columnTypes[fieldIndex];
    if (Types.TINYINT == parameterType || Types.SMALLINT == parameterType) {
      stmt.setShort(sqlIndex, intValue.shortValue());
      return;
    }
    stmt.setInt(sqlIndex, intValue);
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  @Override
  public Configuration getConf() {
    return conf;
  }
}
