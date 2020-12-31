/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.ml;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import io.prestosql.RowPageBuilder;
import io.prestosql.metadata.BoundSignature;
import io.prestosql.metadata.FunctionBinding;
import io.prestosql.metadata.FunctionDependencies;
import io.prestosql.metadata.MetadataManager;
import io.prestosql.operator.aggregation.Accumulator;
import io.prestosql.operator.aggregation.InternalAggregationFunction;
import io.prestosql.operator.aggregation.ParametricAggregation;
import io.prestosql.plugin.ml.type.ClassifierParametricType;
import io.prestosql.plugin.ml.type.ModelType;
import io.prestosql.plugin.ml.type.RegressorType;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.spi.type.TypeSignatureParameter;
import io.prestosql.spi.type.VarcharType;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.operator.aggregation.AggregationFromAnnotationsParser.parseFunctionDefinitionWithTypesConstraint;
import static io.prestosql.plugin.ml.type.ClassifierType.BIGINT_CLASSIFIER;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.testing.StructuralTestUtil.mapBlockOf;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class TestLearnAggregations
{
    private static final MetadataManager METADATA = createTestMetadataManager();
    protected static final FunctionDependencies NO_FUNCTION_DEPENDENCIES = new FunctionDependencies(METADATA, ImmutableMap.of(), ImmutableSet.of());

    static {
        METADATA.addParametricType(new ClassifierParametricType());
        METADATA.addType(ModelType.MODEL);
        METADATA.addType(RegressorType.REGRESSOR);
    }

    @Test
    public void testLearn()
    {
        Type mapType = METADATA.getParameterizedType("map", ImmutableList.of(TypeSignatureParameter.typeParameter(BIGINT.getTypeSignature()), TypeSignatureParameter.typeParameter(DOUBLE.getTypeSignature())));
        List<TypeSignature> inputTypes = ImmutableList.of(BIGINT.getTypeSignature(), mapType.getTypeSignature());
        ParametricAggregation aggregation = parseFunctionDefinitionWithTypesConstraint(
                LearnClassifierAggregation.class,
                BIGINT_CLASSIFIER.getTypeSignature(),
                inputTypes);
        FunctionBinding functionBinding = new FunctionBinding(
                aggregation.getFunctionMetadata().getFunctionId(),
                new BoundSignature(aggregation.getFunctionMetadata().getSignature().getName(), BIGINT_CLASSIFIER, ImmutableList.of(BIGINT, mapType)),
                ImmutableMap.of(),
                ImmutableMap.of());
        InternalAggregationFunction aggregationFunction = aggregation.specialize(functionBinding, NO_FUNCTION_DEPENDENCIES);
        assertLearnClassifer(aggregationFunction.bind(ImmutableList.of(0, 1), Optional.empty()).createAccumulator());
    }

    @Test
    public void testLearnLibSvm()
    {
        Type mapType = METADATA.getParameterizedType("map", ImmutableList.of(TypeSignatureParameter.typeParameter(BIGINT.getTypeSignature()), TypeSignatureParameter.typeParameter(DOUBLE.getTypeSignature())));
        ParametricAggregation aggregation = parseFunctionDefinitionWithTypesConstraint(
                LearnLibSvmClassifierAggregation.class,
                BIGINT_CLASSIFIER.getTypeSignature(),
                ImmutableList.of(BIGINT.getTypeSignature(), mapType.getTypeSignature(), VarcharType.getParametrizedVarcharSignature("x")));
        FunctionBinding functionBinding = new FunctionBinding(
                aggregation.getFunctionMetadata().getFunctionId(),
                new BoundSignature(
                        aggregation.getFunctionMetadata().getSignature().getName(),
                        BIGINT_CLASSIFIER,
                        ImmutableList.of(BIGINT, mapType, VARCHAR)),
                ImmutableMap.of(),
                ImmutableMap.of("x", (long) Integer.MAX_VALUE));
        InternalAggregationFunction aggregationFunction = aggregation.specialize(functionBinding, NO_FUNCTION_DEPENDENCIES);
        assertLearnClassifer(aggregationFunction.bind(ImmutableList.of(0, 1, 2), Optional.empty()).createAccumulator());
    }

    private static void assertLearnClassifer(Accumulator accumulator)
    {
        accumulator.addInput(getPage());
        BlockBuilder finalOut = accumulator.getFinalType().createBlockBuilder(null, 1);
        accumulator.evaluateFinal(finalOut);
        Block block = finalOut.build();
        Slice slice = accumulator.getFinalType().getSlice(block, 0);
        Model deserialized = ModelUtils.deserialize(slice);
        assertNotNull(deserialized, "deserialization failed");
        assertTrue(deserialized instanceof Classifier, "deserialized model is not a classifier");
    }

    private static Page getPage()
    {
        Type mapType = METADATA.getParameterizedType("map", ImmutableList.of(TypeSignatureParameter.typeParameter(BIGINT.getTypeSignature()), TypeSignatureParameter.typeParameter(DOUBLE.getTypeSignature())));
        int datapoints = 100;
        RowPageBuilder builder = RowPageBuilder.rowPageBuilder(BIGINT, mapType, VARCHAR);
        Random rand = new Random(0);
        for (int i = 0; i < datapoints; i++) {
            long label = rand.nextDouble() < 0.5 ? 0 : 1;
            builder.row(label, mapBlockOf(BIGINT, DOUBLE, 0L, label + rand.nextGaussian()), "C=1");
        }

        return builder.build();
    }
}