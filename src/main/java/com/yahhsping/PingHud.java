package com.yahhsping;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Draws ping markers on the HUD: the pinger's head, "<Name>'s Marker", and a diamond on the pinged spot.
 *
 * The marker is only drawn while the spot is actually on your screen. Turn away and it is gone.
 * The position is worked out with the same interpolated camera position/direction and the same field of view
 * (including sprint / speed FOV changes) that the game uses, so it stays glued to the spot.
 */
final class PingHud {
	/** Markers closer than this (in blocks) are hidden, you're basically standing on it. */
	private static final double HIDE_WITHIN = 2.0;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int DIAMOND = 0xFFE8F4E8;
	private static final int OUTLINE = 0xFF1A1A1A;

	// Mirrors the game's own smoothing of the FOV changes you see when sprinting or with speed effects.
	private static float fovModifier = 1.0F;
	private static float oldFovModifier = 1.0F;

	private PingHud() {
	}

	/** Called once per game tick. */
	static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;

		if (player == null) {
			fovModifier = 1.0F;
			oldFovModifier = 1.0F;
			return;
		}

		float target = player.getFieldOfViewModifier(true, mc.options.fovEffectScale().get().floatValue());

		oldFovModifier = fovModifier;
		fovModifier += (target - fovModifier) * 0.5F;
		fovModifier = Math.max(0.1F, Math.min(1.5F, fovModifier));
	}

	static void render(Gfx g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;

		if (player == null || mc.level == null) return;

		// Pings from another dimension are never shown here (they only appear in chat).
		List<PingMarkers.Marker> markers = PingMarkers.active(PingMarkers.dimensionName(mc.level));
		if (markers.isEmpty()) return;

		float partial = g.partialTick();
		Vec3 eye = player.getEyePosition(partial);
		Vec3 forward = player.getViewVector(partial);
		Vec3 right = forward.cross(new Vec3(0, 1, 0));

		if (right.lengthSqr() < 1.0E-6) return; // looking straight up or down, skip this frame

		right = right.normalize();
		Vec3 up = right.cross(forward);

		double fovDegrees = mc.options.fov().get() * (oldFovModifier + (fovModifier - oldFovModifier) * partial);
		fovDegrees = Math.max(1.0, Math.min(fovDegrees, 170.0));
		double tanHalfFov = Math.tan(Math.toRadians(fovDegrees) / 2.0);

		int w = g.width();
		int h = g.height();
		double aspect = (double) w / h;
		Font font = mc.font;

		for (PingMarkers.Marker m : markers) {
			Vec3 d = new Vec3(m.x(), m.y(), m.z()).subtract(eye);

			if (d.length() < HIDE_WITHIN) continue;

			double depth = d.dot(forward);
			if (depth <= 0.05) continue; // behind you

			double sx = w / 2.0 * (1 + d.dot(right) / depth / (tanHalfFov * aspect));
			double sy = h / 2.0 * (1 - d.dot(up) / depth / tanHalfFov);

			if (sx < 0 || sx > w || sy < 0 || sy > h) continue; // not on screen, so not drawn

			drawMarker(g, font, m.user(), (int) sx, (int) sy, w);
		}
	}

	private static void drawMarker(Gfx g, Font font, String user, int cx, int cy, int screenWidth) {
		// Diamond with a dark outline, centred on the target.
		diamond(g, cx, cy, 5, OUTLINE);
		diamond(g, cx, cy, 4, DIAMOND);

		// "<Name>'s Marker" just above the diamond.
		String label = user + "'s Marker";
		int labelWidth = font.width(label);
		int labelX = Math.max(2, Math.min(cx - labelWidth / 2, screenWidth - labelWidth - 2));
		int labelY = cy - 18;
		g.text(font, label, labelX, labelY, WHITE);

		// The pinger's head above the label.
		int size = 12;
		int headX = Math.max(2, Math.min(cx - size / 2, screenWidth - size - 2));
		g.head(user, headX, labelY - size - 3, size);
	}

	private static void diamond(Gfx g, int cx, int cy, int radius, int color) {
		for (int dy = -radius; dy <= radius; dy++) {
			int halfWidth = radius - Math.abs(dy);
			g.fill(cx - halfWidth, cy + dy, cx + halfWidth + 1, cy + dy + 1, color);
		}
	}
}
