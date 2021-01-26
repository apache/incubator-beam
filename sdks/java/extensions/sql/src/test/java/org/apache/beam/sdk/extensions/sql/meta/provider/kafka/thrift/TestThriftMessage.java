/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.sql.meta.provider.kafka.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(
    value = "Autogenerated by Thrift Compiler (0.13.0)",
    date = "2020-12-20")
public class TestThriftMessage
    implements org.apache.thrift.TBase<TestThriftMessage, TestThriftMessage._Fields>,
        java.io.Serializable,
        Cloneable,
        Comparable<TestThriftMessage> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC =
      new org.apache.thrift.protocol.TStruct("TestThriftMessage");

  private static final org.apache.thrift.protocol.TField F_LONG_FIELD_DESC =
      new org.apache.thrift.protocol.TField(
          "f_long", org.apache.thrift.protocol.TType.I64, (short) 1);
  private static final org.apache.thrift.protocol.TField F_INT_FIELD_DESC =
      new org.apache.thrift.protocol.TField(
          "f_int", org.apache.thrift.protocol.TType.I32, (short) 2);
  private static final org.apache.thrift.protocol.TField F_DOUBLE_FIELD_DESC =
      new org.apache.thrift.protocol.TField(
          "f_double", org.apache.thrift.protocol.TType.DOUBLE, (short) 3);
  private static final org.apache.thrift.protocol.TField F_STRING_FIELD_DESC =
      new org.apache.thrift.protocol.TField(
          "f_string", org.apache.thrift.protocol.TType.STRING, (short) 4);
  private static final org.apache.thrift.protocol.TField F_DOUBLE_ARRAY_FIELD_DESC =
      new org.apache.thrift.protocol.TField(
          "f_double_array", org.apache.thrift.protocol.TType.LIST, (short) 5);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY =
      new TestThriftMessageStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY =
      new TestThriftMessageTupleSchemeFactory();

  private long f_long; // required
  private int f_int; // required
  private double f_double; // required
  private @org.apache.thrift.annotation.Nullable java.lang.String f_string; // required
  private @org.apache.thrift.annotation.Nullable java.util.List<java.lang.Double>
      f_double_array; // required

  /**
   * The set of fields this struct contains, along with convenience methods for finding and
   * manipulating them.
   */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    F_LONG((short) 1, "f_long"),
    F_INT((short) 2, "f_int"),
    F_DOUBLE((short) 3, "f_double"),
    F_STRING((short) 4, "f_string"),
    F_DOUBLE_ARRAY((short) 5, "f_double_array");

    private static final java.util.Map<java.lang.String, _Fields> byName =
        new java.util.HashMap<java.lang.String, _Fields>();

    static {
      for (_Fields field : java.util.EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /** Find the _Fields constant that matches fieldId, or null if its not found. */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByThriftId(int fieldId) {
      switch (fieldId) {
        case 1: // F_LONG
          return F_LONG;
        case 2: // F_INT
          return F_INT;
        case 3: // F_DOUBLE
          return F_DOUBLE;
        case 4: // F_STRING
          return F_STRING;
        case 5: // F_DOUBLE_ARRAY
          return F_DOUBLE_ARRAY;
        default:
          return null;
      }
    }

    /** Find the _Fields constant that matches fieldId, throwing an exception if it is not found. */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null)
        throw new java.lang.IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /** Find the _Fields constant that matches name, or null if its not found. */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByName(java.lang.String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final java.lang.String _fieldName;

    _Fields(short thriftId, java.lang.String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public java.lang.String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final int __F_LONG_ISSET_ID = 0;
  private static final int __F_INT_ISSET_ID = 1;
  private static final int __F_DOUBLE_ISSET_ID = 2;
  private byte __isset_bitfield = 0;
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;

  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap =
        new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(
        _Fields.F_LONG,
        new org.apache.thrift.meta_data.FieldMetaData(
            "f_long",
            org.apache.thrift.TFieldRequirementType.REQUIRED,
            new org.apache.thrift.meta_data.FieldValueMetaData(
                org.apache.thrift.protocol.TType.I64)));
    tmpMap.put(
        _Fields.F_INT,
        new org.apache.thrift.meta_data.FieldMetaData(
            "f_int",
            org.apache.thrift.TFieldRequirementType.REQUIRED,
            new org.apache.thrift.meta_data.FieldValueMetaData(
                org.apache.thrift.protocol.TType.I32)));
    tmpMap.put(
        _Fields.F_DOUBLE,
        new org.apache.thrift.meta_data.FieldMetaData(
            "f_double",
            org.apache.thrift.TFieldRequirementType.REQUIRED,
            new org.apache.thrift.meta_data.FieldValueMetaData(
                org.apache.thrift.protocol.TType.DOUBLE)));
    tmpMap.put(
        _Fields.F_STRING,
        new org.apache.thrift.meta_data.FieldMetaData(
            "f_string",
            org.apache.thrift.TFieldRequirementType.REQUIRED,
            new org.apache.thrift.meta_data.FieldValueMetaData(
                org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(
        _Fields.F_DOUBLE_ARRAY,
        new org.apache.thrift.meta_data.FieldMetaData(
            "f_double_array",
            org.apache.thrift.TFieldRequirementType.REQUIRED,
            new org.apache.thrift.meta_data.ListMetaData(
                org.apache.thrift.protocol.TType.LIST,
                new org.apache.thrift.meta_data.FieldValueMetaData(
                    org.apache.thrift.protocol.TType.DOUBLE))));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(
        TestThriftMessage.class, metaDataMap);
  }

  public TestThriftMessage() {
    this.f_double_array = new java.util.ArrayList<java.lang.Double>();
  }

  public TestThriftMessage(
      long f_long,
      int f_int,
      double f_double,
      java.lang.String f_string,
      java.util.List<java.lang.Double> f_double_array) {
    this();
    this.f_long = f_long;
    setFLongIsSet(true);
    this.f_int = f_int;
    setFIntIsSet(true);
    this.f_double = f_double;
    setFDoubleIsSet(true);
    this.f_string = f_string;
    this.f_double_array = f_double_array;
  }

  /** Performs a deep copy on <i>other</i>. */
  public TestThriftMessage(TestThriftMessage other) {
    __isset_bitfield = other.__isset_bitfield;
    this.f_long = other.f_long;
    this.f_int = other.f_int;
    this.f_double = other.f_double;
    if (other.isSetFString()) {
      this.f_string = other.f_string;
    }
    if (other.isSetFDoubleArray()) {
      java.util.List<java.lang.Double> __this__f_double_array =
          new java.util.ArrayList<java.lang.Double>(other.f_double_array);
      this.f_double_array = __this__f_double_array;
    }
  }

  public TestThriftMessage deepCopy() {
    return new TestThriftMessage(this);
  }

  @Override
  public void clear() {
    setFLongIsSet(false);
    this.f_long = 0;
    setFIntIsSet(false);
    this.f_int = 0;
    setFDoubleIsSet(false);
    this.f_double = 0.0;
    this.f_string = null;
    this.f_double_array = new java.util.ArrayList<java.lang.Double>();
  }

  public long getFLong() {
    return this.f_long;
  }

  public TestThriftMessage setFLong(long f_long) {
    this.f_long = f_long;
    setFLongIsSet(true);
    return this;
  }

  public void unsetFLong() {
    __isset_bitfield =
        org.apache.thrift.EncodingUtils.clearBit(__isset_bitfield, __F_LONG_ISSET_ID);
  }

  /** Returns true if field f_long is set (has been assigned a value) and false otherwise */
  public boolean isSetFLong() {
    return org.apache.thrift.EncodingUtils.testBit(__isset_bitfield, __F_LONG_ISSET_ID);
  }

  public void setFLongIsSet(boolean value) {
    __isset_bitfield =
        org.apache.thrift.EncodingUtils.setBit(__isset_bitfield, __F_LONG_ISSET_ID, value);
  }

  public int getFInt() {
    return this.f_int;
  }

  public TestThriftMessage setFInt(int f_int) {
    this.f_int = f_int;
    setFIntIsSet(true);
    return this;
  }

  public void unsetFInt() {
    __isset_bitfield = org.apache.thrift.EncodingUtils.clearBit(__isset_bitfield, __F_INT_ISSET_ID);
  }

  /** Returns true if field f_int is set (has been assigned a value) and false otherwise */
  public boolean isSetFInt() {
    return org.apache.thrift.EncodingUtils.testBit(__isset_bitfield, __F_INT_ISSET_ID);
  }

  public void setFIntIsSet(boolean value) {
    __isset_bitfield =
        org.apache.thrift.EncodingUtils.setBit(__isset_bitfield, __F_INT_ISSET_ID, value);
  }

  public double getFDouble() {
    return this.f_double;
  }

  public TestThriftMessage setFDouble(double f_double) {
    this.f_double = f_double;
    setFDoubleIsSet(true);
    return this;
  }

  public void unsetFDouble() {
    __isset_bitfield =
        org.apache.thrift.EncodingUtils.clearBit(__isset_bitfield, __F_DOUBLE_ISSET_ID);
  }

  /** Returns true if field f_double is set (has been assigned a value) and false otherwise */
  public boolean isSetFDouble() {
    return org.apache.thrift.EncodingUtils.testBit(__isset_bitfield, __F_DOUBLE_ISSET_ID);
  }

  public void setFDoubleIsSet(boolean value) {
    __isset_bitfield =
        org.apache.thrift.EncodingUtils.setBit(__isset_bitfield, __F_DOUBLE_ISSET_ID, value);
  }

  @org.apache.thrift.annotation.Nullable
  public java.lang.String getFString() {
    return this.f_string;
  }

  public TestThriftMessage setFString(
      @org.apache.thrift.annotation.Nullable java.lang.String f_string) {
    this.f_string = f_string;
    return this;
  }

  public void unsetFString() {
    this.f_string = null;
  }

  /** Returns true if field f_string is set (has been assigned a value) and false otherwise */
  public boolean isSetFString() {
    return this.f_string != null;
  }

  public void setFStringIsSet(boolean value) {
    if (!value) {
      this.f_string = null;
    }
  }

  public int getFDoubleArraySize() {
    return (this.f_double_array == null) ? 0 : this.f_double_array.size();
  }

  @org.apache.thrift.annotation.Nullable
  public java.util.Iterator<java.lang.Double> getFDoubleArrayIterator() {
    return (this.f_double_array == null) ? null : this.f_double_array.iterator();
  }

  public void addToFDoubleArray(double elem) {
    if (this.f_double_array == null) {
      this.f_double_array = new java.util.ArrayList<java.lang.Double>();
    }
    this.f_double_array.add(elem);
  }

  @org.apache.thrift.annotation.Nullable
  public java.util.List<java.lang.Double> getFDoubleArray() {
    return this.f_double_array;
  }

  public TestThriftMessage setFDoubleArray(
      @org.apache.thrift.annotation.Nullable java.util.List<java.lang.Double> f_double_array) {
    this.f_double_array = f_double_array;
    return this;
  }

  public void unsetFDoubleArray() {
    this.f_double_array = null;
  }

  /** Returns true if field f_double_array is set (has been assigned a value) and false otherwise */
  public boolean isSetFDoubleArray() {
    return this.f_double_array != null;
  }

  public void setFDoubleArrayIsSet(boolean value) {
    if (!value) {
      this.f_double_array = null;
    }
  }

  public void setFieldValue(
      _Fields field, @org.apache.thrift.annotation.Nullable java.lang.Object value) {
    switch (field) {
      case F_LONG:
        if (value == null) {
          unsetFLong();
        } else {
          setFLong((java.lang.Long) value);
        }
        break;

      case F_INT:
        if (value == null) {
          unsetFInt();
        } else {
          setFInt((java.lang.Integer) value);
        }
        break;

      case F_DOUBLE:
        if (value == null) {
          unsetFDouble();
        } else {
          setFDouble((java.lang.Double) value);
        }
        break;

      case F_STRING:
        if (value == null) {
          unsetFString();
        } else {
          setFString((java.lang.String) value);
        }
        break;

      case F_DOUBLE_ARRAY:
        if (value == null) {
          unsetFDoubleArray();
        } else {
          setFDoubleArray((java.util.List<java.lang.Double>) value);
        }
        break;
    }
  }

  @org.apache.thrift.annotation.Nullable
  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
      case F_LONG:
        return getFLong();

      case F_INT:
        return getFInt();

      case F_DOUBLE:
        return getFDouble();

      case F_STRING:
        return getFString();

      case F_DOUBLE_ARRAY:
        return getFDoubleArray();
    }
    throw new java.lang.IllegalStateException();
  }

  /**
   * Returns true if field corresponding to fieldID is set (has been assigned a value) and false
   * otherwise
   */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
      case F_LONG:
        return isSetFLong();
      case F_INT:
        return isSetFInt();
      case F_DOUBLE:
        return isSetFDouble();
      case F_STRING:
        return isSetFString();
      case F_DOUBLE_ARRAY:
        return isSetFDoubleArray();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that == null) return false;
    if (that instanceof TestThriftMessage) return this.equals((TestThriftMessage) that);
    return false;
  }

  public boolean equals(TestThriftMessage that) {
    if (that == null) return false;
    if (this == that) return true;

    boolean this_present_f_long = true;
    boolean that_present_f_long = true;
    if (this_present_f_long || that_present_f_long) {
      if (!(this_present_f_long && that_present_f_long)) return false;
      if (this.f_long != that.f_long) return false;
    }

    boolean this_present_f_int = true;
    boolean that_present_f_int = true;
    if (this_present_f_int || that_present_f_int) {
      if (!(this_present_f_int && that_present_f_int)) return false;
      if (this.f_int != that.f_int) return false;
    }

    boolean this_present_f_double = true;
    boolean that_present_f_double = true;
    if (this_present_f_double || that_present_f_double) {
      if (!(this_present_f_double && that_present_f_double)) return false;
      if (this.f_double != that.f_double) return false;
    }

    boolean this_present_f_string = true && this.isSetFString();
    boolean that_present_f_string = true && that.isSetFString();
    if (this_present_f_string || that_present_f_string) {
      if (!(this_present_f_string && that_present_f_string)) return false;
      if (!this.f_string.equals(that.f_string)) return false;
    }

    boolean this_present_f_double_array = true && this.isSetFDoubleArray();
    boolean that_present_f_double_array = true && that.isSetFDoubleArray();
    if (this_present_f_double_array || that_present_f_double_array) {
      if (!(this_present_f_double_array && that_present_f_double_array)) return false;
      if (!this.f_double_array.equals(that.f_double_array)) return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + org.apache.thrift.TBaseHelper.hashCode(f_long);

    hashCode = hashCode * 8191 + f_int;

    hashCode = hashCode * 8191 + org.apache.thrift.TBaseHelper.hashCode(f_double);

    hashCode = hashCode * 8191 + ((isSetFString()) ? 131071 : 524287);
    if (isSetFString()) hashCode = hashCode * 8191 + f_string.hashCode();

    hashCode = hashCode * 8191 + ((isSetFDoubleArray()) ? 131071 : 524287);
    if (isSetFDoubleArray()) hashCode = hashCode * 8191 + f_double_array.hashCode();

    return hashCode;
  }

  @Override
  public int compareTo(TestThriftMessage other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.valueOf(isSetFLong()).compareTo(other.isSetFLong());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetFLong()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.f_long, other.f_long);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetFInt()).compareTo(other.isSetFInt());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetFInt()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.f_int, other.f_int);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetFDouble()).compareTo(other.isSetFDouble());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetFDouble()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.f_double, other.f_double);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetFString()).compareTo(other.isSetFString());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetFString()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.f_string, other.f_string);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison =
        java.lang.Boolean.valueOf(isSetFDoubleArray()).compareTo(other.isSetFDoubleArray());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetFDoubleArray()) {
      lastComparison =
          org.apache.thrift.TBaseHelper.compareTo(this.f_double_array, other.f_double_array);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  @org.apache.thrift.annotation.Nullable
  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    scheme(iprot).read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot)
      throws org.apache.thrift.TException {
    scheme(oprot).write(oprot, this);
  }

  @Override
  public java.lang.String toString() {
    java.lang.StringBuilder sb = new java.lang.StringBuilder("TestThriftMessage(");
    boolean first = true;

    sb.append("f_long:");
    sb.append(this.f_long);
    first = false;
    if (!first) sb.append(", ");
    sb.append("f_int:");
    sb.append(this.f_int);
    first = false;
    if (!first) sb.append(", ");
    sb.append("f_double:");
    sb.append(this.f_double);
    first = false;
    if (!first) sb.append(", ");
    sb.append("f_string:");
    if (this.f_string == null) {
      sb.append("null");
    } else {
      sb.append(this.f_string);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("f_double_array:");
    if (this.f_double_array == null) {
      sb.append("null");
    } else {
      sb.append(this.f_double_array);
    }
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // alas, we cannot check 'f_long' because it's a primitive and you chose the non-beans
    // generator.
    // alas, we cannot check 'f_int' because it's a primitive and you chose the non-beans generator.
    // alas, we cannot check 'f_double' because it's a primitive and you chose the non-beans
    // generator.
    if (f_string == null) {
      throw new org.apache.thrift.protocol.TProtocolException(
          "Required field 'f_string' was not present! Struct: " + toString());
    }
    if (f_double_array == null) {
      throw new org.apache.thrift.protocol.TProtocolException(
          "Required field 'f_double_array' was not present! Struct: " + toString());
    }
    // check for sub-struct validity
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(
          new org.apache.thrift.protocol.TCompactProtocol(
              new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in)
      throws java.io.IOException, java.lang.ClassNotFoundException {
    try {
      // it doesn't seem like you should have to do this, but java serialization is wacky, and
      // doesn't call the default constructor.
      __isset_bitfield = 0;
      read(
          new org.apache.thrift.protocol.TCompactProtocol(
              new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class TestThriftMessageStandardSchemeFactory
      implements org.apache.thrift.scheme.SchemeFactory {
    public TestThriftMessageStandardScheme getScheme() {
      return new TestThriftMessageStandardScheme();
    }
  }

  private static class TestThriftMessageStandardScheme
      extends org.apache.thrift.scheme.StandardScheme<TestThriftMessage> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, TestThriftMessage struct)
        throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true) {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) {
          break;
        }
        switch (schemeField.id) {
          case 1: // F_LONG
            if (schemeField.type == org.apache.thrift.protocol.TType.I64) {
              struct.f_long = iprot.readI64();
              struct.setFLongIsSet(true);
            } else {
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // F_INT
            if (schemeField.type == org.apache.thrift.protocol.TType.I32) {
              struct.f_int = iprot.readI32();
              struct.setFIntIsSet(true);
            } else {
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // F_DOUBLE
            if (schemeField.type == org.apache.thrift.protocol.TType.DOUBLE) {
              struct.f_double = iprot.readDouble();
              struct.setFDoubleIsSet(true);
            } else {
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 4: // F_STRING
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.f_string = iprot.readString();
              struct.setFStringIsSet(true);
            } else {
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 5: // F_DOUBLE_ARRAY
            if (schemeField.type == org.apache.thrift.protocol.TType.LIST) {
              {
                org.apache.thrift.protocol.TList _list0 = iprot.readListBegin();
                struct.f_double_array = new java.util.ArrayList<java.lang.Double>(_list0.size);
                double _elem1;
                for (int _i2 = 0; _i2 < _list0.size; ++_i2) {
                  _elem1 = iprot.readDouble();
                  struct.f_double_array.add(_elem1);
                }
                iprot.readListEnd();
              }
              struct.setFDoubleArrayIsSet(true);
            } else {
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      if (!struct.isSetFLong()) {
        throw new org.apache.thrift.protocol.TProtocolException(
            "Required field 'f_long' was not found in serialized data! Struct: " + toString());
      }
      if (!struct.isSetFInt()) {
        throw new org.apache.thrift.protocol.TProtocolException(
            "Required field 'f_int' was not found in serialized data! Struct: " + toString());
      }
      if (!struct.isSetFDouble()) {
        throw new org.apache.thrift.protocol.TProtocolException(
            "Required field 'f_double' was not found in serialized data! Struct: " + toString());
      }
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, TestThriftMessage struct)
        throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      oprot.writeFieldBegin(F_LONG_FIELD_DESC);
      oprot.writeI64(struct.f_long);
      oprot.writeFieldEnd();
      oprot.writeFieldBegin(F_INT_FIELD_DESC);
      oprot.writeI32(struct.f_int);
      oprot.writeFieldEnd();
      oprot.writeFieldBegin(F_DOUBLE_FIELD_DESC);
      oprot.writeDouble(struct.f_double);
      oprot.writeFieldEnd();
      if (struct.f_string != null) {
        oprot.writeFieldBegin(F_STRING_FIELD_DESC);
        oprot.writeString(struct.f_string);
        oprot.writeFieldEnd();
      }
      if (struct.f_double_array != null) {
        oprot.writeFieldBegin(F_DOUBLE_ARRAY_FIELD_DESC);
        {
          oprot.writeListBegin(
              new org.apache.thrift.protocol.TList(
                  org.apache.thrift.protocol.TType.DOUBLE, struct.f_double_array.size()));
          for (double _iter3 : struct.f_double_array) {
            oprot.writeDouble(_iter3);
          }
          oprot.writeListEnd();
        }
        oprot.writeFieldEnd();
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }
  }

  private static class TestThriftMessageTupleSchemeFactory
      implements org.apache.thrift.scheme.SchemeFactory {
    public TestThriftMessageTupleScheme getScheme() {
      return new TestThriftMessageTupleScheme();
    }
  }

  private static class TestThriftMessageTupleScheme
      extends org.apache.thrift.scheme.TupleScheme<TestThriftMessage> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, TestThriftMessage struct)
        throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot =
          (org.apache.thrift.protocol.TTupleProtocol) prot;
      oprot.writeI64(struct.f_long);
      oprot.writeI32(struct.f_int);
      oprot.writeDouble(struct.f_double);
      oprot.writeString(struct.f_string);
      {
        oprot.writeI32(struct.f_double_array.size());
        for (double _iter4 : struct.f_double_array) {
          oprot.writeDouble(_iter4);
        }
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, TestThriftMessage struct)
        throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot =
          (org.apache.thrift.protocol.TTupleProtocol) prot;
      struct.f_long = iprot.readI64();
      struct.setFLongIsSet(true);
      struct.f_int = iprot.readI32();
      struct.setFIntIsSet(true);
      struct.f_double = iprot.readDouble();
      struct.setFDoubleIsSet(true);
      struct.f_string = iprot.readString();
      struct.setFStringIsSet(true);
      {
        org.apache.thrift.protocol.TList _list5 =
            new org.apache.thrift.protocol.TList(
                org.apache.thrift.protocol.TType.DOUBLE, iprot.readI32());
        struct.f_double_array = new java.util.ArrayList<java.lang.Double>(_list5.size);
        double _elem6;
        for (int _i7 = 0; _i7 < _list5.size; ++_i7) {
          _elem6 = iprot.readDouble();
          struct.f_double_array.add(_elem6);
        }
      }
      struct.setFDoubleArrayIsSet(true);
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(
      org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme())
            ? STANDARD_SCHEME_FACTORY
            : TUPLE_SCHEME_FACTORY)
        .getScheme();
  }
}
