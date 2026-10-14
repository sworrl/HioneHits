#!/usr/bin/env bash
# tools/unlock_all_easter_eggs.sh
# Quickly unlock ALL easter eggs and rewards on a connected device for testing/developer use
# WITHOUT hardcoding them into the repository or granting them to general users.

set -euo pipefail

SERIAL="${1:-192.168.13.166:5555}"

if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "Device $SERIAL not found or not authorized via ADB."
    echo "Usage: $0 [adb-serial]"
    exit 1
fi

echo "==> Unlocking all HioneOS and Hione Hits easter eggs on $SERIAL..."

python3 - <<EOF
import json
import subprocess
import time

serial = "$SERIAL"
now_ms = int(time.time() * 1000)

unlock_ids = [
    # HioneUnlocks 35 progression rewards
    "player.feature.dynamic_color",
    "game.lanes.2x",
    "game.lanes.3x",
    "game.lanes.4x",
    "game.lanes.5x",
    "game.lanes.6x",
    "game.lanes.8x",
    "game.lanes.10x",
    "os.wallpaper.w6",
    "os.wallpaper.w7",
    "os.wallpaper.w8",
    "os.wallpaper.w9",
    "os.wallpaper.w10",
    "os.theme.cozy_cafe",
    "os.theme.cyber_stage",
    "os.theme.tokyo_neon",
    "os.theme.synth_sunset",
    "os.theme.dark_matter",
    "os.topbar.aurora",
    "os.topbar.midnight",
    "os.topbar.sunset",
    "os.topbar.matrix",
    "player.viz.negi_rain",
    "player.viz.vocaloid_circuit",
    "player.projectm.vortex_core",
    "player.viz.cyber_tunnel",
    "player.tape.sakura",
    "player.tape.vaporwave",
    "player.tape.glowworm",
    "player.tape.cyberpunk",
    "player.tape.golden_reel",
    "player.tape.clear_prism",
    "game.skin.honey_sweet",
    "game.skin.gothic_diva",
    "game.skin.snow_crystal",

    # HioneSecrets 65 Easter Eggs
    "secret.os.battery.negi",
    "secret.player.tape.hione39",
    "secret.player.leek_spin",
    "secret.os.lock.holo_clock",
    "secret.os.home.concert_mode",
    "secret.os.home.negi_snow",
    "secret.os.boot.chime39",
    "secret.os.icon.golden_music",
    "secret.game.skin.hologram",
    "secret.os.topbar.seasonal",
    "secret.os.riot",
    "secret.os.wheel.2001",
    "secret.os.wheel.2004",
    "secret.os.wheel.2005",
    "secret.os.wheel.2007",

    "secret.player.hologram_art",
    "secret.player.crt_scanlines",
    "secret.player.tape_reel_spin_speed",
    "secret.player.rainbow_wave",
    "secret.player.plasma_tunnel_fx",
    "secret.player.secret_lyrics_glow",
    "secret.player.bass_shake",
    "secret.player.golden_volume_hud",
    "secret.player.laser_scrubber",
    "secret.player.neon_equalizer",
    "secret.player.vinyl_scratch",
    "secret.player.hi_res_badge_gold",
    "secret.player.lofi_rain_ambient",
    "secret.player.tube_amp_warmth",
    "secret.player.starlight_backdrop",

    "secret.os.lock.matrix_rain",
    "secret.os.lock.glitch_clock",
    "secret.os.lock.binaural_ticker",
    "secret.os.home.petals_drift",
    "secret.os.home.grid_floor",
    "secret.os.home.starfield",
    "secret.os.home.dock_reflection",
    "secret.os.home.app_orbit",
    "secret.os.home.leek_cursor",
    "secret.os.home.weather_aurora",
    "secret.os.home.hud_compass",
    "secret.os.home.mascot_peek",
    "secret.os.home.cyber_glitch_icon",

    "secret.os.sound.arcade_click",
    "secret.os.sound.synth_lock",
    "secret.os.sound.level_up_ring",
    "secret.os.sound.retro_boot",
    "secret.os.sound.coda_whisper",

    "secret.os.wheel.stealth_black",
    "secret.os.wheel.mini_colors",
    "secret.os.wheel.shuffler",
    "secret.os.wheel.touch_click",
    "secret.os.wheel.coverflow_3d",

    "secret.game.golden_highway",
    "secret.game.rainbow_notes",
    "secret.game.fever_hyperspace",
    "secret.game.combo_god",
    "secret.game.laser_target",
    "secret.game.retro_8bit_sfx",
    "secret.game.super_critical",
    "secret.game.stage_pyro",
    "secret.game.nightcore_speed",
    "secret.game.mirror_world",
    "secret.game.stealth_notes",
    "secret.game.god_tier_badge"
]

data = {}
for uid in unlock_ids:
    data[uid] = {"at": now_ms, "src": "developer_test_bypass", "off": False}

payload = json.dumps(data)
cmd = ["adb", "-s", serial, "shell", "settings", "put", "global", "hione_unlocks", f"'{payload}'"]
subprocess.run(cmd, check=True)

# Also sync mirror prefs in launcher package if accessible
subprocess.run([
    "adb", "-s", serial, "shell",
    f"run-as com.hione.launcher sh -c 'echo \"{payload}\" > /data/data/com.hione.launcher/shared_prefs/hione_unlocks_mirror.xml'"
], stderr=subprocess.DEVNULL)

print(f"Successfully injected {len(unlock_ids)} unlocks into Settings.Global on {serial}!")
EOF

# Broadcast update event to refresh UI components immediately
adb -s "$SERIAL" shell am broadcast -a com.hione.launcher.action.UNLOCKS_CHANGED >/dev/null 2>&1 || true

echo "==> Done! All easter eggs are unlocked on $SERIAL for testing."
