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

package dagger.internal.codegen.bindinggraphvalidation;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static dagger.internal.codegen.base.Formatter.INDENT;
import static dagger.internal.codegen.model.BindingKind.MULTIBOUND_MAP;
import static dagger.internal.codegen.xprocessing.XAnnotations.asClassName;
import static javax.tools.Diagnostic.Kind.ERROR;

import androidx.room3.compiler.codegen.XClassName;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimaps;
import dagger.internal.codegen.base.MapType;
import dagger.internal.codegen.binding.BindingNode;
import dagger.internal.codegen.binding.ContributionBinding;
import dagger.internal.codegen.binding.Declaration;
import dagger.internal.codegen.binding.DeclarationFormatter;
import dagger.internal.codegen.binding.KeyFactory;
import dagger.internal.codegen.compileroption.CompilerOptions;
import dagger.internal.codegen.model.Binding;
import dagger.internal.codegen.model.BindingGraph;
import dagger.internal.codegen.model.ComponentPath;
import dagger.internal.codegen.model.DaggerTypeElement;
import dagger.internal.codegen.model.DiagnosticReporter;
import dagger.internal.codegen.model.Key;
import dagger.internal.codegen.validation.ValidationBindingGraphPlugin;
import dagger.internal.codegen.xprocessing.XAnnotations;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;

/**
 * Reports an error for any map binding with either more than one contribution with the same map key
 * or contributions with inconsistent map key annotation types.
 */
final class MapMultibindingValidator extends ValidationBindingGraphPlugin {

  private final DeclarationFormatter declarationFormatter;
  private final KeyFactory keyFactory;
  private final CompilerOptions compilerOptions;

  @Inject
  MapMultibindingValidator(
      DeclarationFormatter declarationFormatter,
      KeyFactory keyFactory,
      CompilerOptions compilerOptions) {
    this.declarationFormatter = declarationFormatter;
    this.keyFactory = keyFactory;
    this.compilerOptions = compilerOptions;
  }

  @Override
  public String pluginName() {
    return "Dagger/MapKeys";
  }

  @Override
  public void visitGraph(BindingGraph bindingGraph, DiagnosticReporter diagnosticReporter) {
    ImmutableSet<Binding> mapMultibindings =
        compilerOptions.mapMultibindingDuplicateDetectionFix()
            ? mapMultibindings(bindingGraph)
            : mapMultibindingsLegacy(bindingGraph);

    mapMultibindings.forEach(
        binding -> {
          ImmutableSet<BindingNode> contributions = mapBindingContributions(binding, bindingGraph);
          checkForDuplicateMapKeys(binding, contributions, diagnosticReporter);
          checkForInconsistentMapKeyAnnotationTypes(binding, contributions, diagnosticReporter);
        });
  }

  /**
   * Returns map multibindings in the binding graph, respecting component hierarchy.
   *
   * <p>This method processes bindings to ensure that validation errors are reported only once and
   * in the most specific component:
   *
   * <ol>
   *   <li>If a component offers map bindings for the same key {@code K} but different value types
   *       ({@code V}, {@code Provider<V>}, {@code Producer<V>}), it selects only one binding,
   *       preferring {@code Map<K, V>} over {@code Map<K, Provider<V>>}, etc., to avoid redundant
   *       diagnostics for the same logical map.
   *   <li>If bindings for the same map key exist in both an ancestor and a descendant component, it
   *       selects only the binding from the descendant component. This avoids reporting the same
   *       issue in multiple scopes.
   * </ol>
   *
   * <p>As a result, this method returns map bindings only for the leaf-most component in each
   * component hierarchy that contains a binding for a given key.
   */
  private ImmutableSet<Binding> mapMultibindings(BindingGraph bindingGraph) {
    Set<MapBindingKey> visitedKeys = new HashSet<>();
    ImmutableSetMultimap.Builder<Key, Binding> mapMultibindingsByKeyBuilder =
        ImmutableSetMultimap.builder();
    bindingGraph.bindings().stream()
        .filter(binding -> binding.kind().equals(MULTIBOUND_MAP))
        .sorted(Comparator.comparing(binding -> MapType.from(binding.key()).valueRequestKind()))
        .filter(
            binding ->
                visitedKeys.add(
                    MapBindingKey.create(unwrappedKey(binding), binding.componentPath())))
        .forEach(binding -> mapMultibindingsByKeyBuilder.put(unwrappedKey(binding), binding));
    ImmutableSetMultimap<Key, Binding> mapMultibindingsByKey = mapMultibindingsByKeyBuilder.build();

    ImmutableSet.Builder<Binding> result = ImmutableSet.builder();
    for (Collection<Binding> values : mapMultibindingsByKey.asMap().values()) {
      // Filter to keep only leaf component bindings.
      ImmutableSet<Binding> leafBindings =
          values.stream()
              .filter(
                  b1 ->
                      values.stream()
                          .noneMatch(b2 -> isAncestor(b1.componentPath(), b2.componentPath())))
              .collect(toImmutableSet());
      result.addAll(leafBindings);
    }
    return result.build();
  }

  /**
   * Returns the map multibindings in the binding graph. If a graph contains bindings for more than
   * one of the following for the same {@code K} and {@code V}, then only the first one found will
   * be returned so we don't report the same map contribution problem more than once.
   *
   * <ol>
   *   <li>{@code Map<K, V>}
   *   <li>{@code Map<K, Provider<V>>}
   *   <li>{@code Map<K, Producer<V>>}
   * </ol>
   */
  private ImmutableSet<Binding> mapMultibindingsLegacy(BindingGraph bindingGraph) {
    Set<Key> visitedKeys = new HashSet<>();
    return bindingGraph.bindings().stream()
        .filter(binding -> binding.kind().equals(MULTIBOUND_MAP))
        // Sort by the order of the value in the RequestKind:
        // (Map<K, V>, then Map<K, Provider<V>>, then Map<K, Producer<V>>).
        .sorted(Comparator.comparing(binding -> MapType.from(binding.key()).valueRequestKind()))
        // Only take the first binding (post sorting) per unwrapped key.
        .filter(binding -> visitedKeys.add(unwrappedKey(binding)))
        .collect(toImmutableSet());
  }

  private Key unwrappedKey(Binding binding) {
    return keyFactory.unwrapMapValueType(binding.key());
  }

  private ImmutableSet<BindingNode> mapBindingContributions(
      Binding binding, BindingGraph bindingGraph) {
    checkArgument(binding.kind().equals(MULTIBOUND_MAP));
    return bindingGraph.requestedBindings(binding).stream()
        .map(BindingNode.class::cast)
        .collect(toImmutableSet());
  }

  private void checkForDuplicateMapKeys(
      Binding multiboundMapBinding,
      ImmutableSet<BindingNode> contributions,
      DiagnosticReporter diagnosticReporter) {
    ImmutableSetMultimap<?, BindingNode> contributionsByMapKey =
        ImmutableSetMultimap.copyOf(
            // Note: We're wrapping in XAnnotations.equivalence() to get proper equals/hashcode.
            Multimaps.index(
                contributions,
                node ->
                    ((ContributionBinding) node.delegate())
                        .mapKey()
                        .map(XAnnotations.equivalence()::wrap)));

    for (Set<BindingNode> contributionsForOneMapKey :
        Multimaps.asMap(contributionsByMapKey).values()) {
      if (contributionsForOneMapKey.size() > 1) {
        ImmutableSet<ContributionBinding> contributionBindings =
            contributionsForOneMapKey.stream()
                .map(node -> (ContributionBinding) node.delegate())
                .collect(toImmutableSet());

        diagnosticReporter.reportBinding(
            ERROR,
            multiboundMapBinding,
            duplicateMapKeyErrorMessage(contributionBindings, multiboundMapBinding.key()));
      }
    }
  }

  private void checkForInconsistentMapKeyAnnotationTypes(
      Binding multiboundMapBinding,
      ImmutableSet<BindingNode> contributions,
      DiagnosticReporter diagnosticReporter) {
    ImmutableSetMultimap.Builder<XClassName, ContributionBinding> builder =
        ImmutableSetMultimap.builder();
    for (BindingNode node : contributions) {
      builder.put(
          asClassName(((ContributionBinding) node.delegate()).mapKey().get()),
          (ContributionBinding) node.delegate());
    }
    ImmutableSetMultimap<XClassName, ContributionBinding> contributionsByMapKeyAnnotationType =
        builder.build();

    if (contributionsByMapKeyAnnotationType.keySet().size() > 1) {
      diagnosticReporter.reportBinding(
          ERROR,
          multiboundMapBinding,
          inconsistentMapKeyAnnotationTypesErrorMessage(
              contributionsByMapKeyAnnotationType, multiboundMapBinding.key()));
    }
  }

  private String inconsistentMapKeyAnnotationTypesErrorMessage(
      ImmutableSetMultimap<XClassName, ContributionBinding> contributionsByMapKeyAnnotationType,
      Key mapBindingKey) {
    StringBuilder message =
        new StringBuilder(mapBindingKey.toString())
            .append(" uses more than one @MapKey annotation type");
    Multimaps.asMap(contributionsByMapKeyAnnotationType)
        .forEach(
            (annotationType, contributions) -> {
              message.append('\n').append(INDENT).append(annotationType).append(':');
              declarationFormatter.formatIndentedList(message, contributions, 2);
            });
    return message.toString();
  }

  private String duplicateMapKeyErrorMessage(
      Set<ContributionBinding> contributionsForOneMapKey, Key mapBindingKey) {
    StringBuilder message =
        new StringBuilder("The same map key is bound more than once for ").append(mapBindingKey);

    declarationFormatter.formatIndentedList(
        message, ImmutableList.sortedCopyOf(Declaration.COMPARATOR, contributionsForOneMapKey), 1);
    return message.toString();
  }

  @AutoValue
  abstract static class MapBindingKey {
    abstract Key key();

    abstract ComponentPath componentPath();

    static MapBindingKey create(Key key, ComponentPath componentPath) {
      return new AutoValue_MapMultibindingValidator_MapBindingKey(key, componentPath);
    }
  }

  private static boolean isAncestor(ComponentPath ancestor, ComponentPath descendant) {
    ImmutableList<DaggerTypeElement> ancestorComponents = ancestor.components();
    ImmutableList<DaggerTypeElement> descendantComponents = descendant.components();
    if (ancestorComponents.size() >= descendantComponents.size()) {
      return false;
    }
    return descendantComponents.subList(0, ancestorComponents.size()).equals(ancestorComponents);
  }
}
