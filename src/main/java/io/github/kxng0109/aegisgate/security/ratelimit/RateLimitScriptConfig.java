package io.github.kxng0109.aegisgate.security.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * Spring configuration for the Redis Lua rate-limit script.
 *
 * <p>Loads {@code rate_limit.lua} from the classpath and registers it as a
 * {@link DefaultRedisScript} bean. Spring Data Redis executes the script via {@code EVALSHA} and transparently falls
 * back to {@code EVAL} when the script is not yet cached on the server (the NOSCRIPT path is handled inside Spring, not
 * by the engine).</p>
 */
@Configuration
public class RateLimitScriptConfig {

	/**
	 * Creates the rate-limit script bean used by {@link RateLimitEngine}.
	 *
	 * <p>The declared result type is {@link List}. The elements of the returned
	 * list arrive as {@link String} values when the connection uses the {@code StringRedisTemplate} serializer or as
	 * {@link Long} values with a native serializer; the engine tolerates both.</p>
	 *
	 * @return the configured script, referencing {@code rate_limit.lua} on the classpath
	 */
	@Bean
	public DefaultRedisScript<List> rateLimitScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("rate_limit.lua"));
		script.setResultType(List.class);
		return script;
	}
}