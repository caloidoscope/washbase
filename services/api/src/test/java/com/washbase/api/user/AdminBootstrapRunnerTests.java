package com.washbase.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapRunnerTests {

	@Test
	@DisplayName("A failed bootstrap is logged without its message and never stops start-up")
	void failureDoesNotCrash(CapturedOutput output) {
		AdminBootstrap bootstrap = mock(AdminBootstrap.class);
		AdminProperties settings = new AdminProperties("admin@example.com", null, "Start-Here-2026");
		given(bootstrap.bootstrap(settings))
			.willThrow(new DataIntegrityViolationException("insert ... values ('admin@example.com', 'Start-Here-2026')"));

		assertThatNoException()
			.isThrownBy(() -> new AdminBootstrapRunner(bootstrap, settings).run(new DefaultApplicationArguments()));
		assertThat(output).contains("Admin bootstrap failed (DataIntegrityViolationException)")
			.doesNotContain("Start-Here-2026");
	}

}
