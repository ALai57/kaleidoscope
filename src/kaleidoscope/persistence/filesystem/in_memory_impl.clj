(ns kaleidoscope.persistence.filesystem.in-memory-impl
  (:require [kaleidoscope.persistence.filesystem :as fs]
            [clojure.string :as string]
            [ring.util.response :as ring-response]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core functionality
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn tag-as
  [x tag]
  (with-meta x {:type tag}))

(defn file?
  [x]
  (= 'file (type x)))

(defn file
  [{:keys [name content metadata version]}]
  (tag-as {:name     name
           :content  content
           :version  version
           :metadata metadata
           :type     :file}
          'file))

(defn version
  {:pre [(file? file)]}
  [file]
  (:version file))

(defrecord MemFS [store]
  fs/DistributedFileSystem
  (ls [_ path options]
    (seq (reduce-kv (fn [xs entry v]
                      (conj xs (if (file? v)
                                 v
                                 {:name entry
                                  :path (str path "/" entry)
                                  :type :directory})))
                    []
                    (get-in @store (string/split path #"/")))))
  (get-file [_ path options]
    (log/debugf "Getting %s from in-memory store with options %s" path options)
    (let [x (get-in @store (string/split path #"/"))]
      (log/debugf "Found %s with metadata %s" x (meta x))
      (cond
        (and (file? x)
             (= (:version options) (version x))) fs/not-modified-response
        (nil? x)                                 fs/does-not-exist-response
        (file? x)                                (fs/object {:content (:content x)
                                                             :version (:version x)}))))
  (put-file [_ path input-stream metadata]
    (let [p    (remove empty? (string/split path #"/+"))
          file (tag-as {:name     (last p)
                        :path     path
                        :content  input-stream
                        :metadata metadata
                        :version  (fs/md5 path)
                        :type     :file}
                       'file)]
      (swap! store assoc-in p file)
      file)))

(defn in-memory-fs?
  [x]
  (= kaleidoscope.persistence.filesystem.in_memory_impl.MemFS (class x)))

(def example-fs
  "An in-memory filesystem used for testing"
  {"media"      {"afile" (file {:name     "afile"
                                :content  {:qux :quz}
                                :version  "1.2"
                                :metadata {}})
                 "adir"  {"anotherfile" (file {:name     "afile"
                                               :content  {:qux :quz}
                                               :version  "2.3"
                                               :metadata {}})}}
   "index.html" (file {:name    "index.html"
                       :content "<div>Hello</div>"
                       :version "3.4"
                       })})

(comment
  (def db (atom {"var" {"afile" (tag-as {:name "afile"
                                         :path "/var/afile"
                                         :content :b
                                         :metadata {}
                                         :type :file}
                                        'file)
                        "tmp" {"andrewlai.txt" (tag-as {:name "andrewlai.txt"
                                                        :path "/var/tmp/andrewlai.txt"
                                                        :content "something"
                                                        :metadata {:somethin "good"}
                                                        :type :file}
                                                       'file)}}}))

  (def memfs
    (map->MemFS {:store db}))

  (fs/get memfs "var/")
  (fs/get memfs "var/afile")

  (def x (tag-as {:hi "there"} "myclass"))

  (type x)
  )
