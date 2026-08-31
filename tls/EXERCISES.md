# Exercises — TLS

Rungs 1–3 need only the README and the
[handshake stepper](https://claude.ai/code/artifact/c9e77989-533d-463a-8f3e-3e7643cbc40f)
(or `visualization.html`). Rungs 4–6 need `./certs.sh` and `java TlsLab.java`.
Rungs 7–8 are design work with no single right answer.

Attempt before opening `SOLUTIONS.md`.

---

## Rung 1 — Explain

**1.1** A colleague says: *"the connection is encrypted with AES-256, so it's
secure."* In your own words, no jargon, explain what that sentence does and does
not establish, and give a concrete scenario where it is true and the situation is
still a total compromise.

**1.2** Explain to someone non-technical what a certificate is and why it needs a
signature from someone else. Then explain, in one sentence, why *you* can check
that signature without contacting anyone.

**1.3** Your browser shows a padlock. State precisely what has been proved, and
name two things a user commonly assumes it proves that it does not.

---

## Rung 2 — Identify

Open the stepper. Leave the defaults and press **Play it through**.

**2.1** At which step number does the "nobody else can read this" lamp turn on?
At which step does the "you are talking to who you think" lamp turn on? Why can
they not turn on at the same moment?

**2.2** Switch the protocol to TLS 1.2 and play again. Name two things that
changed, and say which of them is a privacy improvement rather than a speed one.

**2.3** Select the impostor scenario and step to the point of failure. Which of
the four checks failed, and which were never reached? Why does the order of the
checks matter?

**2.4** In experiment 1's output, the chain has two entries. Which one did your
client already have before the connection started, and how do you know?

**2.5** In `TlsLab.java`, find the one line whose removal would make the
`wronghost` certificate acceptable. Quote it and say what it does.

---

## Rung 3 — Predict

Write your answer down *before* running anything.

**3.1** The `wronghost` certificate is signed by a CA you trust and is in date.
Predict the outcome when you ask for `localhost`, and then when you ask for
`other.example`. Check both in the stepper.

**3.2** In the stepper, select the impostor and tick **Interceptor Root CA**.
Before playing: predict what both lamps do, and what an eavesdropper on the wire
would be able to read. Then play it, and run `java TlsLab.java 3` to confirm.

**3.3** You set "Check the certificate at all?" to *trust everything*, with the
good server selected. Predict whether anything visibly changes. Now switch to the
impostor and predict again. What does the difference tell you about why this bug
survives code review?

**3.4** `java TlsLab.java 4` searches the client's first message for a hostname.
Predict whether it will be found, and explain why the protocol has no choice in
the matter.

**3.5** Your laptop's clock is two years fast. Predict which of the four
verification checks fails, for which servers, and what the exception will be.

---

## Rung 4 — Modify

**4.1** Delete `p.setEndpointIdentificationAlgorithm("HTTPS")` from
`TlsLab.connect()` and re-run experiment 2. Which case changes verdict? Which
ones do not, and why not? State exactly which guarantee you just destroyed.

**4.2** In `certs.sh`, change the `good` certificate's SAN from
`dns:localhost,ip:127.0.0.1` to `dns:localhost` only. Re-run `./certs.sh` and
experiment 2. Does anything break? Now change `TlsLab.connect()` to connect by IP
instead of by name and re-run. Explain the result in terms of what the SAN list
actually is.

**4.3** In `certs.sh`, issue the `expired` certificate with `-startdate +30d`
instead of `-startdate -400d`. Re-run. What exception do you get now, and why is
it a *different* one from the expired case? Which real-world situation does this
correspond to?

**4.4** In `TlsServer`, change the mTLS server's `setNeedClientAuth(true)` to
`setWantClientAuth(true)`. Re-run experiment 6. What happens to case A now?
Explain the difference between "need" and "want", and name a situation where each
is the right choice.

**4.5** Remove the CA certificate from `truststore.p12` (or point the client at an
empty trust store) and re-run experiment 1. Predict first: does it fail at the
same place as the self-signed case, or a different one?

---

## Rung 5 — Implement

**5.1** Write `Inspect.java`: given a host and port, connect, and print the full
certificate chain — subject, issuer, validity dates, SAN list — plus the
negotiated protocol and cipher, **without** validating anything. It must work
against a server whose certificate you do not trust, because that is exactly when
you need it. Explain why a deliberately non-validating tool is safe here but the
same code in an HTTP client is failure 6.

**5.2** Implement certificate **pinning**: a `TrustManager` that accepts a
connection only if the server's public key matches a value you hard-coded. Prove
it accepts the `good` server and rejects the impostor *even when the impostor's CA
is in the trust store*. Then answer: what breaks the next time the server rotates
its key, and what is the standard mitigation?

**5.3** Write `ExpiryCheck.java` that takes a list of host:port pairs and reports
days-until-expiry for each, exiting non-zero if any is under 30 days. This is
README stage 15's first monitoring recommendation, made real.

---

## Rung 6 — Debug

**6.1** Run `java BrokenTrust.java`.

- Which check fails, and what exactly did the client hand over?
- Which method causes it, and what is the significance of its body being empty?
- The developer's original error was real — the certificate genuinely could not
  be verified. Name the correct fix, and say why it is more work.
- There is a **second**, independent way to break exactly this protection, and
  `TlsLab.connect()` has a comment naming it. What is it, and why would check 2
  still fail even if this file's `TrustManager` were correct?

**6.2** Write the smallest test you would add to a real suite to catch this class
of bug. What must the test have available to it that a normal unit test does not?

**6.3** Here is a proposed fix a reviewer suggests instead:

```java
HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
```

Explain what this does, why it is *not* the same bug as `BrokenTrust`, and why it
is nevertheless just as dangerous. Which of the four checks does each one disable?

---

## Rung 7 — Handle failure

**7.1 — Make the expiry outage impossible.** Failure 3 is the most common TLS
outage there is, and it is entirely self-inflicted. Design the controls that make
it structurally unable to happen for a service you own. Cover: what you monitor,
what automates renewal, what happens when automation fails, and how you find out
about certificates you depend on but do not control.

**7.2 — Detect failure 5.** An attacker who can add a root to your trust store can
impersonate anything, silently, while everything reports success. Design a
detection strategy for a fleet of servers. What do you record, what would the
signal look like, and what is the fundamental limit on how well this can work?

**7.3 — Rotate a private key you think leaked.** Your service's private key may
have been exposed. Write the runbook. Cover ordering (what must happen before what
and why), why revocation alone is not enough given failure 7, what the client
impact is, and how forward secrecy changes the severity of the incident.

---

## Rung 8 — Design

**8.1 — Where does TLS terminate?** Design the transport security for a system
with an internet-facing API, a load balancer, and eight internal services on
Kubernetes. Decide where TLS terminates, and justify it. Then answer directly:
between which pairs of components is traffic actually plaintext, who can read it,
and is that acceptable? What changes if the answer is no?

**8.2 — Diagnose from a description.** For each report, name the most likely cause
and the single command you would run first:
(a) "Works from my laptop, fails in the Docker container."
(b) "Every outbound HTTPS call started failing at once, to every provider."
(c) "Works in `curl` on the same host, fails in our Java service."
(d) "Fails only for one of our three API hostnames."
(e) "Security scanning says our traffic is being intercepted, but everything works."

**8.3 — Argue against mTLS.** Experiment 6 makes mTLS look strictly better. Make
the strongest honest case *against* adopting it for internal traffic in a specific
system you know. Use the operational costs from stage 15. Then name the conditions
under which you would change your mind.
