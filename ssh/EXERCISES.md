# SSH — Exercises

Eight rungs, easiest first. Attempt each before opening
[SOLUTIONS.md](SOLUTIONS.md). Rungs 7 and 8 have no single right answer — the
solution is a worked discussion to compare your reasoning against.

Setup, once:

```bash
java SshLab.java     # the crypto core, 7 experiments
./ssh-real.sh        # real sshd on 127.0.0.1:2222, sandboxed to ./lab
```

---

## Rung 1 — Explain

**1.1** Your colleague says: "SSH is secure because the traffic is encrypted."
In your own words, and without using the words *host key*, *fingerprint* or
*known_hosts*, explain what is incomplete about that sentence. Use the courier
story or one of your own.

**1.2** Explain, in three sentences, why the very first time you SSH to a new
server is different from every time after.

**1.3** Two files are constantly confused: `known_hosts` and `authorized_keys`.
Say which machine each lives on, what each lists, and which question each
answers.

## Rung 2 — Identify

**2.1** Run `./ssh-real.sh`. In scenario 1's output, find (a) the fingerprint
of the server's host key, (b) the line written to `known_hosts`, and (c) the
proof that those two describe the same key.

**2.2** Open the [visualization](https://claude.ai/code/artifact/f8828744-6598-405c-a6c8-cb33b5fb55ce)
and set it to *an interceptor* + *the real key* + *type yes blindly*. Step
through. At exactly which step does the connection die, and what had the client
sent by that point?

**2.3** In `SshLab.java` experiment 2, six keys are derived from one secret.
Why does each direction need its own encryption key *and* its own integrity
key? What would break if both directions shared one key?

**2.4** Run `java SshLab.java` twice. Which printed values change between runs
and which stay the same? What does that tell you about which values are
per-connection and which are per-machine?

## Rung 3 — Predict

For each, write your answer down first, then run the check.

**3.1** `known_hosts` holds the real key for `prod-db`. An attacker intercepts.
Does the attacker learn your username? Your public key? Your signature?
*Check:* visualization, *an interceptor* + *the real key*.

**3.2** It is your first ever connection to a host, an attacker is intercepting,
and you type `yes`. Later the attacker goes away and you connect again to the
genuine server. What appears on your screen, and about which machine?
*Check:* `java SshLab.java`, experiment 4, story B.

**3.3** You have logged into `evil.example.com`, a box whose root user is
hostile. Can its operator use your SSH key to log into `prod.example.com`
as you? Answer for SSH as it exists, then for the version where the signature
does not cover the session identifier.
*Check:* experiment 5.

**3.4** An attacker recorded your encrypted sessions all year. Today she steals
the server's private host key. What can she read? Answer separately for a
design where the host key encrypts the session key, and for real SSH.
*Check:* experiment 6.

## Rung 4 — Modify

**4.1** In `Auth.java`, `blob()` takes a `sessionId` that may be `null`. Change
experiment 5 so that *both* the honest login and the replay use `null`. Run it.
Which property is lost — safety or liveness? Can the honest user still log in?

**4.2** In `Kex.java`, change `exchangeHash` so it hashes only `qc` and `k`,
dropping the server's public value `qs`. Re-run experiment 3. Predict first,
then observe: does the man in the middle now get past the host key check?

The result is probably not what you expected. Work out *why* the weakened hash
still stops her — the answer is about which of the three inputs an interceptor
cannot possibly make match. Then answer the harder question: if `K` alone is
enough to catch an interceptor, what are the exchange hash's other components
actually protecting against? (Stage 15's omissions table names it.)

**4.3** In `ssh-real.sh`, change scenario 4 so that instead of deleting the
`known_hosts` line, it fetches the impostor's real fingerprint with
`ssh-keygen -lf lab/hostkey_impostor.pub` and prints both fingerprints side by
side before deciding. Which of these two versions of scenario 4 is the correct
production response, and why is it so rarely what happens?

**4.4** Add `PasswordAuthentication yes` to the generated `sshd_config` and
re-run. What changes in the `Permission denied (publickey,...)` message, and
what does that message actually list?

## Rung 5 — Implement

**5.1** `KnownHosts.java` has three outcomes: unknown, match, mismatch. Add a
fourth that real OpenSSH has and this file does not: a host key that is known
but marked **revoked** (OpenSSH's `@revoked` marker). A revoked key must be
refused with a *different* message from a mismatch. Why does that distinction
matter operationally?

**5.2** Implement `Wire.parseAuthorizedKeysLine(String line)` that takes a real
`authorized_keys` line (`ssh-ed25519 AAAAC3... comment`) and returns a usable
`PublicKey`. Prove it works by reading `lab/id_ravi.pub` — produced by the real
`ssh-keygen` — and printing a fingerprint that matches `ssh-keygen -lf`.

**5.3** Extend `SshLab` with an experiment 8 that demonstrates **rekeying**:
run a key exchange, derive keys, then run a *second* exchange on the same
"connection" and show that the session identifier stays fixed at the first `H`
while the encryption keys change. Why must the session identifier not change?

## Rung 6 — Debug

**6.1** Run `java BrokenAuth.java`. It is a hand-written SSH-style login used
in production for eight months. Answer the five questions in the file's header
comment. The critical one: exactly one line in `Server.authenticate` is
responsible — which, and what question does it answer instead of the one it
should?

**6.2** A teammate reports: *"SSH broke after we rebuilt the box. I ran
`ssh-keygen -R` and it works now."* Write the three questions you would ask
before accepting that this was fine.

**6.3** A CI job fails with `Host key verification failed`. Someone proposes
adding `-o StrictHostKeyChecking=no` to the command. Explain what that flag
actually does, what it silently gives up, and what to do instead.

## Rung 7 — Handle failure

**7.1** Your company has 40 servers. Twice a month, one is rebuilt and every
engineer gets the `REMOTE HOST IDENTIFICATION HAS CHANGED` banner. They have
learned to run `ssh-keygen -R` without thinking. Describe a change that makes
rebuilds silent *without* weakening the protection, and say exactly what
becomes trusted instead of what is trusted today.

**7.2** An engineer's laptop is stolen. It held an unencrypted private key
authorized on 300 servers. Write the incident response: what is compromised,
what you do in the first hour, and how long a full remediation takes with
today's design. Then describe the design under which the same incident is a
non-event, and say what the residual risk is even then.

**7.3** You must give a CI pipeline the ability to deploy to production over
SSH, without giving it the ability to open an interactive shell or forward
ports. Specify the exact `authorized_keys` line and say what each option
prevents.

## Rung 8 — Design

**8.1** Design SSH access for **3,000 hosts** across three regions, **200
engineers**, and **50 automated systems**. Hosts are created and destroyed
hourly by an autoscaler. Requirements: no engineer ever sees a fingerprint
prompt; access is removed within 5 minutes of someone leaving; every session
is attributable to a person; a compromised host cannot be used to reach
others. Cover: what replaces `known_hosts`, what replaces `authorized_keys`,
how credentials are issued and expire, how automated systems differ from
humans, what you monitor, and what breaks when your issuing service is down.

**8.2** Argue the other side. For which organisations is plain TOFU with
`authorized_keys` the *correct* answer, and at what point does that stop being
true? Name the specific signal that says "you have outgrown this".

**8.3** SSH and TLS solve the same identity problem with opposite trust models.
Design a version of SSH that uses public certificate authorities the way HTTPS
does. What gets better, what gets worse, and why do you think this is not how
SSH works?
