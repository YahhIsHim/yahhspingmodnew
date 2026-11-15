package com.yahhsping;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns a ping into one short chat message and back.
 *
 * The message carries the list of players it is meant for (as short hashes), so only the people on the
 * sender's trusted list see it. The data is also scrambled with a built-in key, so to anyone without the mod
 * it is just a harmless "YPM2:..." string and the coordinates can't be read by eye.
 */
final class PingCodec {
	static final String PREFIX = "YPM2:";
	static final int TYPE_BLOCK = 0;
	static final int TYPE_PLAYER = 1;

	/** More recipients than this would not fit in one chat message (256 characters). */
	static final int MAX_RECIPIENTS = 15;

	private static final String BUILT_IN_KEY = "yahhs-ping-mod/v2";
	private static final Pattern WIRE = Pattern.compile(Pattern.quote(PREFIX) + "([A-Za-z0-9_-]+)");
	private static final SecureRandom RANDOM = new SecureRandom();

	/** Coordinates are exact to one decimal place. {@code recipients} are hashes of the lower-case player names. */
	record Ping(String user, int type, String target, String dimension, double x, double y, double z, int[] recipients) {
		boolean isFor(String playerName) {
			int hash = nameHash(playerName);

			for (int r : recipients) {
				if (r == hash) return true;
			}

			return false;
		}
	}

	private PingCodec() {
	}

	/** First 4 bytes of SHA-256 of the lower-case name; case doesn't matter and names aren't sent in the clear. */
	static int nameHash(String name) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(("ypm:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
			return ((h[0] & 0xFF) << 24) | ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	static int[] hashes(List<String> names) {
		return names.stream().limit(MAX_RECIPIENTS).mapToInt(PingCodec::nameHash).toArray();
	}

	static String encode(Ping ping) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);

		out.writeByte(ping.type());
		out.writeUTF(limit(ping.user(), 16));
		out.writeUTF(limit(ping.target(), 28));

		int dim = dimensionCode(ping.dimension());
		out.writeByte(dim);
		if (dim == 3) out.writeUTF(limit(ping.dimension(), 26));

		out.writeInt((int) Math.round(ping.x() * 10));
		out.writeInt((int) Math.round(ping.y() * 10));
		out.writeInt((int) Math.round(ping.z() * 10));

		int count = Math.min(ping.recipients().length, MAX_RECIPIENTS);
		out.writeByte(count);
		for (int i = 0; i < count; i++) out.writeInt(ping.recipients()[i]);

		byte[] iv = new byte[12];
		RANDOM.nextBytes(iv);

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
		byte[] encrypted = cipher.doFinal(bytes.toByteArray());

		byte[] all = new byte[iv.length + encrypted.length];
		System.arraycopy(iv, 0, all, 0, iv.length);
		System.arraycopy(encrypted, 0, all, iv.length, encrypted.length);

		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(all);
	}

	/** True if the text contains a ping-shaped payload (whether or not it can be read). */
	static boolean looksLikePing(String text) {
		return text.contains(PREFIX) && WIRE.matcher(text).find();
	}

	/** Returns the ping, or null if the text has none or it is damaged. */
	static Ping decode(String text) {
		try {
			Matcher m = WIRE.matcher(text);
			if (!m.find()) return null;

			byte[] all = Base64.getUrlDecoder().decode(m.group(1));
			if (all.length < 13) return null;

			byte[] iv = Arrays.copyOfRange(all, 0, 12);
			byte[] encrypted = Arrays.copyOfRange(all, 12, all.length);

			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
			byte[] plain = cipher.doFinal(encrypted);

			DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
			int type = in.readByte();
			String user = in.readUTF();
			String target = in.readUTF();

			int dim = in.readByte();
			String dimension = dim == 3 ? in.readUTF() : dimensionName(dim);

			double x = in.readInt() / 10.0;
			double y = in.readInt() / 10.0;
			double z = in.readInt() / 10.0;

			int count = in.readByte();
			if (count < 0 || count > MAX_RECIPIENTS) return null;

			int[] recipients = new int[count];
			for (int i = 0; i < count; i++) recipients[i] = in.readInt();

			return new Ping(user, type, target, dimension, x, y, z, recipients);
		} catch (Exception e) {
			return null;
		}
	}

	private static int dimensionCode(String dimension) {
		return switch (dimension) {
			case PingMarkers.OVERWORLD -> 0;
			case PingMarkers.NETHER -> 1;
			case PingMarkers.END -> 2;
			default -> 3;
		};
	}

	private static String dimensionName(int code) {
		return switch (code) {
			case 0 -> PingMarkers.OVERWORLD;
			case 1 -> PingMarkers.NETHER;
			case 2 -> PingMarkers.END;
			default -> "Unknown";
		};
	}

	private static SecretKeySpec key() throws Exception {
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(BUILT_IN_KEY.getBytes(StandardCharsets.UTF_8));
		return new SecretKeySpec(hash, "AES");
	}

	private static String limit(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}
}
