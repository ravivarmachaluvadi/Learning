# Solutions

Every rung-4 and rung-6 answer below was produced by running the modification
described, against real TLS. Rungs 7–8 are worked design arguments, not single
correct outputs.

---

## Rung 1 — Explain

**1.1** "Encrypted with AES-256" establishes that **nobody on the network path
can read the traffic**. It establishes **nothing about who is on the other end**.

The concrete scenario is experiment 3, and it is not hypothetical. An impostor
holds a certificate saying `CN=localhost`, signed by its own authority. If that
authority is in your trust store, you get a flawless `TLSv1.3` connection with
`TLS_AES_256_GCM_SHA384` — and you hand it your password. Every word of "it's
encrypted with AES-256" is true, and the situation is a total compromise.

**1.2** A certificate is a document saying *"this name belongs to whoever holds
this key."* It needs someone else's signature because otherwise anyone could
write one about anyone — a claim about yourself proves nothing.

You can check the signature offline because signature verification only needs the
signer's **public** key, which you already have: it is in your trust store, put
there before you ever made the connection.

**1.3** The padlock proves: the traffic is encrypted and unmodified, and the
server holds the private key for a certificate covering the name in the address
bar, issued by an authority your browser trusts.

Two things people wrongly assume it proves:
- **That the site is honest.** `paypa1.com` can have a perfectly valid
  certificate. TLS proves identity, not virtue.
- **That nobody is reading it.** If a proxy's CA is installed on the machine
  (corporate laptops, very commonly), the padlock shows while the proxy reads
  everything.

---

## Rung 2 — Identify

**2.1** Privacy turns on at **step 2** (ServerHello — both sides can now derive
the key). Identity turns on at **step 5** (after the client verifies the chain).

They cannot coincide because **the certificate is sent encrypted**, at steps 3–4.
Encryption must come first for the certificate to be private at all. So there is
necessarily a window — steps 2 to 5 — where you have a perfectly private channel
to a party you have not identified. That window is the entire subject of the lab.

**2.2** In TLS 1.2: (a) there are more messages — two round trips instead of one,
and (b) **the certificate travels unencrypted**. The second is the privacy
improvement in 1.3: an eavesdropper on 1.2 sees exactly which certificate the
server presented; on 1.3 they see nothing after the ServerHello.

**2.3** Check 1 fails — the chain does not reach a trusted authority. Checks 2, 3
and 4 are **never reached**.

Order matters for two reasons. Practically, once you have established that the
document is not genuine there is no point examining its contents — the name and
the dates in a forged certificate mean nothing. Diagnostically, it means the
*first* failure is the one reported, so a report of "untrusted issuer" tells you
nothing about whether the name or dates were also wrong.

**2.4** Entry **[1]**, `CN=TLS Lab Root CA`. You know because it is
**self-signed** — its subject and issuer are identical, so nothing else vouches
for it. A self-signed certificate is only meaningful if you already had it, and
you did: it is in `truststore.p12`.

**2.5** In `TlsLab.connect()`:

```java
p.setEndpointIdentificationAlgorithm("HTTPS");
```

It enables hostname verification — checking the certificate's SAN list against
the name you connected to. Without it, the signature is still checked but the
name never is.

---

## Rung 3 — Predict

**3.1** Asked for `localhost` → **rejected**: `No subject alternative DNS name
matching localhost found.` Asked for `other.example` → **accepted**. Verified by
running both. The certificate is byte-for-byte identical in each case; only the
question changed. That is what hostname verification is.

**3.2** Both lamps go green. The connection is fully encrypted, and an
eavesdropper on the wire sees **nothing useful** — the impostor's TLS is genuine.
The compromise is not on the wire; it is that the far end is the wrong party.
This is the point people miss: a MITM with a trusted CA is invisible to network
inspection, because there is nothing wrong with the network traffic.

**3.3** With the *good* server, switching to "trust everything" changes
**nothing visible** — it connects, exactly as before. With the impostor, it also
connects, which is the disaster.

That asymmetry is precisely why the bug survives review: **in every environment
where things are configured correctly, the broken code and the correct code
behave identically.** Tests pass, staging works, production works. It only
differs on the day it matters, and then it fails open.

**3.4** It will be found, because the protocol has no alternative. One IP address
can serve thousands of sites, so the server must know which certificate to
present *before* any key exists. The hostname therefore has to travel in the
first message, in the clear. Measured: found at byte offset **153** of 456.

**3.5** Check 3 (validity dates) fails, for **every server you connect to**, with
`CertificateExpiredException` — because a clock two years fast makes every
certificate look expired. The give-away is the scope: a certificate problem
affects one host, a clock problem affects all of them simultaneously.

---

## Rung 4 — Modify (all measured)

**4.1 — Removing `setEndpointIdentificationAlgorithm("HTTPS")`**

| Case | Before | After |
|---|---|---|
| correct name, trusted CA | ACCEPTED | ACCEPTED |
| self-signed | REJECTED (path) | REJECTED (path) |
| **issued for other.example** | **REJECTED** | **ACCEPTED — "identity proved: CN=other.example"** |
| expired | REJECTED (expiry) | REJECTED (expiry) |

Only the hostname case changes; chain-building and expiry are still enforced,
because they are separate checks.

The guarantee destroyed: **any certificate any trusted CA ever issued now works
for any host.** With a public CA in your trust store that means anyone who can
obtain a certificate for a domain they legitimately own — a few minutes and no
money — can impersonate your bank to your client. The output printing "identity
proved: `CN=other.example`" while we asked for `localhost` says it perfectly.

**4.2 — `good` certificate with SAN `dns:localhost` only**

| Asked for | Result |
|---|---|
| `localhost` | ACCEPTED |
| `127.0.0.1` | REJECTED: `No subject alternative names matching IP address 127.0.0.1 found` |

Nothing "breaks" — the certificate is completely valid. The SAN list is not a
description of the server, it is **an exhaustive list of the names this
certificate may be used for**, and an IP address is a different kind of entry
from a DNS name. `localhost` and `127.0.0.1` point at the same machine and are
still two distinct identities as far as TLS is concerned.

This is the real cause of a very common production surprise: health checks that
hit a pod by IP fail while the same service works fine by name.

**4.3 — `-startdate +30d` instead of `-400d`**

*Measured:* `CertificateNotYetValidException: NotBefore: Sun Sep 27 …`

A different exception, because the failure is the opposite direction on the same
check. Real-world equivalents: a certificate issued in advance and deployed too
early, or — far more often — **a client whose clock is behind**. That second case
is why this exception is worth recognising: it usually means the problem is on
*your* machine, not the server's.

**4.4 — `setNeedClientAuth` → `setWantClientAuth`**

*Measured:* case A (no client certificate) now **succeeds** and receives
`secret-balance:42`.

- **need** — the server *requires* a client certificate and fails the handshake
  without one.
- **want** — the server *asks*, accepts whatever comes, and continues regardless.

"Want" is a trap if you read it as "prefer": it provides no enforcement
whatsoever. It is the right choice only when you use the certificate for
*optional* purposes — attribution, logging, gradual rollout — and enforce
authorisation elsewhere. Any time the certificate is what authorises the call,
it must be "need".

The migration path is real, though: enable "want" first, confirm from logs that
every legitimate caller is presenting a certificate, *then* switch to "need".

**4.5 — An empty trust store**

*Measured:* `InvalidAlgorithmParameterException: the trustAnchors parameter must
be non-empty`.

**A different failure, at a different stage.** The self-signed case fails during
*path validation* — the machinery ran and found no route to a trusted root. An
empty trust store fails at *initialization*: the validator refuses to start,
because "verify against nothing" is not a coherent request.

Worth noticing what this rules out: TLS has **no** "trust nothing, accept
everything" mode. Getting that behaviour requires deliberately writing a
`TrustManager` that does nothing — which is exactly `BrokenTrust.java`. The
default fails closed; only code can make it fail open.

---

## Rung 5 — Implement

**5.1** The distinction to get right: a diagnostic tool that deliberately does not
validate is safe because **it never sends anything**. It looks and reports. The
same non-validating code inside an HTTP client is failure 6 because that client
then *transmits credentials* over the unverified connection. The danger is not
"skipped validation", it is "skipped validation followed by trusting the result".

**5.2** Pin the **public key**, not the certificate — a certificate is renewed
routinely and its bytes change, while the key can be kept across renewals.

What breaks on rotation: everything, instantly and completely, if the new key was
not pinned in advance. The standard mitigation is a **backup pin** — pin two keys,
keep the second one offline and unused until you need it. Pinning without a backup
pin has bricked real applications, which is why browsers abandoned HTTP Public Key
Pinning entirely.

**5.3** The subtlety worth handling: check the expiry of the whole **chain**, not
just the leaf. An intermediate expiring takes you down exactly as hard, and it is
the one nobody is watching.

---

## Rung 6 — Debug

**6.1** Measured output:

```
CHECK 1  connects_to_the_real_server
         server replied : secret-balance:42 (you sent: my-password)   -> PASS

CHECK 2  refuses_an_impostor
         impostor cert  : CN=localhost, signed by "Interceptor Root CA"
         we sent        : my-password
         outcome        : CONNECTED — impostor replied: secret-balance:42 ...
         -> FAIL  (the password was handed to an impostor, over a perfectly
                   encrypted channel)
```

- **Which check fails:** check 2. The client connected to an impostor and sent
  `my-password`, which the impostor received in full.
- **Which method:** `checkServerTrusted`. Its body is empty, and **an empty body
  is a silent success** — the contract is "throw a `CertificateException` if the
  certificate is unacceptable", so doing nothing means accepting everything.
  There is no compiler warning, no runtime warning, and the method looks
  implemented.
- **The correct fix:** import the CA's certificate into a trust store and point
  the client at it — `TlsServer.context(null, "pki/truststore.p12")`, which is
  what every working experiment does. It is more work because it needs the CA
  certificate obtained, distributed to every environment, and rotated when the CA
  rotates. That is real operational work, and it is why the one-line "fix" is so
  attractive.
- **The second, independent way:** omitting
  `p.setEndpointIdentificationAlgorithm("HTTPS")`. Even with a correct
  `TrustManager`, the impostor's certificate says `CN=localhost` — the name we
  asked for — so a correct trust check alone still passes here. It is only
  rejected because its *issuer* is untrusted. Had the attacker obtained a
  genuinely-signed certificate for any name at all, the missing hostname check
  would let it through. Both checks are load-bearing, and disabling either is
  sufficient to lose the guarantee.

**6.2** The test needs something a normal unit test does not have: **a second,
adversarial server** with a certificate the client must reject. Testing only
against the real server can never catch this, because the broken client and the
correct client behave identically there.

```java
@Test void client_refuses_a_certificate_from_an_untrusted_authority() {
    try (TlsServer impostor = new TlsServer("pki/mitm.p12", false)) {
        assertThrows(SSLHandshakeException.class, () -> client.call(impostor.port()));
    }
}
```

The general principle: **a security control can only be tested by attempting the
thing it is supposed to prevent.** A test that exercises the happy path tests
nothing about the control.

**6.3** `setDefaultHostnameVerifier((h, s) -> true)` disables **check 2**
(hostname verification) globally for `HttpsURLConnection`. `BrokenTrust` disables
**check 1** (chain building) — and, because the `TrustManager` is the thing that
runs the date check too, effectively check 3 as well.

They are different bugs, and equally fatal, for the reason in 6.1: the two checks
answer different questions ("is this certificate genuine?" and "is it genuine
*for this site*?"), and an attacker only needs one of them to be missing.

`setDefaultHostnameVerifier` is arguably worse in one respect — the word
`setDefault` means it applies process-wide, including to libraries that never
opted in.

---

## Rung 7 — Handle failure (worked discussions)

**7.1 — Make the expiry outage impossible.**

*Automate issuance and renewal.* ACME with cert-manager, or your internal PKI's
equivalent, renewing at one-third of remaining lifetime. Short lifetimes are a
feature: a 90-day certificate forces the automation to run often enough that
breakage is discovered in days rather than in the one annual attempt that fails.

*Monitor the outcome, not the job.* Alert on **days-until-expiry measured from
the served certificate** — connect and read what the server actually presents.
A renewal job that "succeeded" while writing to a path nobody reads is the
classic failure, and only the served certificate reveals it. Alert at 30 days,
page at 7.

*Include the whole chain*, per 5.3, and *include certificates you depend on but
do not control*: your payment provider's expiry takes you down too, and you will
not be told. A daily probe of every outbound HTTPS dependency is cheap.

*When automation fails*, the runbook needs a manual issuance path that has been
rehearsed. An untested emergency path during an outage is not a plan.

**7.2 — Detect a rogue root.**

Record the **issuer and public-key fingerprint of every certificate your services
accept**, and ship it off-box. The signal is a chain terminating in a root you did
not put there, or a familiar hostname suddenly presenting a certificate from a
different issuer.

The fundamental limit is worth stating plainly: **an attacker who can add a root
to your trust store can very likely also alter what you record.** Detection from
inside a compromised machine is unreliable by construction. That is why the
strong answers move the check outside — Certificate Transparency logs, which make
mis-issuance publicly visible, and pinning, which ignores the trust store
entirely for the connections you care most about.

**7.3 — Rotate a possibly-leaked key.**

Ordering, and each step has a reason:

1. **Generate a new keypair and get it signed** — before touching anything else,
   because until you hold the replacement you cannot act without an outage.
2. **Deploy the new certificate everywhere** and verify it is being served.
3. **Then revoke the old one.** Revoke first and you have an outage with no
   replacement ready.
4. **Rotate anything the key protected** — session tickets, and any secret that
   travelled over connections you now believe were exposed.

*Why revocation is not enough:* per failure 7, many clients do not check
revocation, or soft-fail when the check is unreachable. Assume the old
certificate keeps working for its remaining lifetime, and let that shape your
urgency.

*Client impact:* if the CA is unchanged, none — clients trust the CA, not the
specific key. This is exactly why key pinning (5.2) makes rotation dramatically
riskier, and why it is a deliberate trade rather than a free improvement.

*What forward secrecy changes:* enormously. Because TLS 1.3 always uses ephemeral
key exchange, the leaked key **cannot decrypt previously recorded traffic** — it
only enables impersonation from now until it is replaced. That converts
"potentially years of history exposed" into "a window we can close today", and it
is the single biggest reason the v1 design in stage 10 was abandoned.

---

## Rung 8 — Design (worked arguments)

**8.1 — Where TLS terminates.** A defensible design: TLS terminates at the load
balancer for the public edge (so certificate management is centralised and the
public CA integration lives in one place), and internal traffic runs mTLS via
sidecars.

Then answer the question honestly: **between the load balancer and the first
service, traffic is plaintext unless you re-encrypt.** Anyone with access to that
network segment, to a host in it, or to a packet capture on it can read
everything — including the credentials your edge just validated.

Whether that is acceptable depends entirely on who "anyone" is: your cloud
provider's network, other tenants, your own operators, and anything that gets a
foothold in the VPC. For most systems handling payments or personal data the
honest answer is no, and the fix is re-encryption to the backend or end-to-end
mTLS with the load balancer passing through rather than terminating.

**8.2 — Diagnose from a description.**

| Report | Most likely | First command |
|---|---|---|
| (a) laptop yes, container no | Trust store missing from the base image | Compare `cacerts` between images |
| (b) all providers at once | **Your clock** — not their certificates | `date -u`, check NTP |
| (c) `curl` yes, Java no | Java has its own trust store, separate from the OS | `keytool -list -cacerts` vs the system store |
| (d) one hostname of three | SAN list missing that name | `openssl s_client -connect host:443` and read the SANs |
| (e) works but scanner complains | Failure 5 or 6 — interception, or checks disabled | Log the issuer of what you accept; grep for empty `checkServerTrusted` |

The organising question is the same one from the networking lab: **what is the
scope?** One host is a certificate problem; every host at once is a client
problem.

**8.3 — The case against mTLS internally.**

> Every workload now needs an identity, which means a certificate, which means
> issuance, distribution, rotation and revocation for every pod — at a lifetime
> short enough to be safe, so it is happening constantly. When that machinery
> breaks, it does not break one service, it breaks *the mesh*, and the failure
> presents as universal connection errors with no obvious owner.
>
> Meanwhile the threat it addresses — an attacker already inside our network
> passively reading service-to-service traffic — is one we may have better and
> cheaper answers for, and mTLS does nothing about the far more common case of a
> compromised service with a perfectly valid identity, which it will happily
> authenticate.

What would change my mind: a compliance requirement that names encryption in
transit; a genuinely multi-tenant or untrusted network; or a mature platform team
already running a mesh, where the marginal cost is close to zero because someone
else owns the certificate lifecycle. The argument above is really about
*operational maturity*, not about cryptography.
