#!/bin/bash
# Builds every certificate the lab needs, using keytool — the JDK's own tool,
# the same one you would use to configure TLS in a Spring Boot app.
#
# It creates one private certificate authority and four server identities:
#   good      — correct hostname, signed by our CA        -> should be accepted
#   wronghost — signed by our CA, but for the wrong name   -> hostname mismatch
#   expired   — signed by our CA, already out of date      -> expired
#   selfsigned— signs itself, nobody vouches for it        -> untrusted issuer
#   mitm      — an impostor's CA and cert for "localhost"  -> the interceptor
#
# Passwords are "changeit" throughout. This is a lab; never do that anywhere else.
set -e
cd "$(dirname "$0")"
rm -rf pki && mkdir pki && cd pki
P=changeit
KT="keytool -storepass $P -keypass $P -noprompt"

mk_ca () { # name
  $KT -genkeypair -alias ca -dname "CN=$1 Root CA,O=$1" \
      -keyalg EC -groupname secp256r1 -validity 3650 \
      -ext bc:c=ca:true -keystore "$2" -storetype PKCS12 >/dev/null
  $KT -exportcert -alias ca -keystore "$2" -rvalue -file "$3" >/dev/null 2>&1 \
    || $KT -exportcert -alias ca -keystore "$2" -file "$3" >/dev/null
}

# --- our trusted certificate authority --------------------------------------
mk_ca "TLS Lab" ca.p12 ca.cer

# --- a helper that issues a server cert signed by our CA --------------------
issue () { # alias  dname  san  extra-dates
  $KT -genkeypair -alias "$1" -dname "$2" -keyalg EC -groupname secp256r1 \
      -validity 365 $4 -keystore "$1.p12" -storetype PKCS12 >/dev/null
  $KT -certreq -alias "$1" -keystore "$1.p12" -file "$1.csr" >/dev/null
  $KT -gencert -alias ca -keystore ca.p12 -infile "$1.csr" -outfile "$1.cer" \
      -ext "san=$3" -validity 365 $4 -rfc >/dev/null
  cat ca.cer "$1.cer" > "$1.chain.cer"
  $KT -importcert -alias ca -file ca.cer -keystore "$1.p12" >/dev/null
  $KT -importcert -alias "$1" -file "$1.chain.cer" -keystore "$1.p12" >/dev/null
}

issue good      "CN=localhost,O=TLS Lab"        "dns:localhost,ip:127.0.0.1"  ""
issue wronghost "CN=other.example,O=TLS Lab"    "dns:other.example"           ""
issue expired   "CN=localhost,O=TLS Lab"        "dns:localhost,ip:127.0.0.1"  "-startdate -400d"

# --- a server nobody vouches for --------------------------------------------
$KT -genkeypair -alias selfsigned -dname "CN=localhost,O=Nobody" \
    -keyalg EC -groupname secp256r1 -validity 365 \
    -ext "san=dns:localhost,ip:127.0.0.1" \
    -keystore selfsigned.p12 -storetype PKCS12 >/dev/null

# --- the impostor: its own CA, and a cert claiming to be localhost ----------
mk_ca "Interceptor" mitmca.p12 mitmca.cer
$KT -genkeypair -alias mitm -dname "CN=localhost,O=Interceptor" -keyalg EC \
    -groupname secp256r1 -validity 365 -keystore mitm.p12 -storetype PKCS12 >/dev/null
$KT -certreq -alias mitm -keystore mitm.p12 -file mitm.csr >/dev/null
$KT -gencert -alias ca -keystore mitmca.p12 -infile mitm.csr -outfile mitm.cer \
    -ext "san=dns:localhost,ip:127.0.0.1" -validity 365 -rfc >/dev/null
cat mitmca.cer mitm.cer > mitm.chain.cer
$KT -importcert -alias ca -file mitmca.cer -keystore mitm.p12 >/dev/null
$KT -importcert -alias mitm -file mitm.chain.cer -keystore mitm.p12 >/dev/null

# --- the client's trust store: it trusts our CA and nothing else ------------
$KT -importcert -alias lab-ca -file ca.cer -keystore truststore.p12 -storetype PKCS12 >/dev/null
# --- a second trust store that ALSO trusts the interceptor ------------------
cp truststore.p12 truststore-with-mitm.p12
$KT -importcert -alias interceptor -file mitmca.cer -keystore truststore-with-mitm.p12 >/dev/null

echo "Created in $(pwd):"
ls -1 *.p12 *.cer | sed 's/^/  /'
