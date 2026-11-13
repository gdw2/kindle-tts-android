# Driving the emulator with scrcpy from another machine

The emulator runs headless on a Linux host. scrcpy on another machine on the same network can mirror and control it over SSH. adb is not exposed to the network: the remote machine's own adb server reaches the emulator's adb port through an SSH tunnel.

## On the Linux host

The emulator must be running with its adb port available:

```bash
devbox run emulator-scrcpy     # headless, keeps the emulator's audio device enabled
devbox run -- adb devices      # should show emulator-5554
```

`emulator-scrcpy` is the normal headless emulator without `-no-audio`. Audio forwarding depends on the host's audio backend; video and input work regardless.

Find the host's LAN address from the host's network settings.

SSH listens on port 22.

## On the remote machine

Install adb and scrcpy with your platform's package manager.

Open the tunnel. The local port is `15555` so it cannot collide with a local emulator on 5555:

```bash
ssh -f -N -L 15555:127.0.0.1:5555 <user>@<host-address>
```

Connect and mirror:

```bash
adb connect 127.0.0.1:15555
adb devices                      # expect 127.0.0.1:15555  device
scrcpy -s 127.0.0.1:15555 --window-title "Kindle TTS emulator"
```

If scrcpy complains about audio, add `--no-audio`. Audio forwarding needs scrcpy 2.0+, Android 11+, and a working emulator audio backend; use a physical device if you need to verify the spoken output.

To stop:

```bash
adb disconnect 127.0.0.1:15555
pkill -f 'ssh -f -N -L 15555'
```

## Why the tunnel and not adb over LAN

The host's adb server listens on `127.0.0.1:5037` only. Exposing it with `adb -a` would give anyone on the network shell-equivalent access to the emulator. The SSH tunnel forwards only the emulator's adb port (`5555`) and keeps the host's adb server private. It also avoids adb client/server version mismatches: the remote machine runs its own adb server and talks to the emulator's adbd.

## Troubleshooting

- `Connection refused` when running `adb connect`: check the SSH tunnel is still running and that `devbox run -- adb devices` on the host lists `emulator-5554`.
- `device offline`: run `adb disconnect 127.0.0.1:15555 && adb connect 127.0.0.1:15555`; if it persists, restart the emulator.
- scrcpy starts but shows a black screen: give the emulator a few seconds after boot, then retry.
- SSH prompts but the tunnel closes: the account must exist on the host and allow TCP forwarding (the default).
- To keep the screen awake on the emulator:

  ```bash
  devbox run -- adb shell svc power stayon true
  devbox run -- adb shell settings put system screen_off_timeout 2147483647
  ```

Once connected, sign in to Amazon in the app, open a book, and work through `docs/manual-test.md`.
