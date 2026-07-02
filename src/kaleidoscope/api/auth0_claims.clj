(ns kaleidoscope.api.auth0-claims
  "Auth0-specific JWT claim conventions — not part of the OIDC spec.

  Two things live here that `kaleidoscope.api.authentication` (generic OIDC
  parsing) shouldn't know the details of:
  - Namespaced custom claims (`https://kaleidoscope.pub/...`), added via Auth0
    Actions on tokens that don't carry standard `email`/`email_verified`
    claims (e.g. M2M access tokens).
  - The `gty` (grant type) claim, which Auth0 stamps onto tokens issued via
    certain non-interactive grants. `gty=client-credentials` is the
    authoritative signal for an M2M/service token.

  Swapping identity providers means replacing this namespace, not
  `kaleidoscope.api.authentication`.")

(def ^:private namespaced-email-claim
  (keyword "https://kaleidoscope.pub/email"))

(def ^:private namespaced-email-verified-claim
  (keyword "https://kaleidoscope.pub/email_verified"))

(defn namespaced-email
  [id-token]
  (get id-token namespaced-email-claim))

(defn namespaced-email-verified
  [id-token]
  (get id-token namespaced-email-verified-claim))

(defn m2m-token?
  "True for Auth0 client_credentials (M2M) tokens."
  [id-token]
  (= "client-credentials" (:gty id-token)))
