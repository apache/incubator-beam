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

package org.apache.beam.dsls.sql.interpreter.operator.date;

import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

import org.apache.beam.dsls.sql.interpreter.operator.BeamSqlExpression;
import org.apache.beam.dsls.sql.interpreter.operator.BeamSqlPrimitive;
import org.apache.beam.dsls.sql.schema.BeamRow;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * {@code BeamSqlExpression} for LOCALTIME and CURRENT_TIME.
 *
 * <p>Returns the current date and time in the session time zone in a value of datatype TIME, with
 * precision digits of precision.
 *
 * <p>NOTE: for simplicity, we will ignore the {@code precision} param.
 */
public class BeamSqlCurrentTimeExpression extends BeamSqlExpression {
  public BeamSqlCurrentTimeExpression(List<BeamSqlExpression> operands) {
    super(operands, SqlTypeName.TIME);
  }
  @Override public boolean accept() {
    int opCount = getOperands().size();
    return opCount <= 1;
  }

  @Override public BeamSqlPrimitive evaluate(BeamRow inputRow) {
    GregorianCalendar ret = new GregorianCalendar(TimeZone.getDefault());
    ret.setTime(new Date());
    return BeamSqlPrimitive.of(outputType, ret);
  }
}
