package io.github.kxng0109.aegisgate.budget;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * Registers the atomic spend-budget scripts.
 *
 * <p>Loads {@code budget_limit.lua} (admission), {@code hold.lua} (hold-record creation), and {@code settle.lua}
 * (hold true-up) from the classpath as {@link DefaultRedisScript} beans (EVALSHA with transparent NOSCRIPT
 * fallback), mirroring how the rate limiter registers {@code rate_limit.lua}.</p>
 */
@Configuration
public class BudgetScriptConfig {

	/**
	 * @return the configured admission script, referencing {@code budget_limit.lua} on the classpath
	 */
	@Bean
	public DefaultRedisScript<List> budgetLimitScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("budget_limit.lua"));
		script.setResultType(List.class);
		return script;
	}

	/**
	 * @return the configured hold script, referencing {@code hold.lua} on the classpath
	 */
	@Bean
	public DefaultRedisScript<List> budgetHoldScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("hold.lua"));
		script.setResultType(List.class);
		return script;
	}

	/**
	 * @return the configured settle script, referencing {@code settle.lua} on the classpath
	 */
	@Bean
	public DefaultRedisScript<List> budgetSettleScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("settle.lua"));
		script.setResultType(List.class);
		return script;
	}
}
