package io.github.kxng0109.aegisgate.budget;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opt-in delivery subscriptions: validated at save time (channel shape, target shape, SSRF for URL targets,
 * secret-reference shape), so the send path never discovers a malformed subscription mid-alert.
 */
@Service
public class NotificationPreferenceService {

	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

	private static final Pattern SECRET_REF = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

	private final NotificationPreferenceRepository preferences;

	private final SsrfValidator ssrfValidator;

	public NotificationPreferenceService(NotificationPreferenceRepository preferences,
	                                     SsrfValidator ssrfValidator) {
		this.preferences = preferences;
		this.ssrfValidator = ssrfValidator;
	}

	/**
	 * Creates a subscription after validation.
	 *
	 * @throws ResponseStatusException 400 on malformed input, 409 on duplicate (scope, channel, target)
	 */
	@Transactional
	public NotificationPreference create(String scope, String channel, String target,
	                                     @Nullable String secretRef, @Nullable String minSeverity) {
		String cleanTarget = target == null ? "" : target.trim();
		if ("email".equals(channel)) {
			if (!EMAIL.matcher(cleanTarget).matches()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target is not a valid email address");
			}
		} else {
			final URI uri;
			try {
				uri = URI.create(cleanTarget);
			} catch (IllegalArgumentException malformed) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target is malformed");
			}
			try {
				ssrfValidator.validate(uri);
			} catch (SsrfViolationException violation) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"target not permitted: " + violation.getMessage());
			}
		}
		String cleanRef = secretRef == null || secretRef.isBlank() ? null : secretRef.trim();
		if (cleanRef != null && !SECRET_REF.matcher(cleanRef).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"secretRef must name an environment variable ([A-Z][A-Z0-9_]*)");
		}
		String severity = minSeverity == null || minSeverity.isBlank() ? "warning" : minSeverity;
		try {
			return preferences.save(new NotificationPreference(scope.trim(), channel, cleanTarget, cleanRef,
					severity));
		} catch (DataIntegrityViolationException duplicate) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"subscription already exists for this scope, channel, and target");
		}
	}

	@Transactional(readOnly = true)
	public List<NotificationPreference> list(String scope) {
		return preferences.findByScope(scope);
	}

	/**
	 * Deletes a subscription (opt-out). Unknown ids are no-ops.
	 */
	@Transactional
	public void delete(UUID id) {
		preferences.deleteById(id);
	}
}
