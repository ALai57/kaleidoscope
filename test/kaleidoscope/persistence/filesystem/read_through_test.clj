(ns kaleidoscope.persistence.filesystem.read-through-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as string]
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.persistence.filesystem.in-memory-impl :as memory]
            [kaleidoscope.persistence.filesystem.read-through :as rt]))

(defn- ->file
  "Build an in-memory `file` leaf whose content is an InputStream (the shape
  put-file stores and get-file returns), so `slurp` round-trips it."
  [path content]
  (memory/file {:name    (last (string/split path #"/"))
                :content (java.io.ByteArrayInputStream. (.getBytes (str content)))
                :version "v1"
                :metadata {}}))

(defn mem
  "Compose an in-memory store from a flat {path -> content} map, nesting each
  path the way MemFS stores it (split on `/`) — see `memory/example-fs`."
  [contents]
  (memory/make-mem-fs {:store (atom (reduce-kv (fn [store path content]
                                                 (assoc-in store (string/split path #"/") (->file path content)))
                                               {}
                                               contents))}))

(defn content-str [o] (slurp (fs/object-content o)))

(deftest reads-fall-through-to-later-readers
  (let [own    (mem {})
        shared (mem {"media/abc/raw.jpg" "IMG"})
        media  (rt/->ReadThroughFS own [own shared])]
    (is (= "IMG" (content-str (fs/get-file media "media/abc/raw.jpg" {}))))))

(deftest reads-prefer-the-first-reader
  (let [own    (mem {"media/abc/raw.jpg" "OWN"})
        shared (mem {"media/abc/raw.jpg" "SHARED"})
        media  (rt/->ReadThroughFS own [own shared])]
    (is (= "OWN" (content-str (fs/get-file media "media/abc/raw.jpg" {}))))))

(deftest missing-everywhere-returns-does-not-exist
  (let [media (rt/->ReadThroughFS (mem {}) [(mem {}) (mem {})])]
    (is (fs/does-not-exist? (fs/get-file media "media/nope/raw.jpg" {})))))

(deftest writes-only-touch-the-writer                    ;; the isolation guarantee, by construction
  (let [own    (mem {})
        shared (mem {})
        media  (rt/->ReadThroughFS own [own shared])]
    (fs/put-file media "media/xyz/raw.jpg" (java.io.ByteArrayInputStream. (.getBytes "NEW")) {})
    (is (not (fs/does-not-exist? (fs/get-file own    "media/xyz/raw.jpg" {}))))
    (is      (fs/does-not-exist? (fs/get-file shared "media/xyz/raw.jpg" {})))))
