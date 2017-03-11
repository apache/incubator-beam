/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.beam.sdk.java.sql.rule;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.logical.LogicalProject;
import org.beam.sdk.java.sql.rel.BeamLogicalConvention;
import org.beam.sdk.java.sql.rel.BeamProjectRel;

public class BeamProjectRule extends ConverterRule {
  public static final BeamProjectRule INSTANCE = new BeamProjectRule();

  private BeamProjectRule() {
    super(LogicalProject.class, Convention.NONE, BeamLogicalConvention.INSTANCE, "BeamProjectRule");
  }

  @Override
  public RelNode convert(RelNode rel) {
    final Project project = (Project) rel;
    final RelNode input = project.getInput();

    return new BeamProjectRel(project.getCluster(),
        project.getTraitSet().replace(BeamLogicalConvention.INSTANCE),
        convert(input, input.getTraitSet().replace(BeamLogicalConvention.INSTANCE)),
        project.getProjects(), project.getRowType());
  }
}
