(ns andrewslai.clj.persistence.filesystem
  (:require [andrewslai.clj.persistence.filesystem.url-utils :as url-utils]
            [taoensso.timbre :as log]
            [clojure.string :as string])
  (:refer-clojure :exclude [get]))

(defprotocol FileSystem
  (ls [_ path] "Like the unix `ls` command")
  (get-file [_ path] "Retrieve a single file")
  (put-file [_ path input-stream metadata] "Put a file")
  (get-protocol [_] "Retrieve the protocol (e.g. `http`) the FileSystem uses"))

(defn canonical-url
  [url]
  (if (string/starts-with? url "/")
    (subs url 1)
    url))

(defn get
  [filesystem path]
  (let [uri (canonical-url path)]
    (log/infof "Looking up content at path %s in Filesystem %s" uri filesystem)
    (cond
      (url-utils/folder? uri) (ls filesystem uri)
      :else                   (get-file filesystem uri))))
