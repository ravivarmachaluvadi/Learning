# SSH — Solutions

Try the exercises first. Rungs 7–8 are worked discussions, not single answers.

---

## Rung 1 — Explain

**1.1** Encryption puts your message in a locked box that only the person you
exchanged keys with can open. It says nothing about *who that person is*. If a
stranger intercepted the setup and did the key exchange himself, you now have a
beautifully locked box that only he can open — and it looks exactly like
success. The hard problem is not hiding the message; it is knowing who is at
the other end.

**1.2** On the first connection your laptop has never seen this server, so it
has nothing to compare against and no third party to ask. It shows you a
fingerprint and asks you to decide. From the second connection onwards it has
a recorded answer, so it can check automatically and refuse anything different.
The protection therefore begins at connection two.

**1.3**

| | `known_hosts` | `authorized_keys` |
|---|---|---|
| lives on | your laptop (client) | the server, in the target user's home |
| lists | machines and their host keys | people and their public keys |
| answers | "am I talking to the right machine?" | "is this person allowed in as this user?" |

## Rung 2 — Identify

**2.1** (a) printed at the top: `server host key fingerprint : SHA256:…`.
(b) the single line `[127.0.0.1]:2222 ssh-ed25519 AAAAC3Nza…`.
(c) the script runs `ssh-keygen -lf lab/known_hosts`, which prints the same
`SHA256:…` value — the fingerprint is computed *from* the key, so matching
fingerprints means matching keys.

**2.2** It dies at **step 5, the host key check** — before authentication
begins. At that point the client has sent only the SSH version string and its
throwaway public value `Q_C`. It has **not** sent your username, offered your
public key, or signed anything. The attacker gains nothing reusable.

**2.3** Separate keys per direction and per purpose limit what one compromised
key can do. If both directions shared one key, an attacker who obtained it
could not merely read — they could forge messages *as the server* to the
client. Separating encryption from integrity keys means a flaw that exposes one
does not automatically grant the other. This is a standard principle: one key,
one job.

**2.4** Changing every run: the generated key fingerprints, `Q_C`, `Q_S`, `K`,
all derived keys, session ids, and all ciphertexts. Stable: the *structure* —
blob length 51 bytes, key lengths 32 bytes, the six derived key roles, and the
verdicts (`true`/`false`) of every check. Per-connection values are the
ephemeral ones; a real server's host key would be the stable one, but this lab
generates a fresh one each run, so nothing here is per-machine. That is worth
noticing: it is the difference between a lab and a real deployment.

## Rung 3 — Predict

**3.1** No, no, and no. Host key verification happens in the transport layer,
before the authentication layer starts. The client aborts with
`Host key verification failed` having sent only the version string and its
ephemeral public value. This is the strongest single property in SSH's design:
identity of the *machine* is settled before anything about the *user* is
disclosed.

**3.2** You get the full `REMOTE HOST IDENTIFICATION HAS CHANGED` banner —
pointing at the **genuine server**. Your laptop pinned the attacker's key as
the truth, so the honest machine is now the one that looks wrong. Experiment 4
story B prints exactly this. The lesson: TOFU is faithful to whatever you told
it first, and it cannot distinguish a lie recorded early from a change later.

**3.3** In real SSH: **no**. Your signature covers this connection's session
identifier, which the operator cannot reproduce on a connection to
`prod.example.com`. In the version without it: **yes** — the signature is over
"I am ravi", which is equally true anywhere, so it replays. Experiment 5 shows
`true` then `false`.

**3.4** With host-key encryption of the session key: she reads **everything**,
retroactively, for as long as she has been recording. With real SSH: **nothing
new** — the session keys came from ephemeral values that were destroyed at
logout, and the host key was only ever used to sign. She can *impersonate the
server in future* connections, which is serious, but the archive stays shut.

## Rung 4 — Modify

**4.1** The honest user still logs in perfectly — so **liveness is unaffected**.
What is lost is **safety**: the replay now succeeds, so a signature captured by
any server you touch is usable against any other server that trusts your key.
Note the shape of this: the system looks completely healthy in testing. Only an
adversary notices the difference, which is why this class of bug survives code
review.

**4.2** The interceptor **still fails**. Measured over 200 simulated attempts:

```
  H = hash(Q_C, Q_S, K)  -> attacker survived 0 times
  H = hash(Q_C, K)       -> attacker survived 0 times
  H = hash(K)            -> attacker survived 0 times
```

The reason is `K`. An interceptor must run two *separate* key exchanges, so the
secret she shares with you is necessarily different from the one she shares with
the server. Any hash that includes `K` therefore differs at the two ends, and
the real server's signature cannot verify against the client's hash. The public
values are not what catches her.

So what are the other components for? **Downgrade protection.** The real
exchange hash also covers both sides' version strings and their full
`KEXINIT` algorithm lists. Without that, an interceptor who cannot break the
exchange could still *edit the negotiation* on the way past — stripping strong
ciphers from the list so both ends settle on the weakest option they both
support, then attacking that. Hashing the negotiation means any such edit
changes `H`, and the signature fails. The signature protects the *terms* of the
conversation, not only its participants.

**4.3** The fingerprint-comparison version is correct. `ssh-keygen -R` deletes
your only evidence and re-runs TOFU, which is *exactly* the state an attacker
wants you in. It is rarely what happens because the alarm is nearly always a
legitimate rebuild, so the reflex is reinforced dozens of times before the one
occasion it matters — and because obtaining the true fingerprint requires
leaving the terminal and finding an authoritative source, which is slow at the
precise moment you are blocked. That asymmetry, not ignorance, is why the
protection fails in practice — and it is the argument for host certificates
(stage 15), which remove the alarm from routine rebuilds entirely.

**4.4** The message becomes
`Permission denied (publickey,password,keyboard-interactive)`. That
parenthesised list is the set of authentication methods the **server** is
willing to accept — it is a useful diagnostic in itself. If `publickey` is
absent from it, the server has disabled key authentication and no amount of
fixing your key will help.

## Rung 5 — Implement

**5.1** Add a revoked set checked *before* the match branch:

```java
if (revoked.contains(fp))
    return "!! this host key has been REVOKED. Do not connect. !!";
```

It matters operationally because the two states demand opposite responses. A
mismatch means *"something changed, go and find out what"* — often benign. A
revoked key means *"this key is known to be compromised; a server presenting it
is either the attacker or still running the bad key"* — never benign, and never
to be resolved by deleting a line. OpenSSH supports this as the `@revoked`
marker in `known_hosts` and, for certificates, `RevokedKeys`.

**5.2** Split on spaces, base64-decode field 2, then walk the blob: read a
4-byte length, read that many bytes (`"ssh-ed25519"`), then the same again for
the 32 raw key bytes, and hand those to `Wire.parseEd25519`. The test that it
is right is that your fingerprint matches `ssh-keygen -lf` on a key file the
real `ssh-keygen` produced — the same cross-check experiment 1 uses in the
other direction.

**5.3** The session identifier must stay fixed at the first `H` because it is
what your authentication signature was bound to. If it changed on rekey, either
every existing signature would become invalid, or — far worse — the binding
would become re-negotiable, which reopens the replay hole from experiment 5.
RFC 4253 is explicit: the session identifier is set once, by the first
exchange, and never changes for the life of the connection. Encryption keys, by
contrast, are replaced every rekey; that is the entire point.

## Rung 6 — Debug

**6.1** **Check 2 fails**: a stranger who generated a key five seconds ago is
admitted as `ravi`. What she had to possess: nothing. No stolen key, no
intercepted traffic — just the ability to make a key pair, which anyone can do.

The line is:

```java
return Wire.verify(offeredKey, expected, signature);
```

It answers **"did the holder of this key sign this message?"** — and the answer
is honestly yes, because she signed it with the key she just made. The question
it should answer is **"is this key allowed in?"**. `authorizedKeys` is consulted
only via `containsKey(user)`, which checks the *username* exists, never the
key.

Using experiment 3's sentence: *the signature is fine; the key is wrong.* The
code verifies possession flawlessly and never checks authorization — exactly
the man-in-the-middle failure, moved from the transport layer to the auth layer.

The fix:

```java
PublicKey allowed = authorizedKeys.get(user);
if (allowed == null || !allowed.equals(offeredKey)) return false;
```

On a real server the data structure you must consult is
`~/.ssh/authorized_keys` in the target user's home directory.

Would the session id have helped? **No** — and this is the point of the
question. The session identifier stops a *valid* proof being reused on a
different connection. It says nothing about whether the key behind that proof
was ever authorized. They are two independent checks: *is this proof genuine
and for this connection* (signature + session id) and *is this key permitted*
(`authorized_keys`). This code does the first perfectly and skips the second
entirely.

**6.2** (i) Where did you get the expected fingerprint for the rebuilt box, and
did it come from somewhere other than the SSH connection itself? (ii) Did
everyone else get the same new fingerprint you did — or did some colleagues see
a different one? (iii) Was a rebuild actually scheduled at that time, and does
the build log show a new host key being generated? Any "no" or "don't know"
means it was not verified, only silenced.

**6.3** `StrictHostKeyChecking=no` accepts unknown host keys silently *and*
allows connections to hosts whose key has changed. That second half is the
dangerous one, and it is current behaviour, not a legacy quirk — `ssh_config(5)`
on this machine says of `no`: *"ssh will automatically add new host keys to the
user known hosts files and allow connections to hosts with changed hostkeys to
proceed, subject to some restrictions."* It gives up host verification
completely — the CI job will happily talk to anyone who can answer on that
address. Do instead: commit the expected
host key into the repo and point `UserKnownHostsFile` at it, so the pipeline
verifies against a reviewed constant. If you want the "unknown is fine, changed
is fatal" behaviour, `accept-new` is the correct value — never `no`.

## Rung 7 — Handle failure

**7.1** Deploy an **SSH host certificate authority**. Each server, at build
time, gets its freshly generated host key signed by your CA, producing a host
certificate naming it. Every laptop's `known_hosts` gets one line:

```
@cert-authority *.prod.example.com ssh-ed25519 AAAA…   # the CA's public key
```

What is trusted changes from *"this specific key, per machine, recorded by me
at some point"* to *"any host key the CA has vouched for"*. Rebuilds then verify
silently and correctly on the first connection. Crucially the protection is not
weakened but strengthened: today an engineer approves an unverified key twice a
month; afterwards a prompt never appears at all, so if one ever does, it is
real. The new dependency is the CA private key, which becomes the most
sensitive secret you own and belongs in an HSM or a hardware token.

**7.2** *Compromised:* everything that key could reach — 300 servers, with
whatever privileges the account holds on each, and any further access reachable
from those hosts. Assume it is being used now.

*First hour:* remove the key from the central source that generates
`authorized_keys` (configuration management, not by hand); push that removal to
all 300 hosts and record which succeeded; disable the engineer's account in the
identity provider; check `auth.log` across the fleet for `Accepted publickey`
lines carrying that fingerprint since the theft; look for newly added
`authorized_keys` entries, cron jobs and systemd units on any host that shows
one.

*Full remediation:* days. The hard part is not the removal, it is proving
completeness — hosts that were down, hosts outside configuration management,
keys copied into a container image, keys added to a service account by hand.
There is no mechanism that tells you when you missed one.

*The design where this is a non-event:* short-lived **user certificates**. The
engineer's key alone is useless without a valid certificate, and certificates
expire in hours. Revoke at the CA, or simply disable the SSO account, and access
ends fleet-wide within one certificate lifetime. No file on any host is edited.

*Residual risk even then:* any session already open stays open until killed
(certificate expiry does not terminate a live connection); anything the attacker
established during the valid window — an added `authorized_keys` entry, a
reverse tunnel, a modified image — survives the certificate expiring. Short
credentials shrink the window; they do not undo what happened inside it.

**7.3** A forced command with every capability stripped:

```
command="/usr/local/bin/deploy",restrict ssh-ed25519 AAAA… ci@buildserver
```

`command=` means the server runs *that* program regardless of what the client
asked for, so an interactive shell is impossible and the client's requested
command is available only in `$SSH_ORIGINAL_COMMAND` for the script to inspect.
`restrict` is the modern all-in option: it denies port forwarding, agent
forwarding, X11 forwarding, PTY allocation and user `rc` execution, and is
safer than listing `no-port-forwarding,no-agent-forwarding,…` by hand because
it stays correct when OpenSSH adds new capabilities. Add
`from="10.0.5.0/24"` to bind the key to your build network as well.

## Rung 8 — Design

**8.1** *Sketch of a defensible answer.*

**Replaces `known_hosts`:** a host CA. Every instance, in its bootstrap, gets
its host key signed by the CA with a short-lived host certificate. Laptops trust
one `@cert-authority` line per domain. No prompts, ever; autoscaling stops
mattering.

**Replaces `authorized_keys`:** user certificates carrying **principals**.
`sshd_config` gets `TrustedUserCAKeys` and
`AuthorizedPrincipalsFile`, mapping a principal such as `payments-oncall` to the
local account it may assume. Access becomes group membership, not a file edit.

**Issuance:** an engineer authenticates to the identity provider (SSO + MFA),
receives a certificate valid ~8 hours with principals derived from their current
groups. Removal within 5 minutes is achieved by deactivating the account so no
*new* certificates issue, plus a revocation list distributed to hosts
(`RevokedKeys`) for the case where you cannot wait for expiry.

**Automated systems:** never long-lived keys checked into anything. Workloads
obtain certificates from the same CA using their platform identity (instance
identity document, Kubernetes service account, OIDC federation), with lifetimes
in minutes, principals restricted to a purpose-built account, and forced
commands as in 7.3.

**Attribution:** the certificate's key id carries the human's identity and is
logged by `sshd` on every login, so a shared `deploy` account still produces
per-person audit lines. Log centrally; alert on any `Accepted publickey`
without a certificate (i.e. a raw key still working somewhere).

**Blast radius:** hosts do not hold credentials for other hosts — a compromised
host yields no reusable key, because it never had one. Agent forwarding is
denied by certificate extension, so a hostile host cannot borrow an engineer's
identity; `ProxyJump` is used instead.

**When the CA is down:** nobody can obtain *new* certificates, but existing ones
keep working until they expire — so the outage becomes visible gradually rather
than instantly, which is the right failure shape. You still need a documented
break-glass path: a small number of emergency keys in `authorized_keys` on
critical hosts, held in a vault, alerting loudly on use. Design it before you
need it, and test it on a schedule; an untested break-glass path is decoration.

**8.2** Plain TOFU is correct when the number of hosts is small, stable, and
long-lived, and the number of engineers is small enough that everyone can be
told a fingerprint out of band — realistically, up to a few dozen hosts and a
handful of people. Below that scale a CA is more moving parts than it saves,
and the CA key becomes a new single point of catastrophe you now have to
protect.

The signal that you have outgrown it is behavioural, not numerical: **when
engineers start clearing host-key warnings without investigating**, the
protection has already stopped working — the file still exists, the checks
still run, and the humans they depend on have been trained out of the loop.
Secondary signals: hosts outliving their host keys, `ssh-keyscan` appearing in
scripts, or offboarding requiring an edit on more machines than one person can
verify in an afternoon.

**8.3** *What gets better:* first-contact security, without you running any
infrastructure. Any server could be verified on the very first connection, so
F3 disappears globally rather than per-organisation.

*What gets worse:* every internal machine would need a publicly-issued
certificate, which means a public CA must be told the names of your private
hosts, and names like `db-04.internal` cannot be issued publicly at all.
Renewal becomes a fleet-wide operational dependency with a hard expiry — the
classic PKI outage, now able to lock you out of the very machines you need in
order to fix it. You would also inherit the entire public CA trust surface:
roughly 150 organisations, any one of which mis-issuing gets you a valid
certificate for your own production host.

*Why SSH does not work this way:* SSH's job is administrative access to
machines you already own, usually named privately, often unreachable from the
internet. HTTPS's job is a stranger's browser reaching a public name it has
never seen. Public CAs solve the second problem, and SSH does not have it — so
it declines the dependency and keeps trust local, at the cost of the
first-connection hole. The organisations for whom that hole matters run their
own CA, which is the same design with the trust anchor moved in-house.
