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
package io.micronaut.data.runtime.intercept.async;

import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.intercept.RepositoryMethodKey;
import io.micronaut.data.intercept.async.DeleteAllAsyncInterceptor;
import io.micronaut.data.model.runtime.PreparedQuery;
import io.micronaut.data.operations.RepositoryOperations;

import java.util.Optional;
import java.util.concurrent.CompletionStage;


/**
 * Default implementation of {@link DeleteAllAsyncInterceptor}.
 *
 * @param <T> The declaring type
 * @author graemerocher
 * @since 1.0.0
 */
public class DefaultDeleteAllAsyncInterceptor<T> extends AbstractAsyncInterceptor<T, Object> implements DeleteAllAsyncInterceptor<T, Object> {
    /**
     * Default constructor.
     *
     * @param datastore The operations
     */
    protected DefaultDeleteAllAsyncInterceptor(@NonNull RepositoryOperations datastore) {
        super(datastore);
    }

    @Override
    public CompletionStage<Object> intercept(RepositoryMethodKey methodKey, MethodInvocationContext<T, CompletionStage<Object>> context) {
        Optional<Iterable<Object>> deleteEntities = findEntitiesParameter(context, Object.class);
        Optional<Object> deleteEntity = findEntityParameter(context, Object.class);
        CompletionStage<Number> cs;
        if (!deleteEntity.isPresent() && !deleteEntities.isPresent()) {
            PreparedQuery<?, Number> preparedQuery = (PreparedQuery<?, Number>) prepareQuery(methodKey, context);
            cs = asyncDatastoreOperations.executeDelete(preparedQuery);
        } else {
            cs = asyncDatastoreOperations.deleteAll(getDeleteBatchOperation(context, deleteEntities.get()));
        }
        return cs.thenApply(number -> convertNumberToReturnType(context, number));
    }

}
