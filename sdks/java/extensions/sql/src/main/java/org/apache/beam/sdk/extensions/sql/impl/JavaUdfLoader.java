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
package org.apache.beam.sdk.extensions.sql.impl;

import com.google.auto.value.AutoValue;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.ProviderNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.apache.beam.sdk.extensions.sql.udf.ScalarFn;
import org.apache.beam.sdk.extensions.sql.udf.UdfProvider;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.commons.codec.digest.DigestUtils;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.io.ByteStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@link UdfProvider} implementations from user-provided jars.
 *
 * <p>All UDFs are loaded and cached for each jar to mitigate IO costs.
 */
public class JavaUdfLoader {
  private static final Logger LOG = LoggerFactory.getLogger(JavaUdfLoader.class);

  /**
   * Maps the external jar location to the functions the jar defines. Static so it can persist
   * across multiple SQL transforms.
   */
  private static final Map<String, FunctionDefinitions> cache = new HashMap<>();

  /** Load a user-defined scalar function from the specified jar. */
  public ScalarFn loadScalarFunction(List<String> functionPath, String jarPath) {
    String functionFullName = String.join(".", functionPath);
    try {
      FunctionDefinitions functionDefinitions = loadJar(jarPath);
      if (!functionDefinitions.scalarFunctions().containsKey(functionPath)) {
        throw new IllegalArgumentException(
            String.format(
                "No implementation of scalar function %s found in %s.%n"
                    + " 1. Create a class implementing %s and annotate it with @AutoService(%s.class).%n"
                    + " 2. Add function %s to the class's userDefinedScalarFunctions implementation.",
                functionFullName,
                jarPath,
                UdfProvider.class.getSimpleName(),
                UdfProvider.class.getSimpleName(),
                functionFullName));
      }
      return functionDefinitions.scalarFunctions().get(functionPath);
    } catch (IOException e) {
      throw new RuntimeException(
          String.format(
              "Failed to load user-defined scalar function %s from %s", functionFullName, jarPath),
          e);
    }
  }

  /**
   * Creates a temporary local copy of the file at {@code inputPath}, and returns a handle to the
   * local copy.
   */
  private File downloadFile(String inputPath, String mimeType) throws IOException {
    Preconditions.checkArgument(!inputPath.isEmpty(), "Path cannot be empty.");
    ResourceId inputResource = FileSystems.matchNewResource(inputPath, false /* is directory */);
    try (ReadableByteChannel inputChannel = FileSystems.open(inputResource)) {
      File outputFile = File.createTempFile("sql-udf-", inputResource.getFilename());
      ResourceId outputResource =
          FileSystems.matchNewResource(outputFile.getAbsolutePath(), false /* is directory */);
      try (WritableByteChannel outputChannel = FileSystems.create(outputResource, mimeType)) {
        ByteStreams.copy(inputChannel, outputChannel);
      }
      // Compute and log checksum.
      try (InputStream inputStream = new FileInputStream(outputFile)) {
        LOG.info(
            "Copied {} to {} with md5 hash {}.",
            inputPath,
            outputFile.getAbsolutePath(),
            DigestUtils.md5Hex(inputStream));
      }
      return outputFile;
    }
  }

  private File getLocalJar(String inputJarPath) throws IOException {
    // TODO(ibzib) cache
    return downloadFile(inputJarPath, "application/java-archive");
  }

  private ClassLoader createClassLoader(String inputJarPath) throws IOException {
    File tmpJar = getLocalJar(inputJarPath);
    return new URLClassLoader(new URL[] {tmpJar.toURI().toURL()});
  }

  public ClassLoader createClassLoader(List<String> inputJarPaths) throws IOException {
    List<File> localJars = new ArrayList<>();
    for (String inputJar : inputJarPaths) {
      localJars.add(getLocalJar(inputJar));
    }
    List<URL> urls = new ArrayList<>();
    for (File file : localJars) {
      urls.add(file.toURI().toURL());
    }
    return new URLClassLoader(urls.toArray(new URL[0]));
  }

  @VisibleForTesting
  Iterator<UdfProvider> getUdfProviders(ClassLoader classLoader) throws IOException {
    return ServiceLoader.load(UdfProvider.class, classLoader).iterator();
  }

  private FunctionDefinitions loadJar(String jarPath) throws IOException {
    if (cache.containsKey(jarPath)) {
      LOG.debug("Using cached function definitions from {}", jarPath);
      return cache.get(jarPath);
    }

    ClassLoader classLoader = createClassLoader(jarPath);
    Map<List<String>, ScalarFn> scalarFunctions = new HashMap<>();
    Iterator<UdfProvider> providers = getUdfProviders(classLoader);
    int providersCount = 0;
    while (providers.hasNext()) {
      providersCount++;
      UdfProvider provider = providers.next();
      provider
          .userDefinedScalarFunctions()
          .forEach(
              (functionName, implementation) -> {
                List<String> functionPath = ImmutableList.copyOf(functionName.split("\\."));
                if (scalarFunctions.containsKey(functionPath)) {
                  throw new IllegalArgumentException(
                      String.format(
                          "Found multiple definitions of scalar function %s in %s.",
                          functionName, jarPath));
                }
                scalarFunctions.put(functionPath, implementation);
              });
    }
    if (providersCount == 0) {
      throw new ProviderNotFoundException(
          String.format(
              "No %s implementation found in %s. Create a class implementing %s and annotate it with @AutoService(%s.class).",
              UdfProvider.class.getSimpleName(),
              jarPath,
              UdfProvider.class.getSimpleName(),
              UdfProvider.class.getSimpleName()));
    }
    LOG.info(
        "Loaded {} implementations of {} from {} with {} scalar function(s).",
        providersCount,
        UdfProvider.class.getSimpleName(),
        jarPath,
        scalarFunctions.size());
    FunctionDefinitions userFunctionDefinitions =
        FunctionDefinitions.newBuilder()
            .setScalarFunctions(ImmutableMap.copyOf(scalarFunctions))
            .build();

    cache.put(jarPath, userFunctionDefinitions);

    return userFunctionDefinitions;
  }

  /** Holds user defined function definitions. */
  @AutoValue
  abstract static class FunctionDefinitions {
    abstract ImmutableMap<List<String>, ScalarFn> scalarFunctions();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setScalarFunctions(ImmutableMap<List<String>, ScalarFn> value);

      abstract FunctionDefinitions build();
    }

    static Builder newBuilder() {
      return new AutoValue_JavaUdfLoader_FunctionDefinitions.Builder()
          .setScalarFunctions(ImmutableMap.of());
    }
  }
}
