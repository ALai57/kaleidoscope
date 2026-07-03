(ns kaleidoscope.api.authentication
  (:require [clojure.string :as string]
            [kaleidoscope.api.auth0-claims :as auth0]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OIDC Identity tokens as authentication
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-scopes
  [{:keys [scope] :as id-token}]
  (and scope (set (string/split scope #" "))))

(defn get-realm-roles
  [id-token]
  (set (get-in id-token [:realm_access :roles])))

(defn get-full-name
  [id-token]
  (:name id-token))

(defn get-user-id
  [id-token]
  (:sub id-token))

(defn get-email
  [id-token]
  (or (:email id-token)
      (auth0/namespaced-email id-token)))

(defn email-verified?
  [id-token]
  (if (contains? id-token :email_verified)
    (:email_verified id-token)
    (auth0/namespaced-email-verified id-token)))

(defn get-verified-email
  [id-token]
  (when (email-verified? id-token)
    (get-email id-token)))

(defn classify-identity
  "Classifies a raw JWT claims map into a typed identity.

  Three kinds of credentials exist:
  - M2M/service tokens (Auth0 `gty`=client-credentials — see `auth0-claims`) produce
    :service-account with :user-id set to the :sub claim.
  - Human sessions with a verified email produce :verified-user with :user-id set to
    their email.
  - Human sessions without a verified email produce :unverified-user with :user-id set
    to their email.

  The distinction is made once at the authentication boundary; downstream code reads :type
  and :user-id directly rather than re-deriving them from raw JWT fields."
  [id-token]
  (cond
    (auth0/m2m-token? id-token)
    ;; M2M tokens have no email/email_verified claim, so :sub is the only
    ;; stable identifier available. Per the OIDC spec, the sub field is a
    ;; string identifying the principal — treat every M2M caller as a
    ;; distinct (test/service) user keyed by that string.
    {:type    :service-account
     :user-id (:sub id-token)
     :roles   (get-realm-roles id-token)}

    (email-verified? id-token)
    {:type    :verified-user
     :user-id (get-email id-token)
     :roles   (get-realm-roles id-token)}

    :else
    {:type    :unverified-user
     :user-id (get-email id-token)
     :roles   (get-realm-roles id-token)}))

(comment
  (def example-oidc-token
    {:acr                "1",
     :allowed-origins    ["*"],
     :aud                "account",
     :auth_time          1614122869,
     :azp                "test-login",
     :email              "a@a.com",
     :email_verified     false,
     :exp                1614123170,
     :family_name        "BBBBB",
     :given_name         "a",
     :iat                1614122870
     :iss                "http://172.17.0.1:8080/auth/realms/test",
     :jti                "d49176aa-cd59-45b0-89bb-ca8f9fc43909",
     :locale             "en",
     :name               "a BBBBB",
     :nonce              "32ad8646-d163-41fb-89aa-c969fee7bf90",
     :preferred_username "a@a.com",
     :realm_access       {:roles ["offline_access" "uma_authorization"]},
     :resource_access    {:account {:roles ["manage-account"
                                            "manage-account-links"
                                            "view-profile"]}},
     :scope              "openid email profile",
     :session_state      "3068e375-f2dd-4766-9920-74260baa5b56",
     :sub                "9bb0b134-5698-4fb2-a855-b327886d6bfa",
     :typ                "Bearer",})
  )
