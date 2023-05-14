(ns kaleidoscope.persistence.filesystem.local
  (:require [kaleidoscope.persistence.filesystem :as fs]
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
  [root ^java.io.File file]
  {:name          (get-name file)
   ;; When working with a local filesystem, we want the path relative to the
   ;; root directory where traffic is served. Normally, `.getPath` returns the
   ;; absolute path (e.g. `/home/andrew/dev/myfolder/resources/public/myfile.txt`).
   ;;
   ;; We can't return the fully-qualified path to the client, because the
   ;; contract for `ls` is that it should be listing the paths relative to a
   ;; specific directory.
   :path          (string/replace (get-path file) (re-pattern root) "")
   :root          root
   :last-modified (last-modified file)
   :size          (size file)
   :type          (if (directory? file) :directory :file)})

(defn write-stream!
  [input-stream file-path]
  (io/make-parents file-path)
  (with-open [in  input-stream
              out (io/output-stream file-path)]
    (io/copy in out)))

(defrecord LocalFS [root]
  fs/DistributedFileSystem
  (ls [_ path options]
    (map (partial clojurize root) (.listFiles (io/file (format "%s/%s" root path)))))
  (get-file [_ path options]
    (let [result  (io/input-stream (format "%s/%s" root path))
          version (fs/md5 (slurp (io/input-stream (format "%s/%s" root path))))]
      (if (= version (:version options))
        (do (log/tracef "File %s has not changed. Not modified response" path)
            fs/not-modified-response)
        (do (log/tracef "File %s has changed. Resending" path)
            (fs/object {:content result
                        :version version})))))
  (put-file [this path input-stream _metadata]
    (write-stream! input-stream (format "%s/%s" root path))
    (fs/get this path)))

(defn make-local-fs
  [{:keys [root] :as m}]
  (assoc (map->LocalFS m)
         :storage-driver "local-filesystem"
         :storage-root   root))

(comment
  (def fs
    (map->LocalFS {:root ROOT}))

  (def x
    (fs/ls fs "/resources/public" {}))

  (.listFiles (io/file (format "%s/%s" ROOT "resources/public/")))

  (fs/get fs "src/")
  ;; => ({:name "andrewslai",
  ;;      :path "/home/andrew/dev/andrewslai/src/andrewslai",
  ;;      :last-modified 1636923936697,
  ;;      :size 4096,
  ;;      :type :directory})


  (fs/get fs "/README.md")
  ;; => #object[java.io.BufferedInputStream 0x6293fdb5 "java.io.BufferedInputStream@6293fdb5"]


  (write-stream! (:content (fs/get fs "README.md")) "deleteme/another-folder/myout.md")

  (fs/put-file fs "myout.md" (io/input-stream (.getBytes "README.mdxxx")) nil)
  ;; => nil

  )
