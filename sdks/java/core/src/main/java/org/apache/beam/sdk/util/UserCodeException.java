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
package org.apache.beam.sdk.util;

import com.google.common.base.Strings;
import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * An exception that was thrown in user-code. Sets the stack trace
 * from the first time execution enters user code down through the
 * rest of the user's stack frames until the exception is
 * reached.
 */
public class UserCodeException extends RuntimeException {

  public static UserCodeException wrap(Throwable t) {
    return wrap("", t);
  }

  public static UserCodeException wrap(@Nullable String stepName, Throwable t) {
    if (t instanceof UserCodeException) {
      UserCodeException underlying = (UserCodeException) t;
      if (stepName == null) {
        return underlying;
      }
      return new UserCodeException(stepName, underlying.getCause());
    }
    return new UserCodeException(stepName, t);
  }

  public static RuntimeException wrapIf(boolean condition, Throwable t) {
    if (condition) {
      return wrap(t);
    }

    if (t instanceof RuntimeException) {
      return (RuntimeException) t;
    }

    return new RuntimeException(t);
  }

  private final String transformName;

  private UserCodeException(String transformName, Throwable t) {
    super(t);
    this.transformName = transformName;
    truncateStackTrace(t);
  }

  /**
   * Truncates the @{Throwable}'s stack trace to contain only user code,
   * removing all frames below.
   *
   * <p>This is to remove infrastructure noise below user code entry point. We do this
   * by finding common stack frames between the throwable's captured stack and that
   * of the current thread.
   */
  private void truncateStackTrace(Throwable t) {
    StackTraceElement[] currentStack = Thread.currentThread().getStackTrace();
    StackTraceElement[] throwableStack = t.getStackTrace();

    int currentStackSize = currentStack.length;
    int throwableStackSize = throwableStack.length;

    if (throwableStackSize == 0) {
      // Nothing to truncate.
      return;
    }

    int commonFrames = 0;
    while (framesEqual(currentStack[currentStackSize - commonFrames - 1],
        throwableStack[throwableStackSize - commonFrames - 1])) {
      commonFrames++;
      if (commonFrames >= Math.min(currentStackSize, throwableStackSize)) {
        break;
      }
    }

    StackTraceElement[] truncatedStack = Arrays.copyOfRange(throwableStack, 0,
        throwableStackSize - commonFrames);
    t.setStackTrace(truncatedStack);
  }

  public String getTransformName() {
    return transformName;
  }

  /**
   * Check if two frames are equal; Frames are considered equal if they point to the same method.
   */
  private boolean framesEqual(StackTraceElement frame1, StackTraceElement frame2) {
    boolean areEqual = Objects.equals(frame1.getClassName(), frame2.getClassName());
    areEqual &= Objects.equals(frame1.getMethodName(), frame2.getMethodName());

    return areEqual;
  }
}
