package com.example.springgraphqlmongo.graphql.exception;

import com.example.springgraphqlmongo.exception.ResourceNotFoundException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolationException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

	@Override
	protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
		if (ex instanceof ResourceNotFoundException notFound) {
			return GraphqlErrorBuilder.newError(env)
					.errorType(ErrorType.NOT_FOUND)
					.message(notFound.getMessage())
					.build();
		}

		if (ex instanceof ConstraintViolationException violation) {
			String message = violation.getConstraintViolations().stream()
					.map(v -> v.getPropertyPath() + ": " + v.getMessage())
					.reduce((a, b) -> a + "; " + b)
					.orElse("Validation failed");

			return GraphqlErrorBuilder.newError(env)
					.errorType(ErrorType.BAD_REQUEST)
					.message(message)
					.build();
		}

		if (ex instanceof IllegalArgumentException illegalArgument) {
			return GraphqlErrorBuilder.newError(env)
					.errorType(ErrorType.BAD_REQUEST)
					.message(illegalArgument.getMessage())
					.build();
		}

		if (ex instanceof AccessDeniedException accessDenied) {
			return GraphqlErrorBuilder.newError(env)
					.errorType(ErrorType.FORBIDDEN)
					.message(accessDenied.getMessage())
					.build();
		}

		return null;
	}

}
