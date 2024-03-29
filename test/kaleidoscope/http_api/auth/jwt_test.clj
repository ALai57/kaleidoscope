(ns kaleidoscope.http-api.auth.jwt-test
  (:require [kaleidoscope.http-api.auth.jwt :as sut]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]))

(def example-header "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
(def example-body "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ==")
(def example-signature "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c")

(def valid-token
  (string/join "." [(sut/clj->b64 {:alg "HS256" :typ "JWT"})
                    (sut/clj->b64 {:sub "1234567890", :name "John Doe", :iat 1516239022})
                    example-signature]))

(comment
  (def example-claims
    {:given_name         "a",
     :email              "a@a.com",
     :aud                "account",
     :allowed-origins    ["*"],
     :session_state      "3068e375-f2dd-4766-9920-74260baa5b56",
     :locale             "en",
     :sub                "9bb0b134-5698-4fb2-a855-b327886d6bfa",
     :iss                "http://172.17.0.1:8080/auth/realms/test",
     :name               "a BBBBB",
     :exp                1614123170,
     :azp                "test-login",
     :realm_access       {:roles ["offline_access" "uma_authorization"]},
     :scope              "openid email profile",
     :email_verified     false,
     :family_name        "BBBBB",
     :auth_time          1614122869,
     :jti                "d49176aa-cd59-45b0-89bb-ca8f9fc43909",
     :resource_access    {:account {:roles ["manage-account" "manage-account-links" "view-profile"]}},
     :acr                "1",
     :nonce              "32ad8646-d163-41fb-89aa-c969fee7bf90",
     :typ                "Bearer",
     :preferred_username "a@a.com",
     :iat                1614122870}))

(deftest encoding-test
  (is (= example-header (sut/clj->b64 {:alg "HS256" :typ "JWT"})))
  (is (= example-body (sut/clj->b64 {:sub "1234567890", :name "John Doe", :iat 1516239022}))))

(deftest encoding-decoding-test
  (let [m {:alg "HS256" :typ "JWT"}]
    (is (= m (sut/b64->clj (sut/clj->b64 m))))))

(deftest jwt-header-test
  (let [m {:alg "HS256" :typ "JWT"}]
    (is (= m (sut/header (format "%s.junk.junk" (sut/clj->b64 m)))))
    (is (= m (sut/header valid-token)))))

(deftest jwt-body-test
  (let [m {:sub "1234567890", :name "John Doe", :iat 1516239022}]
    (is (= m (sut/body (format "junk.%s.junk" (sut/clj->b64 m)))))
    (is (= m (sut/body valid-token)))))
