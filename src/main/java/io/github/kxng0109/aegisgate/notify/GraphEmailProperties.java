package io.github.kxng0109.aegisgate.notify;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Microsoft Graph mail configuration, bound from {@code gateway.notify.email.graph.*}. All fields blank means
 * the email channel is disabled (deliveries log SKIPPED, never attempted). The client secret is referenced by
 * environment variable name, never stored.
 *
 * <p>Operator setup: Entra app registration with application permission {@code Mail.Send} (admin-consented),
 * scoped to the alert mailbox via Exchange Online RBAC; the mailbox needs an Exchange Online license.</p>
 *
 * @param tenantId   Entra tenant id for the token endpoint
 * @param clientId   application (client) id
 * @param secretRef  environment variable holding the client secret
 * @param mailbox    sender mailbox UPN for {@code /users/{id}/sendMail}
 */
@ConfigurationProperties("gateway.notify.email.graph")
@Validated
public record GraphEmailProperties(
		@DefaultValue("") String tenantId,
		@DefaultValue("") String clientId,
		@DefaultValue("") String secretRef,
		@DefaultValue("") String mailbox,
		@Min(1) @Max(30) @DefaultValue("25") int perMinuteCap
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final GraphEmailProperties DEFAULTS = new GraphEmailProperties("", "", "", "", 25);
}
