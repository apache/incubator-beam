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
package org.apache.beam.sdk.testutils.publishing;

import static java.util.Objects.isNull;

import java.util.Arrays;
import java.util.Objects;

public final class InfluxDBSettings {

  public final String host;
  public final String userName;
  public final String userPassword;
  public final String measurement;
  public final String database;

  private InfluxDBSettings(
      String host, String userName, String userPassword, String measurement, String database) {
    this.host = host;
    this.userName = userName;
    this.userPassword = userPassword;
    this.measurement = measurement;
    this.database = database;
  }

  public static Builder builder() {
    return new Builder();
  }

  public InfluxDBSettings copyWithMeasurement(final String newMeasurement) {
    return new InfluxDBSettings(host, userName, userPassword, newMeasurement, database);
  }

  public static class Builder {
    private static final String DEFAULT_HOST = "http://localhost:8086/";
    private static final String INFLUX_USER = "INFLUXDB_USER";
    private static final String INFLUX_PASSWORD = "INFLUXDB_USER_PASSWORD";

    private String host;
    private String measurement;
    private String database;

    public Builder withHost(final String host) {
      this.host = host;
      return this;
    }

    public Builder withMeasurement(final String measurement) {
      this.measurement = measurement;
      return this;
    }

    public Builder withDatabase(final String database) {
      this.database = database;
      return this;
    }

    public InfluxDBSettings get() {
      final String userName = System.getenv(INFLUX_USER);
      final String userPassword = System.getenv(INFLUX_PASSWORD);
      final String influxHost = isNull(host) ? DEFAULT_HOST : host;
      allNotNull(measurement, database);

      return new InfluxDBSettings(influxHost, userName, userPassword, measurement, database);
    }

    private void allNotNull(Object... objects) {
      Arrays.stream(objects).forEach(Objects::requireNonNull);
    }
  }
}
