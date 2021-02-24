(ns andrewslai.clj.auth.keycloak
  (:require [buddy.auth.backends.token :as token]
            [cheshire.core :as json]
            [slingshot.slingshot :refer [try+]])
  (:import org.keycloak.adapters.KeycloakDeploymentBuilder
           org.keycloak.adapters.rotation.AdapterTokenVerifier
           org.keycloak.jose.jws.JWSInput
           org.keycloak.representations.adapters.config.AdapterConfig))

(defprotocol TokenAuthenticator
  (-validate [_ token]))

(defn decode-token
  [s]
  (-> s
      (JWSInput.)
      (.readContentAsString)
      (json/parse-string keyword)))

(defn encode-token
  [m]
  (-> m
      (JWSInput.)
      (.readContentAsString)
      (json/parse-string keyword)))

(defn keycloak-adapter
  [{:keys [realm ssl-required auth-server-url
           client-id client-secret confidential-port]}]
  (let [adapter-config (new AdapterConfig)]
    (.setRealm adapter-config realm)
    (.setSslRequired adapter-config ssl-required)
    (.setAuthServerUrl adapter-config auth-server-url)
    (.setResource adapter-config client-id)
    (.setCredentials adapter-config {"secret" client-secret})
    (.setConfidentialPort adapter-config confidential-port)
    adapter-config))

(defn make-keycloak
  [config]
  (let [adapter (keycloak-adapter config)]
    (reify TokenAuthenticator
      (-validate [_ token]
        (->> adapter
             (KeycloakDeploymentBuilder/build)
             (AdapterTokenVerifier/verifyToken token))
        token))))

(defn authenticate [keycloak request token]
  ;;(log/info "Checking authentication with Keycloak")
  ;;(log/info "Request: " request)
  ;;(log/info "Validating token: " (-validate keycloak token))
  (try+
   (-validate keycloak token)
   (decode-token token)
   (catch Throwable e
     nil)))

(defn keycloak-backend
  [keycloak]
  (token/token-backend {:token-name "Bearer"
                        :authfn (partial authenticate keycloak)
                        :unauthorized-handler (fn [])}))

(comment

  (def token
    "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ6RHRuWDMxQ0hUOW5VczVOY1VFRURfazJGU2pQWTdCU0ZscEtYZTI0cTE0In0.eyJleHAiOjE2MTQxMjMxNzAsImlhdCI6MTYxNDEyMjg3MCwiYXV0aF90aW1lIjoxNjE0MTIyODY5LCJqdGkiOiJkNDkxNzZhYS1jZDU5LTQ1YjAtODliYi1jYThmOWZjNDM5MDkiLCJpc3MiOiJodHRwOi8vMTcyLjE3LjAuMTo4MDgwL2F1dGgvcmVhbG1zL3Rlc3QiLCJhdWQiOiJhY2NvdW50Iiwic3ViIjoiOWJiMGIxMzQtNTY5OC00ZmIyLWE4NTUtYjMyNzg4NmQ2YmZhIiwidHlwIjoiQmVhcmVyIiwiYXpwIjoidGVzdC1sb2dpbiIsIm5vbmNlIjoiMzJhZDg2NDYtZDE2My00MWZiLTg5YWEtYzk2OWZlZTdiZjkwIiwic2Vzc2lvbl9zdGF0ZSI6IjMwNjhlMzc1LWYyZGQtNDc2Ni05OTIwLTc0MjYwYmFhNWI1NiIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiKiJdLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsib2ZmbGluZV9hY2Nlc3MiLCJ1bWFfYXV0aG9yaXphdGlvbiJdfSwicmVzb3VyY2VfYWNjZXNzIjp7ImFjY291bnQiOnsicm9sZXMiOlsibWFuYWdlLWFjY291bnQiLCJtYW5hZ2UtYWNjb3VudC1saW5rcyIsInZpZXctcHJvZmlsZSJdfX0sInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUiLCJlbWFpbF92ZXJpZmllZCI6ZmFsc2UsIm5hbWUiOiJhIEJCQkJCIiwicHJlZmVycmVkX3VzZXJuYW1lIjoiYUBhLmNvbSIsImxvY2FsZSI6ImVuIiwiZ2l2ZW5fbmFtZSI6ImEiLCJmYW1pbHlfbmFtZSI6IkJCQkJCIiwiZW1haWwiOiJhQGEuY29tIn0.FHMBV5xn-IwMUxiSAI0uB35g3RcTYIfG6LOdbuF1sYC_sO8F-AECHk-iK0b3NUTRgR8RlnL-p6hvyMWebqxeE63RprGCm6k1Mgg8hh3zXL6HjlyPVnLguxwSIUPb2QTVoPlExPCFA84lrSSwmGLDK7HH7eQKaIC8huu68qplH8yX1-6aKsbavgaL8IpNT5Y74PYPjnaqZkTmVBB-c11ukyzzV7lLwrTHzUcIPzUagWcghjRtk-KYpTB7yvGLJqogvsDCmJaSByW1-vDSLOpconildvmozUfWpfHPfp8BnM6mcE15gb2P3Dy3peB1ahfoJYpiVK10klSVzNyGb_j1kA")

  (-validate (keycloak {:realm             "test"
                        :ssl-required      "external"
                        :auth-server-url   "http://172.17.0.1:8080/auth/"
                        :client-id         "test-login-java"
                        :client-secret     "18c28e7a-3eb6-4726-b8c7-9c5d02f6bc88"
                        :confidential-port 0})
             token)

  (.getRoles (get (.getResourceAccess verified-token) "account"))
  (.getRealmAccess verified-token)
  (.isActive verified-token)
  (.isExpired verified-token)



  (.isPublicClient adapter-config) ;; => false
  (.getCredentials adapter-config) ;; => {:secret "18c28e7a-3eb6-4726-b8c7-9c5d02f6bc88"}
  (.getRealm adapter-config) ;; => "test"
  (.getSslRequired adapter-config) ;; => "external"
  (.getAuthServerUrl adapter-config) ;; => "http://172.17.0.1:8080/auth/"
  (.getResource adapter-config) ;; => "test-login-java"
  (.getConfidentialPort adapter-config) ;; => 0
  (type adapter-config)

  (decode-token token)
  )
