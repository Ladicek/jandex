package org.jboss.jandex;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.jboss.jandex.AnnotationTransformation.TransformationContext;

class AnnotationOverlayImpl implements AnnotationOverlay {
    static final CuckooHashTable.KeyOps<Declaration> KEY_OPS = new CuckooHashTable.KeyOps<Declaration>() {
        @Override
        public boolean equals(Declaration key1, Declaration key2) {
            AnnotationTarget.Kind kind = key1.kind();
            if (kind != key2.kind()) {
                return false;
            }
            if (kind == AnnotationTarget.Kind.CLASS) {
                return nameEquals(key1.asClass().name(), key2.asClass().name());
            } else if (kind == AnnotationTarget.Kind.FIELD) {
                FieldInfo f1 = key1.asField();
                FieldInfo f2 = key2.asField();
                return nameEquals(f1.declaringClass().name(), f2.declaringClass().name())
                        && typeEquals(f1.type(), f2.type())
                        && nameEquals(f1.fieldInternal().nameBytes(), f2.fieldInternal().nameBytes());
            } else if (kind == AnnotationTarget.Kind.METHOD) {
                MethodInfo m1 = key1.asMethod();
                MethodInfo m2 = key2.asMethod();
                return nameEquals(m1.declaringClass().name(), m2.declaringClass().name())
                        && typeEquals(m1.returnType(), m2.returnType())
                        && nameEquals(m1.methodInternal().nameBytes(), m2.methodInternal().nameBytes())
                        && typesEquals(m1.methodInternal().parameterTypesArray(), m2.methodInternal().parameterTypesArray());
            } else if (kind == AnnotationTarget.Kind.METHOD_PARAMETER) {
                MethodParameterInfo mp1 = key1.asMethodParameter();
                MethodParameterInfo mp2 = key2.asMethodParameter();
                return equals(mp1.method(), mp2.method()) && mp1.position() == mp2.position();
            } else if (kind == AnnotationTarget.Kind.RECORD_COMPONENT) {
                RecordComponentInfo rc1 = key1.asRecordComponent();
                RecordComponentInfo rc2 = key2.asRecordComponent();
                return nameEquals(rc1.declaringClass().name(), rc2.declaringClass().name())
                        && typeEquals(rc1.type(), rc2.type())
                        && nameEquals(rc1.recordComponentInternal().nameBytes(), rc2.recordComponentInternal().nameBytes());
            } else {
                // this branch should never be taken
                return false;
            }
        }

        private boolean nameEquals(DotName name1, DotName name2) {
            return name1.equals(name2);
        }

        private boolean nameEquals(byte[] name1, byte[] name2) {
            return Arrays.equals(name1, name2);
        }

        private boolean typeEquals(Type type1, Type type2) {
            Type.Kind kind = type1.kind();
            if (kind != type2.kind()) {
                return false;
            }
            if (!type1.name().equals(type2.name())) {
                return false;
            }

            // at this point, the types have the same kind and the same name
            // that eliminated most of non-equal types, the following tests are more thorough and slower
            //
            // the following kinds of types need no more comparisons: void, primitive types, class types, array types,
            // type variable references, unresolved type variables
            if (kind == Type.Kind.PARAMETERIZED_TYPE) {
                Type[] typeArgs1 = type1.asParameterizedType().argumentsArray();
                Type[] typeArgs2 = type2.asParameterizedType().argumentsArray();
                Type owner1 = type1.asParameterizedType().owner();
                Type owner2 = type2.asParameterizedType().owner();
                return typesEquals(typeArgs1, typeArgs2)
                        && (owner1 == null && owner2 == null || owner1 != null && owner2 != null && typeEquals(owner1, owner2));
            } else if (kind == Type.Kind.TYPE_VARIABLE) {
                Type[] bounds1 = type1.asTypeVariable().boundArray();
                Type[] bounds2 = type2.asTypeVariable().boundArray();
                return typesEquals(bounds1, bounds2);
            } else if (kind == Type.Kind.WILDCARD_TYPE) {
                WildcardType wildcard1 = type1.asWildcardType();
                WildcardType wildcard2 = type2.asWildcardType();
                return wildcard1.isExtends() == wildcard2.isExtends()
                        && wildcard1.hasImplicitObjectBound() == wildcard2.hasImplicitObjectBound()
                        && typeEquals(wildcard1.bound(), wildcard2.bound());
            } else {
                return true;
            }
        }

        private boolean typesEquals(Type[] types1, Type[] types2) {
            if (types1.length != types2.length) {
                return false;
            }
            for (int i = 0; i < types1.length; i++) {
                if (!typeEquals(types1[i], types2[i])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode(Declaration key) {
            if (key.kind() == AnnotationTarget.Kind.CLASS) {
                return nameHashCode(key.asClass().name());
            } else if (key.kind() == AnnotationTarget.Kind.FIELD) {
                FieldInfo f = key.asField();
                int result = nameHashCode(f.declaringClass().name());
                result = 31 * result + typeHashCode(f.type());
                result = 31 * result + nameHashCode(f.fieldInternal().nameBytes());
                return result;
            } else if (key.kind() == AnnotationTarget.Kind.METHOD) {
                MethodInfo m = key.asMethod();
                int result = nameHashCode(m.declaringClass().name());
                result = 31 * result + typeHashCode(m.returnType());
                result = 31 * result + nameHashCode(m.methodInternal().nameBytes());
                result = 31 * result + typesHashCode(m.methodInternal().parameterTypesArray());
                return result;
            } else if (key.kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
                MethodParameterInfo mp = key.asMethodParameter();
                return 31 * mp.position() + hashCode(mp.method());
            } else if (key.kind() == AnnotationTarget.Kind.RECORD_COMPONENT) {
                RecordComponentInfo rc = key.asRecordComponent();
                int result = nameHashCode(rc.declaringClass().name());
                result = 31 * result + typeHashCode(rc.type());
                result = 31 * result + nameHashCode(rc.recordComponentInternal().nameBytes());
                return result;
            } else {
                // this branch should never be taken
                return 0;
            }
        }

        private int nameHashCode(DotName name) {
            return name.hashCode();
        }

        private int nameHashCode(byte[] name) {
            return Arrays.hashCode(name);
        }

        private int typeHashCode(Type type) {
            // this might lead to collisions, but it's relatively fast
            return type.name().hashCode();
        }

        private int typesHashCode(Type[] types) {
            int result = 1;
            for (Type type : types) {
                result = 31 * result + typeHashCode(type);
            }
            return result;
        }
    };

    final IndexView index;
    final boolean compatibleMode;
    final boolean runtimeAnnotationsOnly;
    final boolean inheritedAnnotations;
    final List<AnnotationTransformation> transformations;
    final CuckooHashTable<Declaration, Collection<AnnotationInstance>> overlay = new CuckooHashTable<>(KEY_OPS);

    AnnotationOverlayImpl(IndexView index, boolean compatibleMode, boolean runtimeAnnotationsOnly, boolean inheritedAnnotations,
            Collection<AnnotationTransformation> annotationTransformations) {
        this.index = index;
        this.compatibleMode = compatibleMode;
        this.runtimeAnnotationsOnly = runtimeAnnotationsOnly;
        this.inheritedAnnotations = inheritedAnnotations;
        if (!compatibleMode) {
            for (AnnotationTransformation transformation : annotationTransformations) {
                if (transformation.requiresCompatibleMode()) {
                    throw new IllegalStateException("Compatible mode required by " + transformation);
                }
            }
        }
        List<AnnotationTransformation> transformations = new ArrayList<>(annotationTransformations);
        transformations.sort(new Comparator<AnnotationTransformation>() {
            @Override
            public int compare(AnnotationTransformation o1, AnnotationTransformation o2) {
                return Integer.compare(o2.priority(), o1.priority());
            }
        });
        this.transformations = transformations;
    }

    @Override
    public final IndexView index() {
        return index;
    }

    @Override
    public final boolean hasAnnotation(Declaration declaration, DotName name) {
        if (compatibleMode && declaration.kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
            throw new UnsupportedOperationException();
        }

        Collection<AnnotationInstance> annotations = getAnnotationsFor(declaration);
        for (AnnotationInstance annotation : annotations) {
            if (annotation.name().equals(name)) {
                return true;
            }
        }

        if (inheritedAnnotations && declaration.kind() == AnnotationTarget.Kind.CLASS
                && declaration.asClass().superName() != null) {
            ClassInfo clazz = index.getClassByName(declaration.asClass().superName());
            while (clazz != null && !DotName.OBJECT_NAME.equals(clazz.name())) {
                for (AnnotationInstance annotation : getAnnotationsFor(clazz)) {
                    ClassInfo annotationClass = index.getClassByName(annotation.name());
                    if (annotationClass != null
                            && annotationClass.hasDeclaredAnnotation(DotName.INHERITED_NAME)
                            && annotation.name().equals(name)) {
                        return true;
                    }
                }
                clazz = index.getClassByName(clazz.superName());
            }
        }

        return false;
    }

    @Override
    public final boolean hasAnyAnnotation(Declaration declaration, Set<DotName> names) {
        if (compatibleMode && declaration.kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
            throw new UnsupportedOperationException();
        }

        Collection<AnnotationInstance> annotations = getAnnotationsFor(declaration);
        for (AnnotationInstance annotation : annotations) {
            for (DotName name : names) {
                if (annotation.name().equals(name)) {
                    return true;
                }
            }
        }

        if (inheritedAnnotations && declaration.kind() == AnnotationTarget.Kind.CLASS
                && declaration.asClass().superName() != null) {
            ClassInfo clazz = index.getClassByName(declaration.asClass().superName());
            while (clazz != null && !DotName.OBJECT_NAME.equals(clazz.name())) {
                for (AnnotationInstance annotation : getAnnotationsFor(clazz)) {
                    ClassInfo annotationClass = index.getClassByName(annotation.name());
                    if (annotationClass != null && annotationClass.hasDeclaredAnnotation(DotName.INHERITED_NAME)) {
                        for (DotName name : names) {
                            if (annotation.name().equals(name)) {
                                return true;
                            }
                        }
                    }
                }
                clazz = index.getClassByName(clazz.superName());
            }
        }

        return false;
    }

    @Override
    public final AnnotationInstance annotation(Declaration declaration, DotName name) {
        if (compatibleMode && declaration.kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
            throw new UnsupportedOperationException();
        }

        Collection<AnnotationInstance> annotations = getAnnotationsFor(declaration);
        for (AnnotationInstance annotation : annotations) {
            if (annotation.name().equals(name)) {
                return annotation;
            }
        }

        if (inheritedAnnotations && declaration.kind() == AnnotationTarget.Kind.CLASS
                && declaration.asClass().superName() != null) {
            ClassInfo clazz = index.getClassByName(declaration.asClass().superName());
            while (clazz != null && !DotName.OBJECT_NAME.equals(clazz.name())) {
                for (AnnotationInstance annotation : getAnnotationsFor(clazz)) {
                    ClassInfo annotationClass = index.getClassByName(annotation.name());
                    if (annotationClass != null
                            && annotationClass.hasDeclaredAnnotation(DotName.INHERITED_NAME)
                            && annotation.name().equals(name)) {
                        return annotation;
                    }
                }
                clazz = index.getClassByName(clazz.superName());
            }
        }

        return null;
    }

    @Override
    public final Collection<AnnotationInstance> annotationsWithRepeatable(Declaration declaration, DotName name) {
        if (compatibleMode && declaration.kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
            throw new UnsupportedOperationException();
        }

        DotName containerName = null;
        {
            ClassInfo annotationClass = index.getClassByName(name);
            if (annotationClass != null) {
                AnnotationInstance repeatable = annotationClass.declaredAnnotation(DotName.REPEATABLE_NAME);
                if (repeatable != null) {
                    containerName = repeatable.value().asClass().name();
                }
            }
        }

        List<AnnotationInstance> result = new ArrayList<>();
        for (AnnotationInstance annotation : getAnnotationsFor(declaration)) {
            if (annotation.name().equals(name)) {
                result.add(annotation);
            } else if (annotation.name().equals(containerName)) {
                AnnotationInstance[] nestedAnnotations = annotation.value().asNestedArray();
                for (AnnotationInstance nestedAnnotation : nestedAnnotations) {
                    result.add(AnnotationInstance.create(nestedAnnotation, annotation.target()));
                }
            }
        }

        if (inheritedAnnotations && declaration.kind() == AnnotationTarget.Kind.CLASS
                && declaration.asClass().superName() != null) {
            ClassInfo clazz = index.getClassByName(declaration.asClass().superName());
            while (result.isEmpty() && clazz != null && !DotName.OBJECT_NAME.equals(clazz.name())) {
                for (AnnotationInstance annotation : getAnnotationsFor(clazz)) {
                    ClassInfo annotationClass = index.getClassByName(annotation.name());
                    if (annotationClass != null && annotationClass.hasDeclaredAnnotation(DotName.INHERITED_NAME)) {
                        if (annotation.name().equals(name)) {
                            result.add(annotation);
                        } else if (annotation.name().equals(containerName)) {
                            AnnotationInstance[] nestedAnnotations = annotation.value().asNestedArray();
                            for (AnnotationInstance nestedAnnotation : nestedAnnotations) {
                                result.add(AnnotationInstance.create(nestedAnnotation, annotation.target()));
                            }
                        }
                    }
                }
                clazz = index.getClassByName(clazz.superName());
            }
        }

        return Collections.unmodifiableList(result);
    }

    @Override
    public final Collection<AnnotationInstance> annotations(Declaration declaration) {
        if (compatibleMode && declaration.kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
            throw new UnsupportedOperationException();
        }

        Collection<AnnotationInstance> result = getAnnotationsFor(declaration);

        if (inheritedAnnotations && declaration.kind() == AnnotationTarget.Kind.CLASS
                && declaration.asClass().superName() != null) {
            result = new ArrayList<>(result);
            ClassInfo clazz = index.getClassByName(declaration.asClass().superName());
            while (clazz != null && !DotName.OBJECT_NAME.equals(clazz.name())) {
                for (AnnotationInstance annotation : getAnnotationsFor(clazz)) {
                    ClassInfo annotationClass = index.getClassByName(annotation.name());
                    if (annotationClass != null && annotationClass.hasDeclaredAnnotation(DotName.INHERITED_NAME)) {
                        boolean noMatchingAnnotationPresent = true;
                        for (AnnotationInstance it : result) {
                            if (it.name().equals(annotation.name())) {
                                noMatchingAnnotationPresent = false;
                                break;
                            }
                        }
                        if (noMatchingAnnotationPresent) {
                            result.add(annotation);
                        }
                    }
                }
                clazz = index.getClassByName(clazz.superName());
            }
            result = Collections.unmodifiableCollection(result);
        }

        return result;
    }

    Collection<AnnotationInstance> getAnnotationsFor(Declaration declaration) {
        // optimistic `get` to avoid `getOrPut` for most calls
        Collection<AnnotationInstance> result = overlay.get(declaration);
        if (result != null) {
            return result;
        }
        return overlay.getOrPut(declaration, new Supplier<Collection<AnnotationInstance>>() {
            @Override
            public Collection<AnnotationInstance> get() {
                Collection<AnnotationInstance> original = getOriginalAnnotations(declaration);
                TransformationContextImpl transformationContext = new TransformationContextImpl(declaration, original);
                for (AnnotationTransformation transformation : transformations) {
                    if (transformation.supports(declaration.kind())) {
                        transformation.apply(transformationContext);
                    }
                }

                if (transformationContext.modified()) {
                    Collection<AnnotationInstance> result = transformationContext.annotations;
                    if (result.isEmpty()) {
                        return Collections.emptyList();
                    } else if (result.size() == 1) {
                        return Collections.singleton(result.iterator().next());
                    } else {
                        return Collections.unmodifiableCollection(result);
                    }
                }
                return original;
            }
        });
    }

    // always returns an unmodifiable collection
    final Collection<AnnotationInstance> getOriginalAnnotations(Declaration declaration) {
        if (compatibleMode && declaration.kind() == AnnotationTarget.Kind.METHOD) {
            List<AnnotationInstance> annotations = declaration.asMethod().annotations();
            if (annotations.isEmpty()) {
                return Collections.emptyList();
            }

            List<AnnotationInstance> result = new ArrayList<>(annotations.size());
            for (AnnotationInstance annotation : annotations) {
                if (annotation.target() != null
                        && (annotation.target().kind() == AnnotationTarget.Kind.METHOD
                                || annotation.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER)
                        && (!runtimeAnnotationsOnly || annotation.runtimeVisible())) {
                    result.add(annotation);
                }
            }
            return Collections.unmodifiableList(result);
        }

        Collection<AnnotationInstance> annotations = declaration.declaredAnnotations();

        if (!runtimeAnnotationsOnly) {
            return annotations;
        }

        List<AnnotationInstance> result = new ArrayList<>(annotations.size());
        for (AnnotationInstance annotation : annotations) {
            if (annotation.runtimeVisible()) {
                result.add(annotation);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static final class TransformationContextImpl implements TransformationContext {
        private final Declaration declaration;
        private final Collection<AnnotationInstance> originalAnnotations;
        private Set<AnnotationInstance> annotations;

        TransformationContextImpl(Declaration declaration, Collection<AnnotationInstance> annotations) {
            this.declaration = declaration;
            this.originalAnnotations = annotations;
            this.annotations = null;
        }

        boolean modified() {
            return annotations != null;
        }

        private void initializeIfNecessary() {
            if (annotations == null) {
                annotations = new HashSet<>(originalAnnotations);
            }
        }

        @Override
        public Declaration declaration() {
            return declaration;
        }

        @Override
        public Collection<AnnotationInstance> annotations() {
            initializeIfNecessary();
            return annotations;
        }

        @Override
        public boolean hasAnnotation(Class<? extends Annotation> annotationClass) {
            Objects.requireNonNull(annotationClass);
            return hasAnnotation(DotName.createSimple(annotationClass));
        }

        @Override
        public boolean hasAnnotation(DotName annotationName) {
            Objects.requireNonNull(annotationName);
            for (AnnotationInstance annotation : modified() ? annotations : originalAnnotations) {
                if (annotation.name().equals(annotationName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean hasAnnotation(Predicate<AnnotationInstance> predicate) {
            Objects.requireNonNull(predicate);
            for (AnnotationInstance annotation : modified() ? annotations : originalAnnotations) {
                if (predicate.test(annotation)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void add(Class<? extends Annotation> annotationClass) {
            initializeIfNecessary();

            Objects.requireNonNull(annotationClass);
            annotations.add(AnnotationInstance.builder(annotationClass).buildWithTarget(declaration));
        }

        @Override
        public void add(AnnotationInstance annotation) {
            initializeIfNecessary();

            Objects.requireNonNull(annotation);
            if (annotation.target() == null) {
                annotation = AnnotationInstance.create(annotation, declaration);
            }
            annotations.add(annotation);
        }

        @Override
        public void addAll(AnnotationInstance... annotations) {
            initializeIfNecessary();

            Objects.requireNonNull(annotations);
            for (int i = 0; i < annotations.length; i++) {
                if (annotations[i].target() == null) {
                    annotations[i] = AnnotationInstance.create(annotations[i], declaration);
                }
            }
            Collections.addAll(this.annotations, annotations);
        }

        @Override
        public void addAll(Collection<AnnotationInstance> annotations) {
            initializeIfNecessary();

            Objects.requireNonNull(annotations);
            boolean hasNullTarget = false;
            for (AnnotationInstance annotation : annotations) {
                if (annotation.target() == null) {
                    hasNullTarget = true;
                    break;
                }
            }
            if (hasNullTarget) {
                List<AnnotationInstance> fixed = new ArrayList<>();
                for (AnnotationInstance annotation : annotations) {
                    if (annotation.target() == null) {
                        fixed.add(AnnotationInstance.create(annotation, declaration));
                    } else {
                        fixed.add(annotation);
                    }
                }
                annotations = fixed;
            }
            this.annotations.addAll(annotations);
        }

        @Override
        public void remove(Predicate<AnnotationInstance> predicate) {
            initializeIfNecessary();

            Objects.requireNonNull(predicate);
            annotations.removeIf(predicate);
        }

        @Override
        public void removeAll() {
            // skipping `initializeIfNecessary()` here, because that would do useless work
            if (modified()) {
                annotations.clear();
            } else {
                annotations = new HashSet<>();
            }
        }
    }
}
