(ns andrewslai.clj.test-utils
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [compojure.api.sweet :refer [GET]]
            [andrewslai.clj.utils :as util]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [clojure.test :refer [deftest]]
            [hickory.core :as hkry]
            [migratus.core :as migratus]
            [ring.middleware.session.memory :as mem]
            [taoensso.timbre :as log]
            [slingshot.slingshot :refer [throw+]])
  (:import [io.zonky.test.db.postgres.embedded EmbeddedPostgres]
           [java.io File]
           [java.net URL URLClassLoader]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]
           [java.nio.file.attribute FileAttribute]))

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
   & [{:keys [body parser app]
       :or   {parser #(json/parse-string % keyword)
              app    h/andrewslai-app}
       :as   options}]]
  (let [defaults {:auth    (unauthorized-backend)}
        app      (app (util/deep-merge defaults components))]
    (when-let [result (app (reduce conj
                                   {:request-method method :uri endpoint}
                                   options))]
      (update result :body parser))))

(defn make-jwt
  [{:keys [hdr body sig]
    :or {hdr "" sig ""}}]
  (string/join "." ["" body ""]))

(defn bearer-token
  [m]
  (str "Bearer " (make-jwt {:body (auth/clj->b64 m)})))

(def valid-token
  (str "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
       "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ."
       "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"))



(defn dummy-app
  [response]
  (GET "/" []
    {:status 200 :body response}))

(defn mktmpdir
  ([]
   (mktmpdir "" (into-array FileAttribute [])))
  ([root]
   (mktmpdir root (into-array FileAttribute [])))
  ([root attrs]
   (let [fname (.toFile (Files/createTempDirectory root attrs))]
     (.deleteOnExit fname)
     fname)))

(defn mktmp
  [s dir]
  (when-not (string/includes? s ".")
    (throw (IllegalArgumentException. "Temporary file names must include an extension")))
  (let [[fname ext] (string/split s #"\.")]
    (let [f (File/createTempFile fname (str "." ext) dir)]
      (.deleteOnExit f)
      f)))

(defn- url-array
  [urls]
  (let [urls (if (coll? urls) urls [urls])
        arr (make-array java.net.URL (count urls))]
    (loop [[url & remain] urls
           n              0]
      (if-not url
        arr
        (do (aset arr n url)
            (recur remain (inc n)))))))

(defn ->url
  [url]
  (URL. url))

(defn url-classloader
  [urls]
  (URLClassLoader. (url-array urls)))

(defn file?
  [o]
  (= java.io.File (class o)))

(def tmp-loader
  "A Classloader that loads from the system's temporary directory (usually `/tmp`)"
  (url-classloader (->url (format "file:%s/" (System/getProperty "java.io.tmpdir")))))
