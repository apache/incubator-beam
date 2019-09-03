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
package org.apache.beam.runners.core.construction;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.beam.sdk.util.ZipFiles;
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Strings;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.hash.Funnels;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.hash.Hasher;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.hash.Hashing;

/** Utilities for working with classpath resources for pipelines. */
public class PipelineResources {

  /**
   * Detects all URLs that are present in all class loaders in between context class loader of
   * calling thread and class loader of class passed as parameter. It doesn't follow parents above
   * this class loader stopping it from pulling in resources from the system class loader.
   *
   * @param cls Class whose class loader stops recursion into parent loaders
   * @throws IllegalArgumentException no classloader in context hierarchy is URLClassloader or if
   *     one of the resources any class loader exposes is not a file resource.
   * @return A list of absolute paths to the resources the class loader uses.
   */
  public static List<String> detectClassPathResourcesToStage(Class<?> cls) {
    return detectClassPathResourcesToStage(cls.getClassLoader());
  }

  /**
   * Detect resources to stage, by using hierarchy between current context classloader and the given
   * one. Always include the given class loader in the process.
   *
   * @param loader the loader to use as stopping condition when traversing context class loaders
   * @return A list of absolute paths to the resources the class loader uses.
   */
  @VisibleForTesting
  static List<String> detectClassPathResourcesToStage(ClassLoader loader) {
    Set<String> files = new HashSet<>();
    ClassLoader stoppingLoader = loader;
    ClassLoader currentLoader = ReflectHelpers.findClassLoader();
    while (currentLoader != null && stoppingLoader != currentLoader) {
      files.addAll(extractResourcesFromClassLoader(currentLoader));
      currentLoader = currentLoader.getParent();
    }
    if (loader != null) {
      files.addAll(extractResourcesFromClassLoader(loader));
    }
    if (files.isEmpty()) {
      throw new IllegalArgumentException("Unable to use ClassLoader to detect classpath elements.");
    }
    return new ArrayList<>(files);
  }

  /**
   * Goes through the list of files that need to be staged on runner. Removes nonexistent
   * directories and packages existing ones. This is necessary for runners that require filesToStage
   * to be jars only.
   *
   * @param resourcesToStage list of resources that need to be staged
   * @param tmpJarLocation temporary directory to store the jars
   * @return A list of absolute paths to resources (jar files)
   */
  public static List<String> prepareFilesForStaging(
      List<String> resourcesToStage, String tmpJarLocation) {
    return resourcesToStage.stream()
        .map(File::new)
        .map(
            file -> {
              Preconditions.checkState(
                  file.exists(), "To-be-staged file does not exist: '%s'", file);
              return file.isDirectory()
                  ? packageDirectoriesToStage(file, tmpJarLocation)
                  : file.getAbsolutePath();
            })
        .collect(Collectors.toList());
  }

  private static List<String> extractResourcesFromClassLoader(ClassLoader loader) {
    List<String> files = new ArrayList<>();
    if (loader instanceof URLClassLoader) {
      for (URL url : ((URLClassLoader) loader).getURLs()) {
        try {
          files.add(new File(url.toURI()).getAbsolutePath());
        } catch (IllegalArgumentException | URISyntaxException e) {
          String message = String.format("Unable to convert url (%s) to file.", url);
          throw new IllegalArgumentException(message, e);
        }
      }
    }
    return files;
  }

  private static String packageDirectoriesToStage(File directoryToStage, String tmpJarLocation) {
    String hash = calculateDirectoryContentHash(directoryToStage);
    String pathForJar = getUniqueJarPath(hash, tmpJarLocation);
    zipDirectory(directoryToStage, pathForJar);
    return pathForJar;
  }

  private static String calculateDirectoryContentHash(File directoryToStage) {
    Hasher hasher = Hashing.sha256().newHasher();
    try (OutputStream hashStream = Funnels.asOutputStream(hasher)) {
      ZipFiles.zipDirectory(directoryToStage, hashStream);
      return hasher.hash().toString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String getUniqueJarPath(String contentHash, String tmpJarLocation) {
    checkArgument(
        !Strings.isNullOrEmpty(tmpJarLocation),
        "Please provide temporary location for storing the jar files.");

    return String.format("%s%s%s.jar", tmpJarLocation, File.separator, contentHash);
  }

  private static void zipDirectory(File directoryToStage, String uniqueDirectoryPath) {
    try {
      ZipFiles.zipDirectory(directoryToStage, new FileOutputStream(uniqueDirectoryPath));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
