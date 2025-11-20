package org.jboss.jandex;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class MutableAnnotationOverlayImpl extends AnnotationOverlayImpl implements MutableAnnotationOverlay {
    private volatile boolean frozen;

    MutableAnnotationOverlayImpl(IndexView index, boolean compatibleMode, boolean runtimeAnnotationsOnly,
            boolean inheritedAnnotations) {
        super(index, compatibleMode, runtimeAnnotationsOnly, inheritedAnnotations, Collections.emptyList());
    }

    @Override
    Collection<AnnotationInstance> getAnnotationsFor(Declaration declaration) {
        // optimistic `get` to avoid `getOrPut` for most calls
        Collection<AnnotationInstance> result = overlay.get(declaration);
        if (result != null) {
            return result;
        }
        return overlay.getOrPut(declaration, new Supplier<Collection<AnnotationInstance>>() {
            @Override
            public Collection<AnnotationInstance> get() {
                return new HashSet<>(getOriginalAnnotations(declaration));
            }
        });
    }

    @Override
    public void addAnnotation(Declaration declaration, AnnotationInstance annotation) {
        if (frozen) {
            throw new IllegalStateException("Mutable annotation overlay is already frozen");
        }

        if (annotation.target() == null) {
            annotation = AnnotationInstance.create(annotation, declaration);
        }

        getAnnotationsFor(declaration).add(annotation);
        transformations.add(addTransformation(declaration, annotation));
    }

    private AnnotationTransformation addTransformation(Declaration declaration, AnnotationInstance annotation) {
        Declaration finalDeclaration;
        if (compatibleMode && declaration.kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
            // the `annotation` has correct `target`, see `addAnnotation()` above,
            // so we don't need to do anything else
            finalDeclaration = declaration.asMethodParameter().method();
        } else {
            finalDeclaration = declaration;
        }

        return new AnnotationTransformation() {
            @Override
            public boolean supports(AnnotationTarget.Kind kind) {
                return kind == finalDeclaration.kind();
            }

            @Override
            public void apply(TransformationContext context) {
                if (KEY_OPS.equals(finalDeclaration, context.declaration())) {
                    context.add(annotation);
                }
            }

            @Override
            public boolean requiresCompatibleMode() {
                return compatibleMode;
            }
        };
    }

    @Override
    public void removeAnnotations(Declaration declaration, Predicate<AnnotationInstance> predicate) {
        if (frozen) {
            throw new IllegalStateException("Mutable annotation overlay is already frozen");
        }

        getAnnotationsFor(declaration).removeIf(predicate);
        transformations.add(removeTransformation(declaration, predicate));
    }

    private AnnotationTransformation removeTransformation(Declaration declaration, Predicate<AnnotationInstance> predicate) {
        Declaration finalDeclaration;
        Predicate<AnnotationInstance> finalPredicate;

        if (compatibleMode && declaration.kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
            int position = declaration.asMethodParameter().position();
            finalDeclaration = declaration.asMethodParameter().method();
            finalPredicate = new Predicate<AnnotationInstance>() {
                @Override
                public boolean test(AnnotationInstance annotation) {
                    return annotation.target() != null
                            && annotation.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER
                            && annotation.target().asMethodParameter().position() == position
                            && predicate.test(annotation);
                }
            };
        } else {
            finalDeclaration = declaration;
            finalPredicate = predicate;
        }

        return new AnnotationTransformation() {
            @Override
            public boolean supports(AnnotationTarget.Kind kind) {
                return kind == finalDeclaration.kind();
            }

            @Override
            public void apply(TransformationContext context) {
                if (KEY_OPS.equals(finalDeclaration, context.declaration())) {
                    context.remove(finalPredicate);
                }
            }

            @Override
            public boolean requiresCompatibleMode() {
                return compatibleMode;
            }
        };
    }

    @Override
    public List<AnnotationTransformation> freeze() {
        frozen = true;
        return Collections.unmodifiableList(transformations);
    }
}
