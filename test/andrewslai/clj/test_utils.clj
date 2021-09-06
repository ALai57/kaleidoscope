(ns andrewslai.clj.test-utils
  (:require [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.utils :as util]
            [cheshire.core :as json]
            [clojure.string :as string]
            [compojure.api.sweet :refer [GET routes]]
            [slingshot.slingshot :refer [throw+]]
            [compojure.route :as route])
  (:import [java.io File]
           [java.net URL URLClassLoader]
           [java.nio.file.attribute FileAttribute]
           [java.nio.file Files]))

(defn buffered-input-stream?
  [obj]
  (= (class obj) java.io.BufferedInputStream))

(defn file-input-stream?
  [obj]
  (= (class obj) java.io.FileInputStream))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; App related things
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn captured-logging [logging-atom]
  {:level :debug
   :appenders {:println {:enabled? true,
                         :level :debug
                         :output-fn (fn [data]
                                      (force (:msg_ data)))
                         :fn (fn [data]
                               (let [{:keys [output_]} data]
                                 (swap! logging-atom conj (force output_))))}}})

(defn unauthenticated-backend
  []
  (auth/oauth-backend (reify auth/TokenAuthenticator
                        (auth/valid? [_ token]
                          (throw+ {:type :Unauthorized})))))

(defn authenticated-backend
  []
  (auth/oauth-backend (reify auth/TokenAuthenticator
                        (auth/valid? [_ token]
                          true))))

(defn app-request
  [app request & [{:keys [parser]
                   :or   {parser #(json/parse-string % keyword)}}]]
  (when-let [result (app request)]
    (update result :body parser)))

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
  (str "Bearer " (make-jwt {:body (auth/clj->b64 m)})))

(def valid-token
  (str "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
       "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ."
       "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"))

(defn auth-header
  [roles]
  {"authorization" (bearer-token {:realm_access {:roles roles}})})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Temporary directory
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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
        arr (make-array URL (count urls))]
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
  (= File (class o)))

(def tmp-loader
  "A Classloader that loads from the system's temporary directory (usually `/tmp`)"
  (url-classloader (->url (format "file:%s/" (System/getProperty "java.io.tmpdir")))))


(defn make-loader
  "A Classloader that loads from a particular directory"
  [dir]
  (url-classloader (->url (format "file:%s/" dir))))
