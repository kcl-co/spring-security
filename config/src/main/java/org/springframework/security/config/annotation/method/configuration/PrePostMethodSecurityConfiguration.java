/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.config.annotation.method.configuration;

import java.util.function.Supplier;

import io.micrometer.observation.ObservationRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.method.AuthorizationManagerAfterMethodInterceptor;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.PostAuthorizeAuthorizationManager;
import org.springframework.security.authorization.method.PostFilterAuthorizationMethodInterceptor;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.authorization.method.PreFilterAuthorizationMethodInterceptor;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.util.function.SingletonSupplier;

/**
 * Base {@link Configuration} for enabling Spring Security Method Security.
 *
 * @author Evgeniy Cheban
 * @author Josh Cummings
 * @since 5.6
 * @see EnableMethodSecurity
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
final class PrePostMethodSecurityConfiguration {

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	static MethodInterceptor preFilterAuthorizationMethodInterceptor(
			ObjectProvider<GrantedAuthorityDefaults> defaultsProvider,
			ObjectProvider<MethodSecurityExpressionHandler> expressionHandlerProvider,
			ObjectProvider<SecurityContextHolderStrategy> strategyProvider, ApplicationContext context) {
		PreFilterAuthorizationMethodInterceptor preFilter = new PreFilterAuthorizationMethodInterceptor();
		strategyProvider.ifAvailable(preFilter::setSecurityContextHolderStrategy);
		preFilter.setExpressionHandler(
				new DeferringMethodSecurityExpressionHandler(expressionHandlerProvider, defaultsProvider, context));
		return preFilter;
	}

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	static MethodInterceptor preAuthorizeAuthorizationMethodInterceptor(
			ObjectProvider<GrantedAuthorityDefaults> defaultsProvider,
			ObjectProvider<MethodSecurityExpressionHandler> expressionHandlerProvider,
			ObjectProvider<SecurityContextHolderStrategy> strategyProvider,
			ObjectProvider<AuthorizationEventPublisher> eventPublisherProvider,
			ObjectProvider<ObservationRegistry> registryProvider, ApplicationContext context) {
		PreAuthorizeAuthorizationManager manager = new PreAuthorizeAuthorizationManager();
		manager.setExpressionHandler(
				new DeferringMethodSecurityExpressionHandler(expressionHandlerProvider, defaultsProvider, context));
		AuthorizationManagerBeforeMethodInterceptor preAuthorize = AuthorizationManagerBeforeMethodInterceptor
				.preAuthorize(manager(manager, registryProvider));
		strategyProvider.ifAvailable(preAuthorize::setSecurityContextHolderStrategy);
		eventPublisherProvider.ifAvailable(preAuthorize::setAuthorizationEventPublisher);
		return preAuthorize;
	}

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	static MethodInterceptor postAuthorizeAuthorizationMethodInterceptor(
			ObjectProvider<GrantedAuthorityDefaults> defaultsProvider,
			ObjectProvider<MethodSecurityExpressionHandler> expressionHandlerProvider,
			ObjectProvider<SecurityContextHolderStrategy> strategyProvider,
			ObjectProvider<AuthorizationEventPublisher> eventPublisherProvider,
			ObjectProvider<ObservationRegistry> registryProvider, ApplicationContext context) {
		PostAuthorizeAuthorizationManager manager = new PostAuthorizeAuthorizationManager();
		manager.setExpressionHandler(
				new DeferringMethodSecurityExpressionHandler(expressionHandlerProvider, defaultsProvider, context));
		AuthorizationManagerAfterMethodInterceptor postAuthorize = AuthorizationManagerAfterMethodInterceptor
				.postAuthorize(manager(manager, registryProvider));
		strategyProvider.ifAvailable(postAuthorize::setSecurityContextHolderStrategy);
		eventPublisherProvider.ifAvailable(postAuthorize::setAuthorizationEventPublisher);
		return postAuthorize;
	}

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	static MethodInterceptor postFilterAuthorizationMethodInterceptor(
			ObjectProvider<GrantedAuthorityDefaults> defaultsProvider,
			ObjectProvider<MethodSecurityExpressionHandler> expressionHandlerProvider,
			ObjectProvider<SecurityContextHolderStrategy> strategyProvider, ApplicationContext context) {
		PostFilterAuthorizationMethodInterceptor postFilter = new PostFilterAuthorizationMethodInterceptor();
		strategyProvider.ifAvailable(postFilter::setSecurityContextHolderStrategy);
		postFilter.setExpressionHandler(
				new DeferringMethodSecurityExpressionHandler(expressionHandlerProvider, defaultsProvider, context));
		return postFilter;
	}

	private static MethodSecurityExpressionHandler defaultExpressionHandler(
			ObjectProvider<GrantedAuthorityDefaults> defaultsProvider, ApplicationContext context) {
		DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
		defaultsProvider.ifAvailable((d) -> handler.setDefaultRolePrefix(d.getRolePrefix()));
		handler.setApplicationContext(context);
		return handler;
	}

	static <T> AuthorizationManager<T> manager(AuthorizationManager<T> delegate,
			ObjectProvider<ObservationRegistry> registryProvider) {
		return new DeferringObservationAuthorizationManager<>(registryProvider, delegate);
	}

	private static final class DeferringMethodSecurityExpressionHandler implements MethodSecurityExpressionHandler {

		private final Supplier<MethodSecurityExpressionHandler> expressionHandler;

		private DeferringMethodSecurityExpressionHandler(
				ObjectProvider<MethodSecurityExpressionHandler> expressionHandlerProvider,
				ObjectProvider<GrantedAuthorityDefaults> defaultsProvider, ApplicationContext applicationContext) {
			this.expressionHandler = SingletonSupplier.of(() -> expressionHandlerProvider
					.getIfAvailable(() -> defaultExpressionHandler(defaultsProvider, applicationContext)));
		}

		@Override
		public ExpressionParser getExpressionParser() {
			return this.expressionHandler.get().getExpressionParser();
		}

		@Override
		public EvaluationContext createEvaluationContext(Authentication authentication, MethodInvocation invocation) {
			return this.expressionHandler.get().createEvaluationContext(authentication, invocation);
		}

		@Override
		public EvaluationContext createEvaluationContext(Supplier<Authentication> authentication,
				MethodInvocation invocation) {
			return this.expressionHandler.get().createEvaluationContext(authentication, invocation);
		}

		@Override
		public Object filter(Object filterTarget, Expression filterExpression, EvaluationContext ctx) {
			return this.expressionHandler.get().filter(filterTarget, filterExpression, ctx);
		}

		@Override
		public void setReturnObject(Object returnObject, EvaluationContext ctx) {
			this.expressionHandler.get().setReturnObject(returnObject, ctx);
		}

	}

}
