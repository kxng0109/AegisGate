package io.github.kxng0109.aegisgate.budget;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * Registers the atomic spend-budget script.
 *
 * <p>Loads {@code budget_limit.lua} from the classpath and registers it as a
 * {@link DefaultRedisScript} (EVALSHA with transparent NOSCRIPT fallback), mirroring how the rate limiter registers
 * {@code rate_limit.lua}. Kept as a separate script on purpose: keys without budgets never invoke it, so the existing
 * rate path keeps its single round trip byte-identical.</p>
 */
@Configuration
public class BudgetScriptConfig {

	/**
	 * @return the configured script, referencing {@code budget_limit.lua} on the classpath
	 */
	@Bean
	public DefaultRedisScript<List> budgetLimitScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("budget_limit.lua"));
		script.setResultType(List.class);
		return script;
	}
}
