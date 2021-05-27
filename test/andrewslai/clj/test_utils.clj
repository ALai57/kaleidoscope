(ns andrewslai.clj.test-utils
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.clj.static-content :as sc]
            [compojure.api.sweet :refer [GET]]
            [andrewslai.clj.utils :as util]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [clojure.test :refer [deftest]]
            [migratus.core :as migratus]
            [ring.middleware.session.memory :as mem]
            [ring.util.http-predicates :refer [not-found?]]
            [taoensso.timbre :as log]
            [slingshot.slingshot :refer [throw+]]
            [andrewslai.clj.persistence.s3 :as fs])
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
  (let [defaults {:auth           (unauthorized-backend)
                  :static-content (sc/make-wrapper "classpath"
                                                   "public"
                                                   {})}
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



(def s3-connections
  (reduce (fn [m bucket]
            (conj m {bucket (fs/make-s3 {:bucket-name bucket
                                         :credentials fs/CustomAWSCredentialsProviderChain})}))
          {}
          ["andrewslai-wedding"]))

(defn folder?
  [url]
  (string/ends-with? (str url) "/"))

(defn connection
  [s3 url]
  (proxy
      [java.net.URLConnection]
      [url]
    (getContent []
      (let [[protocol bucket & more] (string/split (str url) #"/")
            s (string/join "/" more)]
        (println "FETCHING CONTENT FOR " s)
        (if (folder? url)
          (fs/ls s3 (str s "/"))
          (fs/get-file s3 s))))))

(defn select-connection
  [bucket url]
  (string/starts-with? url (format "s3p:/%s" bucket)))

(defn get-bucket-name
  [url]
  (let [[protocol bucket & more] (string/split (str url) #"/")]
    bucket))

(comment
  (string/split "s3p:/andrewslai-wedding/andrewlai" #"/")
  )

(defn stream-handler
  [connected-buckets]
  (proxy
      [java.net.URLStreamHandler]
      []
    (openConnection [url]
      (if-let [s3-connection (get connected-buckets (get-bucket-name url))]
        (connection s3-connection url)
        (throw (IllegalArgumentException.
                (format "Requested resource (%s) not within buckets (%s)" url (vals connected-buckets))))))))

(defn s3p-handler
  [connected-buckets]
  (proxy
      [java.net.URLStreamHandlerFactory]
      []
    (createURLStreamHandler [s]
      (println "CREATING CUSTOM HANDLER")
      (if (= "s3p" s)
        (stream-handler connected-buckets)))))

(java.net.URL/setURLStreamHandlerFactory (s3p-handler s3-connections))

(defn s3-loader
  []
  (proxy
      [URLClassLoader]
      [(make-array java.net.URL 0)]
    (getResource [s]
      (java.net.URL. s))))

(defmethod ring.util.response/resource-data :s3p
  [url]
  (let [conn (.openConnection url)]
    {:content (.getContent url)}))


(comment

  (.getResource (s3-loader)
                "s3p:/andrewslai-wedding/media/")


  (ring.util.response/resource-response "s3p:/andrewslai-wedding/media/"
                                        {:loader (s3-loader)})


  (.getURL (.openConnection (java.net.URL. "s3p:/media/")))

  (str (java.net.URL. "s3p:/media/")))
