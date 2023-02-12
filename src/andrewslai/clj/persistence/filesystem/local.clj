(ns andrewslai.clj.persistence.filesystem.local
  (:require [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))

(def ROOT
  (System/getProperty "user.dir"))

(defn directory?
  [^java.io.File file]
  (.isDirectory file))

(defn get-path
  [^java.io.File file]
  (.getPath file))

(defn get-name
  [^java.io.File file]
  (.getName file))

(defn last-modified
  [^java.io.File file]
  (.lastModified file))

(defn size
  [^java.io.File file]
  (.length file))

(defn clojurize
  [^java.io.File file]
  {:name          (get-name file)
   :path          (get-path file)
   :last-modified (last-modified file)
   :size          (size file)
   :type          (if (directory? file) :directory :file)})

(defn write-stream!
  [input-stream file-path]
  (with-open [in  input-stream
              out (io/output-stream file-path)]
    (io/copy in out)))

(defrecord LocalFS [root]
  fs/DistributedFileSystem
  (ls [_ path options]
    (map clojurize (.listFiles (io/file (format "%s/%s" root path)))))
  (get-file [_ path options]
    (let [result  (io/input-stream (format "%s/%s" root path))
          version (fs/md5 (slurp (io/input-stream (format "%s/%s" root path))))]
      (if (= version (:version options))
        (do (log/infof "File %s has not changed. Not modified response" path)
            fs/not-modified-response)
        (do (log/infof "File %s has changed. Resending" path)
            (fs/object {:content result
                        :version version})))))
  (put-file [this path input-stream _metadata]
    (write-stream! input-stream path)
    (fs/get this path)))

(comment
  (def fs
    (map->LocalFS {:root ROOT}))

  (def x
    (fs/ls fs "/"))

  (.listFiles (io/file (format "%s/%s" ROOT "/home/andrew/dev/andrewslai-frontend/resources/public")))

  (fs/get fs "src/")
  ;; => ({:name "andrewslai",
  ;;      :path "/home/andrew/dev/andrewslai/src/andrewslai",
  ;;      :last-modified 1636923936697,
  ;;      :size 4096,
  ;;      :type :directory})


  (fs/get fs "/README.md")
  ;; => #object[java.io.BufferedInputStream 0x6293fdb5 "java.io.BufferedInputStream@6293fdb5"]


  (write-stream! (fs/get fs "README.md") "myout.md")

  (fs/put-file fs "myout.md" (io/input-stream (.getBytes "README.mdxxx")) nil)
  ;; => nil

  )
