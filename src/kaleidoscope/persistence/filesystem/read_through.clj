(ns kaleidoscope.persistence.filesystem.read-through
  "Overlay filesystem: read through an ordered chain of stores, write to exactly
  one. The reader/writer asymmetry is structural — a write can never reach a
  non-writer store (an ephemeral env can never mutate the shared media bucket)."
  (:require [kaleidoscope.persistence.filesystem :as fs]))

(defrecord ReadThroughFS [writer readers]
  fs/DistributedFileSystem
  (get-file [_ path options]
    (or (some (fn [store]
                (let [result (fs/get-file store path options)]
                  (when-not (fs/does-not-exist? result) result)))   ;; not-modified (304) counts as found
              readers)
        fs/does-not-exist-response))
  (put-file [_ path input-stream metadata]
    (fs/put-file writer path input-stream metadata))
  (ls [_ path options]
    (fs/ls writer path options))

  fs/WriteLocation
  (write-location [_ path]
    (fs/write-location writer path)))
