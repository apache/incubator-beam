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
package org.apache.beam.sdk.extensions.sql.zetasql;

import org.apache.beam.sdk.extensions.sql.impl.rel.BeamLogicalConvention;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.plan.Convention;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.plan.RelOptRule;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.plan.RelOptRuleCall;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rel.RelNode;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rel.convert.ConverterRule;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rel.core.Calc;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rel.logical.LogicalCalc;

/** A {@code ConverterRule} to replace {@link Calc} with {@link BeamZetaSqlCalcRel}. */
public class BeamZetaSqlCalcRule extends ConverterRule {
  public static final BeamZetaSqlCalcRule INSTANCE = new BeamZetaSqlCalcRule();

  private BeamZetaSqlCalcRule() {
    super(
        LogicalCalc.class, Convention.NONE, BeamLogicalConvention.INSTANCE, "BeamZetaSqlCalcRule");
  }

  @Override
  public boolean matches(RelOptRuleCall x) {
    return ZetaSQLQueryPlanner.hasNoJavaUdfInProjects(x);
  }

  @Override
  public RelNode convert(RelNode rel) {
    final Calc calc = (Calc) rel;
    final RelNode input = calc.getInput();

    return new BeamZetaSqlCalcRel(
        calc.getCluster(),
        calc.getTraitSet().replace(BeamLogicalConvention.INSTANCE),
        RelOptRule.convert(input, input.getTraitSet().replace(BeamLogicalConvention.INSTANCE)),
        calc.getProgram());
  }
}
