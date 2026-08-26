package io.github.kxng0109.aegisgate.helper;

import java.net.InetAddress;
import java.util.Objects;

/**
 * Represents an IPv4 or IPv6 CIDR range, defined by a network address and a prefix length.
 * <p>
 * This record exists to encapsulate subnet information for IP address range checks, such as determining whether a given IP falls within a specific network segment.
 */
public record CidrRange(
		InetAddress networkAddress,
		int prefixLength
) {

	/**
	 * Validates the {@code networkAddress} and {@code prefixLength} components of this {@code CidrRange} record.
	 *
	 * @param networkAddress the base IP network address ({@code non-null}); its address-family length (4 bytes for IPv4, 16 bytes for IPv6) determines the maximum allowed prefix length
	 * @param prefixLength   the CIDR prefix length; must be between {@code 0} (inclusive) and {@code networkAddress.getAddress().length * 8} (inclusive), reflecting the bit width of
	 *                       the address family
	 * @throws NullPointerException     if {@code networkAddress} is {@code null}
	 * @throws IllegalArgumentException if {@code prefixLength} is negative or exceeds the maximum prefix length for the address family of {@code networkAddress}
	 */
	public CidrRange {
		Objects.requireNonNull(networkAddress, "networkAddress must not be null");

		int maxPrefixLength = networkAddress.getAddress().length * 8;
		if (prefixLength < 0 || prefixLength > maxPrefixLength) {
			throw new IllegalArgumentException(
					"prefixLength must be between 0 and " + maxPrefixLength + " for this address family, got: " + prefixLength
			);
		}
	}

	/**
	 * Checks whether the given IP address falls within this CIDR range.
	 * <p>
	 * Applies the subnet mask built from the prefix length to both the candidate and the network address. Returns false if the candidate's address length (IPv4: 4 bytes, IPv6: 16 bytes
	 * ) does not match the network address's. Throws NullPointerException if the candidate is null.
	 *
	 * @param candidate the IP address to test for inclusion in this range
	 * @return true if the masked addresses match, false otherwise
	 */
	public boolean contains(InetAddress candidate) {
		byte[] candidateBytes = candidate.getAddress();
		byte[] networkBytes = networkAddress.getAddress();
		byte[] mask = buildMask();

		if (candidateBytes.length != networkBytes.length) return false;

		for (int i = 0; i < candidateBytes.length; i++) {
			if ((candidateBytes[i] & mask[i]) != (networkBytes[i] & mask[i])) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Constructs the subnet mask for this CIDR range, with the first {@code prefixLength} bits set to 1 and the rest to 0, based on the length of the network address.
	 * <p>
	 * The mask is calculated by iterating over each byte of the network address. Full bytes are set to 255 when all their bits are part of the prefix. Any remaining bits form a single
	 * partially set byte (e.g., 3 bits yields {@code 224}), and trailing bytes are zeroed. The resulting array's length matches the network address's underlying {@code InetAddress}
	 * size (4 for IPv4, 16 for IPv6).
	 *
	 * @return a byte array representing the subnet mask, with leading {@code prefixLength} bits as 1 and the remainder as 0
	 */
	private byte[] buildMask() {
		int bits = prefixLength;
		int byteCount = networkAddress.getAddress().length;  // 4 for IPv4, 16 for IPv6
		byte[] mask = new byte[byteCount];

		for (int i = 0; i < byteCount && bits > 0; i++) {
			if (bits >= 8) {
				mask[i] = (byte) 255;   // full byte
				bits -= 8;
			} else {
				// partial byte: top 'bits' bits are 1, rest 0
				mask[i] = (byte) (256 - (1 << (8 - bits)));
				bits = 0;
			}
		}
		return mask;
	}
}
