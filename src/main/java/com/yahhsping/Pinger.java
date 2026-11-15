package com.yahhsping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class Pinger {
	/** Effectively unlimited. The client only knows blocks in chunks it has loaded, so in practice it's your render distance. */
	private static final double RANGE = 100_000.0;

	private static final int WHITE = 0xFFFFFF;
	private static final int GRAY = 0xAAAAAA;
	private static final int GREEN = 0x55FF55;
	private static final int RED = 0xFF5555;

	private static long lastSend = 0;

	/** Whispers waiting to go out. One is sent about every second so the server's spam check never trips. */
	private static final java.util.ArrayDeque<String> QUEUE = new java.util.ArrayDeque<>();
	private static long lastWhisper = 0;
	private static final long WHISPER_GAP_MS = 1100;
	private static final Set<String> NOTICED = ConcurrentHashMap.newKeySet();

	private Pinger() {
	}

	// ------------------------------------------------------------------ sending

	static void ping(Minecraft mc) {
		LocalPlayer player = mc.player;
		ClientPacketListener connection = mc.getConnection();

		if (player == null || mc.level == null || connection == null) return;

		// Anti-spam: at most one ping message per cooldown, however fast the key is pressed.
		long now = System.currentTimeMillis();
		if (now - lastSend < Config.cooldownMs()) return;
		if (!QUEUE.isEmpty()) return; // previous ping is still being delivered

		String user = player.getGameProfile().name();

		// The ping goes to everyone on YOUR trusted list.
		List<String> recipients = new ArrayList<>();

		for (String name : Config.names()) {
			if (!name.equalsIgnoreCase(user)) recipients.add(name);
		}

		if (recipients.isEmpty()) {
			Compat.say(player, colored("Your trusted list is empty. Use /ypm add <player>", RED, false));
			return;
		}

		if (recipients.size() > PingCodec.MAX_RECIPIENTS) {
			notice(mc, "too-many", "Only the first " + PingCodec.MAX_RECIPIENTS + " players on your trusted list get pings (spam protection).");
		}

		double ox = player.getX();
		double oy = player.getEyeY();
		double oz = player.getZ();
		Vec3 eye = new Vec3(ox, oy, oz);

		double yaw = Math.toRadians(player.getYRot());
		double pitch = Math.toRadians(player.getXRot());
		double dx = -Math.sin(yaw) * Math.cos(pitch);
		double dy = -Math.sin(pitch);
		double dz = Math.cos(yaw) * Math.cos(pitch);
		Vec3 end = eye.add(dx * RANGE, dy * RANGE, dz * RANGE);

		// Nearest block along the ray.
		double blockDist = Double.MAX_VALUE;
		BlockPos blockPos = null;
		HitResult hit = player.pick(RANGE, 1.0F, false);

		if (hit.getType() == HitResult.Type.BLOCK) {
			blockDist = hit.getLocation().distanceTo(eye);
			blockPos = ((BlockHitResult) hit).getBlockPos();
		}

		// Nearest other player along the ray.
		double playerDist = Double.MAX_VALUE;
		Player targetPlayer = null;

		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof Player other) || other == player || other.isSpectator()) continue;

			Optional<Vec3> point = other.getBoundingBox().inflate(0.15).clip(eye, end);

			if (point.isPresent()) {
				double d = point.get().distanceTo(eye);

				if (d < playerDist) {
					playerDist = d;
					targetPlayer = other;
				}
			}
		}

		int type;
		String targetName;
		double t;

		if (targetPlayer != null && playerDist < blockDist) {
			type = PingCodec.TYPE_PLAYER;
			targetName = targetPlayer.getGameProfile().name();
			t = playerDist;
		} else if (blockPos != null) {
			type = PingCodec.TYPE_BLOCK;
			targetName = mc.level.getBlockState(blockPos).getBlock().getName().getString();
			t = blockDist;
		} else {
			Compat.say(player, colored("Nothing to ping in sight.", RED, false));
			return;
		}

		// These are the coordinates in the dimension YOU are in. Each receiver converts them to their own dimension.
		PingCodec.Ping ping = new PingCodec.Ping(
				user, type, targetName, PingMarkers.dimensionName(mc.level),
				ox + dx * t, oy + dy * t, oz + dz * t, PingCodec.hashes(recipients));

		try {
			// A private whisper to each trusted player (nobody else on the server sees anything).
			// They are queued and sent about one per second so the spam check never trips.
			String payload = PingCodec.encode(ping);
			int count = 0;

			for (String name : recipients) {
				if (count++ >= PingCodec.MAX_RECIPIENTS) break;
				QUEUE.add("msg " + name + " " + payload);
			}

			sendQueued(mc);
		} catch (Exception e) {
			Compat.say(player, colored("Could not send ping: " + e.getMessage(), RED, false));
			return;
		}

		lastSend = now;
		show(mc, ping);
	}

	/** Called every game tick: sends the next queued whisper when it is time. */
	static void tick(Minecraft mc) {
		if (QUEUE.isEmpty()) return;

		if (mc.getConnection() == null) {
			QUEUE.clear();
			return;
		}

		sendQueued(mc);
	}

	private static void sendQueued(Minecraft mc) {
		long now = System.currentTimeMillis();
		ClientPacketListener connection = mc.getConnection();

		if (connection == null || QUEUE.isEmpty() || now - lastWhisper < WHISPER_GAP_MS) return;

		connection.sendCommand(QUEUE.poll());
		lastWhisper = now;
	}

	// ---------------------------------------------------------------- receiving

	/**
	 * Called for every incoming chat/system message. Returns false to hide the message
	 * (ping messages are shown as a nicely formatted line instead of the raw text).
	 *
	 * @param senderName the verified chat sender, or null if the server doesn't tell us
	 */
	static boolean allowIncoming(String text, String senderName) {
		if (!PingCodec.looksLikePing(text)) return true;

		Minecraft mc = Minecraft.getInstance();
		PingCodec.Ping ping = PingCodec.decode(text);

		if (ping == null) {
			notice(mc, "undecodable", "Hid a ping that could not be read. Make sure everyone has the latest version of Yahh's Ping Mod.");
			return false;
		}

		String self = mc.player == null ? null : mc.player.getGameProfile().name();

		if (self == null) return false;
		if (ping.user().equalsIgnoreCase(self)) return false; // our own message coming back, already shown
		if (senderName != null && !senderName.equalsIgnoreCase(ping.user())) return false; // somebody replaying another player's ping
		if (!ping.isFor(self)) return false; // the sender didn't trust us, so it isn't for us

		mc.execute(() -> show(mc, ping));
		return false;
	}

	/** Prints a short gray hint, but only once per reason so it can't spam the chat. */
	private static void notice(Minecraft mc, String reason, String message) {
		if (!NOTICED.add(reason)) return;

		mc.execute(() -> {
			if (mc.player != null) Compat.say(mc.player, colored(message, GRAY, false));
		});
	}

	// ------------------------------------------------------------------ showing

	/**
	 * Converts the ping's coordinates to the dimension we are in. The Nether is 1:8 with the Overworld.
	 * Returns null when there is no meaningful conversion (The End, custom dimensions).
	 */
	static double[] convert(PingCodec.Ping ping, String myDimension) {
		String from = ping.dimension();

		if (from.equals(myDimension)) return new double[] { ping.x(), ping.y(), ping.z() };
		if (from.equals(PingMarkers.NETHER) && myDimension.equals(PingMarkers.OVERWORLD)) return new double[] { ping.x() * 8, ping.y(), ping.z() * 8 };
		if (from.equals(PingMarkers.OVERWORLD) && myDimension.equals(PingMarkers.NETHER)) return new double[] { ping.x() / 8, ping.y(), ping.z() / 8 };

		return null;
	}

	/** Prints the "X marked Y N blocks away (x y z)" line and, if we're in the same dimension, adds the marker. */
	private static void show(Minecraft mc, PingCodec.Ping ping) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) return;

		String myDimension = PingMarkers.dimensionName(mc.level);
		boolean sameDimension = ping.dimension().equals(myDimension);
		double distance = new Vec3(ping.x(), ping.y(), ping.z()).distanceTo(player.position());

		Compat.say(player, chatLine(ping, myDimension, sameDimension, distance));

		// Pings from another dimension are chat-only: no marker, no sound.
		if (sameDimension) {
			PingMarkers.add(ping.user(), ping.x(), ping.y(), ping.z(), ping.dimension());
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5F, 1.0F));
		}
	}

	private static Component chatLine(PingCodec.Ping ping, String myDimension, boolean sameDimension, double distance) {
		boolean isPlayer = ping.type() == PingCodec.TYPE_PLAYER;

		double[] converted = convert(ping, myDimension);
		double[] c = converted != null ? converted : new double[] { ping.x(), ping.y(), ping.z() };
		String coords = " (" + num(c[0]) + " " + num(c[1]) + " " + num(c[2]) + ")";

		MutableComponent line = Component.empty();
		line.append(colored(ping.user(), WHITE, true));
		line.append(colored(" marked ", GRAY, false));
		line.append(colored(ping.target(), isPlayer ? GREEN : WHITE, true));

		if (sameDimension) {
			line.append(colored(" " + num(distance) + " blocks away", GRAY, false));
			line.append(colored(coords, GRAY, false));
		} else if (converted != null) {
			// e.g. pinged in the Nether, we're in the Overworld: show the Overworld coordinates.
			line.append(colored(coords, GRAY, false));
			line.append(colored(" - " + myDimension + " coords (pinged in " + ping.dimension() + ")", GRAY, false));
		} else {
			// The End / custom dimensions can't be converted, so say where it is instead.
			line.append(colored(coords, GRAY, false));
			line.append(colored(" in " + ping.dimension(), GRAY, false));
		}

		return line;
	}

	private static String num(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	private static MutableComponent colored(String text, int rgb, boolean bold) {
		return Component.literal(text).withStyle(style -> style.withColor(rgb).withBold(bold));
	}
}
