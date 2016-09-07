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
package org.apache.beam.sdk.transforms.reflect;

import com.google.common.reflect.TypeToken;
import java.lang.reflect.Method;
import org.apache.beam.sdk.transforms.DoFn;

/** Utilities for use in {@link DoFnSignatures} tests. */
class DoFnSignaturesTestUtils {
  /** An empty base {@link DoFn} class. */
  static class FakeDoFn extends DoFn<Integer, String> {}

  /** An error reporter. */
  static DoFnSignatures.ErrorReporter errors() {
    return new DoFnSignatures.ErrorReporter(null, "[test]");
  }

  /**
   * A class for testing utilities that take {@link Method} objects. Use like this:
   *
   * <pre>{@code
   * Method m = new AnonymousMethod() {
   *   SomeReturnValue someMethod(SomeParameters...) { ... }
   * }.getMethod();  // Will return the Method for "someMethod".
   * }</pre>
   */
  static class AnonymousMethod {
    final Method getMethod() throws Exception {
      Method[] methods = getClass().getDeclaredMethods();
      if (methods.length != 1) {
        throw new IllegalArgumentException(
            "Must declare exactly 1 method, but declares " + methods.length);
      }
      return methods[0];
    }
  }

  static DoFnSignature.ProcessElementMethod analyzeProcessElementMethod(AnonymousMethod method)
      throws Exception {
    return DoFnSignatures.analyzeProcessElementMethod(
        errors(),
        TypeToken.of(FakeDoFn.class),
        method.getMethod(),
        TypeToken.of(Integer.class),
        TypeToken.of(String.class));
  }
}
