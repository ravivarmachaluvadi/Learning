#!/usr/bin/env bash
# ssh-real.sh — the same ideas as SshLab.java, driven through the REAL OpenSSH
# client and server.
#
# Why this part is not Java: the lesson here IS the real tool's files, policies
# and error messages — known_hosts, authorized_keys, file permission checks,
# the sshd log. Re-implementing those in Java would teach you my invention
# instead of the thing you will actually meet at 2am.
#
# SAFETY: everything lives in ./lab. Your real ~/.ssh is never read or written.
# sshd listens on 127.0.0.1:2222 only, and is killed when this script exits.

set -u
LAB="$(cd "$(dirname "$0")" && pwd)/lab"
PORT=2222
ME="$(whoami)"

cleanup() { [ -f "$LAB/sshd.pid" ] && kill "$(cat "$LAB/sshd.pid")" 2>/dev/null; }
trap cleanup EXIT

hr()  { printf '\n%s\n' "--------------------------------------------------------------------------"; }
say() { printf '%s\n' "$*"; }
scene() { printf '\n%s\n SCENARIO %s — %s\n%s\n' \
  "--------------------------------------------------------------------------" "$1" "$2" \
  "--------------------------------------------------------------------------"; }
# Run ssh with the lab's own files ONLY. -o IdentitiesOnly stops it reaching for
# your real keys; UserKnownHostsFile keeps your real known_hosts untouched.
sshlab() { ssh -p $PORT -F /dev/null \
    -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=5 \
    -o UserKnownHostsFile="$LAB/known_hosts" \
    -o GlobalKnownHostsFile=/dev/null \
    "$@" ; }

start_sshd() {
  cleanup; sleep 0.3
  /usr/sbin/sshd -f "$LAB/sshd_config" -E "$LAB/sshd.log"
  sleep 0.8
}

# ============================================================ setup
rm -rf "$LAB"; mkdir -p "$LAB"; cd "$LAB"
say "Building a throwaway SSH world in $LAB"
ssh-keygen -q -t ed25519 -f "$LAB/hostkey"    -N '' -C 'the-real-server'  </dev/null
ssh-keygen -q -t ed25519 -f "$LAB/hostkey_impostor" -N '' -C 'the-impostor' </dev/null
ssh-keygen -q -t ed25519 -f "$LAB/id_ravi"    -N '' -C 'ravi@laptop'      </dev/null
chmod 600 "$LAB"/hostkey "$LAB"/hostkey_impostor "$LAB"/id_ravi
cp "$LAB/id_ravi.pub" "$LAB/authorized_keys"; chmod 600 "$LAB/authorized_keys"

cat > "$LAB/sshd_config" <<EOF
Port $PORT
ListenAddress 127.0.0.1
HostKey $LAB/hostkey
PidFile $LAB/sshd.pid
AuthorizedKeysFile $LAB/authorized_keys
StrictModes no
UsePAM no
PasswordAuthentication no
PubkeyAuthentication yes
AllowTcpForwarding yes
LogLevel VERBOSE
EOF
sed "s|HostKey $LAB/hostkey$|HostKey $LAB/hostkey_impostor|" "$LAB/sshd_config" > "$LAB/sshd_config_impostor"

say "server host key fingerprint : $(ssh-keygen -lf "$LAB/hostkey.pub"  | awk '{print $2}')"
say "impostor  host key fingerprint : $(ssh-keygen -lf "$LAB/hostkey_impostor.pub" | awk '{print $2}')"
say "your user key fingerprint   : $(ssh-keygen -lf "$LAB/id_ravi.pub" | awk '{print $2}')"
start_sshd

# ============================================================ 1
scene 1 "The first connection: trust on first use"
say "known_hosts right now:"; say "  $( [ -s "$LAB/known_hosts" ] && cat "$LAB/known_hosts" || echo '(empty — we have never met this server)')"
say ""
say "Connecting with StrictHostKeyChecking=accept-new, which is what clicking"
say "'yes' at the prompt does:"
sshlab -i "$LAB/id_ravi" -o StrictHostKeyChecking=accept-new "$ME@127.0.0.1" \
  'echo "   [remote] logged in as $(whoami) on $(hostname -s)"' 2>&1 | sed 's/^/  /'
say ""
say "known_hosts now holds one line — this is the whole trust decision:"
cut -c1-100 "$LAB/known_hosts" | sed 's/^/  /'
say ""
say "and that line's fingerprint matches the server key we generated:"
ssh-keygen -lf "$LAB/known_hosts" 2>/dev/null | sed 's/^/  /'

# ============================================================ 2
scene 2 "The second connection: silence means it was checked"
sshlab -i "$LAB/id_ravi" "$ME@127.0.0.1" 'echo "   [remote] in again, no prompt"' 2>&1 | sed 's/^/  /'
say ""
say "No output about host keys. That silence IS the verification passing."

# ============================================================ 3
scene 3 "The host key changes — the alarm everyone has seen"
say "Restarting the server with a DIFFERENT host key. From the client's point"
say "of view this is indistinguishable from someone intercepting the connection."
cp "$LAB/sshd_config_impostor" "$LAB/sshd_config.active"
cleanup; sleep 0.3
/usr/sbin/sshd -f "$LAB/sshd_config_impostor" -E "$LAB/sshd.log"; sleep 0.8
sshlab -i "$LAB/id_ravi" "$ME@127.0.0.1" 'echo GOT_IN' 2>&1 | sed 's/^/  /'
say ""
say "Exit status was non-zero: the client refused to send anything at all."
say "It did not send your username. It did not offer your key. It stopped"
say "BEFORE giving the stranger a single useful byte."

# ============================================================ 4
scene 4 "The reflex that throws the protection away"
say "What almost everyone does next is delete the offending line:"
say "  \$ ssh-keygen -R '[127.0.0.1]:2222'"
ssh-keygen -R "[127.0.0.1]:$PORT" -f "$LAB/known_hosts" 2>&1 | sed 's/^/  /'
sshlab -i "$LAB/id_ravi" -o StrictHostKeyChecking=accept-new "$ME@127.0.0.1" \
  'echo "   [remote] and we are straight back in — with the IMPOSTOR"' 2>&1 | sed 's/^/  /'
say ""
say "That worked, and it felt like fixing the problem. If this had been a real"
say "attack, the fix WAS the attack succeeding. The only correct response is to"
say "get the expected fingerprint from somewhere other than the connection you"
say "are suspicious of, and compare it by eye."

# Put the honest server back.
cleanup; sleep 0.3; start_sshd
ssh-keygen -R "[127.0.0.1]:$PORT" -f "$LAB/known_hosts" >/dev/null 2>&1
sshlab -i "$LAB/id_ravi" -o StrictHostKeyChecking=accept-new "$ME@127.0.0.1" true 2>/dev/null

# ============================================================ 5
scene 5 "Your private key is world-readable"
chmod 644 "$LAB/id_ravi"
sshlab -i "$LAB/id_ravi" "$ME@127.0.0.1" 'echo GOT_IN' 2>&1 | sed 's/^/  /'
chmod 600 "$LAB/id_ravi"
say ""
say "The client refused to USE the key, then reported 'Permission denied'."
say "The server was never the problem. Read the whole message, not the last line."

# ============================================================ 6
scene 6 "The server does not have your public key"
mv "$LAB/authorized_keys" "$LAB/authorized_keys.bak"
: > "$LAB/authorized_keys"; chmod 600 "$LAB/authorized_keys"
sshlab -i "$LAB/id_ravi" "$ME@127.0.0.1" 'echo GOT_IN' 2>&1 | sed 's/^/  /'
say ""
say "Same message as scenario 5, completely different cause. THIS is how you"
say "tell them apart — the server log names the key it was offered:"
grep -E 'Failed publickey|Postponed|Connection closed' "$LAB/sshd.log" | tail -3 | sed 's/^/  /'
say ""
say "Compare that fingerprint to your key:"
say "  ours: $(ssh-keygen -lf "$LAB/id_ravi.pub" | awk '{print $2}')"
say "If they match, the key reached the server and the server rejected it —"
say "so the problem is authorized_keys, not your client."
mv "$LAB/authorized_keys.bak" "$LAB/authorized_keys"

# ============================================================ 7
scene 7 "One connection, many channels: a port forward"
say "Starting a plain HTTP server on 127.0.0.1:8099 that we will pretend is a"
say "database admin page, reachable only from inside the server."
(cd "$LAB" && echo "TOP SECRET INTERNAL PAGE" > secret.txt && \
   python3 -m http.server 8099 --bind 127.0.0.1 >/dev/null 2>&1 & echo $! > "$LAB/http.pid")
sleep 1.2
say ""
say "Opening a tunnel: local port 9099 -> (through ssh) -> 127.0.0.1:8099"
sshlab -i "$LAB/id_ravi" -f -N -L 9099:127.0.0.1:8099 "$ME@127.0.0.1" 2>&1 | sed 's/^/  /'
sleep 1
say "Now fetching http://127.0.0.1:9099/secret.txt from THIS machine:"
curl -s --max-time 5 http://127.0.0.1:9099/secret.txt | sed 's/^/  /'
say ""
say "Those bytes crossed the encrypted SSH connection. The HTTP server never"
say "listened on anything but loopback. This is the same connection as the"
say "shell in scenario 1 — SSH multiplexes independent channels over it."
pkill -f "ssh -p $PORT.*9099:127.0.0.1:8099" 2>/dev/null
[ -f "$LAB/http.pid" ] && kill "$(cat "$LAB/http.pid")" 2>/dev/null

# ============================================================ 8
scene 8 "The agent, and why forwarding it is dangerous"
# Pin the agent's socket inside the lab. Left to itself, macOS puts it under
# your real ~/.ssh — and this lab promises not to go there.
rm -f "$LAB/agent.sock"
eval "$(ssh-agent -s -a "$LAB/agent.sock")" >/dev/null
ssh-add "$LAB/id_ravi" 2>/dev/null
say "ssh-agent is holding:"
ssh-add -l | sed 's/^/  /'
say ""
say "Its whole interface is one unix socket:"
say "  SSH_AUTH_SOCK=$SSH_AUTH_SOCK"
ls -l "$SSH_AUTH_SOCK" | sed 's/^/  /'
say ""
say "Connecting with NO -i flag at all — the agent supplies the proof:"
# IdentityFile=none proves the point: no key file is named anywhere, so the
# only possible source of a signature is the agent.
ssh -p $PORT -F /dev/null -o BatchMode=yes -o IdentityFile=none \
    -o UserKnownHostsFile="$LAB/known_hosts" -o GlobalKnownHostsFile=/dev/null \
    "$ME@127.0.0.1" 'echo "   [remote] authenticated straight from the agent"' 2>&1 | sed 's/^/  /'
say ""
say "The agent never hands over the private key — it signs on request. But"
say "anyone who can reach that socket can ask it to sign ANYTHING, for as long"
say "as it is running. That is exactly what 'ssh -A' exposes to root on every"
say "host you forward it to. Use ProxyJump instead."
ssh-agent -k >/dev/null 2>&1

hr
say " Done. sshd stopped, agent killed. Everything created lives in ./lab"
say " and your real ~/.ssh was never touched."
hr
