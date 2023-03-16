(ns kaleidoscope.clj.http-api.auth.keycloak
  (:require [kaleidoscope.clj.http-api.auth.jwt :as jwt]
            [clj-http.client :as http]
            [taoensso.timbre :as log])
  (:import org.keycloak.adapters.KeycloakDeploymentBuilder
           org.keycloak.adapters.rotation.AdapterTokenVerifier
           (org.keycloak.admin.client Keycloak)
           org.keycloak.jose.jws.JWSInput
           org.keycloak.representations.adapters.config.AdapterConfig))

(defn- keycloak-adapter
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

(defn- make-adapter
  [config]
  (->> config
       (keycloak-adapter)
       (KeycloakDeploymentBuilder/build)))

(defn- validate-token
  [keycloak token]
  (AdapterTokenVerifier/verifyToken token keycloak))

(defn make-keycloak-authenticator
  [config]
  (let [keycloak (make-adapter config)]
    (fn authfn
      [{:keys [request-id] :as request} token]
      (try
        (log/infof "Validating jwt token:\n %s" (-> {:header     (jwt/header token)
                                                     :body       (jwt/body token)
                                                     :request-id request-id}
                                                    clojure.pprint/pprint
                                                    with-out-str))
        (when (validate-token keycloak token)
          (jwt/body token))
        (catch Exception e
          (log/error "Authentication exception" (pr-str e)))))))

;; Set up a Keycloak serice user
(comment
  ;; Setting up a service user in Keycloak.
  ;;
  ;; First, create a new client. This will be the Java client that will be able
  ;; to talk to Keycloak.
  ;;
  ;; After creating the client, download the client credentials/secret
  ;; information. This will be used to authenticate. Use the
  ;; `Keycloak/getInstance` builder to create a new Keycloak client.


  ;; Setting up roles in Keycloak
  ;;
  ;; Then, on the `Role Mapping` screen, assign roles to that user.  That user
  ;; will need roles from the `relam-management` client to be able to view and
  ;; manipulate users.

  (def kc-service-user
    (Keycloak/getInstance "http://localhost:8080"
                          "test"
                          "local-svc-user" ;; username
                          "local-svc-user" ;; pw
                          "test-login-java"
                          "DES2AG0AjGggQKe1L25VPduVVHF5oaP0"))

  (defn get-token
    [kc-instance]
    (:token (bean (.getAccessToken (.tokenManager kc-instance)))))

  (defn get-users
    [kc-instance]
    (:body (http/request {:method  :get
                          :url     "http://localhost:8080/admin/realms/test/users"
                          :headers {"Authorization" (format "Bearer %s" (get-token kc-instance))}
                          :as      :json})))

  (defn create-user!
    [kc-instance user]
    (:body (http/request {:method  :post
                          :url     "http://localhost:8080/admin/realms/test/users"
                          :headers {"Authorization" (format "Bearer %s" (get-token kc-instance))}
                          :body    {:email "my"}
                          :as      :json})))

  (def x
    (get-users kc-service-user))
  x;; => [{:requiredActions [],
  ;;      :email "something@a.com",
  ;;      :username "local-svc-user",
  ;;      :disableableCredentialTypes [],
  ;;      :firstName "",
  ;;      :emailVerified false,
  ;;      :id "aed3e21b-484d-40a7-8459-2e6fd6e60653",
  ;;      :lastName "",
  ;;      :notBefore 0,
  ;;      :totp false,
  ;;      :access {:manageGroupMembership true,
  ;;               :view true,
  ;;               :mapRoles true,
  ;;               :impersonate false,
  ;;               :manage true},
  ;;      :enabled true,
  ;;      :createdTimestamp 1677806861965} ]

  (defn verified-user?
    [user]
    (:emailVerified user))

  (defn email
    [user]
    (:email user))

  (defn id
    [user]
    (:id user))

  (defn username
    [user]
    (:username user))

  (defn first-name
    [user]
    (:firstName user))

  (defn last-name
    [user]
    (:lastName user))

  (map (juxt id email verified-user?) x)
  )

(comment

  (def token
    (str "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ6RHRuWDMxQ0hUOW5VczVOY1VFRURfazJGU2pQWTdCU0ZscEtYZTI0cTE0In0."
         "eyJleHAiOjE2MTQxMjMxNzAsImlhdCI6MTYxNDEyMjg3MCwiYXV0aF90aW1lIjoxNjE0MTIyODY5LCJqdGkiOiJkNDkxNzZhYS1jZDU5LTQ1YjAtODliYi1jYThmOWZjNDM5MDkiLCJpc3MiOiJodHRwOi8vMTcyLjE3LjAuMTo4MDgwL2F1dGgvcmVhbG1zL3Rlc3QiLCJhdWQiOiJhY2NvdW50Iiwic3ViIjoiOWJiMGIxMzQtNTY5OC00ZmIyLWE4NTUtYjMyNzg4NmQ2YmZhIiwidHlwIjoiQmVhcmVyIiwiYXpwIjoidGVzdC1sb2dpbiIsIm5vbmNlIjoiMzJhZDg2NDYtZDE2My00MWZiLTg5YWEtYzk2OWZlZTdiZjkwIiwic2Vzc2lvbl9zdGF0ZSI6IjMwNjhlMzc1LWYyZGQtNDc2Ni05OTIwLTc0MjYwYmFhNWI1NiIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiKiJdLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsib2ZmbGluZV9hY2Nlc3MiLCJ1bWFfYXV0aG9yaXphdGlvbiJdfSwicmVzb3VyY2VfYWNjZXNzIjp7ImFjY291bnQiOnsicm9sZXMiOlsibWFuYWdlLWFjY291bnQiLCJtYW5hZ2UtYWNjb3VudC1saW5rcyIsInZpZXctcHJvZmlsZSJdfX0sInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUiLCJlbWFpbF92ZXJpZmllZCI6ZmFsc2UsIm5hbWUiOiJhIEJCQkJCIiwicHJlZmVycmVkX3VzZXJuYW1lIjoiYUBhLmNvbSIsImxvY2FsZSI6ImVuIiwiZ2l2ZW5fbmFtZSI6ImEiLCJmYW1pbHlfbmFtZSI6IkJCQkJCIiwiZW1haWwiOiJhQGEuY29tIn0."
         "FHMBV5xn-IwMUxiSAI0uB35g3RcTYIfG6LOdbuF1sYC_sO8F-AECHk-iK0b3NUTRgR8RlnL-p6hvyMWebqxeE63RprGCm6k1Mgg8hh3zXL6HjlyPVnLguxwSIUPb2QTVoPlExPCFA84lrSSwmGLDK7HH7eQKaIC8huu68qplH8yX1-6aKsbavgaL8IpNT5Y74PYPjnaqZkTmVBB-c11ukyzzV7lLwrTHzUcIPzUagWcghjRtk-KYpTB7yvGLJqogvsDCmJaSByW1-vDSLOpconildvmozUfWpfHPfp8BnM6mcE15gb2P3Dy3peB1ahfoJYpiVK10klSVzNyGb_j1kA"))

  ((make-keycloak-authenticator {:realm             "test"
                                 :ssl-required      "external"
                                 :auth-server-url   "http://172.17.0.1:8080"
                                 :client-id         "test-login-java"
                                 :client-secret     "18c28e7a-3eb6-4726-b8c7-9c5d02f6bc88"
                                 :confidential-port 0})
   {}
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

  )
