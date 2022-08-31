(ns andrewslai.clj.entities.oidc-id-token
  (:require [clojure.string :as string]))

(defn get-scopes
  [{:keys [scope] :as id-token}]
  (and scope (set (string/split scope #" "))))

(defn get-realm-roles
  [id-token]
  (set (get-in id-token [:realm_access :roles])))

(defn get-full-name
  [id-token]
  (:name id-token))

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

  (get-scopes {:scope "openid email profile",})
  )
