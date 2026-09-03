package io.github.kxng0109.aegisgate.security.compliance;

/**
 * Thrown when an inference request cannot be fulfilled within the tenant's bounded sovereign jurisdiction under
 * {@link ResidencyPolicy#STRICT_SOVEREIGN}.
 */
public class DataResidencyBreachException extends RuntimeException {

	private final Jurisdiction jurisdiction;
	private final String model;

	public DataResidencyBreachException(Jurisdiction jurisdiction, String model) {
		super("All providers in designated sovereign zone [" + jurisdiction
				      + "] are unavailable for model [" + model + "]. Cross-border failover rejected by policy.");
		this.jurisdiction = jurisdiction;
		this.model = model;
	}

	public Jurisdiction getJurisdiction() {
		return jurisdiction;
	}

	public String getModel() {
		return model;
	}
}
