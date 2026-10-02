package com.washbase.api.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Runs the {@link AdminBootstrap} on every start-up. A failed bootstrap is logged (without secrets or SQL
 * values) and never stops the API from starting.
 */
@Component
@EnableConfigurationProperties(AdminProperties.class)
class AdminBootstrapRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

	private final AdminBootstrap adminBootstrap;

	private final AdminProperties adminProperties;

	AdminBootstrapRunner(AdminBootstrap adminBootstrap, AdminProperties adminProperties) {
		this.adminBootstrap = adminBootstrap;
		this.adminProperties = adminProperties;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			adminBootstrap.bootstrap(adminProperties);
		}
		catch (DataAccessException | TransactionException ex) {
			// Only the exception type: messages may quote SQL parameters.
			log.error("Admin bootstrap failed ({}); the API starts without it.", ex.getClass().getSimpleName());
		}
	}

}
