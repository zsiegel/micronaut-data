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
package io.micronaut.data.intercept;

import io.micronaut.aop.InterceptedMethod;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.BeanLocator;
import io.micronaut.context.Qualifier;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.context.exceptions.NoSuchBeanException;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.util.KotlinUtils;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.annotation.RepositoryConfiguration;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.data.intercept.annotation.DataMethod;
import io.micronaut.data.operations.PrimaryRepositoryOperations;
import io.micronaut.data.operations.RepositoryOperations;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The root Data introduction advice, which simply delegates to an appropriate interceptor
 * declared in the {@link io.micronaut.data.intercept} package.
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
@Internal
public final class DataIntroductionAdvice implements MethodInterceptor<Object, Object> {

    private final BeanLocator beanLocator;
    private final Map<RepositoryMethodKey, DataInterceptor> interceptorMap = new ConcurrentHashMap<>(20);

    /**
     * Default constructor.
     *
     * @param beanLocator The bean locator
     */
    DataIntroductionAdvice(BeanLocator beanLocator) {
        this.beanLocator = beanLocator;
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        RepositoryMethodKey key = new RepositoryMethodKey(context.getTarget(), context.getExecutableMethod());
        DataInterceptor<Object, Object> dataInterceptor = interceptorMap.get(key);
        if (dataInterceptor != null) {
            return intercept(context, dataInterceptor, key);
        }
        String dataSourceName = context.stringValue(Repository.class).orElse(null);
        Class<?> operationsType = context.classValue(RepositoryConfiguration.class, "operations")
                .orElse(PrimaryRepositoryOperations.class);
        Class<?> interceptorType = context
                .classValue(DataMethod.class, DataMethod.META_MEMBER_INTERCEPTOR)
                .orElse(null);

        if (interceptorType != null && DataInterceptor.class.isAssignableFrom(interceptorType)) {
            DataInterceptor<Object, Object> childInterceptor =
                    findInterceptor(dataSourceName, operationsType, interceptorType);
            interceptorMap.put(key, childInterceptor);
            return intercept(context, childInterceptor, key);
        } else {
            final AnnotationValue<DataMethod> declaredAnnotation = context.getDeclaredAnnotation(DataMethod.class);
            if (declaredAnnotation != null) {
                interceptorType = declaredAnnotation.classValue(DataMethod.META_MEMBER_INTERCEPTOR).orElse(null);
                if (interceptorType != null && DataInterceptor.class.isAssignableFrom(interceptorType)) {
                    DataInterceptor<Object, Object> childInterceptor =
                            findInterceptor(dataSourceName, operationsType, interceptorType);
                    interceptorMap.put(key, childInterceptor);
                    return intercept(context, childInterceptor, key);
                }
            }

            final String interceptorName = context.getAnnotationMetadata().stringValue(DataMethod.class, DataMethod.META_MEMBER_INTERCEPTOR).orElse(null);
            if (interceptorName != null) {
                throw new IllegalStateException("Micronaut Data Interceptor [" + interceptorName + "] is not on the classpath but required by the method: " + context.getExecutableMethod().toString());
            }
            throw new IllegalStateException("Micronaut Data method is missing compilation time query information. Ensure that the Micronaut Data annotation processors are declared in your build and try again with a clean re-build.");
        }
    }

    private Object intercept(MethodInvocationContext<Object, Object> context,
                             DataInterceptor<Object, Object> dataInterceptor,
                             RepositoryMethodKey key) {
        InterceptedMethod interceptedMethod = InterceptedMethod.of(context);
        try {
            Object result = dataInterceptor.intercept(key, context);
            switch (interceptedMethod.resultType()) {
                case PUBLISHER:
                    return interceptedMethod.handleResult(result);
                case COMPLETION_STAGE:
                    if (context.isSuspend()) {
                        // Unwrap CompletionException
                        CompletionStage<Object> completionStage = (CompletionStage) result;
                        completionStage.whenComplete((value, throwable) -> {
                            if (throwable == null) {
                                interceptedMethod.handleResult(CompletableFuture.completedFuture(value));
                            } else {
                                if (throwable instanceof CompletionException) {
                                    throwable = throwable.getCause();
                                }
                                CompletableFuture<?> completableFuture = new CompletableFuture<>();
                                completableFuture.completeExceptionally(throwable);
                                interceptedMethod.handleResult(completableFuture);
                            }
                        });
                        return KotlinUtils.COROUTINE_SUSPENDED;
                    } else {
                        return interceptedMethod.handleResult(result);
                    }
                case SYNCHRONOUS:
                    return result;
                default:
                    return interceptedMethod.unsupported();
            }
        } catch (Exception e) {
            return interceptedMethod.handleException(e);
        }
    }

    private @NonNull
    DataInterceptor<Object, Object> findInterceptor(
            @Nullable String dataSourceName,
            @NonNull Class<?> operationsType,
            @NonNull Class<?> interceptorType) {
        DataInterceptor interceptor;
        if (!RepositoryOperations.class.isAssignableFrom(operationsType)) {
            throw new IllegalArgumentException("Repository type must be an instance of RepositoryOperations!");
        }

        RepositoryOperations datastore;
        try {
            if (dataSourceName != null) {
                Qualifier qualifier = Qualifiers.byName(dataSourceName);
                datastore = (RepositoryOperations) beanLocator.getBean(operationsType, qualifier);
            } else {
                datastore = (RepositoryOperations) beanLocator.getBean(operationsType);
            }
        } catch (NoSuchBeanException e) {
            throw new ConfigurationException("No backing RepositoryOperations configured for repository. Check your configuration and try again", e);
        }
        BeanIntrospection<Object> introspection = BeanIntrospector.SHARED.findIntrospections(ref ->
                ref.isPresent() && interceptorType.isAssignableFrom(ref.getBeanType())).stream().findFirst().orElseThrow(() ->
                new DataAccessException("No Data interceptor found for type: " + interceptorType)
        );
        if (introspection.getConstructorArguments().length == 0) {
            interceptor = (DataInterceptor) introspection.instantiate();
        } else {
            interceptor = (DataInterceptor) introspection.instantiate(datastore);
        }
        return interceptor;
    }

}
