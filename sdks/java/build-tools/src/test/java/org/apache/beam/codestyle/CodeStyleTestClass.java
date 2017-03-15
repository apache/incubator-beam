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
package org.apache.beam.codestyle;

import java.util.List;

/**
 * Class to be formatted using beam-codestyle.xml to test it is configured correctly.
 */
public class CodeStyleTestClass {

  public static MyClass withSideInputs(List<?>... sideInputs) {
    return new MyClass().myMethod(sideInputs);
  }

  /**
   * Fake Class.
   */
  public static class MyClass {

    private static final String MY_CONSTANT = "";

    MyClass() {
    }

    public MyClass myMethod(List<?>... sideInputs) {
      return null;
    }
  }
}
