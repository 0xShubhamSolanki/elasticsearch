/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.gen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import org.elasticsearch.compute.ann.Aggregator;

import java.util.Locale;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

import static org.elasticsearch.compute.gen.Methods.findMethod;
import static org.elasticsearch.compute.gen.Methods.findRequiredMethod;
import static org.elasticsearch.compute.gen.Types.AGGREGATOR_STATE_VECTOR;
import static org.elasticsearch.compute.gen.Types.AGGREGATOR_STATE_VECTOR_BUILDER;
import static org.elasticsearch.compute.gen.Types.BIG_ARRAYS;
import static org.elasticsearch.compute.gen.Types.BLOCK;
import static org.elasticsearch.compute.gen.Types.DOUBLE_ARRAY_VECTOR;
import static org.elasticsearch.compute.gen.Types.DOUBLE_BLOCK;
import static org.elasticsearch.compute.gen.Types.DOUBLE_VECTOR;
import static org.elasticsearch.compute.gen.Types.GROUPING_AGGREGATOR_FUNCTION;
import static org.elasticsearch.compute.gen.Types.LONG_ARRAY_VECTOR;
import static org.elasticsearch.compute.gen.Types.LONG_BLOCK;
import static org.elasticsearch.compute.gen.Types.LONG_VECTOR;
import static org.elasticsearch.compute.gen.Types.PAGE;
import static org.elasticsearch.compute.gen.Types.VECTOR;

/**
 * Implements "GroupingAggregationFunction" from a class containing static methods
 * annotated with {@link Aggregator}.
 * <p>The goal here is the implement an GroupingAggregationFunction who's inner loops
 * don't contain any {@code invokevirtual}s. Instead, we generate a class
 * that calls static methods in the inner loops.
 * <p>A secondary goal is to make the generated code as readable, debuggable,
 * and break-point-able as possible.
 */
public class GroupingAggregatorImplementer {
    private final TypeElement declarationType;
    private final ExecutableElement init;
    private final ExecutableElement combine;
    private final ExecutableElement combineStates;
    private final ExecutableElement evaluateFinal;
    private final ClassName implementation;
    private final TypeName stateType;

    public GroupingAggregatorImplementer(Elements elements, TypeElement declarationType) {
        this.declarationType = declarationType;

        this.init = findRequiredMethod(declarationType, new String[] { "init", "initGrouping" }, e -> true);
        this.stateType = choseStateType();

        this.combine = findRequiredMethod(declarationType, new String[] { "combine" }, e -> {
            if (e.getParameters().size() == 0) {
                return false;
            }
            TypeName firstParamType = TypeName.get(e.getParameters().get(0).asType());
            return firstParamType.isPrimitive() || firstParamType.toString().equals(stateType.toString());
        });
        this.combineStates = findMethod(declarationType, "combineStates");
        this.evaluateFinal = findMethod(declarationType, "evaluateFinal");

        this.implementation = ClassName.get(
            elements.getPackageOf(declarationType).toString(),
            (declarationType.getSimpleName() + "GroupingAggregatorFunction").replace("AggregatorGroupingAggregator", "GroupingAggregator")
        );
    }

    private TypeName choseStateType() {
        TypeName initReturn = TypeName.get(init.getReturnType());
        if (false == initReturn.isPrimitive()) {
            return initReturn;
        }
        String head = initReturn.toString().substring(0, 1).toUpperCase(Locale.ROOT);
        String tail = initReturn.toString().substring(1);
        return ClassName.get("org.elasticsearch.compute.aggregation", head + tail + "ArrayState");
    }

    private String primitiveType() {
        String initReturn = declarationType.toString().toLowerCase(Locale.ROOT);
        if (initReturn.contains("double")) {
            return "double";
        } else if (initReturn.contains("long")) {
            return "long";
        } else {
            throw new IllegalArgumentException("unknown primitive type for " + initReturn);
        }
    }

    private ClassName valueBlockType() {
        return switch (primitiveType()) {
            case "double" -> DOUBLE_BLOCK;
            case "long" -> LONG_BLOCK;
            default -> throw new IllegalArgumentException("unknown block type for " + primitiveType());
        };
    }

    private ClassName valueVectorType() {
        return switch (primitiveType()) {
            case "double" -> DOUBLE_VECTOR;
            case "long" -> LONG_VECTOR;
            default -> throw new IllegalArgumentException("unknown vector type for " + primitiveType());
        };
    }

    public JavaFile sourceFile() {
        JavaFile.Builder builder = JavaFile.builder(implementation.packageName(), type());
        return builder.build();
    }

    private TypeSpec type() {
        TypeSpec.Builder builder = TypeSpec.classBuilder(implementation);
        builder.addJavadoc("{@link $T} implementation for {@link $T}.\n", GROUPING_AGGREGATOR_FUNCTION, declarationType);
        builder.addJavadoc("This class is generated. Do not edit it.");
        builder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        builder.addSuperinterface(GROUPING_AGGREGATOR_FUNCTION);
        builder.addField(stateType, "state", Modifier.PRIVATE, Modifier.FINAL);
        builder.addField(TypeName.INT, "channel", Modifier.PRIVATE, Modifier.FINAL);

        builder.addMethod(create());
        builder.addMethod(ctor());
        builder.addMethod(addRawInput());
        builder.addMethod(addRawVector());
        builder.addMethod(addRawBlock());
        builder.addMethod(addIntermediateInput());
        builder.addMethod(addIntermediateRowInput());
        builder.addMethod(evaluateIntermediate());
        builder.addMethod(evaluateFinal());
        builder.addMethod(toStringMethod());
        builder.addMethod(close());
        return builder.build();
    }

    private MethodSpec create() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("create");
        builder.addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(implementation);
        builder.addParameter(BIG_ARRAYS, "bigArrays").addParameter(TypeName.INT, "channel");
        builder.addStatement("return new $T(channel, $L)", implementation, callInit());
        return builder.build();
    }

    private CodeBlock callInit() {
        CodeBlock.Builder builder = CodeBlock.builder();
        if (init.getReturnType().toString().equals(stateType.toString())) {
            builder.add("$T.$L(bigArrays)", declarationType, init.getSimpleName());
        } else {
            builder.add("new $T(bigArrays, $T.$L())", stateType, declarationType, init.getSimpleName());
        }
        return builder.build();
    }

    private MethodSpec ctor() {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        builder.addParameter(TypeName.INT, "channel");
        builder.addParameter(stateType, "state");
        builder.addStatement("this.channel = channel");
        builder.addStatement("this.state = state");
        return builder.build();
    }

    private MethodSpec addRawInput() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("addRawInput");
        builder.addAnnotation(Override.class).addModifiers(Modifier.PUBLIC);
        builder.addParameter(LONG_VECTOR, "groupIdVector").addParameter(PAGE, "page");
        builder.addStatement("assert channel >= 0");
        builder.addStatement("$T block = page.getBlock(channel)", valueBlockType());
        builder.addStatement("$T vector = block.asVector()", valueVectorType());
        builder.beginControlFlow("if (vector != null)").addStatement("addRawVector(groupIdVector, vector)");
        builder.nextControlFlow("else").addStatement("addRawBlock(groupIdVector, block)").endControlFlow();
        return builder.build();
    }

    private MethodSpec addRawVector() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("addRawVector");
        builder.addModifiers(Modifier.PRIVATE).addParameter(LONG_VECTOR, "groupIdVector").addParameter(valueVectorType(), "vector");
        builder.beginControlFlow("for (int position = 0; position < vector.getPositionCount(); position++)");
        {
            builder.addStatement("int groupId = Math.toIntExact(groupIdVector.getLong(position))");
            combineRawInput(builder, "vector", "position");
        }
        builder.endControlFlow();
        return builder.build();
    }

    private MethodSpec addRawBlock() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("addRawBlock");
        builder.addModifiers(Modifier.PRIVATE).addParameter(LONG_VECTOR, "groupIdVector").addParameter(valueBlockType(), "block");

        builder.beginControlFlow("for (int offset = 0; offset < block.getTotalValueCount(); offset++)");
        {
            builder.beginControlFlow("if (block.isNull(offset) == false)");
            {
                builder.addStatement("int groupId = Math.toIntExact(groupIdVector.getLong(offset))");
                combineRawInput(builder, "block", "offset");
            }
            builder.endControlFlow();
        }
        builder.endControlFlow();
        return builder.build();
    }

    private void combineRawInput(MethodSpec.Builder builder, String blockVariable, String offsetVariable) {
        TypeName valueType = TypeName.get(combine.getParameters().get(combine.getParameters().size() - 1).asType());
        if (valueType.isPrimitive() == false) {
            throw new IllegalArgumentException("second parameter to combine must be a primitive");
        }
        String secondParameterGetter = "get"
            + valueType.toString().substring(0, 1).toUpperCase(Locale.ROOT)
            + valueType.toString().substring(1);
        TypeName returnType = TypeName.get(combine.getReturnType());
        if (returnType.isPrimitive()) {
            combineRawInputForPrimitive(builder, secondParameterGetter, blockVariable, offsetVariable);
            return;
        }
        if (returnType == TypeName.VOID) {
            combineRawInputForVoid(builder, secondParameterGetter, blockVariable, offsetVariable);
            return;
        }
        throw new IllegalArgumentException("combine must return void or a primitive");
    }

    private void combineRawInputForPrimitive(
        MethodSpec.Builder builder,
        String secondParameterGetter,
        String blockVariable,
        String offsetVariable
    ) {
        builder.addStatement(
            "state.set($T.combine(state.getOrDefault(groupId), $L.$L($L)), groupId)",
            declarationType,
            blockVariable,
            secondParameterGetter,
            offsetVariable
        );
    }

    private void combineRawInputForVoid(
        MethodSpec.Builder builder,
        String secondParameterGetter,
        String blockVariable,
        String offsetVariable
    ) {
        builder.addStatement(
            "$T.combine(state, groupId, $L.$L($L))",
            declarationType,
            blockVariable,
            secondParameterGetter,
            offsetVariable
        );
    }

    private MethodSpec addIntermediateInput() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("addIntermediateInput");
        builder.addAnnotation(Override.class).addModifiers(Modifier.PUBLIC);
        builder.addParameter(LONG_VECTOR, "groupIdVector").addParameter(BLOCK, "block");
        builder.addStatement("assert channel == -1");
        builder.addStatement("$T vector = block.asVector()", VECTOR);
        builder.beginControlFlow("if (vector == null || vector instanceof $T == false)", AGGREGATOR_STATE_VECTOR);
        {
            builder.addStatement("throw new RuntimeException($S + block)", "expected AggregatorStateBlock, got:");
            builder.endControlFlow();
        }
        builder.addStatement("@SuppressWarnings($S) $T blobVector = ($T) vector", "unchecked", stateBlockType(), stateBlockType());
        builder.addComment("TODO exchange big arrays directly without funny serialization - no more copying");
        builder.addStatement("$T bigArrays = $T.NON_RECYCLING_INSTANCE", BIG_ARRAYS, BIG_ARRAYS);
        builder.addStatement("$T inState = $L", stateType, callInit());
        builder.addStatement("blobVector.get(0, inState)");
        builder.beginControlFlow("for (int position = 0; position < groupIdVector.getPositionCount(); position++)");
        {
            builder.addStatement("int groupId = Math.toIntExact(groupIdVector.getLong(position))");
            combineStates(builder);
            builder.endControlFlow();
        }
        return builder.build();
    }

    private void combineStates(MethodSpec.Builder builder) {
        if (combineStates == null) {
            builder.addStatement("state.set($T.combine(state.getOrDefault(groupId), inState.get(position)), groupId)", declarationType);
            return;
        }
        builder.addStatement("$T.combineStates(state, groupId, inState, position)", declarationType);
    }

    private MethodSpec addIntermediateRowInput() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("addIntermediateRowInput");
        builder.addAnnotation(Override.class).addModifiers(Modifier.PUBLIC);
        builder.addParameter(int.class, "groupId").addParameter(GROUPING_AGGREGATOR_FUNCTION, "input").addParameter(int.class, "position");
        builder.beginControlFlow("if (input.getClass() != getClass())");
        {
            builder.addStatement("throw new IllegalArgumentException($S + getClass() + $S + input.getClass())", "expected ", "; got ");
        }
        builder.endControlFlow();
        builder.addStatement("$T inState = (($T) input).state", stateType, implementation);
        combineStates(builder);
        return builder.build();
    }

    private MethodSpec evaluateIntermediate() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("evaluateIntermediate");
        builder.addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).returns(BLOCK);
        ParameterizedTypeName stateBlockBuilderType = ParameterizedTypeName.get(
            AGGREGATOR_STATE_VECTOR_BUILDER,
            stateBlockType(),
            stateType
        );
        builder.addStatement(
            "$T builder =\n$T.builderOfAggregatorState($T.class, state.getEstimatedSize())",
            stateBlockBuilderType,
            AGGREGATOR_STATE_VECTOR,
            stateType
        );
        builder.addStatement("builder.add(state)");
        builder.addStatement("return builder.build().asBlock()");
        return builder.build();
    }

    private MethodSpec evaluateFinal() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("evaluateFinal");
        builder.addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).returns(BLOCK);
        if (evaluateFinal == null) {
            primitiveStateToResult(builder);
        } else {
            builder.addStatement("return $T.evaluateFinal(state)", declarationType);
        }
        return builder.build();
    }

    private void primitiveStateToResult(MethodSpec.Builder builder) {
        TypeName vectorType;
        TypeName elementType;
        switch (stateType.toString()) {
            case "org.elasticsearch.compute.aggregation.LongArrayState":
                vectorType = LONG_ARRAY_VECTOR;
                elementType = TypeName.get(long.class);
                break;
            case "org.elasticsearch.compute.aggregation.DoubleArrayState":
                vectorType = DOUBLE_ARRAY_VECTOR;
                elementType = TypeName.get(double.class);
                break;
            default:
                throw new IllegalArgumentException("don't know how to convert state to result: " + stateType);
        }
        builder.addStatement("int positions = state.largestIndex + 1");
        builder.addStatement("$T[] values = new $T[positions]", elementType, elementType);
        builder.beginControlFlow("for (int i = 0; i < positions; i++)");
        {
            builder.addStatement("values[i] = state.get(i)");
        }
        builder.endControlFlow();
        builder.addStatement("return new $T(values, positions).asBlock()", vectorType);
    }

    private MethodSpec toStringMethod() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("toString");
        builder.addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).returns(String.class);
        builder.addStatement("$T sb = new $T()", StringBuilder.class, StringBuilder.class);
        builder.addStatement("sb.append(getClass().getSimpleName()).append($S)", "[");
        builder.addStatement("sb.append($S).append(channel)", "channel=");
        builder.addStatement("sb.append($S)", "]");
        builder.addStatement("return sb.toString()");
        return builder.build();
    }

    private MethodSpec close() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("close");
        builder.addAnnotation(Override.class).addModifiers(Modifier.PUBLIC);
        builder.addStatement("state.close()");
        return builder.build();
    }

    private ParameterizedTypeName stateBlockType() {
        return ParameterizedTypeName.get(AGGREGATOR_STATE_VECTOR, stateType);
    }
}
