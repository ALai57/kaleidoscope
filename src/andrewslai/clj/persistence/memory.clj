(ns andrewslai.clj.persistence.memory
  (:require [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.string :as string]))

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
(defrecord MemFS [store-atom]
  fs/FileSystem
  (ls [_ path]
    (seq (reduce-kv (fn [xs entry v]
                      (conj xs (if (file? v)
                                 v
                                 {:name entry
                                  :path (str path "/" entry)
                                  :type :directory})))
                    []
                    (get-in @store-atom (string/split path #"/")))))

  (get-file [_ path]
    (let [x (get-in @store-atom (string/split path #"/"))]
      (when (file? x)
        x)))
  (put-file [_ path input-stream metadata]
    (let [p    (string/split path #"/")
          file (tag-as {:name     (last p)
                        :path     path
                        :content  input-stream
                        :metadata metadata
                        :type     :file}
                       'file)]
      (swap! store-atom assoc-in p file)
      file)))

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
    (->MemFS db))

  (fs/ls memfs "var")

  (fs/get-file memfs "var/afile")

  (def x (tag-as {:hi "there"} "myclass"))

  (type x)
  )
