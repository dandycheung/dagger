/*
 * Copyright (C) 2014 The Dagger Authors.
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

package dagger.internal.codegen;

import androidx.room3.compiler.processing.util.Source;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import dagger.testing.compile.CompilerTests;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class MapMultibindingValidationTest {
  @Parameters(name = "{0}")
  public static ImmutableList<Object[]> parameters() {
    return CompilerMode.TEST_PARAMETERS;
  }

  private final CompilerMode compilerMode;

  public MapMultibindingValidationTest(CompilerMode compilerMode) {
    this.compilerMode = compilerMode;
  }

  @Test
  public void duplicateMapKeys_UnwrappedMapKey() {
    Source module =
        CompilerTests.javaSource(
            "test.MapModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.StringKey;",
            "import dagger.multibindings.IntoMap;",
            "",
            "@Module",
            "final class MapModule {",
            "  @Provides @IntoMap @StringKey(\"AKey\") Object provideObjectForAKey() {",
            "    return \"one\";",
            "  }",
            "",
            "  @Provides @IntoMap @StringKey(\"AKey\") Object provideObjectForAKeyAgain() {",
            "    return \"one again\";",
            "  }",
            "}");

    // If they're all there, report only Map<K, V>.
    CompilerTests.daggerCompiler(
            module,
            component(
                "Map<String, Object> objects();",
                "Map<String, Provider<Object>> objectProviders();",
                "Producer<Map<String, Producer<Object>>> objectProducers();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "The same map key is bound more than once for Map<String,Object>");
              subject.hasErrorContaining("provideObjectForAKey()");
              subject.hasErrorContaining("provideObjectForAKeyAgain()");
            });

    CompilerTests.daggerCompiler(module)
        .withProcessingOptions(
            ImmutableMap.<String, String>builder()
                .putAll(compilerMode.processorOptions())
                .put("dagger.fullBindingGraphValidation", "ERROR")
                .buildOrThrow())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject
                  .hasErrorContaining(
                      "The same map key is bound more than once for Map<String,Object>")
                  .onSource(module)
                  .onLineContaining("class MapModule");
              subject.hasErrorContaining("provideObjectForAKey()");
              subject.hasErrorContaining("provideObjectForAKeyAgain()");
            });

    // If there's Map<K, V> and Map<K, Provider<V>>, report only Map<K, V>.
    CompilerTests.daggerCompiler(
            module,
            component(
                "Map<String, Object> objects();",
                "Map<String, Provider<Object>> objectProviders();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "The same map key is bound more than once for Map<String,Object>");
            });

    // If there's Map<K, V> and Map<K, Producer<V>>, report only Map<K, V>.
    CompilerTests.daggerCompiler(
            module,
            component(
                "Map<String, Object> objects();",
                "Producer<Map<String, Producer<Object>>> objectProducers();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "The same map key is bound more than once for Map<String,Object>");
            });

    // If there's Map<K, Provider<V>> and Map<K, Producer<V>>, report only Map<K, Provider<V>>.
    CompilerTests.daggerCompiler(
            module,
            component(
                "Map<String, Provider<Object>> objectProviders();",
                "Producer<Map<String, Producer<Object>>> objectProducers();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "The same map key is bound more than once for Map<String,Provider<Object>>");
            });

    CompilerTests.daggerCompiler(module, component("Map<String, Object> objects();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "The same map key is bound more than once for Map<String,Object>");
            });

    CompilerTests.daggerCompiler(
            module, component("Map<String, Provider<Object>> objectProviders();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "The same map key is bound more than once for Map<String,Provider<Object>>");
            });

    CompilerTests.daggerCompiler(
            module, component("Producer<Map<String, Producer<Object>>> objectProducers();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "The same map key is bound more than once for Map<String,Producer<Object>>");
            });
  }

  @Test
  public void duplicateMapKeys_WrappedMapKey() {
    Source module =
        CompilerTests.javaSource(
            "test.MapModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.MapKey;",
            "",
            "@Module",
            "abstract class MapModule {",
            "",
            "  @MapKey(unwrapValue = false)",
            "  @interface WrappedMapKey {",
            "    String value();",
            "  }",
            "",
            "  @Provides",
            "  @IntoMap",
            "  @WrappedMapKey(\"foo\")",
            "  static String stringMapEntry1() { return \"\"; }",
            "",
            "  @Provides",
            "  @IntoMap",
            "  @WrappedMapKey(\"foo\")",
            "  static String stringMapEntry2() { return \"\"; }",
            "}");

    Source component = component("Map<test.MapModule.WrappedMapKey, String> objects();");

    CompilerTests.daggerCompiler(module, component)
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject
                  .hasErrorContaining(
                      String.join(
                          "\n",
                          "\033[1;31m[Dagger/MapKeys]\033[0m The same map key is bound more than "
                              + "once for Map<MapModule.WrappedMapKey,String>",
                          "    @Provides @IntoMap @MapModule.WrappedMapKey(\"foo\") String "
                              + "MapModule.stringMapEntry1()",
                          "    @Provides @IntoMap @MapModule.WrappedMapKey(\"foo\") String "
                              + "MapModule.stringMapEntry2()"))
                  .onSource(component)
                  .onLineContaining("interface TestComponent");
            });
  }

  @Test
  public void inconsistentMapKeyAnnotations() {
    Source module =
        CompilerTests.javaSource(
            "test.MapModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.StringKey;",
            "import dagger.multibindings.IntoMap;",
            "",
            "@Module",
            "final class MapModule {",
            "  @Provides @IntoMap @StringKey(\"AKey\") Object provideObjectForAKey() {",
            "    return \"one\";",
            "  }",
            "",
            "  @Provides @IntoMap @StringKeyTwo(\"BKey\") Object provideObjectForBKey() {",
            "    return \"two\";",
            "  }",
            "}");
    Source stringKeyTwoFile =
        CompilerTests.javaSource(
            "test.StringKeyTwo",
            "package test;",
            "",
            "import dagger.MapKey;",
            "",
            "@MapKey(unwrapValue = true)",
            "public @interface StringKeyTwo {",
            "  String value();",
            "}");

    // If they're all there, report only Map<K, V>.
    CompilerTests.daggerCompiler(
            module,
            stringKeyTwoFile,
            component(
                "Map<String, Object> objects();",
                "Map<String, Provider<Object>> objectProviders();",
                "Producer<Map<String, Producer<Object>>> objectProducers();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "Map<String,Object> uses more than one @MapKey annotation type");
              subject.hasErrorContaining("provideObjectForAKey()");
              subject.hasErrorContaining("provideObjectForBKey()");
            });

    CompilerTests.daggerCompiler(module, stringKeyTwoFile)
        .withProcessingOptions(
            ImmutableMap.<String, String>builder()
                .putAll(compilerMode.processorOptions())
                .put("dagger.fullBindingGraphValidation", "ERROR")
                .buildOrThrow())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject
                  .hasErrorContaining(
                      "Map<String,Object> uses more than one @MapKey annotation type")
                  .onSource(module)
                  .onLineContaining("class MapModule");
              subject.hasErrorContaining("provideObjectForAKey()");
              subject.hasErrorContaining("provideObjectForBKey()");
            });

    // If there's Map<K, V> and Map<K, Provider<V>>, report only Map<K, V>.
    CompilerTests.daggerCompiler(
            module,
            stringKeyTwoFile,
            component(
                "Map<String, Object> objects();",
                "Map<String, Provider<Object>> objectProviders();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "Map<String,Object> uses more than one @MapKey annotation type");
            });

    // If there's Map<K, V> and Map<K, Producer<V>>, report only Map<K, V>.
    CompilerTests.daggerCompiler(
            module,
            stringKeyTwoFile,
            component(
                "Map<String, Object> objects();",
                "Producer<Map<String, Producer<Object>>> objectProducers();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "Map<String,Object> uses more than one @MapKey annotation type");
            });

    // If there's Map<K, Provider<V>> and Map<K, Producer<V>>, report only Map<K, Provider<V>>.
    CompilerTests.daggerCompiler(
            module,
            stringKeyTwoFile,
            component(
                "Map<String, Provider<Object>> objectProviders();",
                "Producer<Map<String, Producer<Object>>> objectProducers();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "Map<String,Provider<Object>> uses more than one @MapKey annotation type");
            });

    CompilerTests.daggerCompiler(
            module, stringKeyTwoFile, component("Map<String, Object> objects();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "Map<String,Object> uses more than one @MapKey annotation type");
            });

    CompilerTests.daggerCompiler(
            module, stringKeyTwoFile, component("Map<String, Provider<Object>> objectProviders();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "Map<String,Provider<Object>> uses more than one @MapKey annotation type");
            });

    CompilerTests.daggerCompiler(
            module,
            stringKeyTwoFile,
            component("Producer<Map<String, Producer<Object>>> objectProducers();"))
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(1);
              subject.hasErrorContaining(
                  "Map<String,Producer<Object>> uses more than one @MapKey annotation type");
            });
  }

  @Test
  public void mapBindingOfProvider_provides() {
    Source providesModule =
        CompilerTests.javaSource(
            "test.MapModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "import javax.inject.Provider;",
            "",
            "@Module",
            "abstract class MapModule {",
            "",
            "  @Provides",
            "  @IntoMap",
            "  @StringKey(\"foo\")",
            "  static Provider<String> provideProvider() {",
            "    return null;",
            "  }",
            "}");

    // Entry points aren't needed because the check we care about here is a module validation
    Source providesComponent = component("");

    CompilerTests.daggerCompiler(providesModule, providesComponent)
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(2);
              subject.hasErrorContaining(
                  "@Provides methods with @IntoMap must not return framework types");
              subject
                  .hasErrorContaining("test.MapModule has errors")
                  .onSource(providesComponent)
                  .onLineContaining("@Component(modules = {MapModule.class})");
            });
  }

  @Test
  public void mapBindingOfProvider_binds() {
    Source bindsModule =
        CompilerTests.javaSource(
            "test.MapModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Binds;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "import javax.inject.Provider;",
            "",
            "@Module",
            "abstract class MapModule {",
            "",
            "  @Binds",
            "  @IntoMap",
            "  @StringKey(\"foo\")",
            "  abstract Provider<String> provideProvider(Provider<String> provider);",
            "}");

    // Entry points aren't needed because the check we care about here is a module validation
    Source bindsComponent = component("");

    CompilerTests.daggerCompiler(bindsModule, bindsComponent)
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(
            subject -> {
              subject.hasErrorCount(2);
              subject.hasErrorContaining(
                  "@Binds methods with @IntoMap must not return framework types");
              subject
                  .hasErrorContaining("test.MapModule has errors")
                  .onSource(bindsComponent)
                  .onLineContaining("@Component(modules = {MapModule.class})");
            });
  }

  @Test
  public void duplicateMapKeysAcrossComponents() {
    Source parentModule =
        CompilerTests.javaSource(
            "test.ParentModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "final class ParentModule {",
            "  @Provides @IntoMap @StringKey(\"A\") Object provideParent() { return \"parent\"; }",
            "}");
    Source childModule =
        CompilerTests.javaSource(
            "test.ChildModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "final class ChildModule {",
            "  @Provides @IntoMap @StringKey(\"A\") Object provideChild() { return \"child\"; }",
            "}");
    Source parentComponent =
        CompilerTests.javaSource(
            "test.ParentComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component(modules = ParentModule.class)",
            "interface ParentComponent {",
            "  ChildComponent child();",
            "}");
    Source childComponent =
        CompilerTests.javaSource(
            "test.ChildComponent",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "import java.util.Map;",
            "",
            "@Subcomponent(modules = ChildModule.class)",
            "interface ChildComponent {",
            "  Map<String, Object> map();",
            "}");

    CompilerTests.daggerCompiler(parentModule, childModule, parentComponent, childComponent)
        .withProcessingOptions(
            ImmutableMap.<String, String>builder()
                .putAll(compilerMode.processorOptions())
                .put("dagger.mapMultibindingDuplicateDetectionFix", "ENABLED")
                .buildOrThrow())
        .compile(
            subject -> {
              subject
                  .hasErrorContaining(
                      String.join(
                          "\n",
                          "\033[1;31m[Dagger/MapKeys]\033[0m The same map key is bound more than "
                              + "once for Map<String,Object>",
                          "    @Provides @IntoMap @StringKey(\"A\") Object "
                              + "ChildModule.provideChild()",
                          "    @Provides @IntoMap @StringKey(\"A\") Object "
                              + "ParentModule.provideParent()",
                          "    Map<String,Object> is requested at",
                          "        [ChildComponent] ChildComponent.map() "
                              + "[ParentComponent → ChildComponent]"))
                  .onSource(parentComponent)
                  .onLineContaining("interface ParentComponent");
            });
  }

  @Test
  public void siblingComponentDuplicateMapKeys() {
    Source parentModule =
        CompilerTests.javaSource(
            "test.ParentModule",
            "package test;",
            "",
            "import dagger.Module;",
            "",
            "@Module",
            "final class ParentModule {}");
    Source child1Module =
        CompilerTests.javaSource(
            "test.Child1Module",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "final class Child1Module {",
            "  @Provides @IntoMap @StringKey(\"A\") Object provideChild1() { return \"child1\"; }",
            "}");
    Source child2Module =
        CompilerTests.javaSource(
            "test.Child2Module",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "final class Child2Module {",
            "  @Provides @IntoMap @StringKey(\"A\") Object provideChild2() { return \"child2\"; }",
            "}");
    Source parentComponent =
        CompilerTests.javaSource(
            "test.ParentComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component(modules = ParentModule.class)",
            "interface ParentComponent {",
            "  Child1Component child1();",
            "",
            "  Child2Component child2();",
            "}");
    Source child1Component =
        CompilerTests.javaSource(
            "test.Child1Component",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "import java.util.Map;",
            "",
            "@Subcomponent(modules = Child1Module.class)",
            "interface Child1Component {",
            "  Map<String, Object> map();",
            "}");
    Source child2Component =
        CompilerTests.javaSource(
            "test.Child2Component",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "import java.util.Map;",
            "",
            "@Subcomponent(modules = Child2Module.class)",
            "interface Child2Component {",
            "  Map<String, Object> map();",
            "}");

    CompilerTests.daggerCompiler(
            parentModule,
            child1Module,
            child2Module,
            parentComponent,
            child1Component,
            child2Component)
        .withProcessingOptions(
            ImmutableMap.<String, String>builder()
                .putAll(compilerMode.processorOptions())
                .put("dagger.mapMultibindingDuplicateDetectionFix", "ENABLED")
                .buildOrThrow())
        .compile(
            subject -> {
              // Sibling components should be allowed to have duplicate keys.
              subject.hasErrorCount(0);
            });
  }

  @Test
  public void ancestorConflictDuplicateMapKeys() {
    Source parentModule =
        CompilerTests.javaSource(
            "test.ParentModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "final class ParentModule {",
            "  @Provides @IntoMap @StringKey(\"A\") Object provideParent() { return \"parent\"; }",
            "}");
    Source childModule =
        CompilerTests.javaSource(
            "test.ChildModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "final class ChildModule {",
            "  @Provides @IntoMap @StringKey(\"A\") Object provideChild() { return \"child\"; }",
            "}");
    Source grandchildModule =
        CompilerTests.javaSource(
            "test.GrandchildModule",
            "package test;",
            "",
            "import dagger.Module;",
            "",
            "@Module",
            "final class GrandchildModule {}");
    Source parentComponent =
        CompilerTests.javaSource(
            "test.ParentComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component(modules = ParentModule.class)",
            "interface ParentComponent {",
            "  ChildComponent child();",
            "}");
    Source childComponent =
        CompilerTests.javaSource(
            "test.ChildComponent",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "",
            "@Subcomponent(modules = ChildModule.class)",
            "interface ChildComponent {",
            "  GrandchildComponent grandchild();",
            "}");
    Source grandchildComponent =
        CompilerTests.javaSource(
            "test.GrandchildComponent",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "import java.util.Map;",
            "",
            "@Subcomponent(modules = GrandchildModule.class)",
            "interface GrandchildComponent {",
            "  Map<String, Object> map();",
            "}");

    CompilerTests.daggerCompiler(
            parentModule,
            childModule,
            grandchildModule,
            parentComponent,
            childComponent,
            grandchildComponent)
        .withProcessingOptions(
            ImmutableMap.<String, String>builder()
                .putAll(compilerMode.processorOptions())
                .put("dagger.mapMultibindingDuplicateDetectionFix", "ENABLED")
                .buildOrThrow())
        .compile(
            subject -> {
              subject
                  .hasErrorContaining(
                      String.join(
                          "\n",
                          "\033[1;31m[Dagger/MapKeys]\033[0m The same map key is bound more than "
                              + "once for Map<String,Object>",
                          "    @Provides @IntoMap @StringKey(\"A\") Object "
                              + "ChildModule.provideChild()",
                          "    @Provides @IntoMap @StringKey(\"A\") Object "
                              + "ParentModule.provideParent()",
                          "    Map<String,Object> is requested at",
                          "        [GrandchildComponent] GrandchildComponent.map() "
                              + "[ParentComponent → ChildComponent → GrandchildComponent]"))
                  .onSource(parentComponent)
                  .onLineContaining("interface ParentComponent");
            });
  }

  @Test
  public void nonConflictingGrandchildDuplicateMapKeys() {
    Source parentModule =
        CompilerTests.javaSource(
            "test.ParentModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "final class ParentModule {",
            "  @Provides @IntoMap @StringKey(\"ParentKey\") Object provideParent() {",
            "    return \"parent\";",
            "  }",
            "}");
    Source childModule =
        CompilerTests.javaSource(
            "test.ChildModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "final class ChildModule {",
            "  @Provides @IntoMap @StringKey(\"ChildKey\") Object provideChild() {",
            "    return \"child\";",
            "  }",
            "}");
    Source grandchildModule =
        CompilerTests.javaSource(
            "test.GrandchildModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "final class GrandchildModule {",
            "  @Provides @IntoMap @StringKey(\"GrandchildKey\") Object provideGrandchild() {",
            "    return \"grandchild\";",
            "  }",
            "}");
    Source parentComponent =
        CompilerTests.javaSource(
            "test.ParentComponent",
            "package test;",
            "import dagger.Component;",
            "@Component(modules = ParentModule.class)",
            "interface ParentComponent {",
            "  ChildComponent child();",
            "}");
    Source childComponent =
        CompilerTests.javaSource(
            "test.ChildComponent",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "",
            "@Subcomponent(modules = ChildModule.class)",
            "interface ChildComponent {",
            "  GrandchildComponent grandchild();",
            "}");
    Source grandchildComponent =
        CompilerTests.javaSource(
            "test.GrandchildComponent",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "import java.util.Map;",
            "",
            "@Subcomponent(modules = GrandchildModule.class)",
            "interface GrandchildComponent {",
            "  Map<String, Object> map();",
            "}");

    CompilerTests.daggerCompiler(
            parentModule,
            childModule,
            grandchildModule,
            parentComponent,
            childComponent,
            grandchildComponent)
        .withProcessingOptions(
            ImmutableMap.<String, String>builder()
                .putAll(compilerMode.processorOptions())
                .put("dagger.mapMultibindingDuplicateDetectionFix", "ENABLED")
                .buildOrThrow())
        .compile(
            subject -> {
              subject.hasErrorCount(0);
            });
  }

  @Test
  public void moduleInclusionDeduplication() {
    // Verifies that when a component installs multiple modules that both include
    // the same shared module (which contains multibinding contributions), Dagger
    // successfully de-duplicates the shared module and does not report duplicate
    // key errors under strict mode.
    Source sharedModule =
        CompilerTests.javaSource(
            "test.SharedModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "final class SharedModule {",
            "  @Provides @IntoMap @StringKey(\"SharedKey\") Object provideShared() {",
            "    return \"shared\";",
            "  }",
            "}");
    Source moduleA =
        CompilerTests.javaSource(
            "test.ModuleA",
            "package test;",
            "",
            "import dagger.Module;",
            "",
            "@Module(includes = SharedModule.class)",
            "final class ModuleA {}");
    Source moduleB =
        CompilerTests.javaSource(
            "test.ModuleB",
            "package test;",
            "",
            "import dagger.Module;",
            "",
            "@Module(includes = SharedModule.class)",
            "final class ModuleB {}");
    Source component =
        CompilerTests.javaSource(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "import java.util.Map;",
            "",
            "@Component(modules = {ModuleA.class, ModuleB.class})",
            "interface TestComponent {",
            "  Map<String, Object> map();",
            "}");

    CompilerTests.daggerCompiler(sharedModule, moduleA, moduleB, component)
        .withProcessingOptions(
            ImmutableMap.<String, String>builder()
                .putAll(compilerMode.processorOptions())
                .put("dagger.mapMultibindingDuplicateDetectionFix", "ENABLED")
                .buildOrThrow())
        .compile(
            subject -> {
              subject.hasErrorCount(0);
            });
  }

  @Test
  public void unusedSubcomponentDuplicates_prunedVsFullGraph() {
    // Verifies Dagger's validation behavior for unused subcomponents under strict mode:
    // 1. Pruned Graph Validation (default): Duplicate multibinding keys in an unused
    //    subcomponent are ignored because the subcomponent is pruned from the graph.
    // 2. Full Graph Validation: The same duplicate keys are successfully caught and
    //    reported as errors because the entire graph is validated regardless of reachability.
    Source parentModule =
        CompilerTests.javaSource(
            "test.ParentModule",
            "package test;",
            "",
            "import dagger.Module;",
            "",
            "@Module(subcomponents = ChildComponent.class)",
            "final class ParentModule {}");
    Source childModule =
        CompilerTests.javaSource(
            "test.ChildModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "final class ChildModule {",
            "  @Provides @IntoMap @StringKey(\"A\") Object provideChild1() {",
            "    return \"child1\";",
            "  }",
            "",
            "  @Provides @IntoMap @StringKey(\"A\") Object provideChild2() {",
            "    return \"child2\";",
            "  }",
            "}");
    Source parentComponent =
        CompilerTests.javaSource(
            "test.ParentComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component(modules = ParentModule.class)",
            "interface ParentComponent {",
            "  // No entry point for ChildComponent or its builder",
            "}");
    Source childComponent =
        CompilerTests.javaSource(
            "test.ChildComponent",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "import java.util.Map;",
            "",
            "@Subcomponent(modules = ChildModule.class)",
            "interface ChildComponent {",
            "  Map<String, Object> map();",
            "",
            "  @Subcomponent.Builder",
            "  interface Builder {",
            "    ChildComponent build();",
            "  }",
            "}");

    // 1. Pruned validation (default): Should pass because ChildComponent is unused and pruned.
    CompilerTests.daggerCompiler(parentModule, childModule, parentComponent, childComponent)
        .withProcessingOptions(
            ImmutableMap.<String, String>builder()
                .putAll(compilerMode.processorOptions())
                .put("dagger.mapMultibindingDuplicateDetectionFix", "ENABLED")
                .buildOrThrow())
        .compile(
            subject -> {
              subject.hasErrorCount(0);
            });

    // 2. Full Graph validation: Should fail because it validates the full graph including unused
    // subcomponents.
    CompilerTests.daggerCompiler(parentModule, childModule, parentComponent, childComponent)
        .withProcessingOptions(
            ImmutableMap.<String, String>builder()
                .putAll(compilerMode.processorOptions())
                .put("dagger.mapMultibindingDuplicateDetectionFix", "ENABLED")
                .put("dagger.fullBindingGraphValidation", "ERROR")
                .buildOrThrow())
        .compile(
            subject -> {
              subject.hasErrorContaining(
                  "The same map key is bound more than once for Map<String,Object>");
              subject.hasErrorContaining("provideChild1()");
              subject.hasErrorContaining("provideChild2()");
            });
  }

  private static Source component(String... entryPoints) {
    return CompilerTests.javaSource(
        "test.TestComponent",
        ImmutableList.<String>builder()
            .add(
                "package test;",
                "",
                "import dagger.Component;",
                "import dagger.producers.Producer;",
                "import java.util.Map;",
                "import javax.inject.Provider;",
                "",
                "@Component(modules = {MapModule.class})",
                "interface TestComponent {")
            .add(entryPoints)
            .add("}")
            .build());
  }
}
