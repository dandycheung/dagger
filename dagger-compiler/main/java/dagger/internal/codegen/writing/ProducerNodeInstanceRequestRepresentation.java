/*
 * Copyright (C) 2018 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen.writing;

import androidx.room.compiler.codegen.XClassName;
import androidx.room.compiler.codegen.XCodeBlock;
import androidx.room.compiler.processing.XProcessingEnv;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import dagger.internal.codegen.binding.ComponentDescriptor.ComponentMethodDescriptor;
import dagger.internal.codegen.binding.ContributionBinding;
import dagger.internal.codegen.binding.FrameworkType;
import dagger.internal.codegen.model.Key;
import dagger.internal.codegen.writing.ComponentImplementation.ShardImplementation;
import dagger.internal.codegen.xprocessing.XExpression;
import dagger.internal.codegen.xprocessing.XTypeNames;

/** Binding expression for producer node instances. */
final class ProducerNodeInstanceRequestRepresentation
    extends FrameworkInstanceRequestRepresentation {
  private final ShardImplementation shardImplementation;
  private final Key key;
  private final ProducerEntryPointView producerEntryPointView;

  @AssistedInject
  ProducerNodeInstanceRequestRepresentation(
      @Assisted ContributionBinding binding,
      @Assisted FrameworkInstanceSupplier frameworkInstanceSupplier,
      XProcessingEnv processingEnv,
      ComponentImplementation componentImplementation) {
    super(binding, frameworkInstanceSupplier, processingEnv);
    this.shardImplementation = componentImplementation.shardImplementation(binding);
    this.key = binding.key();
    this.producerEntryPointView = new ProducerEntryPointView(shardImplementation, processingEnv);
  }

  @Override
  protected FrameworkType frameworkType() {
    return FrameworkType.PRODUCER_NODE;
  }

  @Override
  XExpression getDependencyExpression(XClassName requestingClass) {
    XExpression result = super.getDependencyExpression(requestingClass);
    shardImplementation.addCancellation(
        key,
        XCodeBlock.of(
            "%T.cancel(%L, %N);",
            XTypeNames.PRODUCERS,
            result.codeBlock(),
            ComponentImplementation.MAY_INTERRUPT_IF_RUNNING_PARAM.getName())); // SUPPRESS_GET_NAME_CHECK
    return result;
  }

  @Override
  XExpression getDependencyExpressionForComponentMethod(
      ComponentMethodDescriptor componentMethod, ComponentImplementation component) {
    return producerEntryPointView
        .getProducerEntryPointField(this, componentMethod, component.name())
        .orElseGet(
            () -> super.getDependencyExpressionForComponentMethod(componentMethod, component));
  }

  @AssistedFactory
  static interface Factory {
    ProducerNodeInstanceRequestRepresentation create(
        ContributionBinding binding, FrameworkInstanceSupplier frameworkInstanceSupplier);
  }
}
