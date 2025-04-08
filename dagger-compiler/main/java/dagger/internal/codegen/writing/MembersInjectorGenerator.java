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

package dagger.internal.codegen.writing;

import static androidx.room.compiler.codegen.compat.XConverters.toJavaPoet;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getOnlyElement;
import static dagger.internal.codegen.binding.SourceFiles.bindingTypeElementTypeVariableNames;
import static dagger.internal.codegen.binding.SourceFiles.generateBindingFieldsForDependencies;
import static dagger.internal.codegen.binding.SourceFiles.memberInjectedFieldSignatureForVariable;
import static dagger.internal.codegen.binding.SourceFiles.membersInjectorMethodName;
import static dagger.internal.codegen.binding.SourceFiles.membersInjectorNameForType;
import static dagger.internal.codegen.binding.SourceFiles.parameterizedGeneratedTypeNameForBinding;
import static dagger.internal.codegen.extension.DaggerStreams.presentValues;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableList;
import static dagger.internal.codegen.langmodel.Accessibility.isRawTypePubliclyAccessible;
import static dagger.internal.codegen.langmodel.Accessibility.isTypeAccessibleFrom;
import static dagger.internal.codegen.writing.GwtCompatibility.gwtIncompatibleAnnotation;
import static dagger.internal.codegen.writing.InjectionMethods.copyParameter;
import static dagger.internal.codegen.writing.InjectionMethods.copyParameters;
import static dagger.internal.codegen.xprocessing.XAnnotationSpecs.Suppression.RAWTYPES;
import static dagger.internal.codegen.xprocessing.XAnnotationSpecs.suppressWarnings;
import static dagger.internal.codegen.xprocessing.XCodeBlocks.parameterNames;
import static dagger.internal.codegen.xprocessing.XCodeBlocks.toConcatenatedCodeBlock;
import static dagger.internal.codegen.xprocessing.XElements.asField;
import static dagger.internal.codegen.xprocessing.XElements.asMethod;
import static dagger.internal.codegen.xprocessing.XElements.asTypeElement;
import static dagger.internal.codegen.xprocessing.XElements.getSimpleName;
import static dagger.internal.codegen.xprocessing.XFunSpecs.constructorBuilder;
import static dagger.internal.codegen.xprocessing.XFunSpecs.methodBuilder;
import static dagger.internal.codegen.xprocessing.XTypeElements.typeVariableNames;
import static dagger.internal.codegen.xprocessing.XTypeNames.membersInjectorOf;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

import androidx.room.compiler.codegen.VisibilityModifier;
import androidx.room.compiler.codegen.XAnnotationSpec;
import androidx.room.compiler.codegen.XClassName;
import androidx.room.compiler.codegen.XCodeBlock;
import androidx.room.compiler.codegen.XFunSpec;
import androidx.room.compiler.codegen.XPropertySpec;
import androidx.room.compiler.codegen.XTypeName;
import androidx.room.compiler.codegen.XTypeSpec;
import androidx.room.compiler.processing.XAnnotation;
import androidx.room.compiler.processing.XElement;
import androidx.room.compiler.processing.XFieldElement;
import androidx.room.compiler.processing.XFiler;
import androidx.room.compiler.processing.XMethodElement;
import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XType;
import androidx.room.compiler.processing.XTypeElement;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.squareup.javapoet.ParameterSpec;
import dagger.internal.codegen.base.SourceFileGenerator;
import dagger.internal.codegen.base.UniqueNameSet;
import dagger.internal.codegen.binding.MembersInjectionBinding;
import dagger.internal.codegen.binding.MembersInjectionBinding.InjectionSite;
import dagger.internal.codegen.binding.SourceFiles;
import dagger.internal.codegen.model.DaggerAnnotation;
import dagger.internal.codegen.model.DependencyRequest;
import dagger.internal.codegen.model.Key;
import dagger.internal.codegen.writing.InjectionMethods.InjectionSiteMethod;
import dagger.internal.codegen.xprocessing.Nullability;
import dagger.internal.codegen.xprocessing.XAnnotations;
import dagger.internal.codegen.xprocessing.XFunSpecs;
import dagger.internal.codegen.xprocessing.XTypeNames;
import dagger.internal.codegen.xprocessing.XTypeSpecs;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;

/**
 * Generates {@link MembersInjector} implementations from {@link MembersInjectionBinding} instances.
 */
public final class MembersInjectorGenerator extends SourceFileGenerator<MembersInjectionBinding> {
  private final SourceFiles sourceFiles;

  @Inject
  MembersInjectorGenerator(
      XFiler filer,
      SourceFiles sourceFiles,
      XProcessingEnv processingEnv) {
    super(filer, processingEnv);
    this.sourceFiles = sourceFiles;
  }

  @Override
  public XElement originatingElement(MembersInjectionBinding binding) {
    return binding.membersInjectedType();
  }

  @Override
  public ImmutableList<XTypeSpec> topLevelTypes(MembersInjectionBinding binding) {

    // We don't want to write out resolved bindings -- we want to write out the generic version.
    checkState(
        !binding.unresolved().isPresent(),
        "tried to generate a MembersInjector for a binding of a resolved generic type: %s",
        binding);

    XClassName generatedTypeName = membersInjectorNameForType(binding.membersInjectedType());
    ImmutableList<XTypeName> typeParameters = bindingTypeElementTypeVariableNames(binding);
    ImmutableMap<DependencyRequest, XPropertySpec> frameworkFields = frameworkFields(binding);
    XTypeSpecs.Builder injectorTypeBuilder =
        XTypeSpecs.classBuilder(generatedTypeName)
            .addModifiers(PUBLIC, FINAL)
            .addTypeVariableNames(typeParameters)
            .addAnnotation(qualifierMetadataAnnotation(binding))
            .addSuperinterface(membersInjectorOf(binding.key().type().xprocessing().asTypeName()))
            .addProperties(frameworkFields.values())
            .addFunction(constructor(frameworkFields))
            .addFunction(createMethod(binding, frameworkFields))
            .addFunction(injectMembersMethod(binding, frameworkFields))
            .addFunctions(
                binding.injectionSites().stream()
                    .filter(
                        site -> site.enclosingTypeElement().equals(binding.membersInjectedType()))
                    .map(MembersInjectorGenerator::membersInjectionMethod)
                    .collect(toImmutableList()));

    gwtIncompatibleAnnotation(binding).ifPresent(injectorTypeBuilder::addAnnotation);

    return ImmutableList.of(injectorTypeBuilder.build());
  }

  private static XFunSpec membersInjectionMethod(InjectionSite injectionSite) {
    String methodName = membersInjectorMethodName(injectionSite);
    switch (injectionSite.kind()) {
      case METHOD:
        return methodInjectionMethod(asMethod(injectionSite.element()), methodName);
      case FIELD:
        Optional<XAnnotation> qualifier =
            // methods for fields have a single dependency request
            getOnlyElement(injectionSite.dependencies())
                .key()
                .qualifier()
                .map(DaggerAnnotation::xprocessing);
        return fieldInjectionMethod(asField(injectionSite.element()), methodName, qualifier);
    }
    throw new AssertionError(injectionSite);
  }

  // Example:
  //
  // public static void injectMethod(Instance instance, Foo foo, Bar bar) {
  //   instance.injectMethod(foo, bar);
  // }
  private static XFunSpec methodInjectionMethod(XMethodElement method, String methodName) {
    XTypeElement enclosingType = asTypeElement(method.getEnclosingElement());
    XFunSpecs.Builder builder =
        methodBuilder(methodName)
            .addModifiers(PUBLIC, STATIC)
            .varargs(method.isVarArgs())
            .addTypeVariableNames(typeVariableNames(enclosingType))
            .addExceptions(method.getThrownTypes());

    UniqueNameSet parameterNameSet = new UniqueNameSet();
    XCodeBlock instance = copyInstance(builder, parameterNameSet, enclosingType.getType());
    XCodeBlock arguments = copyParameters(builder, parameterNameSet, method.getParameters());
    return builder.addStatement("%L.%N(%L)", instance, method.getJvmName(), arguments).build();
  }

  // Example:
  //
  // public static void injectFoo(Instance instance, Foo foo) {
  //   instance.foo = foo;
  // }
  private static XFunSpec fieldInjectionMethod(
      XFieldElement field, String methodName, Optional<XAnnotation> qualifier) {
    XTypeElement enclosingType = asTypeElement(field.getEnclosingElement());

    XFunSpecs.Builder builder =
        methodBuilder(methodName)
            .addModifiers(PUBLIC, STATIC)
            .addAnnotation(
                XAnnotationSpec.builder(XTypeNames.INJECTED_FIELD_SIGNATURE)
                    .addMember("value", "%S", memberInjectedFieldSignatureForVariable(field))
                    .build())
            .addTypeVariableNames(typeVariableNames(enclosingType));

    qualifier.map(XAnnotations::getAnnotationSpec).ifPresent(builder::addAnnotation);

    UniqueNameSet parameterNameSet = new UniqueNameSet();
    XCodeBlock instance = copyInstance(builder, parameterNameSet, enclosingType.getType());
    XCodeBlock argument = copyParameters(builder, parameterNameSet, ImmutableList.of(field));
    return builder.addStatement("%L.%N = %L", instance, getSimpleName(field), argument).build();
  }

  private static XCodeBlock copyInstance(
      XFunSpecs.Builder methodBuilder, UniqueNameSet parameterNameSet, XType type) {
    boolean useObject = !isRawTypePubliclyAccessible(type);
    XCodeBlock instance =
        copyParameter(
            methodBuilder,
            type,
            parameterNameSet.getUniqueName("instance"),
            useObject,
            Nullability.NOT_NULLABLE);
    // If we had to cast the instance add an extra parenthesis incase we're calling a method on it.
    return useObject ? XCodeBlock.of("(%L)", instance) : instance;
  }

  // MyClass(
  //     Provider<Dep1> dep1Provider,
  //     Provider<Dep2> dep2Provider,
  //     // Note: The raw type can happen if Dep3 is injected in a super type and not accessible to
  //     // the parent. Ideally, we would have passed in the parent MembersInjector instance itself
  //     // which would have avoided this situation, but doing it now would cause version skew.
  //     @SuppressWarnings("RAW_TYPE") Provider dep3Provider) {
  //   this.dep1Provider = dep1Provider;
  //   this.dep2Provider = dep2Provider;
  //   this.dep3Provider = dep3Provider;
  // }
  private XFunSpec constructor(ImmutableMap<DependencyRequest, XPropertySpec> frameworkFields) {
    ImmutableList<ParameterSpec> dependencyParameters =
        frameworkFields.values().stream()
            .map(
                field ->
                    ParameterSpec.builder(
                            toJavaPoet(field.getType()), field.getName()) // SUPPRESS_GET_NAME_CHECK
                        .addAnnotations(toJavaPoet(field).annotations)
                        .build())
            .collect(toImmutableList());
    return constructorBuilder()
        .addModifiers(PUBLIC)
        .addJavaParameters(dependencyParameters)
        .addCode(
            dependencyParameters.stream()
                .map(parameter -> XCodeBlock.of("this.%1L = %1L;", parameter.name))
                .collect(toConcatenatedCodeBlock()))
        .build();
  }

  // public static MyClass_MembersInjector create(
  //     Provider<Dep1> dep1Provider,
  //     Provider<Dep2> dep2Provider,
  //     // Note: The raw type can happen if Dep3 is injected in a super type and not accessible to
  //     // the parent. Ideally, we would have passed in the parent MembersInjector instance itself
  //     // which would have avoided this situation, but doing it now would cause version skew.
  //     @SuppressWarnings("RAW_TYPE") Provider dep3Provider) {
  //   return new MyClass_MembersInjector(dep1Provider, dep2Provider, dep3Provider);
  // }
  private XFunSpec createMethod(
      MembersInjectionBinding binding,
      ImmutableMap<DependencyRequest, XPropertySpec> frameworkFields) {
    List<ParameterSpec> params = toJavaPoet(constructor(frameworkFields)).parameters;
    // We use a static create method so that generated components can avoid having
    // to refer to the generic types of the factory.
    // (Otherwise they may have visibility problems referring to the types.)
    return methodBuilder("create")
        .addModifiers(PUBLIC, STATIC)
        .addTypeVariableNames(bindingTypeElementTypeVariableNames(binding))
        .returns(membersInjectorOf(binding.key().type().xprocessing().asTypeName()))
        .addJavaParameters(params)
        .addStatement(
            "return %L",
            XCodeBlock.ofNewInstance(
                parameterizedGeneratedTypeNameForBinding(binding), "%L", parameterNames(params)))
        .build();
  }

  // @Override
  // public void injectMembers(Thing instance) {
  //   injectDep1(instance, dep1Provider.get());
  //   injectSomeMethod(instance, dep2Provider.get());
  //   // This is a case where Dep3 is injected in the base class.
  //   MyBaseClass_MembersInjector.injectDep3(instance, dep3Provider.get());
  // }
  private XFunSpec injectMembersMethod(
      MembersInjectionBinding binding,
      ImmutableMap<DependencyRequest, XPropertySpec> frameworkFields) {
    XType instanceType = binding.key().type().xprocessing();
    ImmutableMap<DependencyRequest, XCodeBlock> dependencyCodeBlocks =
        sourceFiles.frameworkFieldUsages(binding.dependencies(), frameworkFields);
    XCodeBlock invokeInjectionSites =
        InjectionSiteMethod.invokeAll(
            binding.injectionSites(),
            membersInjectorNameForType(binding.membersInjectedType()),
            XCodeBlock.of("instance"),
            instanceType,
            dependencyCodeBlocks::get);
    return methodBuilder("injectMembers")
        .addModifiers(PUBLIC)
        .addAnnotation(Override.class)
        .addParameter(toJavaPoet(instanceType.asTypeName()), "instance")
        .addCode(invokeInjectionSites)
        .build();
  }

  private XAnnotationSpec qualifierMetadataAnnotation(MembersInjectionBinding binding) {
    XAnnotationSpec.Builder builder = XAnnotationSpec.builder(XTypeNames.QUALIFIER_METADATA);
    binding.injectionSites().stream()
        // filter out non-local injection sites. Injection sites for super types will be in their
        // own generated _MembersInjector class.
        .filter(
            injectionSite ->
                injectionSite.enclosingTypeElement().equals(binding.membersInjectedType()))
        .flatMap(injectionSite -> injectionSite.dependencies().stream())
        .map(DependencyRequest::key)
        .map(Key::qualifier)
        .flatMap(presentValues())
        .map(DaggerAnnotation::xprocessing)
        .map(XAnnotation::getQualifiedName)
        .distinct()
        .forEach(qualifier -> builder.addMember("value", "%S", qualifier));
    return builder.build();
  }

  private static ImmutableMap<DependencyRequest, XPropertySpec> frameworkFields(
      MembersInjectionBinding binding) {
    UniqueNameSet fieldNames = new UniqueNameSet();
    XClassName membersInjectorTypeName = membersInjectorNameForType(binding.membersInjectedType());
    ImmutableMap.Builder<DependencyRequest, XPropertySpec> builder = ImmutableMap.builder();
    generateBindingFieldsForDependencies(binding)
        .forEach(
            (request, bindingField) -> {
              // If the dependency type is not visible to this members injector, then use the raw
              // framework type for the field.
              boolean useRawFrameworkType =
                  !isTypeAccessibleFrom(
                      request.key().type().xprocessing(), membersInjectorTypeName.getPackageName());
              XTypeName fieldType =
                  useRawFrameworkType ? bindingField.type().getRawTypeName() : bindingField.type();
              String fieldName = fieldNames.getUniqueName(bindingField.name());
              XPropertySpec.Builder fieldBuilder =
                  XPropertySpec.builder(
                      /* name= */ fieldName,
                      /* typeName= */ fieldType,
                      /* visibility= */ VisibilityModifier.PRIVATE,
                      /* isMutable= */ false,
                      /* addJavaNullabilityAnnotation= */ false);
              if (useRawFrameworkType) {
                fieldBuilder.addAnnotation(suppressWarnings(RAWTYPES));
              }
              builder.put(request, fieldBuilder.build());
            });
    return builder.buildOrThrow();
  }
}
