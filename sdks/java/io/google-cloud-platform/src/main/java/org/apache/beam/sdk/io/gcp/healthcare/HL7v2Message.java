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
package org.apache.beam.sdk.io.gcp.healthcare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.healthcare.v1beta1.model.Message;
import com.google.api.services.healthcare.v1beta1.model.ParsedData;
import com.google.api.services.healthcare.v1beta1.model.SchematizedData;
import java.io.IOException;
import java.util.Map;

/** The type HL7v2 message to wrap the {@link Message} model. */
public class HL7v2Message {
  private String name;
  private String messageType;
  private String sendTime;
  private String createTime;
  private String data;
  private String sendFacility;
  private ParsedData parsedData;
  private String schematizedData;
  private Map<String, String> labels;

  @Override
  public String toString() {
    ObjectMapper mapper = new ObjectMapper();
    try {
      return mapper.writeValueAsString(this);
    } catch (IOException e) {
      return this.getData();
    }
  }

  public HL7v2Message() {}
  /**
   * From model {@link Message} to hl7v2 message.
   *
   * @param msg the model msg
   * @return the hl7v2 message
   */
  public static HL7v2Message fromModel(Message msg) {
    HL7v2MessageBuilder mb =
        new HL7v2MessageBuilder(
            msg.getName(),
            msg.getMessageType(),
            msg.getSendTime(),
            msg.getCreateTime(),
            msg.getData(),
            msg.getSendFacility());
    mb.parsedData = msg.getParsedData();
    mb.schematizedData =
        msg.getSchematizedData() != null ? msg.getSchematizedData().getData() : null;
    mb.labels = msg.getLabels();
    return mb.build();
  }

  /**
   * To model message.
   *
   * @return the message
   */
  public Message toModel() {
    Message out = new Message();
    out.setName(this.getName());
    out.setMessageType(this.getMessageType());
    out.setSendTime(this.getSendTime());
    out.setCreateTime(this.getCreateTime());
    out.setData(this.getData());
    out.setSendFacility(this.getSendFacility());
    if (this.getParsedData() != null) {
      out.setParsedData(this.getParsedData());
    }
    if (this.getSchematizedData() != null) {
      out.setSchematizedData(new SchematizedData().setData(this.schematizedData));
    }
    if (this.getLabels() != null) {
      out.setLabels(this.labels);
    }
    return out;
  }

  public static class HL7v2MessageBuilder {
    private final String name;
    private final String messageType;
    private final String sendTime;
    private final String createTime;
    private final String data;
    private final String sendFacility;

    private ParsedData parsedData;
    private String schematizedData;
    private Map<String, String> labels;

    public HL7v2MessageBuilder(
        String name,
        String messageType,
        String sendTime,
        String createTime,
        String data,
        String sendFacility) {
      this.name = name;
      this.messageType = messageType;
      this.sendTime = sendTime;
      this.createTime = createTime;
      this.data = data;
      this.sendFacility = sendFacility;
    }

    public HL7v2MessageBuilder setParsedData(ParsedData parsedData) {
      this.parsedData = parsedData;
      return this;
    }

    public HL7v2MessageBuilder setSchematizedData(String schematizedData) {
      this.schematizedData = schematizedData;
      return this;
    }

    public HL7v2MessageBuilder setLabels(Map<String, String> labels) {
      this.labels = labels;
      return this;
    }

    public HL7v2Message build() {
      // call the private constructor in the outer class
      return new HL7v2Message(this);
    }
  }

  public HL7v2Message(HL7v2MessageBuilder mb) {
    this.name = mb.name;
    this.messageType = mb.messageType;
    this.sendTime = mb.sendTime;
    this.createTime = mb.createTime;
    this.data = mb.data;
    this.sendFacility = mb.sendFacility;
    this.parsedData = mb.parsedData;
    this.schematizedData = mb.schematizedData;
    this.labels = mb.labels;
  }

  @Override
  public int hashCode() {
    int hash = 5;
    hash = 31 * hash + (this.name != null ? this.name.hashCode() : 0);
    hash = 31 * hash + (this.messageType != null ? this.messageType.hashCode() : 0);
    hash = 31 * hash + (this.sendTime != null ? this.sendTime.hashCode() : 0);
    hash = 31 * hash + (this.createTime != null ? this.createTime.hashCode() : 0);
    hash = 31 * hash + (this.data != null ? this.data.hashCode() : 0);
    hash = 31 * hash + (this.sendFacility != null ? this.sendFacility.hashCode() : 0);
    hash = 31 * hash + (this.messageType != null ? this.messageType.hashCode() : 0);
    hash = 31 * hash + (this.parsedData != null ? this.parsedData.hashCode() : 0);
    hash = 31 * hash + (this.schematizedData != null ? this.schematizedData.hashCode() : 0);
    hash = 31 * hash + (this.labels != null ? this.labels.hashCode() : 0);
    return hash;
  }

  // Overriding equals() to compare two HL7v2Message objects
  @Override
  public boolean equals(Object o) {

    // If the object is compared with itself then return true
    if (o == this) {
      return true;
    }

    /* Check if o is an instance of HL7v2Message or not
    "null instanceof [type]" also returns false */
    if (!(o instanceof HL7v2Message)) {
      return false;
    }

    // typecast o to HLv2Message so that we can compare data members
    HL7v2Message c = (HL7v2Message) o;

    boolean parsedDataCompare = true;
    boolean schematizedDataCompare = true;
    boolean labelsCompare = true;
    boolean messageCompare = true;

    // Compare the data members and return accordingly
    if (parsedData != null) {
      parsedDataCompare = parsedData.equals(c.parsedData);
    }
    if (schematizedData != null) {
      schematizedDataCompare = schematizedData.equals(c.schematizedData);
    }
    if (labels != null) {
      labelsCompare = labels.equals(c.labels);
    }
    messageCompare =
        name.equals(c.name)
            && messageType.equals(c.messageType)
            && sendTime.equals(c.sendTime)
            && createTime.equals(c.createTime)
            && data.equals(c.data)
            && sendFacility.equals(c.sendFacility);
    return parsedDataCompare && schematizedDataCompare && labelsCompare && messageCompare;
  }

  /**
   * Gets name.
   *
   * @return the name
   */
  public String getName() {
    return name;
  }

  /**
   * Gets message type.
   *
   * @return the message type
   */
  public String getMessageType() {
    return messageType;
  }

  /**
   * Gets send time.
   *
   * @return the send time
   */
  public String getSendTime() {
    return sendTime;
  }

  /**
   * Gets create time.
   *
   * @return the create time
   */
  public String getCreateTime() {
    return createTime;
  }

  /**
   * Gets data.
   *
   * @return the data
   */
  public String getData() {
    return data;
  }

  /**
   * Gets send facility.
   *
   * @return the send facility
   */
  public String getSendFacility() {
    return sendFacility;
  }

  /**
   * Gets parsed data.
   *
   * @return the parsed data
   */
  public ParsedData getParsedData() {
    return parsedData;
  }

  public void setParsedData(ParsedData parsedData) {
    this.parsedData = parsedData;
  }
  /**
   * Gets schematized data.
   *
   * @return the schematized data
   */
  public String getSchematizedData() {
    return schematizedData;
  }

  public void setSchematizedData(String schematizedData) {
    this.schematizedData = schematizedData;
  }
  /**
   * Gets labels.
   *
   * @return the labels
   */
  public Map<String, String> getLabels() {
    return labels;
  }
}
