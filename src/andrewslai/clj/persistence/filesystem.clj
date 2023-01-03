(ns andrewslai.clj.persistence.filesystem
  (:require [taoensso.timbre :as log]
            [clojure.string :as string])
  (:refer-clojure :exclude [get]))

(defprotocol FileSystem
  (ls [_ path] "Like the unix `ls` command")
  (get-file [_ path] "Retrieve a single file")
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

(defn get
  [filesystem path]
  (let [uri (canonical-url path)]
    (log/infof "Looking up content at path %s in Filesystem %s" uri filesystem)
    (cond
      (folder? uri) (ls filesystem uri)
      :else         (get-file filesystem uri))))
