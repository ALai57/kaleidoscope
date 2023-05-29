(ns kaleidoscope.test-utils
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [compojure.api.sweet :refer [GET routes]]
            [compojure.route :as route]
            [kaleidoscope.api.authorization :as auth]
            [kaleidoscope.http-api.auth.jwt :as jwt])
  (:import
   (java.io File)))

(def TEMP-DIRECTORY
  (System/getProperty "java.io.tmpdir"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn buffered-input-stream?
  [obj]
  (= (class obj) java.io.BufferedInputStream))

(defn file-input-stream?
  [obj]
  (= (class obj) java.io.FileInputStream))

(defn file?
  [o]
  (= File (class o)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; App related things
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn wrap-clojure-response
  [handler]
  (fn [request]
    (update (handler request) :body (fn [x]
                                      (try
                                        (json/parse-string-strict x keyword)
                                        (catch Exception e
                                          x))))))

(defn dummy-app
  [response]
  (routes
    (GET "/" []
      {:status 200 :body response})
    (route/not-found "No matching route")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Token-related things
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-jwt
  [{:keys [hdr body sig]
    :or {hdr "" sig ""}}]
  (string/join "." ["" body ""]))

(defn bearer-token
  [m]
  (str "Bearer " (make-jwt {:body (jwt/clj->b64 m)})))

(def valid-token
  (str "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
       "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ."
       "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"))

(defn auth-header
  [roles]
  {"authorization" (bearer-token {:realm_access {:roles roles}})})

(def public-access
  [{:pattern #".*"
    :handler (constantly true)}])

(defn restricted-access
  [role]
  [{:pattern #".*"
    :handler (partial auth/require-role role)}])
