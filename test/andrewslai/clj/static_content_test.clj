(ns andrewslai.clj.static-content-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.test-utils :as tu]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test :refer [are deftest is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.properties :as prop]
            [compojure.api.sweet :refer [api defroutes GET routes]]
            [matcher-combinators.test]
            [ring.util.request :as req]
            [ring.util.response :as response]
            [taoensso.timbre :as log])
  (:import [java.io File]
           [java.net URL URLClassLoader]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

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

(defn file
  [& paths]
  (format "file:%s" (string/join "/" paths)))

(defn ->url
  [url]
  (URL. url))

(defn url-classloader
  [urls]
  (URLClassLoader. (url-array urls)))

(def tmp-loader
  "A Classloader that loads from the system's temporary directory (usually `/tmp`)"
  (url-classloader (->url (format "file:%s/" (System/getProperty "java.io.tmpdir")))))

(deftest load-from-classpath-test
  "The Thread's default classloader should be unable to load a resource from a
  location that is not on the classpath. We can make a custom classloader, and
  using that, we can customize the Classpath to load arbitrary files."
  (let [tmpdir  (mktmpdir "andrewslai-test")
        tmpfile (mktmp "delete.txt" tmpdir)
        path    (str (.getName tmpdir) "/" (.getName tmpfile))]

    (is (nil? (.getResource (.getContextClassLoader (Thread/currentThread)) path)))
    (is (some? (.getResource tmp-loader path)))))

(deftest resources-from-classpath-test
  "The Thread's default classloader should be unable to load a resource from a
  location that is not on the classpath. We can make a custom classloader, and
  using that, we can customize the Classpath to load arbitrary files."
  (let [tmpdir  (mktmpdir "andrewslai-test")
        tmpfile (mktmp "delete.txt" tmpdir)
        path    (str (.getName tmpdir) "/" (.getName tmpfile))]

    (are [pred loader]
      (pred (response/resource-response path {:loader loader}))

      nil? (.getContextClassLoader (Thread/currentThread))
      map? tmp-loader)))

(deftest static-content-from-classpath-test
  (let [tmpdir  (mktmpdir "andrewslai-test")
        tmpfile (mktmp "delete.txt" tmpdir)

        path    (str "/" (.getName tmpfile))

        wrapper (sc/configure-wrapper
                 {"ANDREWSLAI_STATIC_CONTENT" "classpath"
                  "ANDREWSLAI_STATIC_CONTENT_BASE_URL" (.getName tmpdir)}
                 {:loader tmp-loader})]

    (is (some? ((wrapper (dummy-app :hello)) {:request-method :get
                                              :uri            path})))))

(comment
  (def tmpdir
    (System/getProperty "java.io.tmpdir"))

  (defn pwd
    []
    (System/getProperty "user.dir"))
  )
