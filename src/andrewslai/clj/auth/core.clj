(ns andrewslai.clj.auth.core
  (:require [buddy.auth.backends.token :as token]
            [buddy.core.codecs.base64 :as b64]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [slingshot.slingshot :refer [try+]]))

(defprotocol TokenAuthenticator
  (valid? [_ token] "Returns true if authenticated"))

(defn b64->clj [s]
  (json/parse-string (apply str (map char (b64/decode s)))
                     keyword))

(defn clj->b64 [m]
  (apply str (map char (b64/encode (json/generate-string m)))))

(defn jwt-header [jwt]
  (b64->clj (first (clojure.string/split jwt #"\."))))

(defn jwt-body [jwt]
  (b64->clj (second (clojure.string/split jwt #"\."))))

(defn authenticate
  [authenticator {:keys [request-id] :as request} token]
  (try
    (log/info "Validating token: " {:header     (jwt-header token)
                                    :body       (jwt-body token)
                                    :request-id request-id})
    (when (valid? authenticator token)
      (jwt-body token))
    (catch Throwable e
      nil)))

(defn oauth-backend
  [authenticator]
  (token/token-backend {:token-name "Bearer"
                        :authfn (partial authenticate authenticator)
                        :unauthorized-handler (fn [])}))



(comment
  (def valid-token
    (str "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
         "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ."
         "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"))

  (jwt-header valid-token)
  ;; => {:alg "HS256", :typ "JWT"}

  (jwt-body valid-token)
  ;; => {:sub "1234567890", :name "John Doe", :iat 1516239022}

  (def token
    (str "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ6RHRuWDMxQ0hUOW5VczVOY1VFRURfazJGU2pQWTdCU0ZscEtYZTI0cTE0In0."
         "eyJleHAiOjE2MTQxMjMxNzAsImlhdCI6MTYxNDEyMjg3MCwiYXV0aF90aW1lIjoxNjE0MTIyODY5LCJqdGkiOiJkNDkxNzZhYS1jZDU5LTQ1YjAtODliYi1jYThmOWZjNDM5MDkiLCJpc3MiOiJodHRwOi8vMTcyLjE3LjAuMTo4MDgwL2F1dGgvcmVhbG1zL3Rlc3QiLCJhdWQiOiJhY2NvdW50Iiwic3ViIjoiOWJiMGIxMzQtNTY5OC00ZmIyLWE4NTUtYjMyNzg4NmQ2YmZhIiwidHlwIjoiQmVhcmVyIiwiYXpwIjoidGVzdC1sb2dpbiIsIm5vbmNlIjoiMzJhZDg2NDYtZDE2My00MWZiLTg5YWEtYzk2OWZlZTdiZjkwIiwic2Vzc2lvbl9zdGF0ZSI6IjMwNjhlMzc1LWYyZGQtNDc2Ni05OTIwLTc0MjYwYmFhNWI1NiIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiKiJdLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsib2ZmbGluZV9hY2Nlc3MiLCJ1bWFfYXV0aG9yaXphdGlvbiJdfSwicmVzb3VyY2VfYWNjZXNzIjp7ImFjY291bnQiOnsicm9sZXMiOlsibWFuYWdlLWFjY291bnQiLCJtYW5hZ2UtYWNjb3VudC1saW5rcyIsInZpZXctcHJvZmlsZSJdfX0sInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUiLCJlbWFpbF92ZXJpZmllZCI6ZmFsc2UsIm5hbWUiOiJhIEJCQkJCIiwicHJlZmVycmVkX3VzZXJuYW1lIjoiYUBhLmNvbSIsImxvY2FsZSI6ImVuIiwiZ2l2ZW5fbmFtZSI6ImEiLCJmYW1pbHlfbmFtZSI6IkJCQkJCIiwiZW1haWwiOiJhQGEuY29tIn0."
         "FHMBV5xn-IwMUxiSAI0uB35g3RcTYIfG6LOdbuF1sYC_sO8F-AECHk-iK0b3NUTRgR8RlnL-p6hvyMWebqxeE63RprGCm6k1Mgg8hh3zXL6HjlyPVnLguxwSIUPb2QTVoPlExPCFA84lrSSwmGLDK7HH7eQKaIC8huu68qplH8yX1-6aKsbavgaL8IpNT5Y74PYPjnaqZkTmVBB-c11ukyzzV7lLwrTHzUcIPzUagWcghjRtk-KYpTB7yvGLJqogvsDCmJaSByW1-vDSLOpconildvmozUfWpfHPfp8BnM6mcE15gb2P3Dy3peB1ahfoJYpiVK10klSVzNyGb_j1kA"))

  (jwt-header token)
  ;; => {:alg "RS256", :typ "JWT", :kid "zDtnX31CHT9nUs5NcUEED_k2FSjPY7BSFlpKXe24q14"}

  (jwt-body token)
  ;; => {:given_name "a",
  ;;     :email "a@a.com",
  ;;     :aud "account",
  ;;     :allowed-origins ["*"],
  ;;     :session_state "3068e375-f2dd-4766-9920-74260baa5b56",
  ;;     :locale "en",
  ;;     :sub "9bb0b134-5698-4fb2-a855-b327886d6bfa",
  ;;     :iss "http://172.17.0.1:8080/auth/realms/test",
  ;;     :name "a BBBBB",
  ;;     :exp 1614123170,
  ;;     :azp "test-login",
  ;;     :realm_access {:roles ["offline_access" "uma_authorization"]},
  ;;     :scope "openid email profile",
  ;;     :email_verified false,
  ;;     :family_name "BBBBB",
  ;;     :auth_time 1614122869,
  ;;     :jti "d49176aa-cd59-45b0-89bb-ca8f9fc43909",
  ;;     :resource_access
  ;;     {:account {:roles ["manage-account" "manage-account-links" "view-profile"]}},
  ;;     :acr "1",
  ;;     :nonce "32ad8646-d163-41fb-89aa-c969fee7bf90",
  ;;     :typ "Bearer",
  ;;     :preferred_username "a@a.com",
  ;;     :iat 1614122870}
  )
