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

package com.google.cloud.dataflow.examples;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Random;

/**
 * Base class for all end-to-end tests for Apache Beam.
 */
public abstract class E2ETest {
  /**
   * @return String with a unique test identifier based on the current date, time, and a random int.
   */
  protected String generateTestIdentifier() {
    int random = new Random().nextInt(10000);
    DateFormat dateFormat = new SimpleDateFormat("MMddHHmmss");
    Calendar cal = Calendar.getInstance();
    String now = dateFormat.format(cal.getTime());
    return now + random;
  }
}

