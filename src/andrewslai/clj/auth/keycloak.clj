(ns andrewslai.clj.auth.keycloak
  (:require
   [andrewslai.clj.utils :as util]
   [buddy.auth.protocols :as proto]
   [buddy.auth.backends.token :as token]
   [cheshire.core :as json])
  (:import [org.keycloak.adapters BearerTokenRequestAuthenticator
            KeycloakDeploymentBuilder
            KeycloakDeployment]
           [org.keycloak.adapters.rotation AdapterTokenVerifier]))


;;import java client
(defn keycloak-validate []
  )


;;
(def keycloak-backend
  (token/token-backend
   {:token-name "Bearer"
    :authfn  (fn [request token])
    :unauthorized-handler (fn [])}))

(def adapter-config
  (new org.keycloak.representations.adapters.config.AdapterConfig))

(do (.setRealm adapter-config "test")
    (.setSslRequired adapter-config "external")
    (.setAuthServerUrl adapter-config "http://172.17.0.1:8080/auth/")
    (.setResource adapter-config "test-login-java")
    (.setCredentials adapter-config {"secret" "18c28e7a-3eb6-4726-b8c7-9c5d02f6bc88"})
    (.setConfidentialPort adapter-config 0))

(.isPublicClient adapter-config) ;; => false
(.getCredentials adapter-config) ;; => {:secret "18c28e7a-3eb6-4726-b8c7-9c5d02f6bc88"}
(.getRealm adapter-config) ;; => "test"
(.getSslRequired adapter-config) ;; => "external"
(.getAuthServerUrl adapter-config) ;; => "http://172.17.0.1:8080/auth/"
(.getResource adapter-config) ;; => "test-login-java"
(.getConfidentialPort adapter-config) ;; => 0
(type adapter-config)


(KeycloakDeploymentBuilder/build adapter-config)


(comment
  ;; Missing keycloak parent...
  (BearerTokenRequestAuthenticator. (KeycloakDeploymentBuilder/build adapter-config))
  (AdapterTokenVerifier/verifyToken "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ6RHRuWDMxQ0hUOW5VczVOY1VFRURfazJGU2pQWTdCU0ZscEtYZTI0cTE0In0.eyJleHAiOjE2MTQwNDAzMTgsImlhdCI6MTYxNDA0MDAxOCwiYXV0aF90aW1lIjoxNjE0MDQwMDE3LCJqdGkiOiI0NWJhYTM3My05OWM3LTRmZDAtODhkZi03NzhlMzUxZDhlZjkiLCJpc3MiOiJodHRwOi8vMTcyLjE3LjAuMTo4MDgwL2F1dGgvcmVhbG1zL3Rlc3QiLCJhdWQiOiJhY2NvdW50Iiwic3ViIjoiOWJiMGIxMzQtNTY5OC00ZmIyLWE4NTUtYjMyNzg4NmQ2YmZhIiwidHlwIjoiQmVhcmVyIiwiYXpwIjoidGVzdC1sb2dpbiIsIm5vbmNlIjoiYTkwYmJjOWYtMTJlYi00MzlmLThjZjEtZjE1ZTEwNGE5ZjY5Iiwic2Vzc2lvbl9zdGF0ZSI6IjgzZTE5NzQxLTVmZTctNDhmMi1hMzkwLTZmNzVlYWIwM2I4ZiIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiKiJdLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsib2ZmbGluZV9hY2Nlc3MiLCJ1bWFfYXV0aG9yaXphdGlvbiJdfSwicmVzb3VyY2VfYWNjZXNzIjp7ImFjY291bnQiOnsicm9sZXMiOlsibWFuYWdlLWFjY291bnQiLCJtYW5hZ2UtYWNjb3VudC1saW5rcyIsInZpZXctcHJvZmlsZSJdfX0sInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUiLCJlbWFpbF92ZXJpZmllZCI6ZmFsc2UsIm5hbWUiOiJhIEJCQkJCIiwicHJlZmVycmVkX3VzZXJuYW1lIjoiYUBhLmNvbSIsImxvY2FsZSI6ImVuIiwiZ2l2ZW5fbmFtZSI6ImEiLCJmYW1pbHlfbmFtZSI6IkJCQkJCIiwiZW1haWwiOiJhQGEuY29tIn0.Jf9TlPSczMvqAuBIXYy52MFfkQVdvhr_r-VLaoHHcHIkfWr-NM_Jy8aVwOjb0J78Nk7c5xZx6h-1dm9mS3CEXP6aiYWJ9KOqz9LrqJXEBEg-ghuaR3twxKcSub9UHkicjtchlwMZOMeUeRbykeUllzxmqpLLK01PmvwbbpGkzvitec5jY4HjQw8WP01qxFJK3SYlwSWCP9sMse1_72NePKPu9nLUM24vi7e3-IhXcEp6oEwsGjEdUy_PZJoNdzqukJpdupnNjuPnJb-KVZ2fnYeeSKI_d5iI8TVmUh9C06qU3DDNKDo1TtJw6p7HZ0Jke0Jf5TxMjapenMouqg-rSQ"
                                    (KeycloakDeploymentBuilder/build adapter-config))
  )
