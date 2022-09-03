(ns andrewslai.clj.persistence.filesystem.in-memory-impl
  (:require [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.string :as string]
            [ring.util.response :as ring-response]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Install multimethod to get resource-data from URLs using MEM-PROTOCOL
;; Useful for teaching http-mw how to retrieve static assets from a FS
;; TODO: Remove this/move to HTTP middleware ns?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def MEM-PROTOCOL
  "In memory protocol"
  "mem")

(defmethod ring-response/resource-data (keyword MEM-PROTOCOL)
  [url]
  (let [conn (.openConnection url)]
    {:content (.getContent url)}))

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
  [{:keys [name content metadata]}]
  (tag-as {:name name
           :content content
           :metadata metadata
           :type :file}
          'file))

;; Should persistence depend on teh protocols? i.e. should persistence need to know about URLs.. etc?
;; Add protocol as the first argument to the FilesysteM?
;; Configuration map as first argument and extract other args from there?
(defrecord MemFS [store protocol]
  fs/FileSystem
  (ls [_ path]
    (seq (reduce-kv (fn [xs entry v]
                      (conj xs (if (file? v)
                                 v
                                 {:name entry
                                  :path (str path "/" entry)
                                  :type :directory})))
                    []
                    (get-in @store (string/split path #"/")))))

  (get-file [_ path]
    (let [x (get-in @store (string/split path #"/"))]
      (when (file? x)
        x)))
  (put-file [_ path input-stream metadata]
    (let [p    (remove empty? (string/split path #"/+"))
          file (tag-as {:name     (last p)
                        :path     path
                        :content  input-stream
                        :metadata metadata
                        :type     :file}
                       'file)]
      (swap! store assoc-in p file)
      file))
  (get-protocol [_]
    (or protocol MEM-PROTOCOL)))

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

  (fs/ls memfs "var")

  (fs/get-file memfs "var/afile")

  (def x (tag-as {:hi "there"} "myclass"))

  (type x)
  )
