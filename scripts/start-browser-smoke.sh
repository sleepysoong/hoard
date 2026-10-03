#!/usr/bin/env bash
# CI-only, disposable real SSH/X11/Chrome/VNC fixture. No production relay/service.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == "true" ]] || { echo 'Browser fixture is restricted to the disposable CI runner' >&2; exit 1; }
out="$(realpath "$1")/browser-fixture"
mkdir -p "$out/site"
sudo apt-get update -qq
sudo apt-get install -y xvfb openbox x11vnc x11-utils openssh-server fonts-noto-cjk
chrome="$(command -v google-chrome || command -v google-chrome-stable || command -v chromium)"
ssh-keygen -q -t ed25519 -N '' -f "$out/host-key"
ssh-keygen -q -t ed25519 -N '' -f "$out/client-key"
cp "$out/client-key.pub" "$out/authorized_keys"
cat > "$out/sshd.conf" <<EOF
Port 22022
ListenAddress 127.0.0.1
HostKey $out/host-key
AuthorizedKeysFile $out/authorized_keys
PidFile $out/sshd.pid
StrictModes no
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM yes
AllowUsers $(id -un)
AllowTcpForwarding local
PermitOpen 127.0.0.1:9222 127.0.0.1:5900
LogLevel VERBOSE
EOF
sudo mkdir -p /run/sshd
sudo /usr/sbin/sshd -D -e -f "$out/sshd.conf" > "$out/ssh.log" 2>&1 &
echo "$!" >> "$out/pids"
Xvfb :97 -screen 0 1000x800x24 -nolisten tcp -ac > "$out/display.log" 2>&1 &
echo "$!" >> "$out/pids"
export DISPLAY=:97
for i in {1..40}; do xdpyinfo >/dev/null 2>&1 && break; sleep 0.25; done
xdpyinfo > "$out/display-info.txt"
openbox > "$out/window-manager.log" 2>&1 &
echo "$!" >> "$out/pids"
for i in {1..40}; do xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id #' && break; sleep 0.25; done
xprop -root _NET_SUPPORTING_WM_CHECK | grep -q 'window id #'
x11vnc -storepasswd nativepw "$out/vnc.auth" > /dev/null 2>&1
x11vnc -display :97 -localhost -rfbport 5900 -rfbauth "$out/vnc.auth" -shared -forever \
    -clear_keys -clear_mods -xkb -add_keysyms -noxdamage -debug_keyboard > "$out/vnc.log" 2>&1 &
echo "$!" >> "$out/pids"
# The fixture serves only authored HTML, not the directory with ephemeral SSH keys.
cat > "$out/site/first.html" <<'EOF'
<!doctype html><html><head><meta charset="utf-8"><title>Desktop first tab</title>
<style>body{margin:0;background:#bb3d30;min-height:2200px;color:white;font:22px sans-serif}input{position:absolute;left:80px;top:140px;width:320px;height:50px;font:20px sans-serif}</style>
</head><body><h1>Real desktop first page</h1><input aria-label="Desktop input" placeholder="Desktop input"></body></html>
EOF
cat > "$out/site/second.html" <<'EOF'
<!doctype html><html><head><meta charset="utf-8"><title>Desktop second tab</title>
<style>body{margin:0;background:#285dbc;min-height:2200px;color:white;font:22px sans-serif}</style>
</head><body><h1>Real desktop second page</h1><button>Second-page action</button></body></html>
EOF
python3 -m http.server 18080 --bind 127.0.0.1 --directory "$out/site" > "$out/site.log" 2>&1 &
echo "$!" >> "$out/pids"
"$chrome" --no-sandbox --no-first-run --no-default-browser-check --disable-dev-shm-usage \
    --remote-debugging-address=127.0.0.1 --remote-debugging-port=9222 \
    --user-data-dir="$out/profile" --start-maximized --window-position=0,0 --window-size=1000,800 about:blank > "$out/chrome.log" 2>&1 &
chrome_pid=$!
echo "$chrome_pid" >> "$out/pids"
# Emulator boot competes with a cold Chrome launch on the hosted runner. Wait for
# the real endpoint, not a fixed delay, and fail with diagnostics if it never opens.
deadline=$((SECONDS + 120))
until curl --max-time 2 -sf http://127.0.0.1:9222/json/version > "$out/cdp-version.json"; do
    if ! kill -0 "$chrome_pid" 2>/dev/null || (( SECONDS >= deadline )); then
        echo 'Chrome fixture did not open CDP before the startup deadline' >&2
        cat "$out/chrome.log" >&2
        exit 1
    fi
    sleep 0.5
done
sudo install -m 755 scripts/vps/ensure-browser-runtime /usr/local/bin/ensure-browser-runtime
cat > "$out/runtime.conf" <<EOF
BROWSER_DISPLAY=":97"
DISPLAY_RESTART_CMD="true"
VNC_PATTERN="x11vnc -display :97"
VNC_RESTART_CMD="true"
PROFILE_DIR="$out/profile"
CHROME_BIN="$chrome"
EOF
sudo install -m 644 "$out/runtime.conf" /etc/ensure-browser-runtime.conf
echo 'Real browser fixture ready on loopback SSH 22022, CDP 9222 and VNC 5900'
