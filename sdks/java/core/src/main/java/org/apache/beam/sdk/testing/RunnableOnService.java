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
package org.apache.beam.sdk.testing;

import org.apache.beam.sdk.runners.PipelineRunner;

/**
 * Category tag for tests which utilize {@link TestPipeline} for execution and
 * {@link PAssert} for validation. Tests in this category are used to validate
 * the correct implementation of a {@link PipelineRunner}. Example usage:
 * <pre><code>
 *     {@literal @}Test
 *     {@literal @}Category(RunnableOnService.class)
 *     public class ParDoTest {
 *       {@literal @}Rule
 *       public final transient TestPipeline p = TestPipeline.create();
 *
 *       public void testParDo() {
 *         p.apply(...);
 *         PAssert.that(p);
 *         p.run();
 *       }
 *     }
 * </code></pre>
 */
public interface RunnableOnService extends NeedsRunner {}
