(ns andrewslai.clj.test-utils
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.clj.utils :as util]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest]]
            [hickory.core :as hkry]
            [migratus.core :as migratus]
            [ring.middleware.session.memory :as mem]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]
            [slingshot.slingshot :refer [throw+]])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

;; Deal with the dynamic vars better/at all

(defn test-app-component-config [db-spec]
  {:database (pg/->Database db-spec)
   :logging  (merge log/*config* {:level :error})
   :session  {:cookie-attrs {:max-age 3600 :secure false}
              :store        (mem/memory-store (atom {}))}})

(defn captured-logging [logging-atom]
  {:level :debug
   :appenders {:println {:enabled? true,
                         :level :debug
                         :output-fn (fn [data]
                                      (force (:msg_ data)))
                         :fn (fn [data]
                               (let [{:keys [output_]} data]
                                 (swap! logging-atom conj (force output_))))}}})

(defn ->hiccup [s]
  (hkry/as-hiccup (hkry/parse s)))

;; TODO: This is throwing but not getting caught... How to get around it?
(defn unauthorized-backend
  []
  (keycloak/keycloak-backend (reify keycloak/TokenAuthenticator
                               (keycloak/-validate [_ token]
                                 (throw+ {:type :InvalidAccessError})))))

(defn authorized-backend
  []
  (keycloak/keycloak-backend (reify keycloak/TokenAuthenticator
                               (keycloak/-validate [_ token]
                                 true))))

(defn http-request
  [method endpoint components
   & [{:keys [body parser]
       :or {parser #(json/parse-string % keyword)}
       :as options}]]
  (let [defaults {:logging (merge log/*config* {:level :error})
                  :auth (unauthorized-backend)}
        app (h/wrap-middleware h/app-routes (util/deep-merge defaults components))]
    (update (app (reduce conj
                         {:request-method method :uri endpoint}
                         options))
            :body #(parser (slurp %)))))

(defn get-cookie [response cookie]
  (->> (get-in response [:headers "Set-Cookie"])
       (filter (partial re-find (re-pattern cookie)))
       first))

(def valid-token
  (str "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
       "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ."
       "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"))
