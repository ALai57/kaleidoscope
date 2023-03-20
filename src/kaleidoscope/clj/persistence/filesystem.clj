(ns kaleidoscope.clj.persistence.filesystem
  (:require [taoensso.timbre :as log]
            [clojure.string :as string]
            [kaleidoscope.clj.persistence.filesystem :as fs])
  (:import java.security.MessageDigest
           java.math.BigInteger)
  (:refer-clojure :exclude [get]))

(defprotocol DistributedFileSystem
  "A distributed Filesystem. This has properties that are different from
  a normal filesystem.

  For example, in a distributed filesystem we want to be able to build caching
  into the filesystem. When we ask for a file, we also want to be able to say
  'Please don't actually send me the contents of the file if I have the most
  recent, up-to-date version'"
  (ls [_ path options] "Like the unix `ls` command. Returns a collection of metadata from a path, without the contents")
  (get-file [_ path options] "Retrieve a single file")
  (put-file [_ path input-stream metadata] "Put a file"))

(defn folder?
  [url]
  (string/ends-with? (str url) "/"))

(defn canonical-url
  [url]
  (if (and (string/starts-with? url "/")
           (< 1 (count url)))
    (subs url 1)
    url))

;; Change contract so this returns :content and :metadata
(defn get
  ([filesystem path]
   (get filesystem path {}))
  ([filesystem path options]
   (let [uri (canonical-url path)]
     (log/debugf "Looking up content at path %s in Filesystem %s" uri filesystem)
     (cond
       (folder? uri) (ls filesystem uri options)
       :else         (get-file filesystem uri options)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Representing objects in the filesystem.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn object?
  [x]
  (= ::object (type x)))

(defn object-content
  [fs-object]
  {:pre [(object? fs-object)]}
  (get-in fs-object [:content]))

(defn object-version
  [fs-object]
  {:pre [(object? fs-object)]}
  (get-in fs-object [:version]))

(defn object-metadata
  [fs-object]
  {:pre [(object? fs-object)]}
  (get-in fs-object [:metadata]))

(defn object
  "An object in the distributed Filesystem.
  All objects must have a version"
  [x]
  {:pre [(:version x)]}
  (with-meta x {:type ::object}))

(def not-modified-response
  "There is an asymmetry in a distributed filesystem - when we try to get a
  resource, we don't always want the full resource back because it would take
  longer to send over the wire.

  So we may have multiple ways to represent a resource - we can represent the
  raw resource, or we can say 'You already have the most recent version, use
  your copy'"
  (object {:version ::not-modified}))

(def does-not-exist-response
  (object {:version ::does-not-exist}))

(defn not-modified?
  [x]
  (= ::not-modified (object-version x)))

(defn does-not-exist?
  [x]
  (= ::does-not-exist (object-version x)))

(defn md5
  [s]
  (->> s
       str
       .getBytes
       (.digest (MessageDigest/getInstance "MD5"))
       (BigInteger. 1)
       (format "%032x")))

(comment
  (does-not-exist? does-not-exist-response)
  (file-metadata (object {:FOO "Bar"}))

  )
