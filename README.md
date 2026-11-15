# Yahh's Ping Mod (Fabric, client-side)

Look at a block (or a player) and press **V** (rebind in Controls > "Yahh's Ping Mod").

Your trusted teammates see this in their chat:

    Yahh marked Grass Block 141.2 blocks away (-1960.0 65.0 -717.1)

and a marker on screen (the pinger's head, "<Name>'s Marker", and a diamond on the spot) plus a sound.

- Pinging a player shows their name in green instead of a block.
- If the ping is in a different dimension there is no marker or sound, just the chat line, which ends with
  "in The Nether" etc.
- No distance limit (the client can only see loaded chunks, so in practice it is your render distance).
- The marker stays for 30 seconds.

## How it is sent
Each ping is a private whisper (`/msg`) to every player on your trusted list, queued about one per second so the
server's spam kick can't trigger. Nobody else on the server sees anything. Teammates with the mod never see the raw
text (it is hidden and shown as the nice line instead). Your own "You whisper to ..." lines are hidden too.

## Setup (every teammate)
1. Install the jar for your Minecraft version + Fabric API.
2. `/ypm add <name>` for each teammate you trust (your pings go only to the people on your list; they don't need to add you back).
3. Everyone on the team needs the mod. Pings from another dimension show in chat only, with coordinates converted
   to the receiver's dimension (Nether x8 / Overworld /8). The marker only shows while the spot is on your screen.

Commands: `/ypm add <player>`, `/ypm remove <player>`, `/ypm list` (`/yahhsping` works too).
Settings are in `config/yahhsping.json` (trusted list, `cooldownMs`). The old Ping Trusted list is imported automatically.

## Versions
One jar per Minecraft version: 1.21.11, 26.1 (also 26.1.x), 26.2 (also 26.2.x), 26.3 and newer.
The 26.3 jar is allowed to load on newer versions, but Minecraft may change things again, so rebuild if one breaks.

## Building (GitHub does it for you)
`.github/workflows/build.yml` builds all four versions. Open the Actions tab, open the latest run, and download the
`yahhs-ping-mod-<version>` artifact for your version.
Locally: JDK 25, then `./gradlew -Pmc=1.21.11 build` (or 26.1 / 26.2 / 26.3). The jar is in `build/libs/`.

## Layout
- `src/main/java` - all the mod logic (same for every version)
- `src/compat/a` - 1.21.11 versions of the few calls that changed
- `src/compat/b` - 26.1 and 26.2
- `src/compat/c` - 26.3 (keybinds switched to SDL)
