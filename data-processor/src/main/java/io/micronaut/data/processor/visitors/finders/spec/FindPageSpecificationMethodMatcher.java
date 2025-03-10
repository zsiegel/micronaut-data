/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.data.processor.visitors.finders.spec;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.processor.visitors.MethodMatchContext;
import io.micronaut.data.processor.visitors.finders.AbstractSpecificationMethodMatcher;
import io.micronaut.data.processor.visitors.finders.FindersUtils;
import io.micronaut.data.processor.visitors.finders.MethodMatchInfo;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;

import java.util.Map;

/**
 * Compilation time implementation of {@code Page find(Specification, Pageable)} for JPA.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public class FindPageSpecificationMethodMatcher extends AbstractSpecificationMethodMatcher {

    /**
     * Find one method.
     */
    public FindPageSpecificationMethodMatcher() {
        super("get", "find", "search", "query");
    }

    @Override
    public int getOrder() {
        return DEFAULT_POSITION - 301;
    }

    @Override
    protected MethodMatch match(MethodMatchContext matchContext, java.util.regex.Matcher matcher) {
        ClassElement returnType = matchContext.getReturnType();
        if ((returnType.isAssignable("org.springframework.data.domain.Page") || returnType.isAssignable("io.micronaut.data.model.Page"))
                && areParametersValid(matchContext.getMethodElement())) {
            if (isFirstParameterMicronautDataQuerySpecification(matchContext.getMethodElement())) {
                Map.Entry<ClassElement, ClassElement> e = FindersUtils.pickFindPageSpecInterceptor(matchContext, matchContext.getReturnType());
                return mc -> new MethodMatchInfo(
                        mc.getReturnType(),
                        getInterceptorElement(mc, "io.micronaut.data.runtime.intercept.criteria.FindPageSpecificationInterceptor")
                );
            }
            if (isFirstParameterSpringJpaSpecification(matchContext.getMethodElement())) {
                return mc -> new MethodMatchInfo(
                        mc.getReturnType(),
                        getInterceptorElement(mc, "io.micronaut.data.spring.jpa.intercept.FindPageSpecificationInterceptor")
                );
            }
            return mc -> new MethodMatchInfo(
                    mc.getReturnType(),
                    getInterceptorElement(mc, "io.micronaut.data.jpa.repository.intercept.FindPageSpecificationInterceptor")
            );
        }
        return null;
    }

    private boolean areParametersValid(@NonNull MethodElement methodElement) {
        final ParameterElement[] parameters = methodElement.getParameters();
        final int len = parameters.length;
        if (len != 2) {
            return false;
        }
        return parameters[1].getType().isAssignable("org.springframework.data.domain.Pageable") ||
                parameters[1].getType().isAssignable("io.micronaut.data.model.Pageable");
    }

    @Override
    protected boolean isMatchesParameters(MethodMatchContext matchContext) {
        return super.isMatchesParameters(matchContext) || isFirstParameterMicronautDataQuerySpecification(matchContext.getMethodElement());
    }
}
