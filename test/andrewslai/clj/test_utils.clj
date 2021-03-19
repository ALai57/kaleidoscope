(ns andrewslai.clj.test-utils
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.auth.core :as auth]
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

(defn unauthorized-backend
  []
  (auth/oauth-backend (reify auth/TokenAuthenticator
                        (auth/valid? [_ token]
                          (throw+ {:type :Unauthorized})))))

(defn authorized-backend
  []
  (auth/oauth-backend (reify auth/TokenAuthenticator
                        (auth/valid? [_ token]
                          true))))

(defn http-request
  [method endpoint components
   & [{:keys [body parser]
       :or   {parser #(json/parse-string % keyword)}
       :as   options}]]
  (let [defaults {:logging (merge log/*config* {:level :fatal})
                  :auth    (unauthorized-backend)}
        app      (h/app-routes (util/deep-merge defaults components))]
    (update (app (reduce conj
                         {:request-method method :uri endpoint}
                         options))
            :body #(parser %))))

(def valid-token
  (str "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
       "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ."
       "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"))
