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
package org.beam.sdk.java.sql.rel;

import java.util.List;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.values.PCollection;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rex.RexNode;
import org.beam.sdk.java.sql.planner.BeamPipelineCreator;
import org.beam.sdk.java.sql.planner.BeamSQLRelUtils;
import org.beam.sdk.java.sql.schema.BaseBeamTable;
import org.beam.sdk.java.sql.schema.BeamSQLRow;

import com.google.common.base.Joiner;

public class BeamIOSinkRel extends TableModify implements BeamRelNode {
  public BeamIOSinkRel(RelOptCluster cluster, RelTraitSet traits, RelOptTable table,
      Prepare.CatalogReader catalogReader, RelNode child, Operation operation,
      List<String> updateColumnList, List<RexNode> sourceExpressionList, boolean flattened) {
    super(cluster, traits, table, catalogReader, child, operation, updateColumnList,
        sourceExpressionList, flattened);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new BeamIOSinkRel(getCluster(), traitSet, getTable(), getCatalogReader(), sole(inputs),
        getOperation(), getUpdateColumnList(), getSourceExpressionList(), isFlattened());
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Override
  public Pipeline buildBeamPipeline(BeamPipelineCreator planCreator) throws Exception {

    RelNode input = getInput();
    BeamSQLRelUtils.getBeamRelInput(input).buildBeamPipeline(planCreator);

    String stageName = BeamSQLRelUtils.getStageName(this);

    PCollection<BeamSQLRow> upstream = planCreator.getLatestStream();

    String sourceName = Joiner.on('.').join(getTable().getQualifiedName());

    BaseBeamTable targetTable = planCreator.getSourceTables().get(sourceName);

    PCollection preformattedStream = upstream.apply("preformat_to_target", targetTable.getOutputTransform());
    preformattedStream.apply("persistent", targetTable.buildIOWriter());

    planCreator.setHasPersistent(true);

    return planCreator.getPipeline();
  }

}
