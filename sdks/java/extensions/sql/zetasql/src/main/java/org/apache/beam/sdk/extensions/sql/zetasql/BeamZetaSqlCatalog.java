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

import com.google.common.collect.ImmutableList;
import com.google.zetasql.Analyzer;
import com.google.zetasql.AnalyzerOptions;
import com.google.zetasql.Function;
import com.google.zetasql.FunctionArgumentType;
import com.google.zetasql.FunctionSignature;
import com.google.zetasql.SimpleCatalog;
import com.google.zetasql.TVFRelation;
import com.google.zetasql.TableValuedFunction;
import com.google.zetasql.TypeFactory;
import com.google.zetasql.ZetaSQLBuiltinFunctionOptions;
import com.google.zetasql.ZetaSQLFunctions;
import com.google.zetasql.ZetaSQLType;
import com.google.zetasql.resolvedast.ResolvedNode;
import com.google.zetasql.resolvedast.ResolvedNodes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.beam.sdk.extensions.sql.impl.JavaUdfLoader;
import org.apache.beam.sdk.extensions.sql.impl.SqlConversionException;
import org.apache.beam.sdk.extensions.sql.impl.utils.TVFStreamingUtils;
import org.apache.beam.sdk.extensions.sql.udf.ScalarFn;
import org.apache.beam.sdk.extensions.sql.zetasql.translation.UserFunctionDefinitions;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rel.type.RelDataType;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.schema.SchemaPlus;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;

/**
 * Catalog for registering tables and functions. Populates a {@link SimpleCatalog} based on a {@link
 * SchemaPlus}.
 */
public class BeamZetaSqlCatalog {
  // ZetaSQL function group identifiers. Different function groups may have divergent translation
  // paths.
  public static final String PRE_DEFINED_WINDOW_FUNCTIONS = "pre_defined_window_functions";
  public static final String USER_DEFINED_SQL_FUNCTIONS = "user_defined_functions";
  public static final String USER_DEFINED_JAVA_SCALAR_FUNCTIONS =
      "user_defined_java_scalar_functions";
  /**
   * Same as {@link Function}.ZETASQL_FUNCTION_GROUP_NAME. Identifies built-in ZetaSQL functions.
   */
  public static final String ZETASQL_FUNCTION_GROUP_NAME = "ZetaSQL";

  private static final ImmutableList<String> PRE_DEFINED_WINDOW_FUNCTION_DECLARATIONS =
      ImmutableList.of(
          // TODO: support optional function argument (for window_offset).
          "CREATE FUNCTION TUMBLE(ts TIMESTAMP, window_size STRING) AS (1);",
          "CREATE FUNCTION TUMBLE_START(window_size STRING) RETURNS TIMESTAMP AS (null);",
          "CREATE FUNCTION TUMBLE_END(window_size STRING) RETURNS TIMESTAMP AS (null);",
          "CREATE FUNCTION HOP(ts TIMESTAMP, emit_frequency STRING, window_size STRING) AS (1);",
          "CREATE FUNCTION HOP_START(emit_frequency STRING, window_size STRING) "
              + "RETURNS TIMESTAMP AS (null);",
          "CREATE FUNCTION HOP_END(emit_frequency STRING, window_size STRING) "
              + "RETURNS TIMESTAMP AS (null);",
          "CREATE FUNCTION SESSION(ts TIMESTAMP, session_gap STRING) AS (1);",
          "CREATE FUNCTION SESSION_START(session_gap STRING) RETURNS TIMESTAMP AS (null);",
          "CREATE FUNCTION SESSION_END(session_gap STRING) RETURNS TIMESTAMP AS (null);");

  /** The top-level Calcite schema, which may contain sub-schemas. */
  private final SchemaPlus calciteSchema;
  /**
   * The top-level ZetaSQL catalog, which may contain nested catalogs for qualified table and
   * function references.
   */
  private final SimpleCatalog zetaSqlCatalog;

  private final JavaTypeFactory typeFactory;

  private final JavaUdfLoader javaUdfLoader = new JavaUdfLoader();
  private final Map<List<String>, ResolvedNodes.ResolvedCreateFunctionStmt> sqlScalarUdfs =
      new HashMap<>();
  /** User-defined table valued functions. */
  private final Map<List<String>, ResolvedNode> sqlUdtvfs = new HashMap<>();

  private final Map<List<String>, UserFunctionDefinitions.JavaScalarFunction> javaScalarUdfs =
      new HashMap<>();

  private BeamZetaSqlCatalog(
      SchemaPlus calciteSchema, SimpleCatalog zetaSqlCatalog, JavaTypeFactory typeFactory) {
    this.calciteSchema = calciteSchema;
    this.zetaSqlCatalog = zetaSqlCatalog;
    this.typeFactory = typeFactory;
  }

  /** Return catalog pre-populated with builtin functions. */
  static BeamZetaSqlCatalog create(
      SchemaPlus topLevelSchema,
      SimpleCatalog zetaSqlCatalog,
      JavaTypeFactory typeFactory,
      AnalyzerOptions options) {
    BeamZetaSqlCatalog catalog =
        new BeamZetaSqlCatalog(topLevelSchema, zetaSqlCatalog, typeFactory);
    catalog.addBuiltinFunctionsToCatalog(options);
    return catalog;
  }

  SimpleCatalog getZetaSqlCatalog() {
    return zetaSqlCatalog;
  }

  void addTables(List<List<String>> tables, QueryTrait queryTrait) {
    tables.forEach(table -> addTableToLeafCatalog(table, queryTrait));
  }

  void addFunction(ResolvedNodes.ResolvedCreateFunctionStmt createFunctionStmt) {
    String functionGroup = getFunctionGroup(createFunctionStmt);
    switch (functionGroup) {
      case USER_DEFINED_SQL_FUNCTIONS:
        sqlScalarUdfs.put(createFunctionStmt.getNamePath(), createFunctionStmt);
        break;
      case USER_DEFINED_JAVA_SCALAR_FUNCTIONS:
        String jarPath = getJarPath(createFunctionStmt);
        ScalarFn scalarFn =
            javaUdfLoader.loadScalarFunction(createFunctionStmt.getNamePath(), jarPath);
        javaScalarUdfs.put(
            createFunctionStmt.getNamePath(),
            UserFunctionDefinitions.JavaScalarFunction.create(scalarFn, jarPath));
        break;
      default:
        throw new IllegalArgumentException(
            String.format("Encountered unrecognized function group %s.", functionGroup));
    }
    zetaSqlCatalog.addFunction(
        new Function(
            createFunctionStmt.getNamePath(),
            functionGroup,
            createFunctionStmt.getIsAggregate()
                ? ZetaSQLFunctions.FunctionEnums.Mode.AGGREGATE
                : ZetaSQLFunctions.FunctionEnums.Mode.SCALAR,
            ImmutableList.of(createFunctionStmt.getSignature())));
  }

  void addTableValuedFunction(
      ResolvedNodes.ResolvedCreateTableFunctionStmt createTableFunctionStmt) {
    zetaSqlCatalog.addTableValuedFunction(
        new TableValuedFunction.FixedOutputSchemaTVF(
            createTableFunctionStmt.getNamePath(),
            createTableFunctionStmt.getSignature(),
            TVFRelation.createColumnBased(
                createTableFunctionStmt.getQuery().getColumnList().stream()
                    .map(c -> TVFRelation.Column.create(c.getName(), c.getType()))
                    .collect(Collectors.toList()))));
    sqlUdtvfs.put(createTableFunctionStmt.getNamePath(), createTableFunctionStmt.getQuery());
  }

  UserFunctionDefinitions getUserFunctionDefinitions() {
    return UserFunctionDefinitions.newBuilder()
        .setSqlScalarFunctions(ImmutableMap.copyOf(sqlScalarUdfs))
        .setSqlTableValuedFunctions(ImmutableMap.copyOf(sqlUdtvfs))
        .setJavaScalarFunctions(ImmutableMap.copyOf(javaScalarUdfs))
        .build();
  }

  private void addBuiltinFunctionsToCatalog(AnalyzerOptions options) {
    // Enable ZetaSQL builtin functions.
    ZetaSQLBuiltinFunctionOptions zetasqlBuiltinFunctionOptions =
        new ZetaSQLBuiltinFunctionOptions(options.getLanguageOptions());
    SupportedZetaSqlBuiltinFunctions.ALLOWLIST.forEach(
        zetasqlBuiltinFunctionOptions::includeFunctionSignatureId);
    zetaSqlCatalog.addZetaSQLFunctions(zetasqlBuiltinFunctionOptions);

    // Enable Beam SQL's builtin windowing functions.
    addWindowScalarFunctions(options);
    addWindowTvfs();
  }

  private void addWindowScalarFunctions(AnalyzerOptions options) {
    PRE_DEFINED_WINDOW_FUNCTION_DECLARATIONS.stream()
        .map(
            func ->
                (ResolvedNodes.ResolvedCreateFunctionStmt)
                    Analyzer.analyzeStatement(func, options, zetaSqlCatalog))
        .map(
            resolvedFunc ->
                new Function(
                    String.join(".", resolvedFunc.getNamePath()),
                    PRE_DEFINED_WINDOW_FUNCTIONS,
                    ZetaSQLFunctions.FunctionEnums.Mode.SCALAR,
                    ImmutableList.of(resolvedFunc.getSignature())))
        .forEach(zetaSqlCatalog::addFunction);
  }

  @SuppressWarnings({
    "nullness" // customContext and volatility are in fact nullable, but they are missing the
    // annotation upstream. TODO Unsuppress when this is fixed in ZetaSQL.
  })
  private void addWindowTvfs() {
    FunctionArgumentType retType =
        new FunctionArgumentType(ZetaSQLFunctions.SignatureArgumentKind.ARG_TYPE_RELATION);

    FunctionArgumentType inputTableType =
        new FunctionArgumentType(ZetaSQLFunctions.SignatureArgumentKind.ARG_TYPE_RELATION);

    FunctionArgumentType descriptorType =
        new FunctionArgumentType(
            ZetaSQLFunctions.SignatureArgumentKind.ARG_TYPE_DESCRIPTOR,
            FunctionArgumentType.FunctionArgumentTypeOptions.builder()
                .setDescriptorResolutionTableOffset(0)
                .build(),
            1);

    FunctionArgumentType stringType =
        new FunctionArgumentType(TypeFactory.createSimpleType(ZetaSQLType.TypeKind.TYPE_STRING));

    // TUMBLE
    zetaSqlCatalog.addTableValuedFunction(
        new TableValuedFunction.ForwardInputSchemaToOutputSchemaWithAppendedColumnTVF(
            ImmutableList.of(TVFStreamingUtils.FIXED_WINDOW_TVF),
            new FunctionSignature(
                retType, ImmutableList.of(inputTableType, descriptorType, stringType), -1),
            ImmutableList.of(
                TVFRelation.Column.create(
                    TVFStreamingUtils.WINDOW_START,
                    TypeFactory.createSimpleType(ZetaSQLType.TypeKind.TYPE_TIMESTAMP)),
                TVFRelation.Column.create(
                    TVFStreamingUtils.WINDOW_END,
                    TypeFactory.createSimpleType(ZetaSQLType.TypeKind.TYPE_TIMESTAMP))),
            null,
            null));

    // HOP
    zetaSqlCatalog.addTableValuedFunction(
        new TableValuedFunction.ForwardInputSchemaToOutputSchemaWithAppendedColumnTVF(
            ImmutableList.of(TVFStreamingUtils.SLIDING_WINDOW_TVF),
            new FunctionSignature(
                retType,
                ImmutableList.of(inputTableType, descriptorType, stringType, stringType),
                -1),
            ImmutableList.of(
                TVFRelation.Column.create(
                    TVFStreamingUtils.WINDOW_START,
                    TypeFactory.createSimpleType(ZetaSQLType.TypeKind.TYPE_TIMESTAMP)),
                TVFRelation.Column.create(
                    TVFStreamingUtils.WINDOW_END,
                    TypeFactory.createSimpleType(ZetaSQLType.TypeKind.TYPE_TIMESTAMP))),
            null,
            null));

    // SESSION
    zetaSqlCatalog.addTableValuedFunction(
        new TableValuedFunction.ForwardInputSchemaToOutputSchemaWithAppendedColumnTVF(
            ImmutableList.of(TVFStreamingUtils.SESSION_WINDOW_TVF),
            new FunctionSignature(
                retType,
                ImmutableList.of(inputTableType, descriptorType, descriptorType, stringType),
                -1),
            ImmutableList.of(
                TVFRelation.Column.create(
                    TVFStreamingUtils.WINDOW_START,
                    TypeFactory.createSimpleType(ZetaSQLType.TypeKind.TYPE_TIMESTAMP)),
                TVFRelation.Column.create(
                    TVFStreamingUtils.WINDOW_END,
                    TypeFactory.createSimpleType(ZetaSQLType.TypeKind.TYPE_TIMESTAMP))),
            null,
            null));
  }

  private String getFunctionGroup(ResolvedNodes.ResolvedCreateFunctionStmt createFunctionStmt) {
    switch (createFunctionStmt.getLanguage().toUpperCase()) {
      case "JAVA":
        if (createFunctionStmt.getIsAggregate()) {
          throw new UnsupportedOperationException(
              "Java SQL aggregate functions are not supported (BEAM-10925).");
        }
        return USER_DEFINED_JAVA_SCALAR_FUNCTIONS;
      case "SQL":
        if (createFunctionStmt.getIsAggregate()) {
          throw new UnsupportedOperationException(
              "Native SQL aggregate functions are not supported (BEAM-9954).");
        }
        return USER_DEFINED_SQL_FUNCTIONS;
      case "PY":
      case "PYTHON":
      case "JS":
      case "JAVASCRIPT":
        throw new UnsupportedOperationException(
            String.format(
                "Function %s uses unsupported language %s.",
                String.join(".", createFunctionStmt.getNamePath()),
                createFunctionStmt.getLanguage()));
      default:
        throw new IllegalArgumentException(
            String.format(
                "Function %s uses unrecognized language %s.",
                String.join(".", createFunctionStmt.getNamePath()),
                createFunctionStmt.getLanguage()));
    }
  }

  /**
   * Assume last element in tablePath is a table name, and everything before is catalogs. So the
   * logic is to create nested catalogs until the last level, then add a table at the last level.
   *
   * <p>Table schema is extracted from Calcite schema based on the table name resolution strategy,
   * e.g. either by drilling down the schema.getSubschema() path or joining the table name with dots
   * to construct a single compound identifier (e.g. Data Catalog use case).
   */
  private void addTableToLeafCatalog(List<String> tablePath, QueryTrait queryTrait) {

    SimpleCatalog leafCatalog = createNestedCatalogs(zetaSqlCatalog, tablePath);

    org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.schema.Table calciteTable =
        TableResolution.resolveCalciteTable(calciteSchema, tablePath);

    if (calciteTable == null) {
      throw new SqlConversionException(
          "Wasn't able to resolve the path "
              + tablePath
              + " in schema: "
              + calciteSchema.getName());
    }

    RelDataType rowType = calciteTable.getRowType(typeFactory);

    TableResolution.SimpleTableWithPath tableWithPath =
        TableResolution.SimpleTableWithPath.of(tablePath);
    queryTrait.addResolvedTable(tableWithPath);

    addFieldsToTable(tableWithPath, rowType);
    leafCatalog.addSimpleTable(tableWithPath.getTable());
  }

  private static void addFieldsToTable(
      TableResolution.SimpleTableWithPath tableWithPath, RelDataType rowType) {
    for (RelDataTypeField field : rowType.getFieldList()) {
      tableWithPath
          .getTable()
          .addSimpleColumn(
              field.getName(), ZetaSqlCalciteTranslationUtils.toZetaSqlType(field.getType()));
    }
  }

  /** For table path like a.b.c we assume c is the table and a.b are the nested catalogs/schemas. */
  private static SimpleCatalog createNestedCatalogs(SimpleCatalog catalog, List<String> tablePath) {
    SimpleCatalog currentCatalog = catalog;
    for (int i = 0; i < tablePath.size() - 1; i++) {
      String nextCatalogName = tablePath.get(i);

      Optional<SimpleCatalog> existing = tryGetExisting(currentCatalog, nextCatalogName);

      currentCatalog =
          existing.isPresent() ? existing.get() : addNewCatalog(currentCatalog, nextCatalogName);
    }
    return currentCatalog;
  }

  private static Optional<SimpleCatalog> tryGetExisting(
      SimpleCatalog currentCatalog, String nextCatalogName) {
    return currentCatalog.getCatalogList().stream()
        .filter(c -> nextCatalogName.equals(c.getFullName()))
        .findFirst();
  }

  private static SimpleCatalog addNewCatalog(SimpleCatalog currentCatalog, String nextCatalogName) {
    SimpleCatalog nextCatalog = new SimpleCatalog(nextCatalogName);
    currentCatalog.addSimpleCatalog(nextCatalog);
    return nextCatalog;
  }

  private static String getJarPath(ResolvedNodes.ResolvedCreateFunctionStmt createFunctionStmt) {
    String jarPath = getOptionStringValue(createFunctionStmt, "path");
    if (jarPath.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "No jar was provided to define function %s. Add 'OPTIONS (path=<jar location>)' to the CREATE FUNCTION statement.",
              String.join(".", createFunctionStmt.getNamePath())));
    }
    return jarPath;
  }

  private static String getOptionStringValue(
      ResolvedNodes.ResolvedCreateFunctionStmt createFunctionStmt, String optionName) {
    for (ResolvedNodes.ResolvedOption option : createFunctionStmt.getOptionList()) {
      if (optionName.equals(option.getName())) {
        if (option.getValue() == null) {
          throw new IllegalArgumentException(
              String.format(
                  "Option '%s' has null value (expected %s).",
                  optionName, ZetaSQLType.TypeKind.TYPE_STRING));
        }
        if (option.getValue().getType().getKind() != ZetaSQLType.TypeKind.TYPE_STRING) {
          throw new IllegalArgumentException(
              String.format(
                  "Option '%s' has type %s (expected %s).",
                  optionName,
                  option.getValue().getType().getKind(),
                  ZetaSQLType.TypeKind.TYPE_STRING));
        }
        return ((ResolvedNodes.ResolvedLiteral) option.getValue()).getValue().getStringValue();
      }
    }
    return "";
  }
}
